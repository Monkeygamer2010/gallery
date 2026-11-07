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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
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
  const val STEP_TIMEOUT_MS = 60_000L
  const val STEP_TIMEOUT_THINKING_MS = 180_000L

  /** How often in a row the model is asked again because the screen changed under its choice. */
  const val MAX_REASKS_IN_A_ROW = 2

  const val LOCKED = "Stopped: the phone was locked."

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val stateFlow = MutableStateFlow(RunState())
  private var job: Job? = null
  @Volatile private var stopReason: String? = null
  @Volatile private var stopStatus = RunStatus.STOPPED
  @Volatile private var lastRunPssAfterMb: Int? = null

  val state: StateFlow<RunState> = stateFlow.asStateFlow()

  private class Result(
    val ok: Boolean,
    val forModel: String,
    val forOwner: String,
    /** The settled screen after the action, used as the next step's observation. */
    val next: AgentObservation? = null,
    /** Ends the run with this reason. */
    val stopRun: String? = null,
  )

  /** How a run ended, as the owner sees it. */
  private class Ending(
    val status: RunStatus,
    val outcome: String?,
    val evidence: String? = null,
    /** Set when the answer came from the final, finish-only call: why the run had ended. */
    val finalCall: String? = null,
    /** Bring PhoneAgent back to the front with the answer. */
    val returnToApp: Boolean = true,
  )

  /**
   * Starts a run for [goal], which the owner typed. Call it from the visible, resumed activity:
   * the foreground service and Firefox's private home page (the run's first app launch) are
   * started from there, as Android's background-start rules require. [launchNote] is the run
   * log's first line (for example which model the tile asked for). Returns an error message, or
   * null.
   */
  fun start(
    activity: Activity,
    model: Model,
    goal: String,
    thinking: Boolean,
    otherModelsLoaded: Int = 0,
    launchNote: String? = null,
  ): String? {
    if (state.value.active) return "A task is already running."
    val task = goal.trim()
    if (task.isEmpty()) return "Type a task first."
    if ((activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == false) {
      return "Open PhoneAgent to start the task."
    }
    val service = PhoneAgentAccessibilityService.instance.value
      ?: return "Turn on the PhoneAgent accessibility service first (Setup)."
    if (model.instance !is LlmModelInstance) return "The model is not loaded yet."
    if (service.isLocked()) return "Unlock the phone first."
    val firefox = FirefoxLaunch.privateTab(activity.packageManager, null) ?: return "Firefox is not installed."
    // Android refuses foreground services started from the background; report it instead of crashing.
    try {
      AgentRunService.start(activity)
    } catch (e: Exception) {
      return "Android did not allow the run to start (${e.javaClass.simpleName}). Open PhoneAgent and try again."
    }
    val load = AgentEngine.lastLoad.value?.takeIf { it.modelName == model.name }
    stopReason = null
    stopStatus = RunStatus.STOPPED
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
    job = scope.launch { run(service, model, task, thinking, ownPackage, launchNote) }
    return null
  }

  /** Stops the run (Stop chip, notification, task screen). The step in progress is cancelled. */
  fun stop(reason: String = "Stopped by the owner.") = cancel(reason, RunStatus.STOPPED)

  /** Ends the run at once because the screen turned off or the keyguard showed. */
  fun stopLocked() = cancel(LOCKED, RunStatus.ABORTED)

  private fun cancel(reason: String, status: RunStatus) {
    if (!state.value.active) return
    // The first reason wins: Stop, then the screen going off, still reads "Stopped by the owner".
    if (stopReason == null) {
      stopStatus = status
      stopReason = reason
    }
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
    launchNote: String?,
  ) {
    val startTime = SystemClock.elapsedRealtime()
    var ending = Ending(RunStatus.ABORTED, null)
    val screenOff = ScreenOffWatch(service.applicationContext) { stopLocked() }
    try {
      screenOff.register()
      ending = loop(service, model, goal, thinking, ownPackage, launchNote)
    } catch (e: CancellationException) {
      // Stop, the screen going off, or the service or model going away. The owner stays where they are.
      ending = Ending(stopStatus, stopReason ?: "Stopped.", returnToApp = false)
    } catch (e: AgentError) {
      ending = Ending(RunStatus.ABORTED, "Stopped: ${e.message}")
    } catch (e: Exception) {
      // Exception messages could quote screen content; only the type is shown.
      ending = Ending(RunStatus.ABORTED, "Stopped by an error (${e.javaClass.simpleName}).")
    } finally {
      screenOff.unregister()
      withContext(NonCancellable) {
        val end = ending
        AgentEngine.endRun(model)
        val after = MemoryProbe.read(withGraphics = true)
        lastRunPssAfterMb = after.pssMb
        stateFlow.update {
          it.copy(
            status = end.status,
            outcome = end.outcome,
            evidence = end.evidence,
            finalCall = end.finalCall,
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
        if (end.returnToApp && !service.isLocked()) service.returnToPhoneAgent()
      }
    }
  }

  private suspend fun loop(
    service: PhoneAgentAccessibilityService,
    model: Model,
    goal: String,
    thinking: Boolean,
    ownPackage: String,
    launchNote: String?,
  ): Ending {
    val guard = LoopGuard()
    val history = mutableListOf<HistoryEntry>()
    val notes = mutableListOf<String>()
    val seen = SeenScreens()
    val timeout = if (thinking) STEP_TIMEOUT_THINKING_MS else STEP_TIMEOUT_MS
    val before = MemoryProbe.read(withGraphics = true)
    stateFlow.update { it.copy(metrics = it.metrics.copy(memoryBefore = before, pssPeakMb = before.pssMb)) }
    launchNote?.let { log(0, it) }
    log(0, "Opened a Firefox private tab")
    var observation = settle(service, null, ScreenWaiter.START)
    stateFlow.update { it.copy(status = RunStatus.RUNNING) }
    var notice: String? = null
    var reasks = 0
    var finishRejected = false
    while (true) {
      if (observation.view.locked || service.isLocked()) return Ending(RunStatus.ABORTED, LOCKED, returnToApp = false)
      (guard.beforeStep() as? LoopGuard.Verdict.Abort)?.let {
        return finalAnswer(model, goal, thinking, timeout, seen, it.reason, guard.steps)
      }
      val stepNumber = guard.steps + 1
      record(stepNumber, observation)
      seen.add(stepNumber, observation.view)
      if (observation.empty) {
        return Ending(RunStatus.ABORTED, "The screen is empty or hidden (for example a secure window). Stopped here.")
      }
      if (observation.view.pageWithoutContent) {
        return Ending(
          RunStatus.ABORTED,
          "Stopped: can't see the page content. A web page is on screen, but none of its text " +
            "reached the screen read after waiting ${ScreenWaiter.PAGE_LOAD_MS / 1000} s (last read: " +
            "${observation.read?.elements?.size ?: 0} elements, none from the page).",
        )
      }
      val observeStart = SystemClock.elapsedRealtime()
      val prompt = PromptBuilder.build(goal, stepNumber, guard.maxSteps, notes, history, observation.modelText, notice)
      notice = null
      val promptTokens = ScreenSerializer.estimateTokens(PromptBuilder.SYSTEM_PROMPT + prompt)
      val observeMs = observation.readMs + (SystemClock.elapsedRealtime() - observeStart)
      guard.onStep()
      val step = guard.steps
      stateFlow.update { it.copy(step = step) }

      val reply = withTimeoutOrNull(timeout) { AgentEngine.step(model, PromptBuilder.SYSTEM_PROMPT, prompt, thinking) }
      if (reply == null) {
        metric(StepMetrics(step, "timeout", observeMs, timeout, 0, promptTokens))
        return Ending(RunStatus.ABORTED, "Step $step took longer than ${timeout / 1000} s. Stopped.")
      }
      if (service.isLocked()) return Ending(RunStatus.ABORTED, LOCKED, returnToApp = false)
      checkLine(reply.text)?.let { log(step, "model: $it") }
      val parsed =
        reply.toolCalls.firstOrNull()?.let { (name, args) -> AgentTools.parse(name, args) }
          ?: AgentTools.parseFromText(reply.text)
          ?: ParsedCall.Invalid("Your reply had no tool call. Call exactly one tool.")
      val actStart = SystemClock.elapsedRealtime()
      when (parsed) {
        is ParsedCall.Invalid -> {
          reasks = 0
          metric(StepMetrics(step, "none", observeMs, reply.ms, 0, promptTokens))
          history += HistoryEntry("(no usable tool call)", parsed.reason)
          log(step, "No usable tool call: ${parsed.reason}")
          (guard.onInvalid() as? LoopGuard.Verdict.Abort)?.let {
            return finalAnswer(model, goal, thinking, timeout, seen, it.reason, step)
          }
          observation = settle(service, observation, ScreenWaiter.QUICK)
        }
        is ParsedCall.Ok -> {
          val action = parsed.action
          if (action is AgentAction.Finish) {
            reasks = 0
            metric(StepMetrics(step, action.tool, observeMs, reply.ms, 0, promptTokens))
            val verdict = EvidenceCheck.check(action.summary, action.evidence, goal, seen.all)
            // One rejection per run; a second unverified finish ends the run, marked as such.
            if (verdict.ok || finishRejected) {
              log(step, "finish -> ${verdict.line()}")
              return Ending(RunStatus.FINISHED, action.summary, evidenceText(verdict, action.evidence))
            }
            finishRejected = true
            history +=
              HistoryEntry(
                action.describe(),
                "Not accepted: ${verdict.problem}. Copy the text that shows the answer from a screen into " +
                  "evidence, or finish saying it was not found.",
              )
            log(step, "finish not accepted: ${verdict.line()}")
            continue
          }
          // The model took a while: look again right before acting.
          val fresh = if (action.actsOnScreen) service.observeForAgent() else null
          if (fresh?.view?.locked == true) return Ending(RunStatus.ABORTED, LOCKED, returnToApp = false)
          val id = action.elementId
          if (fresh != null && id != null && screenChanged(observation, fresh, id) && reasks < MAX_REASKS_IN_A_ROW) {
            // The id now means something else: not the model's fault, so not a step.
            reasks++
            guard.refund()
            metric(StepMetrics(step, action.tool, observeMs, reply.ms, 0, promptTokens, reask = true))
            log(step, "${action.describe()}: not run, the screen changed before it; asked again on the new screen")
            stateFlow.update { it.copy(step = guard.steps) }
            observation = settle(service, null, ScreenWaiter.START)
            notice =
              "Your last choice (${action.describe()}) was not carried out: the screen changed before it ran. " +
                "Choose again from the current screen."
            continue
          }
          reasks = 0
          (guard.onAction(action.toString(), observation.key) as? LoopGuard.Verdict.Abort)?.let {
            metric(StepMetrics(step, action.tool, observeMs, reply.ms, 0, promptTokens))
            log(step, "${action.describe()}: not run, ${it.reason.replaceFirstChar(Char::lowercase)}")
            return finalAnswer(model, goal, thinking, timeout, seen, it.reason, step)
          }
          val result = execute(action, observation, fresh, service, notes, ownPackage)
          metric(StepMetrics(step, action.tool, observeMs, reply.ms, SystemClock.elapsedRealtime() - actStart, promptTokens))
          history += HistoryEntry(action.describe(), result.forModel)
          log(step, "${action.describe()} -> ${result.forOwner}")
          result.stopRun?.let { return Ending(RunStatus.ABORTED, it) }
          if (result.ok) {
            guard.onDone()
          } else {
            (guard.onRefused() as? LoopGuard.Verdict.Abort)?.let {
              return finalAnswer(model, goal, thinking, timeout, seen, it.reason, step)
            }
          }
          observation = result.next ?: settle(service, observation, ScreenWaiter.QUICK)
        }
      }
      samplePss()
    }
  }

  /**
   * The run ended without a finish ([reason]: the loop guard or the step limit). If a web page
   * with text was on screen during the run, the model gets one last call that can only finish,
   * with the last page screens: answer from them and quote them, or say the answer wasn't found.
   * The answer is checked like any finish; a rejected one gets one more try.
   */
  private suspend fun finalAnswer(
    model: Model,
    goal: String,
    thinking: Boolean,
    timeout: Long,
    seen: SeenScreens,
    reason: String,
    step: Int,
  ): Ending {
    val screens = PromptBuilder.finalScreens(seen.pages)
    if (screens.isEmpty()) return Ending(RunStatus.ABORTED, reason)
    log(step, "Final call (finish only) after: $reason")
    var problem: String? = null
    for (attempt in 1..2) {
      val prompt = PromptBuilder.buildFinal(goal, reason, screens, problem)
      val tokens = ScreenSerializer.estimateTokens(PromptBuilder.FINAL_SYSTEM_PROMPT + prompt)
      val reply =
        withTimeoutOrNull(timeout) {
          AgentEngine.step(model, PromptBuilder.FINAL_SYSTEM_PROMPT, prompt, thinking, finishOnly = true)
        }
      if (reply == null) {
        metric(StepMetrics(step, "final: timeout", 0, timeout, 0, tokens, finalCall = true))
        break
      }
      val parsed =
        reply.toolCalls.firstOrNull()?.let { (name, args) -> AgentTools.parse(name, args) }
          ?: AgentTools.parseFromText(reply.text)
      val finish = (parsed as? ParsedCall.Ok)?.action as? AgentAction.Finish
      metric(StepMetrics(step, if (finish != null) "final: finish" else "final: none", 0, reply.ms, 0, tokens, finalCall = true))
      if (finish == null) {
        log(step, "Final call: no finish")
        break
      }
      val verdict = EvidenceCheck.check(finish.summary, finish.evidence, goal, seen.all)
      if (verdict.ok || attempt == 2) {
        log(step, "Final call: finish -> ${verdict.line()}")
        return Ending(RunStatus.FINISHED, finish.summary, evidenceText(verdict, finish.evidence), finalCall = reason)
      }
      log(step, "Final call: finish not accepted: ${verdict.line()}")
      problem = verdict.problem
    }
    return Ending(RunStatus.ABORTED, reason)
  }

  private fun evidenceText(verdict: EvidenceCheck.Verdict, quote: String): String =
    if (quote.isBlank()) verdict.line() else "${verdict.line()}\nQuote: \"${oneLine(quote, 400)}\""

  /** The model's one-line check before its tool call (the run log only). */
  private fun checkLine(text: String): String? =
    text
      .lineSequence()
      .map { it.trim() }
      .firstOrNull { it.isNotEmpty() && !it.startsWith("{") && !it.startsWith("<") }
      ?.let { oneLine(it, 160) }

  /** Waits for the screen after an action ([ScreenWaiter]) and returns the settled read. */
  private suspend fun settle(service: PhoneAgentAccessibilityService, before: AgentObservation?, wait: Wait): AgentObservation =
    ScreenWaiter.settle(look = { service.observeForAgent() }, view = { it.view }, before = before, wait = wait)

  private suspend fun execute(
    action: AgentAction,
    observation: AgentObservation,
    fresh: AgentObservation?,
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
      // Handled by the loop (the evidence check); never reaches here.
      is AgentAction.Finish -> return Result(true, "Finished.", "finished")
      AgentAction.Home -> return done(service, observation, service.agentHome(), ScreenWaiter.SMALL, "Pressed Home.")
      is AgentAction.OpenApp -> {
        refusal(ToolPolicy.check(action, observation.facts, ownPackage, null))?.let { return it }
        return done(
          service,
          observation,
          service.agentOpenPrivateTab(null),
          ScreenWaiter.NAVIGATION,
          "Opened Firefox in a new private tab; it is shown below.",
        )
      }
      is AgentAction.OpenUrl -> {
        refusal(ToolPolicy.check(action, observation.facts, ownPackage, null))?.let { return it }
        val url = ToolPolicy.safeUrl(action.url) ?: return Result(false, "Address refused.", "refused")
        stateFlow.update { it.copy(urls = it.urls + url) }
        val (report, after) =
          PageOpener.open(
            url = url,
            before = observation,
            look = { service.observeForAgent() },
            view = { it.view },
            send = { service.agentOpenPrivateTab(url) },
            typeAddress = { service.agentTypeInAddressBar(it, url) },
          )
        return Result(
          report.loaded,
          report.forModel,
          report.detail,
          next = after,
          stopRun =
            if (report.notPrivate) {
              "Stopped: Firefox opened the page outside private browsing, so it was not read. Close that tab in Firefox."
            } else {
              null
            },
        )
      }
      AgentAction.Back,
      is AgentAction.Scroll,
      is AgentAction.Tap,
      is AgentAction.TypeText -> {
        val now = fresh ?: service.observeForAgent()
        val id = action.elementId
        val element = id?.let { now.read?.element(it) }
        refusal(ToolPolicy.check(action, now.facts, ownPackage, element))?.let { return it }
        if (id != null && screenChanged(observation, now, id)) {
          return Result(
            false,
            "The screen changed before the action, so nothing was done. Ids change after every action: use the ids of the current screen.",
            "not done: the screen changed",
            next = settle(service, null, ScreenWaiter.START),
          )
        }
        val result =
          when (action) {
            is AgentAction.Tap -> done(service, now, service.agentTap(now, action.id), ScreenWaiter.TAP, "Tapped.")
            is AgentAction.TypeText -> {
              val outcome = service.agentType(now, action.id, action.text, action.submit)
              when {
                outcome.ok && action.submit && outcome.detail.contains("Enter was refused") ->
                  done(
                    service,
                    now,
                    outcome,
                    ScreenWaiter.SMALL,
                    "Typed the text, but Enter doesn't work in this box. Tap the search button next to it.",
                  )
                action.submit -> done(service, now, outcome, ScreenWaiter.NAVIGATION, "Typed and pressed Enter.")
                else -> done(service, now, outcome, ScreenWaiter.SMALL, "Typed.")
              }
            }
            is AgentAction.Scroll -> {
              val down = action.direction == ScrollDirection.DOWN
              done(service, now, service.agentScroll(now, down), ScreenWaiter.SMALL, if (down) "Scrolled down." else "Scrolled up.")
            }
            else -> done(service, now, service.agentBack(), ScreenWaiter.TAP, "Went back.")
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

  /** Element [id] means something else now than on the screen the model chose from. */
  private fun screenChanged(before: AgentObservation, now: AgentObservation, id: Int): Boolean =
    before.read?.element(id)?.signature != now.read?.element(id)?.signature

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

/** The element a tap or type_text names. */
private val AgentAction.elementId: Int?
  get() =
    when (this) {
      is AgentAction.Tap -> id
      is AgentAction.TypeText -> id
      else -> null
    }

/** Actions on the app in front, which are checked against a fresh look right before they run. */
private val AgentAction.actsOnScreen: Boolean
  get() = this is AgentAction.Tap || this is AgentAction.TypeText || this is AgentAction.Scroll || this == AgentAction.Back

/**
 * The screens the model saw in this run: all of them for the evidence check, the ones with web
 * page text for the final call. Memory only.
 */
internal class SeenScreens {
  val all = mutableListOf<Pair<Int, String>>()
  val pages = mutableListOf<Pair<Int, String>>()

  fun add(step: Int, view: Observation) {
    val text = view.modelText
    if (all.lastOrNull()?.second != text) all += step to text
    if (view.pageArea && view.pageLines > 0 && pages.lastOrNull()?.second != text) pages += step to text
  }
}

/**
 * Ends the run when the screen turns off ([Intent.ACTION_SCREEN_OFF], a system broadcast that
 * needs no permission). A keyguard that shows while the screen stays on is caught by the checks
 * before every step and action.
 */
private class ScreenOffWatch(private val context: Context, private val onScreenOff: () -> Unit) {
  private var registered = false
  private val receiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
      }
    }

  fun register() {
    ContextCompat.registerReceiver(
      context,
      receiver,
      IntentFilter(Intent.ACTION_SCREEN_OFF),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    registered = true
  }

  fun unregister() {
    if (registered) runCatching { context.unregisterReceiver(receiver) }
    registered = false
  }
}
