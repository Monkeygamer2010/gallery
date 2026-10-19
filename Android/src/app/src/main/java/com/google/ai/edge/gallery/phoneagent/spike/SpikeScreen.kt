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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.phoneagent.a11y.PhoneAgentAccessibilityService
import com.google.ai.edge.gallery.phoneagent.browser.FirefoxReader

/** Public pages for the checklist: a Wikipedia article, a DuckDuckGo search and a plain form. */
private val TEST_PAGES =
  listOf(
    "Wikipedia" to "https://en.wikipedia.org/wiki/Munich",
    "DuckDuckGo" to "https://duckduckgo.com/?q=munich",
    "Form" to "https://httpbin.org/forms/post",
  )

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpikeScreen(onClose: () -> Unit) {
  val context = LocalContext.current
  val service by PhoneAgentAccessibilityService.instance.collectAsState()
  val status by SpikeSession.status.collectAsState()
  val entries by SpikeSession.entries.collectAsState()
  var setup by remember { mutableStateOf(DeviceSetup.read(context)) }
  var returnToApp by remember { mutableStateOf(true) }
  var idText by remember { mutableStateOf("") }
  var typeText by remember { mutableStateOf("") }
  var pressEnter by remember { mutableStateOf(true) }
  var url by remember { mutableStateOf(TEST_PAGES.first().second) }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) setup = DeviceSetup.read(context)
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  /** Starts [command] in the service and moves PhoneAgent out of the way. */
  fun start(command: SpikeCommand) {
    val running = service
    if (running == null) {
      Toast.makeText(context, "Turn on the PhoneAgent accessibility service first.", Toast.LENGTH_LONG).show()
      return
    }
    running.run(command, returnToApp)
    context.findActivity()?.moveTaskToBack(true)
  }

  fun expectedFor(id: Int): String? = SpikeSession.lastRead()?.signatures?.get(id)

  Surface(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .safeDrawingPadding()
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "Phone Agent spike (Phase 1)",
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClose) { Text("Close") }
      }
      Text(
        "Reads and acts on the app in front only when you start a test here. Results stay on this " +
          "screen until you clear them or the app closes; nothing is saved or sent.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Section("Setup") {
        StatusLine(
          "Accessibility service",
          when {
            service != null -> "connected"
            setup.serviceEnabled -> "on, not connected yet"
            else -> "off"
          },
          ok = service != null,
        )
        StatusLine("Battery unrestricted", if (setup.batteryUnrestricted) "yes" else "no", setup.batteryUnrestricted)
        StatusLine("Firefox", setup.firefox ?: "not installed", setup.firefox != null)
        StatusLine(
          "Default browser",
          setup.defaultBrowser ?: "none (Android asks)",
          setup.firefoxIsDefault,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { DeviceSetup.openAccessibilitySettings(context) }) { Text("Accessibility settings") }
          OutlinedButton(onClick = { DeviceSetup.openAppInfo(context) }) { Text("App info") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(checked = returnToApp, onCheckedChange = { returnToApp = it })
          Text("Come back here after each test", style = MaterialTheme.typography.bodyMedium)
        }
      }

      Section("Screen Inspector") {
        Text(
          "PhoneAgent goes to the background, waits 5 s and reads the app in front. Switch to " +
            "another app within the 5 s if needed.",
          style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { start(SpikeCommand.Inspect) }, enabled = status == null) { Text("Inspect in 5 s") }
      }

      Section("Act on the app in front (after 5 s)") {
        Text(
          "Use an id from the last inspection. If the screen changed, nothing is done.",
          style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
          value = idText,
          onValueChange = { idText = it.filter(Char::isDigit).take(4) },
          label = { Text("Element id") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = typeText,
          onValueChange = { typeText = it.take(500) },
          label = { Text("Text to type") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Checkbox(checked = pressEnter, onCheckedChange = { pressEnter = it })
          Text("Press Enter after typing", style = MaterialTheme.typography.bodyMedium)
        }
        val id = idText.toIntOrNull()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(enabled = status == null && id != null, onClick = { start(SpikeCommand.Tap(id!!, expectedFor(id))) }) {
            Text("Tap")
          }
          Button(
            enabled = status == null && id != null && typeText.isNotEmpty(),
            onClick = { start(SpikeCommand.Type(id!!, typeText, pressEnter, expectedFor(id))) },
          ) {
            Text("Type")
          }
          OutlinedButton(enabled = status == null, onClick = { start(SpikeCommand.Scroll(down = true)) }) { Text("Scroll down") }
          OutlinedButton(enabled = status == null, onClick = { start(SpikeCommand.Scroll(down = false)) }) { Text("Scroll up") }
          OutlinedButton(enabled = status == null, onClick = { start(SpikeCommand.Back) }) { Text("Back") }
        }
      }

      Section("Firefox (after 5 s, from the background)") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for ((name, page) in TEST_PAGES) AssistChip(onClick = { url = page }, label = { Text(name) })
        }
        OutlinedTextField(
          value = url,
          onValueChange = { url = it.take(FirefoxReader.MAX_URL_LENGTH) },
          label = { Text("https:// address") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          isError = FirefoxReader.safeHttpsUrl(url) == null,
          modifier = Modifier.fillMaxWidth(),
        )
        val urlOk = FirefoxReader.safeHttpsUrl(url) != null
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(enabled = status == null && urlOk, onClick = { start(SpikeCommand.OpenUrl(url, privateIntent = false)) }) {
            Text("Open (ACTION_VIEW)")
          }
          Button(enabled = status == null && urlOk, onClick = { start(SpikeCommand.OpenUrl(url, privateIntent = true)) }) {
            Text("Open private (OPEN_PRIVATE_TAB)")
          }
          OutlinedButton(enabled = status == null, onClick = { start(SpikeCommand.LaunchFirefox) }) {
            Text("Launch from service")
          }
          OutlinedButton(enabled = status == null, onClick = { start(SpikeCommand.LaunchFirefoxFromHome) }) {
            Text("Launch via Home + icon")
          }
        }
      }

      status?.let {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
          TextButton(onClick = { service?.cancelPending() ?: SpikeSession.setStatus(null) }) { Text("Cancel") }
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Results (${entries.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(enabled = entries.isNotEmpty(), onClick = { SpikeSession.clear() }) { Text("Clear") }
      }
      for (entry in entries) EntryCard(entry, context)
    }
  }
}

