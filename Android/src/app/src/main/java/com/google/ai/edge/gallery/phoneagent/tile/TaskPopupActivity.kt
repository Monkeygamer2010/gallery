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

package com.google.ai.edge.gallery.phoneagent.tile

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.phoneagent.agent.AgentRunner
import com.google.ai.edge.gallery.phoneagent.agent.AgentSettingsStore
import com.google.ai.edge.gallery.phoneagent.agent.PromptBuilder
import com.google.ai.edge.gallery.phoneagent.os.NoKeyboardLearning
import com.google.ai.edge.gallery.phoneagent.os.optOutOfOsCapture
import com.google.ai.edge.gallery.phoneagent.task.PhoneAgentLaunch
import com.google.ai.edge.gallery.ui.theme.GalleryTheme

/**
 * The Quick Settings popup: a small box to type a task. Start hands the text to the Phone Agent
 * screen in memory ([PhoneAgentLaunch]) and opens PhoneAgent, which loads the model and starts
 * the run from the visible app. Not exported; only the tile opens it.
 */
class TaskPopupActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // Never restore saved state: the box holds what the owner typed.
    super.onCreate(null)
    optOutOfOsCapture(this)
    if (!AgentSettingsStore.load(this).allowScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    setContent {
      GalleryTheme {
        NoKeyboardLearning {
          TaskPopup(
            running = AgentRunner.state.value.active,
            onStart = { goal ->
              PhoneAgentLaunch.request(this, goal)
              finish()
            },
            onOpenApp = {
              packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
              finish()
            },
            onDismiss = { finish() },
          )
        }
      }
    }
  }
}

@Composable
private fun TaskPopup(running: Boolean, onStart: (String) -> Unit, onOpenApp: () -> Unit, onDismiss: () -> Unit) {
  var goal by remember { mutableStateOf("") }
  val focus = remember { FocusRequester() }
  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(Color.Black.copy(alpha = 0.4f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
        .imePadding(),
    contentAlignment = Alignment.Center,
  ) {
    Card(
      modifier =
        Modifier.fillMaxWidth()
          .padding(24.dp)
          // Taps on the card must not dismiss it.
          .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Phone Agent", style = MaterialTheme.typography.titleMedium)
        if (running) {
          Text("A task is running. Stop it with the Stop chip or the notification first.")
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenApp) { Text("Open PhoneAgent") }
            TextButton(onClick = onDismiss) { Text("Close") }
          }
        } else {
          OutlinedTextField(
            value = goal,
            onValueChange = { goal = it.take(PromptBuilder.MAX_GOAL_CHARS) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            label = { Text("Task") },
            placeholder = { Text("e.g. Find today's weather in Munich") },
            minLines = 2,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (goal.isNotBlank()) onStart(goal.trim()) }),
          )
          Text(
            "Opens PhoneAgent, loads the model and starts. Firefox private tabs only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onStart(goal.trim()) }, enabled = goal.isNotBlank()) { Text("Start") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
          }
          LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
      }
    }
  }
}
