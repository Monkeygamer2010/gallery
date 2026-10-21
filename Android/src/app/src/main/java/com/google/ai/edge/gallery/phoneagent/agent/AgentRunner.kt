/*
 * Copyright 2026 The PhoneAgent Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.phoneagent.agent

import android.app.Activity
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.phoneagent.a11y.ActionOutcome
import com.google.ai.edge.gallery.phoneagent.a11y.AgentObservation
import com.google.ai.edge.gallery.phoneagent.a11y.PhoneAgentAccessibilityService
import com.google.ai.edge.gallery.phoneagent.browser.FirefoxLaunch
import com.google.ai.edge.gallery.phoneagent.screen.ScreenSerializer
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs one Phone Agent task: observe, ask the model for one tool call, check it, carry it out,
 * wait for the screen to settle, repeat (docs/DECISIONS.md, Architecture). One run at a time, in
 * this process. Everything about the run ([state]) stays in memory; nothing is logged, saved or
 * sent.
 */
object AgentRunner {
  /** The longest wait for a page to load and show its text. */
  const val PAGE_LOAD_MS = 10_000L
  private const val FIRST_LOOK_MS = 400L
  private const val POLL_MS = 500L
  const val STEP_TIMEOUT_MS = 60_000L
  const val STEP_TIMEOUT_THINKING_MS = 180_000L

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val stateFlow = MutableStateFlow(RunState())
  private var job: Job? = null
  @Volatile private var stopReason: String? = null
  @Volatile private var lastRunPssAfterMb: Int? = null

  val state: StateFlow<RunState> = stateFlow.asStateFlow()

  /**
   * How to wait after an action: for the screen to change (within [changeWithinMs]), then to
   * hold still for two reads in a row with any web page's text present, at most [maxMs].
   */
  private class Wait(val expectChange: Boolean, val changeWithinMs: Long, val maxMs: Long)

  private val START = Wait(expectChange = false, changeWithinMs = 0, maxMs = PAGE_LOAD_MS)
  private val NAVIGATION = Wait(expectChange = true, changeWithinMs = 4_000, maxMs = PAGE_LOAD_MS)
  private val TAP = Wait(expectChange = true, changeWithinMs = 2_500, maxMs = PAGE_LOAD_MS)
  private val SMALL = Wait(expectChange = true, changeWithinMs = 1_500, maxMs = 3_000)
  private val QUICK = Wait(expectChange = false, changeWithinMs = 0, maxMs = 1_500)

  private class Result(
    val ok: Boolean,
    val forModel: String,
    val forOwner: String,
    val finished: Boolean = false,
    /** The settled screen after the action, used as the next step's observation. */
    val next: AgentObservation? = null,
  )

