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

package com.google.ai.edge.gallery.phoneagent.task

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.phoneagent.a11y.PhoneAgentAccessibilityService
import com.google.ai.edge.gallery.phoneagent.agent.AgentEngine
import com.google.ai.edge.gallery.phoneagent.agent.AgentRunner
import com.google.ai.edge.gallery.phoneagent.agent.AgentSettings
import com.google.ai.edge.gallery.phoneagent.agent.AgentSettingsStore
import com.google.ai.edge.gallery.phoneagent.agent.EngineLoad
import com.google.ai.edge.gallery.phoneagent.agent.KeepLoaded
import com.google.ai.edge.gallery.phoneagent.agent.LoopGuard
import com.google.ai.edge.gallery.phoneagent.agent.MemoryProbe
import com.google.ai.edge.gallery.phoneagent.agent.ModelKeeper
import com.google.ai.edge.gallery.phoneagent.agent.PromptBuilder
import com.google.ai.edge.gallery.phoneagent.agent.RunState
import com.google.ai.edge.gallery.phoneagent.agent.RunStatus
import com.google.ai.edge.gallery.phoneagent.os.BlockScreenCapture
import com.google.ai.edge.gallery.phoneagent.spike.DeviceSetup

/**
 * The Phone Agent screen: setup checks, the task box, Start and Stop, the run's answer, log, what
 * the model saw and measurements, and the model-memory settings. Everything shown stays in
 * memory; Copy puts it on the clipboard (marked sensitive) only when the owner asks.
 */
