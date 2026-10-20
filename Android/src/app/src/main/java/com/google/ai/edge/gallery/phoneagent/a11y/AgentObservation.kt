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

package com.google.ai.edge.gallery.phoneagent.a11y

import com.google.ai.edge.gallery.phoneagent.agent.ScreenFacts
import com.google.ai.edge.gallery.phoneagent.screen.ScreenRead
import com.google.ai.edge.gallery.phoneagent.screen.ScreenStatus

/**
 * One look at the screen for the agent loop. Apps other than Firefox are never converted or
 * serialized: for them only the package and app name are known. A Firefox tab is serialized only
 * when its own UI shows private mode.
 */
class AgentObservation
internal constructor(
  val facts: ScreenFacts,
  /** The compact screen read, or null when the policy refused to read this screen. */
  val read: ScreenRead?,
  /** Why the screen was not read (PhoneAgent's own words, no screen text). */
  val refusal: String?,
  /** The address in Firefox's toolbar, when read. */
  val url: String?,
  val readMs: Long,
  internal val snapshot: PhoneAgentAccessibilityService.Snapshot?,
) {
  /** What the model sees between the screen delimiters. */
  val modelText: String
    get() =
      read?.text
        ?: buildString {
          facts.appLabel?.let { append("App: ").append(it).append('\n') }
          append("(Not read: ").append(refusal ?: "unknown").append(')')
        }

  /** An empty, secure or protected window: the run must stop (no workaround). */
  val empty: Boolean
    get() = read?.status == ScreenStatus.EMPTY

  /** Identifies what the model saw, for the loop guard. */
  val key: String
    get() = modelText
}

/** How an action went. [detail] is PhoneAgent's own description, never screen text. */
data class ActionOutcome(val ok: Boolean, val detail: String)