  /**
   * Starts a run for [goal], which the owner typed. Call it from the visible, resumed activity:
   * the foreground service and Firefox's private home page (the run's first app launch) are
   * started from there, as Android's background-start rules require. Returns an error message,
   * or null.
   */
  fun start(activity: Activity, model: Model, goal: String, thinking: Boolean, otherModelsLoaded: Int = 0): String? {
    if (state.value.active) return "A task is already running."
    val task = goal.trim()
    if (task.isEmpty()) return "Type a task first."
    if ((activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == false) {
      return "Open PhoneAgent to start the task."
    }
    val service = PhoneAgentAccessibilityService.instance.value
      ?: return "Turn on the PhoneAgent accessibility service first (Setup)."
    if (model.instance !is LlmModelInstance) return "The model is not loaded yet."
    val firefox = FirefoxLaunch.privateTab(activity.packageManager, null) ?: return "Firefox is not installed."
    // Android refuses foreground services started from the background; report it instead of crashing.
    try {
      AgentRunService.start(activity)
    } catch (e: Exception) {
      return "Android did not allow the run to start (${e.javaClass.simpleName}). Open PhoneAgent and try again."
    }
    val load = AgentEngine.lastLoad.value?.takeIf { it.modelName == model.name }
    stopReason = null
    stateFlow.value =
      RunState(
        status = RunStatus.STARTING,
        goal = task,
        metrics =
          RunMetrics(
            modelName = model.name,
            backend = load?.backend ?: (model.currentAccelerator?.label ?: "GPU"),
            thinking = thinking,
            speculativeDecoding = load?.speculativeDecoding ?: "unknown",
            modelLoadMs = load?.loadMs,
            previousRunPssAfterMb = lastRunPssAfterMb,
            otherModelsLoaded = otherModelsLoaded,
          ),
      )
    ModelKeeper.onRunStarted()
    try {
      activity.startActivity(firefox)
    } catch (e: Exception) {
      stateFlow.update { it.copy(status = RunStatus.ABORTED, outcome = "Firefox did not start (${e.javaClass.simpleName}).") }
      ModelKeeper.onRunEnded()
      return "Firefox did not start."
    }
    val ownPackage = activity.packageName
    job = scope.launch { run(service, model, task, thinking, ownPackage) }
    return null
  }

  /** Stops the run (Stop chip, notification, task screen). The step in progress is cancelled. */
  fun stop(reason: String = "Stopped by the owner.") {
    if (!state.value.active) return
    stopReason = reason
    job?.cancel()
  }

  /** Clears a finished run from memory. */
  fun clear() {
    if (!state.value.active) stateFlow.value = RunState()
  }

  private suspend fun run(
    service: PhoneAgentAccessibilityService,
    model: Model,
    goal: String,
    thinking: Boolean,
    ownPackage: String,
  ) {
    val startTime = SystemClock.elapsedRealtime()
    val guard = LoopGuard()
    val history = mutableListOf<HistoryEntry>()
    val notes = mutableListOf<String>()
    var status = RunStatus.ABORTED
    var outcome: String? = null
    val timeout = if (thinking) STEP_TIMEOUT_THINKING_MS else STEP_TIMEOUT_MS
    try {
      val before = MemoryProbe.read(withGraphics = true)
      stateFlow.update { it.copy(metrics = it.metrics.copy(memoryBefore = before, pssPeakMb = before.pssMb)) }
      log(0, "Opened a Firefox private tab")
      var observation = settle(service, null, START)
      stateFlow.update { it.copy(status = RunStatus.RUNNING) }
      while (true) {
        (guard.beforeStep() as? LoopGuard.Verdict.Abort)?.let {
          outcome = it.reason
          return
        }
        val stepNumber = guard.steps + 1
        record(stepNumber, observation)
        if (observation.empty) {
          outcome = "The screen is empty or hidden (for example a secure window). Stopped here."
          return
        }
        if (observation.view.pageWithoutContent) {
          outcome =
            "Stopped: can't see the page content. A web page is on screen, but none of its text " +
              "reached the screen read after waiting ${PAGE_LOAD_MS / 1000} s (last read: " +
              "${observation.read?.elements?.size ?: 0} elements, none from the page)."
          return
        }
        val observeStart = SystemClock.elapsedRealtime()
        val prompt = PromptBuilder.build(goal, stepNumber, guard.maxSteps, notes, history, observation.modelText)
        val promptTokens = ScreenSerializer.estimateTokens(PromptBuilder.SYSTEM_PROMPT + prompt)
        val observeMs = observation.readMs + (SystemClock.elapsedRealtime() - observeStart)
        guard.onStep()
        val step = guard.steps
        stateFlow.update { it.copy(step = step) }

        val reply = withTimeoutOrNull(timeout) { AgentEngine.step(model, PromptBuilder.SYSTEM_PROMPT, prompt, thinking) }
        if (reply == null) {
          metric(StepMetrics(step, "timeout", observeMs, timeout, 0, promptTokens))
          outcome = "Step $step took longer than ${timeout / 1000} s. Stopped."
          return
        }
        val parsed =
          reply.toolCalls.firstOrNull()?.let { (name, args) -> AgentTools.parse(name, args) }
            ?: AgentTools.parseFromText(reply.text)
            ?: ParsedCall.Invalid("Your reply had no tool call. Call exactly one tool.")
        val actStart = SystemClock.elapsedRealtime()
        when (parsed) {
          is ParsedCall.Invalid -> {
            metric(StepMetrics(step, "none", observeMs, reply.ms, 0, promptTokens))
            history += HistoryEntry("(no usable tool call)", parsed.reason)
            log(step, "No usable tool call: ${parsed.reason}")
            (guard.onInvalid() as? LoopGuard.Verdict.Abort)?.let {
              outcome = it.reason
              return
            }
            observation = settle(service, observation, QUICK)
          }
          is ParsedCall.Ok -> {
            val action = parsed.action
            (guard.onAction(action.toString(), observation.key) as? LoopGuard.Verdict.Abort)?.let {
              metric(StepMetrics(step, action.tool, observeMs, reply.ms, 0, promptTokens))
              log(step, "${action.describe()}: not run, ${it.reason.replaceFirstChar(Char::lowercase)}")
              outcome = it.reason
              return
            }
            val result = execute(action, observation, service, notes, ownPackage)
            metric(StepMetrics(step, action.tool, observeMs, reply.ms, SystemClock.elapsedRealtime() - actStart, promptTokens))
            history += HistoryEntry(action.describe(), result.forModel)
            log(step, "${action.describe()} -> ${result.forOwner}")
            if (result.finished) {
              status = RunStatus.FINISHED
              outcome = (action as AgentAction.Finish).summary
              return
            }
            if (result.ok) {
              guard.onDone()
            } else {
              (guard.onRefused() as? LoopGuard.Verdict.Abort)?.let {
                outcome = it.reason
                return
              }
            }
            observation = result.next ?: settle(service, observation, QUICK)
          }
        }
        samplePss()
      }
    } catch (e: CancellationException) {
      status = RunStatus.STOPPED
      outcome = stopReason ?: "Stopped."
    } catch (e: AgentError) {
      outcome = "Stopped: ${e.message}"
    } catch (e: Exception) {
      // Exception messages could quote screen content; only the type is shown.
      outcome = "Stopped by an error (${e.javaClass.simpleName})."
    } finally {
      withContext(NonCancellable) {
        val finalStatus = status
        val finalOutcome = outcome
        AgentEngine.endRun(model)
        val after = MemoryProbe.read(withGraphics = true)
        lastRunPssAfterMb = after.pssMb
        stateFlow.update {
          it.copy(
            status = finalStatus,
            outcome = finalOutcome,
            metrics =
              it.metrics.copy(
                totalMs = SystemClock.elapsedRealtime() - startTime,
                memoryAfter = after,
                pssPeakMb = maxOf(it.metrics.pssPeakMb ?: 0, after.pssMb),
              ),
          )
        }
        ModelKeeper.onRunEnded()
        // The owner waits for the answer; an owner who pressed Stop stays where they are.
        if (finalStatus != RunStatus.STOPPED) service.returnToPhoneAgent()
      }
    }
  }

  /**
   * Waits for the screen after an action (see [Wait]) and returns the settled read, which the
   * next step uses as its observation.
   */
  private suspend fun settle(service: PhoneAgentAccessibilityService, before: AgentObservation?, wait: Wait): AgentObservation {
    val start = SystemClock.elapsedRealtime()
    delay(FIRST_LOOK_MS)
    var last = service.observeForAgent()
    var changed = !wait.expectChange || before == null || last.key != before.key
    while (true) {
      val elapsed = SystemClock.elapsedRealtime() - start
      if (elapsed >= wait.maxMs) return last
      // Nothing happened: don't wait for a page that isn't coming.
      if (!changed && elapsed >= wait.changeWithinMs) return last
      delay(POLL_MS)
      val next = service.observeForAgent()
      if (!changed && next.key != before?.key) changed = true
      val stable = next.key == last.key
      last = next
      if (changed && stable && !next.view.pageWithoutContent) return next
    }
  }

  private suspend fun execute(
    action: AgentAction,
    observation: AgentObservation,
    service: PhoneAgentAccessibilityService,
    notes: MutableList<String>,
    ownPackage: String,
  ): Result {
    when (action) {
      is AgentAction.Note -> {
        notes += action.text
        stateFlow.update { it.copy(notes = notes.toList()) }
        return Result(true, "Noted.", "noted")
      }
      is AgentAction.SaveResult -> {
        stateFlow.update { it.copy(results = it.results + (action.title to action.text)) }
        return Result(true, "Saved for the owner.", "saved")
      }
      is AgentAction.Finish -> return Result(true, "Finished.", "finished", finished = true)
      AgentAction.Home -> return done(service, observation, service.agentHome(), SMALL, "Pressed Home.")
      is AgentAction.OpenApp -> {
        refusal(ToolPolicy.check(action, observation.facts, ownPackage, null))?.let { return it }
        return done(
          service,
          observation,
          service.agentOpenPrivateTab(null),
          NAVIGATION,
          "Opened Firefox in a new private tab; it is shown below.",
        )
      }
      is AgentAction.OpenUrl -> {
        refusal(ToolPolicy.check(action, observation.facts, ownPackage, null))?.let { return it }
        val url = ToolPolicy.safeUrl(action.url) ?: return Result(false, "Address refused.", "refused")
        stateFlow.update { it.copy(urls = it.urls + url) }
        return done(
          service,
          observation,
          service.agentOpenPrivateTab(url),
          NAVIGATION,
          "Opened in a new private tab. The page is shown below: read it.",
        )
      }
      AgentAction.Back,
      is AgentAction.Scroll,
      is AgentAction.Tap,
      is AgentAction.TypeText -> {
        // Look again right before acting: the model took a while, and the screen may have changed.
        val fresh = service.observeForAgent()
        val id =
          when (action) {
            is AgentAction.Tap -> action.id
            is AgentAction.TypeText -> action.id
            else -> null
          }
        val element = id?.let { fresh.read?.element(it) }
        refusal(ToolPolicy.check(action, fresh.facts, ownPackage, element))?.let { return it }
        if (id != null && observation.read?.element(id)?.signature != element?.signature) {
          return Result(
            false,
            "The screen changed before the action, so nothing was done. Ids change after every action: use the ids of the current screen.",
            "not done: the screen changed",
          )
        }
        val result =
          when (action) {
            is AgentAction.Tap -> done(service, fresh, service.agentTap(fresh, action.id), TAP, "Tapped.")
            is AgentAction.TypeText -> {
              val outcome = service.agentType(fresh, action.id, action.text, action.submit)
              when {
                outcome.ok && action.submit && outcome.detail.contains("Enter was refused") ->
                  done(
                    service,
                    fresh,
                    outcome,
                    SMALL,
                    "Typed the text, but Enter doesn't work in this box. Tap the search button next " +
                      "to it, or use open_url with the search in the address.",
                  )
                action.submit -> done(service, fresh, outcome, NAVIGATION, "Typed and pressed Enter.")
                else -> done(service, fresh, outcome, SMALL, "Typed.")
              }
            }
            is AgentAction.Scroll -> {
              val down = action.direction == ScrollDirection.DOWN
              done(service, fresh, service.agentScroll(fresh, down), SMALL, if (down) "Scrolled down." else "Scrolled up.")
            }
            else -> done(service, fresh, service.agentBack(), TAP, "Went back.")
          }
        // The owner's log names the element; the model already knows which one it chose.
        return if (element != null) {
          Result(result.ok, result.forModel, "${element.signature}: ${result.forOwner}", next = result.next)
        } else {
          result
        }
      }
    }
  }

  /** Waits for the screen after a successful action; failures come back at once. */
  private suspend fun done(
    service: PhoneAgentAccessibilityService,
    before: AgentObservation,
    outcome: ActionOutcome,
    wait: Wait,
    forModel: String,
  ): Result {
    if (!outcome.ok) return Result(false, "It did not work: ${outcome.detail}.", outcome.detail)
    val next = settle(service, before, wait)
    val unchanged = next.key == before.key && wait.expectChange
    val model = if (unchanged) "$forModel The screen did not change." else forModel
    return Result(true, model, outcome.detail + if (unchanged) " (screen unchanged)" else "", next = next)
  }

  private fun refusal(decision: Decision): Result? =
    (decision as? Decision.Refuse)?.let { Result(false, "Refused: ${it.reason}", "refused: ${it.reason}") }

  private fun log(step: Int, text: String) {
    stateFlow.update { it.copy(log = it.log + LogEntry(step, text)) }
  }

  /** Keeps what the model is about to see, and its counts, for the run log. */
  private fun record(step: Int, observation: AgentObservation) {
    val view = observation.view
    val entry = StepObservation(step, view.summary(), view.classification(), view.modelText)
    stateFlow.update { it.copy(observations = it.observations + entry, log = it.log + LogEntry(step, "screen: ${view.summary()}")) }
  }

  private fun metric(step: StepMetrics) {
    stateFlow.update { it.copy(metrics = it.metrics.copy(steps = it.metrics.steps + step)) }
  }

  private fun samplePss() {
    val now = MemoryProbe.read().pssMb
    stateFlow.update { it.copy(metrics = it.metrics.copy(pssPeakMb = maxOf(it.metrics.pssPeakMb ?: 0, now))) }
  }
}
