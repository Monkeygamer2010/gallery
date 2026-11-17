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

// PhoneAgent stand-ins. Upstream's speech-model code for the model manager is out of the build
// (app/phoneagent-excluded-sources.txt: ModelManagerAudioSettings, VoicePreviewController,
// TtsModelDetails, SupplementaryModelsViewModel, TaskModelSelectionViewModel,
// SectionedModelSelectionScreen and their helpers). These declarations have the same names, so the
// model manager and the screens that call them compile unchanged, and every one of them does
// nothing: no speech model is registered, shown, downloaded, selected or played.
// Keep them in step with upstream after a sync (docs/UPSTREAM_SYNC.md).
@file:Suppress("UNUSED_PARAMETER", "unused", "UnusedReceiverParameter")

package com.google.ai.edge.gallery.ui.modelmanager

import android.content.Context
import android.media.AudioTrack
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelSelectionConfig
import com.google.ai.edge.gallery.data.ModelSelectionTarget
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.supplementary.SpeechSelectionUseCase
import com.google.ai.edge.gallery.data.supplementary.SupplementarySpeechModelRepository
import com.google.ai.edge.gallery.services.asr.AsrEngineType
import com.google.ai.edge.gallery.services.asr.AsrServiceProvider
import com.google.ai.edge.gallery.services.asr.AsrSettingsRepository
import com.google.ai.edge.gallery.services.tts.TtsSettingsRepository
import com.google.ai.edge.gallery.services.tts.TtsSettingsUiState
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// ---- Speech models in the model manager (upstream: ModelManagerAudioSettings.kt) ----

internal fun createDefaultSupplementaryRepository(
  context: Context,
  asrServiceProvider: AsrServiceProvider,
  ioDispatcher: CoroutineDispatcher,
  repositoryScope: CoroutineScope = CoroutineScope(ioDispatcher),
): SupplementarySpeechModelRepository = SupplementarySpeechModelRepository()

internal fun createDefaultSpeechSelectionUseCase(
  context: Context,
  ttsSettingsRepository: TtsSettingsRepository,
  asrSettingsRepository: AsrSettingsRepository,
  asrServiceProvider: AsrServiceProvider,
  dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
  scope: CoroutineScope = CoroutineScope(dispatcher),
): SpeechSelectionUseCase = SpeechSelectionUseCase()

internal fun ModelManagerViewModel.observeTtsSettingsUiState() {}

internal fun ModelManagerViewModel.cleanupDeletedSpeechModel(model: Model) {}

internal fun ModelManagerViewModel.publishSupplementaryDownloadStatus(
  model: Model,
  status: ModelDownloadStatus,
) {}

/** No speech model from the model list is taken in. */
internal fun ModelManagerViewModel.shouldIncludeSupplementaryAllowlistModel(model: Model): Boolean =
  false

internal fun ModelManagerViewModel.registerAllowlistSpeechModels(
  allSupplementaryModels: List<Model>
) {}

fun ModelManagerViewModel.refreshSupplementaryStatuses() {}

fun ModelManagerViewModel.refreshLanguagePackStatuses(model: Model, languageCodes: List<String>) {}

internal fun ModelManagerViewModel.refreshSupplementaryLanguagePack(
  model: Model,
  languageCode: String,
) {}

internal fun ModelManagerViewModel.refreshTtsSettingsUiState() {}

fun ModelManagerViewModel.checkAsrEngineSupport() {}

fun ModelManagerViewModel.saveAsrEngineType(engineType: AsrEngineType) {}

// ---- The Audio tab of the config sheet and its promo (upstream: ModelManagerAudioSettings.kt) ----

/** No task gets the audio settings promo. */
fun ModelManagerViewModel.isTaskEligibleForAudioConfigOnboarding(task: Task): Boolean = false

/** Returns "already seen", so the promo is never shown and nothing waits for it. */
suspend fun ModelManagerViewModel.refreshAudioConfigOnboardingState(): Boolean = true

fun ModelManagerViewModel.dismissAudioConfigOnboardingForSession() {}

fun ModelManagerViewModel.dismissAudioConfigOnboardingPermanently() {}

