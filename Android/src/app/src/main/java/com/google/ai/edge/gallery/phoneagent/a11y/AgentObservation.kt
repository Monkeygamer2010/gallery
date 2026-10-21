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

import com.google.ai.edge.gallery.phoneagent.agent.Observation
import com.google.ai.edge.gallery.phoneagent.agent.ObservationBuilder
import com.google.ai.edge.gallery.phoneagent.agent.ScreenFacts
import com.google.ai.edge.gallery.phoneagent.screen.ScreenRead

/**
 * One look at the screen for the agent loop: the [view] built by [ObservationBuilder] plus the
 * live nodes needed to act on it. Apps other than Firefox are never converted; a Firefox tab is
 * serialized only when its own UI shows private mode.
 */
class AgentObservation
internal constructor(
  val view: Observation,
  val readMs: Long,
  internal val snapshot: PhoneAgentAccessibilityService.Snapshot?,
) {
  val facts: ScreenFacts
    get() = view.facts

  val read: ScreenRead?
    get() = view.read

  val refusal: String?
    get() = view.refusal

  val modelText: String
    get() = view.modelText

  val empty: Boolean
    get() = view.empty

  val key: String
    get() = view.key
}

/** How an action went. [detail] is PhoneAgent's own description, never screen text. */
data class ActionOutcome(val ok: Boolean, val detail: String)
