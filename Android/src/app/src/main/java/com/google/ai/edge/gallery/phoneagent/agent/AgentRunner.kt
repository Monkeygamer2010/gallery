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
import android.os.Debug
import android.os.SystemClock
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
 * repeat (docs/DECISIONS.md, Architecture). One run at a time, in this process. Everything about
 * the run ([state]) stays in memory; nothing is logged, saved or sent.
 */
object AgentRunner {
  private const val LAUNCH_SETTLE_MS = 3000L
  private const val SETTLE_MS = 1500L
  private const val READ_WAIT_MS = 2000L
  const val STEP_TIMEOUT_MS = 60_000L
  const val STEP_TIMEOUT_THINKING_MS = 180_000L

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val stateFlow = MutableStateFlow(RunState())
  private var job: Job? = null
  @Volatile private var stopReason: String? = null

  val state: StateFlow<RunState> = stateFlow.asStateFlow()

  private class Result(val ok: Boolean, val forModel: String, val forOwner: String, val finished: Boolean = false)

  /**
   * Starts a run for [goal], which the owner typed. Call it from a visible activity: the
   * foreground service and Firefox's private home page (the run's first app launch) are started
   * from there, as Android's background-start rules require. Returns an error message, or null.
   */
  fun start(activity: Activity, model: Model, goal: String, thinking: Boolean): String? {
    if (state.value.active) return "A task is already running."
    val task = goal.trim()
    if (task.isEmpty()) return "Type a task first."
    val service = PhoneAgentAccessibilityService.instance.value
      ?: return "Turn on the PhoneAgent accessibility service first (Setup)."
    if (model.instance !is LlmModelInstance) return "The model is not loaded yet."
    val firefox = FirefoxLaunch.privateTab(activity.packageManager, null) ?: return "Firefox is not installed."
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
            pssBeforeMb = pssMb(),
          ),
      )
    AgentRunService.start(activity)
    try {
      activity.startActivity(firefox)
    } catch (e: Exception) {
      stateFlow.update { it.copy(status = RunStatus.ABORTED, outcome = "Firefox did not start (${e.javaClass.simpleName}).") }
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
      log(0, "Opened a Firefox private tab")
      delay(LAUNCH_SETTLE_MS)
      stateFlow.update { it.copy(status = RunStatus.RUNNING) }
      while (true) {
        (guard.beforeStep() as? LoopGuard.Verdict.Abort)?.let {
          outcome = it.reason
          return
        }
        val observeStart = SystemClock.elapsedRealtime()
        val observation = service.observeForAgent()
        if (observation.empty) {
          outcome = "The screen is empty or hidden (for example a secure window). Stopped here."
          return
        }
        val prompt = PromptBuilder.build(goal, guard.steps + 1, guard.maxSteps, notes, history, observation.modelText)
        val promptTokens = ScreenSerializer.estimateTokens(PromptBuilder.SYSTEM_PROMPT + prompt)
        val observeMs = SystemClock.elapsedRealtime() - observeStart
        guard.onStep()
        val step = guard.steps
        stateFlow.update { it.copy(step = step) }

        val reply = withTimeoutOrNull(timeout) { AgentEngine.step(model, PromptBuilder.SYSTEM_PROMPT, prompt, thinking) }
        if (reply == null) {
          record(StepMetrics(step, "timeout", observeMs, timeout, 0, promptTokens))
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
            record(StepMetrics(step, "none", observeMs, reply.ms, 0, promptTokens))
            history += HistoryEntry("(no usable tool call)", parsed.reason)
            log(step, "No usable tool call: ${parsed.reason}")
            (guard.onInvalid() as? LoopGuard.Verdict.Abort)?.let {
              outcome = it.reason
              return
            }
          }
          is ParsedCall.Ok -> {
            val action = parsed.action
            (guard.onAction(action.toString(), observation.key) as? LoopGuard.Verdict.Abort)?.let {
              record(StepMetrics(step, action.tool, observeMs, reply.ms, 0, promptTokens))
              log(step, "${action.describe()}: not run, ${it.reason.replaceFirstChar(Char::lowercase)}")
              outcome = it.reason
              return
            }
            val result = execute(action, observation, service, notes, ownPackage)
            record(StepMetrics(step, action.tool, observeMs, reply.ms, SystemClock.elapsedRealtime() - actStart, promptTokens))
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
        val after = pssMb()
        stateFlow.update {
          val peak = maxOf(it.metrics.pssPeakMb ?: 0, it.metrics.pssBeforeMb ?: 0, after)
          it.copy(
            status = finalStatus,
            outcome = finalOutcome,
            metrics =
              it.metrics.copy(
                totalMs = SystemClock.elapsedRealtime() - startTime,
                pssPeakMb = peak,
                pssAfterMb = after,
              ),
          )
        }
        AgentEngine.endRun(model)
        // The owner waits for the answer; an owner who pressed Stop stays where they are.
        if (finalStatus != RunStatus.STOPPED) service.returnToPhoneAgent()
      }
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
      AgentAction.ReadScreen -> {
        delay(READ_WAIT_MS)
        return Result(true, "Waited; the screen below is new.", "waited")
      }
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
      AgentAction.Home -> return settle(service.agentHome(), SETTLE_MS)
      is AgentAction.OpenApp -> {
        refusal(ToolPolicy.check(action, observation.facts, ownPackage, null))?.let { return it }
        return settle(service.agentOpenPrivateTab(null), LAUNCH_SETTLE_MS)
      }
      is AgentAction.OpenUrl -> {
        refusal(ToolPolicy.check(action, observation.facts, ownPackage, null))?.let { return it }
        val url = ToolPolicy.safeUrl(action.url) ?: return Result(false, "Address refused.", "refused")
        stateFlow.update { it.copy(urls = it.urls + url) }
        return settle(service.agentOpenPrivateTab(url), LAUNCH_SETTLE_MS)
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
            "The screen changed before the action, so nothing was done. Use the ids of the current screen.",
            "not done: the screen changed",
          )
        }
        val outcome =
          when (action) {
            is AgentAction.Tap -> service.agentTap(fresh, action.id)
            is AgentAction.TypeText -> service.agentType(fresh, action.id, action.text, action.submit)
            is AgentAction.Scroll -> service.agentScroll(fresh, action.direction == ScrollDirection.DOWN)
            else -> service.agentBack()
          }
        val result = settle(outcome, SETTLE_MS)
        // The owner's log names the element; the model already knows which one it chose.
        return if (element != null) Result(result.ok, result.forModel, "${element.signature}: ${result.forOwner}") else result
      }
    }
  }

  private suspend fun settle(outcome: ActionOutcome, waitMs: Long): Result {
    if (outcome.ok) delay(waitMs)
    return Result(outcome.ok, if (outcome.ok) "${outcome.detail}." else "It did not work: ${outcome.detail}.", outcome.detail)
  }

  private fun refusal(decision: Decision): Result? =
    (decision as? Decision.Refuse)?.let { Result(false, "Refused: ${it.reason}", "refused: ${it.reason}") }

  private fun log(step: Int, text: String) {
    stateFlow.update { it.copy(log = it.log + LogEntry(step, text)) }
  }

  private fun record(step: StepMetrics) {
    stateFlow.update { it.copy(metrics = it.metrics.copy(steps = it.metrics.steps + step)) }
  }

  private fun samplePss() {
    val now = pssMb()
    stateFlow.update {
      val peak = maxOf(it.metrics.pssPeakMb ?: 0, it.metrics.pssBeforeMb ?: 0, now)
      it.copy(metrics = it.metrics.copy(pssPeakMb = peak))
    }
  }

  /** The app process's proportional set size in MB (what Android counts as its memory use). */
  private fun pssMb(): Int = (Debug.getPss() / 1024).toInt()
}
