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

package com.google.ai.edge.gallery.phoneagent.confirm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.ai.edge.gallery.tools.ToolActionName
import kotlinx.coroutines.CompletableDeferred

/**
 * Which tool calls in the existing tiles need the owner's OK every time (docs/DECISIONS.md). This
 * stands in for Phase 4's policy engine: anything that reads or writes the calendar or contacts,
 * sends a message, or reaches the network is confirmed. Unknown intents are confirmed too.
 */
object ToolCallPolicy {
  /** Agent Chat intents that only read the clock or fail without side effects. */
  private val INTENTS_WITHOUT_CONFIRMATION = setOf("get_current_date_and_time", "schedule_notification")

  fun intentNeedsConfirmation(intent: String): Boolean = intent !in INTENTS_WITHOUT_CONFIRMATION

  /**
   * An intent may run only while an enabled skill's instructions name it, so turning a skill off
   * also turns off what it can do. Reading the clock needs no skill.
   */
  fun intentOfferedBySkills(intent: String, enabledSkillInstructions: List<String>): Boolean {
    if (intent == "get_current_date_and_time") return true
    val name = Regex("(?<![A-Za-z0-9_])${Regex.escape(intent)}(?![A-Za-z0-9_])")
    return enabledSkillInstructions.any { name.containsMatchIn(it) }
  }

  /** Agent Chat skills run without asking only while their "Allow network" switch is off. */
  fun skillNeedsConfirmation(networkAllowed: Boolean): Boolean = networkAllowed

  /** Mobile Actions that stay on the phone and touch no personal data. */
  private val MOBILE_ACTIONS_WITHOUT_CONFIRMATION =
    setOf("ACTION_FLASHLIGHT_ON", "ACTION_FLASHLIGHT_OFF", "ACTION_OPEN_WIFI_SETTINGS")

  fun mobileActionNeedsConfirmation(actionTypeName: String): Boolean =
    actionTypeName !in MOBILE_ACTIONS_WITHOUT_CONFIRMATION
}

/** Sent by an Agent Chat tool to ask the owner before it acts; completes with true to proceed. */
class ConfirmToolCallAction(
  val title: String,
  val details: String,
  val result: CompletableDeferred<Boolean> = CompletableDeferred(),
) : ToolAction(name = ToolActionName.CONFIRM_TOOL_CALL)

/** "Allow once" or "Don't allow"; dismissing the dialog counts as "Don't allow". */
@Composable
fun ToolCallConfirmationDialog(title: String, details: String, onResult: (Boolean) -> Unit) {
  AlertDialog(
    onDismissRequest = { onResult(false) },
    title = { Text(title, style = MaterialTheme.typography.titleMedium) },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          "The model wants to do this. It runs only if you allow it, and it asks again next time.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (details.isNotBlank()) {
          Text(details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
      }
    },
    confirmButton = { Button(onClick = { onResult(true) }) { Text("Allow once") } },
    dismissButton = { OutlinedButton(onClick = { onResult(false) }) { Text("Don't allow") } },
  )
}
