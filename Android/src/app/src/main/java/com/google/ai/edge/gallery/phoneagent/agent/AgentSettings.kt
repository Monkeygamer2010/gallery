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

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long Phone Agent's model stays loaded when no task runs. */
enum class KeepLoaded(val title: String, val explanation: String) {
  OFF("Off", "Unloads when you leave Phone Agent or PhoneAgent goes to the background."),
  WHILE_OPEN("While PhoneAgent is open", "Unloads when PhoneAgent goes to the background."),
  MINUTES("For a while after the last use", "Unloads after the minutes below without a task."),
}

/** Phone Agent's own settings. Kept in app-private storage; no task data. */
data class AgentSettings(
  val keepLoaded: KeepLoaded = KeepLoaded.OFF,
  val keepMinutes: Int = 15,
  /** The model the Quick Settings tile uses (by name). */
  val tileModel: String = "Gemma-4-E4B-it",
  /** Lets screenshots and screen recording see the Phone Agent and spike screens. */
  val allowScreenshots: Boolean = false,
) {
  companion object {
    val KEEP_MINUTES_CHOICES = listOf(5, 15, 30)
  }
}

/** Reads and writes [AgentSettings] (a small private SharedPreferences file). */
object AgentSettingsStore {
  private const val FILE = "phoneagent_settings"
  private val flow = MutableStateFlow(AgentSettings())
  @Volatile private var loaded = false

  val settings: StateFlow<AgentSettings> = flow.asStateFlow()

  /** Loads the settings once per process. */
  fun load(context: Context): AgentSettings {
    if (loaded) return flow.value
    val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    val defaults = AgentSettings()
    flow.value =
      AgentSettings(
        keepLoaded = runCatching { KeepLoaded.valueOf(prefs.getString("keep_loaded", null) ?: "") }.getOrDefault(defaults.keepLoaded),
        keepMinutes = prefs.getInt("keep_minutes", defaults.keepMinutes).takeIf { it in AgentSettings.KEEP_MINUTES_CHOICES } ?: defaults.keepMinutes,
        tileModel = prefs.getString("tile_model", null) ?: defaults.tileModel,
        allowScreenshots = prefs.getBoolean("allow_screenshots", defaults.allowScreenshots),
      )
    loaded = true
    return flow.value
  }

  fun update(context: Context, transform: (AgentSettings) -> AgentSettings) {
    val next = transform(load(context))
    context.applicationContext
      .getSharedPreferences(FILE, Context.MODE_PRIVATE)
      .edit()
      .putString("keep_loaded", next.keepLoaded.name)
      .putInt("keep_minutes", next.keepMinutes)
      .putString("tile_model", next.tileModel)
      .putBoolean("allow_screenshots", next.allowScreenshots)
      .apply()
    flow.value = next
  }
}