@Composable
fun PhoneAgentScreen(task: Task, data: CustomTaskData) {
  val context = LocalContext.current
  val activity = LocalActivity.current
  val uiState by data.modelManagerViewModel.uiState.collectAsState()
  val model = uiState.selectedModel
  val initStatus by model.initStatusFlow.collectAsState()
  val modelReady = initStatus is Model.InitializationStatus.Initialized
  val downloaded = uiState.modelDownloadStatus[model.name]?.status == ModelDownloadStatusType.SUCCEEDED
  val run by AgentRunner.state.collectAsState()
  val service by PhoneAgentAccessibilityService.instance.collectAsState()
  val load by AgentEngine.lastLoad.collectAsState()
  val settings by AgentSettingsStore.settings.collectAsState()
  var setup by remember { mutableStateOf(DeviceSetup.read(context)) }
  var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
  var memoryNow by remember { mutableStateOf("") }
  var goal by rememberSaveable { mutableStateOf("") }
  var thinking by rememberSaveable { mutableStateOf(false) }

  val lifecycleOwner = LocalLifecycleOwner.current
  val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
  val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
  DisposableEffect(lifecycleOwner) {
    AgentSettingsStore.load(context)
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        setup = DeviceSetup.read(context)
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        memoryNow = MemoryProbe.read().line()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    PhoneAgentLaunch.screenShown = true
    ModelKeeper.onScreenShown()
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      PhoneAgentLaunch.screenShown = false
      ModelKeeper.onScreenLeft()
    }
  }
  // Runs and results show other apps' text: no screenshots or system capture unless allowed.
  BlockScreenCapture(!settings.allowScreenshots)

  // No model switching while a run uses the model.
  LaunchedEffect(run.active) { data.setAppBarControlsDisabled(run.active) }

  // The model was unloaded while this screen stayed open (background, memory, idle timer): load it again.
  LaunchedEffect(initStatus, downloaded, run.active, resumed) {
    if (initStatus is Model.InitializationStatus.Idle && downloaded && !run.active && resumed) {
      data.modelManagerViewModel.initializeModel(context, task = task, model = model)
    }
  }
  LaunchedEffect(modelReady) { memoryNow = MemoryProbe.read().line() }

  val blockers = buildList {
    if (service == null) add("Turn on the PhoneAgent accessibility service.")
    if (setup.firefox == null) add("Install Firefox.")
    else if (!setup.firefoxIsDefault) add("Make Firefox the default browser (Settings > Apps > Default apps > Browser app).")
  }

  fun start(text: String) {
    if (blockers.isNotEmpty()) {
      Toast.makeText(context, blockers.first(), Toast.LENGTH_LONG).show()
      return
    }
    val host = activity ?: return
    val others = uiState.tasks.flatMap { it.models }.distinct().count { it.instance != null && it !== model }
    val error = AgentRunner.start(host, model, text, thinking, otherModelsLoaded = others)
    if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
  }

  // A task typed in the Quick Settings popup starts once the model is ready and this screen is
  // in front (Android only lets a visible app start the run's foreground service).
  val pending by PhoneAgentLaunch.pending.collectAsState()
  LaunchedEffect(pending, modelReady, run.active, resumed) {
    if (pending == null || !modelReady || run.active || !resumed) return@LaunchedEffect
    val text = PhoneAgentLaunch.take() ?: return@LaunchedEffect
    goal = text
    start(text)
  }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = data.bottomPadding + 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      "Phone Agent reads and operates Firefox private tabs only. It never reads or acts in other " +
        "apps, in PhoneAgent or in Settings. Stop it any time with the Stop chip on screen or in " +
        "the notification. Nothing from the run is saved or sent.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (blockers.isNotEmpty() || !setup.batteryUnrestricted || !notificationsOn) {
      Section("Setup") {
        for (line in blockers) Text("• $line", style = MaterialTheme.typography.bodyMedium)
        if (!setup.batteryUnrestricted) {
          Text(
            "• Recommended: set PhoneAgent's battery use to Unrestricted (App info > Battery).",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        if (!notificationsOn) {
          Text(
            "• Notifications are off, so the Stop button shows only on the on-screen chip.",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (service == null) {
            OutlinedButton(onClick = { DeviceSetup.openAccessibilitySettings(context) }) { Text("Accessibility") }
          }
          OutlinedButton(onClick = { DeviceSetup.openAppInfo(context) }) { Text("App info") }
        }
      }
    }

    ModelNote(model, load?.takeIf { it.modelName == model.name }, modelReady)

    OutlinedTextField(
      value = goal,
      onValueChange = { goal = it.take(PromptBuilder.MAX_GOAL_CHARS) },
      modifier = Modifier.fillMaxWidth(),
      enabled = !run.active,
      label = { Text("Task") },
      placeholder = { Text("e.g. What is the population of Munich according to Wikipedia?") },
      minLines = 2,
      maxLines = 5,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Thinking", style = MaterialTheme.typography.bodyMedium)
        Text(
          "Slower; may help with harder tasks.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(checked = thinking, onCheckedChange = { thinking = it }, enabled = !run.active)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (run.active) {
        Button(onClick = { AgentRunner.stop() }) { Text("Stop") }
      } else {
        Button(onClick = { start(goal) }, enabled = modelReady && goal.isNotBlank()) { Text("Start") }
        if (run.status != RunStatus.IDLE) OutlinedButton(onClick = { AgentRunner.clear() }) { Text("Clear") }
      }
    }

    if (run.status != RunStatus.IDLE) RunReport(run)

    ModelMemorySettings(settings, memoryNow, enabled = !run.active) { transform ->
      AgentSettingsStore.update(context, transform)
    }
  }
}

