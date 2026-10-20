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

enum class RunStatus {
  IDLE,
  STARTING,
  RUNNING,
  /** The model called finish. Whether its answer is right is for the owner to judge. */
  FINISHED,
  /** Stopped by the loop guard, an empty screen, a timeout or an error. */
  ABORTED,
  /** Stopped by the owner. */
  STOPPED,
}

/** One line of the run log the owner sees. Kept in memory only; copied only on request. */
data class LogEntry(val step: Int, val text: String)

/** Timing of one step. Contains no screen content. */
data class StepMetrics(
  val step: Int,
  val tool: String,
  /** Reading the screen and building the prompt. */
  val observeMs: Long,
  /** The model producing its reply (prefill and decode). */
  val modelMs: Long,
  /** Carrying out the action, including the wait for the screen to settle. */
  val actMs: Long,
  val promptTokens: Int,
)

/** Measurements of one run, for docs/LEARNINGS.md. Contains no screen content and no URLs. */
data class RunMetrics(
  val modelName: String = "",
  val backend: String = "",
  val thinking: Boolean = false,
  val speculativeDecoding: String = "",
  /** Model load time of the current engine (cold start), if this app measured it. */
  val modelLoadMs: Long? = null,
  val steps: List<StepMetrics> = emptyList(),
  val totalMs: Long = 0,
  /** Proportional set size of the app's process in MB: before the run, highest seen, after. */
  val pssBeforeMb: Int? = null,
  val pssPeakMb: Int? = null,
  val pssAfterMb: Int? = null,
) {
  fun report(status: RunStatus, outcome: String?): String = buildString {
    append("Phone Agent run\n")
    append("Result: ").append(status.name.lowercase())
    outcome?.let { append(" (").append(it).append(')') }
    append('\n')
    append("Model: ").append(modelName).append(", backend ").append(backend)
    append(", thinking ").append(if (thinking) "on" else "off")
    append(", speculative decoding ").append(speculativeDecoding).append('\n')
    append("Model load (cold start): ").append(modelLoadMs?.let { "$it ms" } ?: "not measured (already loaded)").append('\n')
    append("Steps: ").append(steps.size).append(", total ").append(totalMs).append(" ms\n")
    if (steps.isNotEmpty()) {
      val model = steps.map { it.modelMs }.sorted()
      val perStep = steps.map { it.observeMs + it.modelMs + it.actMs }.sorted()
      append("Model time per step: median ").append(median(model)).append(" ms, max ").append(model.last()).append(" ms\n")
      append("Whole step: median ").append(median(perStep)).append(" ms, max ").append(perStep.last()).append(" ms\n")
      append("Prompt tokens (estimate): max ").append(steps.maxOf { it.promptTokens }).append('\n')
      for (s in steps) {
        append("  ").append(s.step).append(". ").append(s.tool).append(": read ").append(s.observeMs)
        append(" ms, model ").append(s.modelMs).append(" ms, act ").append(s.actMs).append(" ms, ~")
        append(s.promptTokens).append(" tokens\n")
      }
    }
    append("Memory (PSS): before ").append(pssBeforeMb?.let { "$it MB" } ?: "-")
    append(", peak ").append(pssPeakMb?.let { "$it MB" } ?: "-")
    append(", after ").append(pssAfterMb?.let { "$it MB" } ?: "-")
  }

  private fun median(sorted: List<Long>): Long =
    if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
}

/** Everything the screens, the notification and the overlay show about the current run. */
data class RunState(
  val status: RunStatus = RunStatus.IDLE,
  val goal: String = "",
  val step: Int = 0,
  val maxSteps: Int = LoopGuard.MAX_STEPS,
  val log: List<LogEntry> = emptyList(),
  val notes: List<String> = emptyList(),
  val results: List<Pair<String, String>> = emptyList(),
  /** Every address opened in this run, in order (SECURITY.md: every URL is logged). */
  val urls: List<String> = emptyList(),
  /** The model's finish summary, or why the run stopped. */
  val outcome: String? = null,
  val metrics: RunMetrics = RunMetrics(),
) {
  val active: Boolean
    get() = status == RunStatus.STARTING || status == RunStatus.RUNNING

  /** The run log as text for the Copy button: actions, URLs, results and the answer. */
  fun logText(): String = buildString {
    append("Task: ").append(goal).append('\n')
    append("Status: ").append(status.name.lowercase()).append('\n')
    for (entry in log) {
      if (entry.step > 0) append(entry.step).append(". ")
      append(entry.text).append('\n')
    }
    if (urls.isNotEmpty()) {
      append("Addresses opened:\n")
      for (url in urls) append("- ").append(url).append('\n')
    }
    for ((title, text) in results) append("Result \"").append(title).append("\":\n").append(text).append('\n')
    outcome?.let { append("Answer / outcome:\n").append(it).append('\n') }
  }
}
