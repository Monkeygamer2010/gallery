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

// PhoneAgent stand-ins. Upstream's dictation UI (this package) is out of the build
// (app/phoneagent-excluded-sources.txt). The chat asks for a dictation view model with
// rememberChatAsrDictationViewModel(); here it is always null, which is upstream's own "no speech
// recognition" path and hides the microphone button. The hold-to-dictate view model says dictation
// is unavailable, which hides the voice toggle in Tiny Garden and Mobile Actions. Nothing here
// records or recognizes. Keep them in step with upstream after a sync (docs/UPSTREAM_SYNC.md).
@file:Suppress("UNUSED_PARAMETER", "unused")

package com.google.ai.edge.gallery.ui.common.asr

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import com.google.ai.edge.gallery.data.Task
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ---- Dictation in the chat input ----

data class ChatAsrDictationUiState(
  val isDictating: Boolean = false,
  val transcript: String = "",
  val audioLevel: Float = 0f,
)

/** Never created: [rememberChatAsrDictationViewModel] returns null. */
class ChatAsrDictationViewModel private constructor() {
  val uiState: StateFlow<ChatAsrDictationUiState> = MutableStateFlow(ChatAsrDictationUiState())

  fun start(onEndedBySession: (transcript: String, failed: Boolean) -> Unit = { _, _ -> }) {}

  fun finish(onTranscript: (String) -> Unit) {}

  fun cancel() {}
}

/** No dictation: the chat input shows no microphone button. */
@Composable fun rememberChatAsrDictationViewModel(context: Context): ChatAsrDictationViewModel? = null

data class ChatAsrDictationActions(
  val uiState: ChatAsrDictationUiState,
  val onStartDictation: () -> Unit,
  val onStopDictation: () -> Unit,
  val onSendDictation: () -> Unit,
  val onCancelDictation: () -> Unit,
  val isMicPermissionDenied: Boolean = false,
)

/** Actions that do nothing, with a state that is never "dictating". */
@Composable
fun rememberChatAsrDictationActions(
  asrDictationUiState: ChatAsrDictationUiState?,
  curMessage: String,
  onValueChanged: (String) -> Unit,
  onSendTranscriptMessage: (String) -> Unit,
  onStartAsrDictation: ((onEndedBySession: (transcript: String, failed: Boolean) -> Unit) -> Unit)? =
    null,
  onFinishAsrDictation: ((onTranscript: (String) -> Unit) -> Unit)? = null,
  onCancelAsrDictation: (() -> Unit)? = null,
  onAsrDictationError: () -> Unit = {},
): ChatAsrDictationActions = remember {
  ChatAsrDictationActions(
    uiState = ChatAsrDictationUiState(),
    onStartDictation = {},
    onStopDictation = {},
    onSendDictation = {},
    onCancelDictation = {},
  )
}

@Composable
fun ChatAsrDictationPanel(
  uiState: ChatAsrDictationUiState,
  onCancel: () -> Unit,
  onStop: () -> Unit,
  onSend: () -> Unit,
  modifier: Modifier = Modifier,
) {}

// ---- Hold to dictate (Tiny Garden, Mobile Actions) ----

data class HoldToDictateUiState(val recognizing: Boolean = false, val recognizedText: String = "")

/** Dictation is never available, so the screens that use it show text input only. */
@HiltViewModel
class HoldToDictateViewModel @Inject constructor() : ViewModel() {
  val uiState: StateFlow<HoldToDictateUiState> = MutableStateFlow(HoldToDictateUiState())

  /** Read by the fork's edit in TextAndVoiceInput.kt. */
  val dictationAvailable: Boolean = false
}

@Composable
fun HoldToDictate(
  task: Task,
  viewModel: HoldToDictateViewModel,
  onDone: (String) -> Unit,
  onAmplitudeChanged: (Int) -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {}

@Composable
fun VoiceRecognizerOverlay(
  task: Task,
  viewModel: HoldToDictateViewModel,
  bottomPadding: Dp,
  curAmplitude: Int,
  modifier: Modifier = Modifier,
) {}
