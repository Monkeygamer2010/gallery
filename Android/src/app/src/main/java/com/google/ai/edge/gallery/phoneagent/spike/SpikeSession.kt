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

package com.google.ai.edge.gallery.phoneagent.spike

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A test the owner starts from the spike screen; it runs in the app in front after a delay. */
sealed class SpikeCommand(val title: String) {
  data object Inspect : SpikeCommand("Inspect the screen")

  /** [expected] is the element's role and label from the last inspection, to catch a changed screen. */
  data class Tap(val id: Int, val expected: String?) : SpikeCommand("Tap [$id]")

  data class Type(val id: Int, val text: String, val pressEnter: Boolean, val expected: String?) :
    SpikeCommand("Type into [$id]" + if (pressEnter) " and press Enter" else "")

  data class Scroll(val down: Boolean) : SpikeCommand(if (down) "Scroll down" else "Scroll up")

  data object Back : SpikeCommand("Back")

  data class OpenUrl(val url: String, val privateIntent: Boolean) :
    SpikeCommand(if (privateIntent) "Open in a private tab (OPEN_PRIVATE_TAB)" else "Open with ACTION_VIEW")

  data object LaunchFirefox : SpikeCommand("Launch Firefox from the service")

  data object LaunchFirefoxFromHome : SpikeCommand("Launch Firefox via Home + icon")
}

/** One screen read as shown in the spike screen. */
data class ReadSummary(
  /** The compact list a model would get. */
  val compact: String,
  /** Developer view of all visible nodes (class, view id, text, flags). */
  val details: String,
  /** Node count, elements, token estimates and timing. */
  val stats: String,
  /** Firefox address, its source and private-mode evidence, when Firefox is in front. */
  val browser: String?,
  /** Element role and label by id, to check a later tap or type still targets the same element. */
  val signatures: Map<Int, String>,
)

data class SpikeEntry(
  val title: String,
  val outcome: String,
  val before: ReadSummary?,
  val after: ReadSummary?,
)

/**
 * Results of the spike tests, held in memory only: never written to disk, never logged, never
 * uploaded. They disappear when the app process ends or on "Clear".
 */
object SpikeSession {
  private const val MAX_ENTRIES = 5

  private val _entries = MutableStateFlow<List<SpikeEntry>>(emptyList())
  val entries: StateFlow<List<SpikeEntry>> = _entries.asStateFlow()

  private val _status = MutableStateFlow<String?>(null)
  /** What is running right now, for example "Inspect the screen in 5 s". */
  val status: StateFlow<String?> = _status.asStateFlow()

  fun setStatus(text: String?) {
    _status.value = text
  }

  fun add(entry: SpikeEntry) {
    _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
  }

  /** The most recent screen read, for checking ids before a tap or type. */
  fun lastRead(): ReadSummary? = _entries.value.firstNotNullOfOrNull { it.after ?: it.before }

  fun clear() {
    _entries.value = emptyList()
    _status.value = null
  }
}