fun ModelManagerViewModel.onAudioConfigOpened(task: Task) {}

/** An empty state: the config sheet shows no Audio tab. */
fun ModelManagerViewModel.buildAudioTabUiState(task: Task): ConfigAudioTabUiState =
  ConfigAudioTabUiState()

fun ModelManagerViewModel.observeAudioTabUiState(task: Task): Flow<ConfigAudioTabUiState> =
  flowOf(ConfigAudioTabUiState())

fun ModelManagerViewModel.createAudioTabActions(task: Task): ConfigAudioTabActions =
  ConfigAudioTabActions()

// ---- Voice preview (upstream: VoicePreviewController.kt) ----

/** Plays nothing. */
internal class VoicePreviewController(
  context: Context,
  scope: CoroutineScope,
  ioDispatcher: CoroutineDispatcher,
  ttsSettingsRepository: TtsSettingsRepository,
  speechSelectionUseCase: SpeechSelectionUseCase,
  uiStateProvider: () -> ModelManagerUiState,
  ttsSettingsUiStateProvider: () -> TtsSettingsUiState,
  onRefreshTtsSettingsUiState: () -> Unit,
  onRefreshLanguagePackStatuses: (Model, List<String>) -> Unit,
  onRefreshSupplementaryLanguagePack: (Model, String) -> Unit,
) {
  val uiState: StateFlow<VoicePreviewUiState> = MutableStateFlow(VoicePreviewUiState())
  var playbackHeadProvider: (AudioTrack) -> Long = { 0L }

  fun onCleared() {}

  fun stopActiveVoicePreview() {}
}

fun ModelManagerViewModel.stopActiveVoicePreview() {}

// ---- The "Supplementary" tab of the model manager (upstream: TtsModelDetails.kt) ----

/** Only [MAIN] is reachable: GlobalModelManager doesn't show the tab row in this build. */
internal enum class ModelManagerTab {
  MAIN,
  SUPPLEMENTARY,
}

internal class SupplementaryModelsActions

@Composable
internal fun rememberSupplementaryModelsActions(
  supplementaryModelsViewModel: SupplementaryModelsViewModel?,
  modelManagerViewModel: ModelManagerViewModel,
): SupplementaryModelsActions = remember { SupplementaryModelsActions() }

/** Adds no items. */
internal fun LazyListScope.supplementaryModelItems(
  uiState: SupplementaryModelsUiState,
  modelItemExpandedStates: MutableMap<String, Boolean>,
  viewModel: ModelManagerViewModel,
  tosViewModel: TosViewModel?,
  actions: SupplementaryModelsActions,
) {}

/** Holds an empty list (upstream: SupplementaryModelsViewModel.kt). */
@HiltViewModel
class SupplementaryModelsViewModel @Inject constructor() : ViewModel() {
  val uiState: StateFlow<SupplementaryModelsUiState> = MutableStateFlow(SupplementaryModelsUiState())

  fun refreshOnResume() {}
}

// ---- The several-models selection screen (upstream: TaskModelSelectionViewModel.kt and
// SectionedModelSelectionScreen.kt). Only the XR language teacher asks for it, and that task is
// out of the build, so no task has a selection config and none of this is reached. ----

interface ModelSelectionHost

fun ModelManagerViewModel.asModelSelectionHost(): ModelSelectionHost = object : ModelSelectionHost {}

/** Complete, so a task is never sent to the selection screen. */
fun ModelSelectionHost.isSavedModelSelectionComplete(
  task: Task,
  config: ModelSelectionConfig,
  mainModel: Model,
): Boolean = true

/** Shows nothing and goes back. */
@Composable
fun SectionedModelSelectionScreen(
  task: Task,
  config: ModelSelectionConfig,
  modelManagerViewModel: ModelManagerViewModel,
  enableAnimation: Boolean,
  navigateUp: () -> Unit,
  onLaunch: (Model) -> Unit,
  modifier: Modifier = Modifier,
  expandTarget: ModelSelectionTarget? = null,
  onExpandTargetHandled: () -> Unit = {},
) {
  androidx.compose.runtime.LaunchedEffect(Unit) { navigateUp() }
}