/** Keep-loaded, tile model and screenshot settings, with the measured memory cost. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelMemorySettings(
  settings: AgentSettings,
  memoryNow: String,
  enabled: Boolean,
  update: ((AgentSettings) -> AgentSettings) -> Unit,
) {
  Section("Model memory") {
    Text(
      "Measured on your phone: Gemma 4 E4B takes about 3.2 to 3.4 GB and E2B about 2.4 to 2.6 GB on " +
        "top of the app's 1 GB while loaded. Only one model is loaded at a time.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (memoryNow.isNotEmpty()) Text("Now: $memoryNow", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    Text("Keep the model loaded", style = MaterialTheme.typography.labelLarge)
    for (option in KeepLoaded.entries) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(
          selected = settings.keepLoaded == option,
          onClick = { update { it.copy(keepLoaded = option) } },
          enabled = enabled,
        )
        Column {
          Text(option.title, style = MaterialTheme.typography.bodyMedium)
          Text(option.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
    if (settings.keepLoaded == KeepLoaded.MINUTES) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (minutes in AgentSettings.KEEP_MINUTES_CHOICES) {
          FilterChip(
            selected = settings.keepMinutes == minutes,
            onClick = { update { it.copy(keepMinutes = minutes) } },
            label = { Text("$minutes min") },
            enabled = enabled,
          )
        }
      }
    }
    Text(
      "Android unloads it anyway when memory runs low. Nothing is loaded in advance.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Model for the Quick Settings tile", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for ((name, label) in listOf(DEFAULT_AGENT_MODEL to "E4B (default)", SMALL_AGENT_MODEL to "E2B (lighter)")) {
        FilterChip(selected = settings.tileModel == name, onClick = { update { it.copy(tileModel = name) } }, label = { Text(label) })
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Allow screenshots", style = MaterialTheme.typography.bodyMedium)
        Text(
          "Off: screenshots, screen recording and the system's screen capture can't see Phone Agent " +
            "and spike screens, which show web page text.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(checked = settings.allowScreenshots, onCheckedChange = { on -> update { it.copy(allowScreenshots = on) } })
    }
  }
}

@Composable
private fun ModelNote(model: Model, load: EngineLoad?, ready: Boolean) {
  val warning =
    when {
      model.name == SMALL_AGENT_MODEL ->
        "Gemma 4 E2B is smaller and faster but less reliable at multi-step tasks. E4B is the default."
      model.downloadInfo.imported -> "Imported model: not tested with Phone Agent. Results may be poor."
      model.name != DEFAULT_AGENT_MODEL -> "This model is not tested with Phone Agent."
      else -> null
    }
  if (warning != null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
      Text(warning, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
  }
  val status =
    when {
      ready && load != null ->
        "Model loaded in ${load.loadMs} ms on ${load.backend}; speculative decoding ${load.speculativeDecoding}."
      ready -> "Model loaded."
      else -> "Loading the model…"
    }
  Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RunReport(run: RunState) {
  val context = LocalContext.current
  val heading =
    when (run.status) {
      RunStatus.STARTING -> "Starting…"
      RunStatus.RUNNING -> "Running: step ${run.step} of ${run.maxSteps}"
      RunStatus.FINISHED -> "Finished in ${run.step} steps"
      RunStatus.ABORTED -> "Stopped"
      RunStatus.STOPPED -> "Stopped by you"
      RunStatus.IDLE -> ""
    }
  Text(heading, style = MaterialTheme.typography.titleMedium)
  run.outcome?.let { outcome ->
    Section(if (run.status == RunStatus.FINISHED) "Answer" else "Why it stopped") {
      SelectionContainer { Text(outcome, style = MaterialTheme.typography.bodyMedium) }
    }
  }
  for ((title, text) in run.results) {
    Section("Result: $title") { SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) } }
  }
  Section("Run log") {
    for (entry in run.log) {
      Text(
        (if (entry.step > 0) "${entry.step}. " else "") + entry.text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
      )
    }
    if (run.urls.isNotEmpty()) {
      Spacer(Modifier.height(4.dp))
      Text("Addresses opened:", style = MaterialTheme.typography.labelMedium)
      for (url in run.urls) Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    TextButton(onClick = { DeviceSetup.copy(context, run.logText()) }) { Text("Copy run log") }
  }
  if (run.observations.isNotEmpty()) {
    var show by remember(run.goal) { mutableStateOf(false) }
    Section("What the model saw (${run.observations.size} steps)") {
      Text(
        "The exact screen text of each step, with how its nodes were classified. Included in Copy run log.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") }
      if (show) {
        for (o in run.observations) {
          Text("Step ${o.step}: ${o.summary}", style = MaterialTheme.typography.labelMedium)
          Text("nodes: ${o.classification}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
          SelectionContainer { Text(o.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
          Spacer(Modifier.height(6.dp))
        }
      }
    }
  }
  if (!run.active) {
    Section("Measurements") {
      val report = run.metrics.report(run.status, run.outcome?.takeIf { run.status != RunStatus.FINISHED })
      Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
      Text(
        "Contains no screen content and no addresses.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = { DeviceSetup.copy(context, report) }) { Text("Copy measurements") }
    }
  }
  if (run.step >= LoopGuard.MAX_STEPS && run.status == RunStatus.ABORTED) {
    Text(
      "Tip: a more specific task (name the site, the fact and the format) needs fewer steps.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium))
      content()
    }
  }
}