@Composable
private fun EntryCard(entry: SpikeEntry, context: Context) {
  var showDetails by remember(entry) { mutableStateOf(false) }
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(entry.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium))
      Text(entry.outcome, style = MaterialTheme.typography.bodyMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { copyEntry(context, entry, withDetails = false) }) { Text("Copy") }
        OutlinedButton(onClick = { copyEntry(context, entry, withDetails = true) }) { Text("Copy with details") }
      }
      TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide details" else "Show details") }
      entry.before?.let { ReadBlock(if (entry.after != null) "Before" else "Screen", it, showDetails) }
      entry.after?.let { ReadBlock("After", it, showDetails) }
    }
  }
}

@Composable
private fun ReadBlock(title: String, read: ReadSummary, showDetails: Boolean) {
  HorizontalDivider()
  Text(title, style = MaterialTheme.typography.labelLarge)
  Mono(read.stats)
  read.browser?.let { Mono(it) }
  SelectionContainer { Mono(read.compact) }
  if (showDetails) SelectionContainer { Mono(read.details) }
}

@Composable
private fun Mono(text: String) {
  Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      content()
    }
  }
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
  Row {
    Text(if (ok) "✓ " else "✗ ", color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    Text("$label: ", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
    Text(value, style = MaterialTheme.typography.bodyMedium)
  }
}

private fun copyEntry(context: Context, entry: SpikeEntry, withDetails: Boolean) {
  val text = buildString {
    append(entry.title).append('\n').append(entry.outcome).append('\n')
    for ((name, read) in listOf("Before" to entry.before, "After" to entry.after)) {
      if (read == null) continue
      append("\n== ").append(name).append(" ==\n").append(read.stats).append('\n')
      read.browser?.let { append(it).append('\n') }
      append(read.compact).append('\n')
      if (withDetails) append("\n-- details --\n").append(read.details)
    }
  }
  DeviceSetup.copy(context, text)
  Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun Context.findActivity(): Activity? {
  var current: Context? = this
  while (current is ContextWrapper) {
    if (current is Activity) return current
    current = current.baseContext
  }
  return null
}
