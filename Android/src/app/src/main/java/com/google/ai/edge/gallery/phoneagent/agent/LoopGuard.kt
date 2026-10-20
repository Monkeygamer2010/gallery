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

/**
 * Stops a run that is not getting anywhere. It is plain code: the model cannot extend or reset it.
 *
 * - At most [maxSteps] model steps per run.
 * - The same action on the same screen twice aborts the run: the first time did not help, so the
 *   second will not either. This also catches cycles such as back, tap, back, tap.
 * - [maxInvalidInARow] replies without a usable tool call in a row abort the run.
 * - [maxRefusedInARow] refused actions in a row abort the run.
 */
class LoopGuard(
  val maxSteps: Int = MAX_STEPS,
  private val maxInvalidInARow: Int = 3,
  private val maxRefusedInARow: Int = 3,
) {
  sealed interface Verdict {
    data object Continue : Verdict

    data class Abort(val reason: String) : Verdict
  }

  private val seen = HashSet<String>()
  private var invalidInARow = 0
  private var refusedInARow = 0

  var steps: Int = 0
    private set

  /** Call before asking the model for the next step. */
  fun beforeStep(): Verdict =
    if (steps >= maxSteps) Verdict.Abort("Stopped after $maxSteps steps without finishing.") else Verdict.Continue

  /** A model step happened, whatever it produced. */
  fun onStep() {
    steps++
  }

  /** The model's reply had no usable tool call. */
  fun onInvalid(): Verdict {
    invalidInARow++
    return if (invalidInARow >= maxInvalidInARow) {
      Verdict.Abort("Stopped: $invalidInARow replies in a row without a usable tool call.")
    } else {
      Verdict.Continue
    }
  }

  /**
   * The model asked for [actionKey] while [screenKey] was on screen. Call it for every parsed
   * action, before it runs, so a repeat is caught before it is executed again.
   */
  fun onAction(actionKey: String, screenKey: String): Verdict {
    invalidInARow = 0
    return if (!seen.add("$actionKey\u0000$screenKey")) {
      Verdict.Abort("Stopped: the same action on the same screen again.")
    } else {
      Verdict.Continue
    }
  }

  /** The policy refused the action, or it failed. */
  fun onRefused(): Verdict {
    refusedInARow++
    return if (refusedInARow >= maxRefusedInARow) {
      Verdict.Abort("Stopped: $refusedInARow actions in a row were refused or failed.")
    } else {
      Verdict.Continue
    }
  }

  /** The action ran. */
  fun onDone() {
    refusedInARow = 0
  }

  companion object {
    const val MAX_STEPS = 15
  }
}
