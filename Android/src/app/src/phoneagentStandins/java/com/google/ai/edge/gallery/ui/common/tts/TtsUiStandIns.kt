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

// PhoneAgent stand-ins. Upstream's read-aloud UI (this package) is out of the build
// (app/phoneagent-excluded-sources.txt). The chat screens ask for a controller with
// rememberChatTtsController(); here it is always null, which is upstream's own "no text-to-speech"
// path: no speaker buttons, no banner, no playback. The composables below are never reached and
// draw nothing. Keep them in step with upstream after a sync (docs/UPSTREAM_SYNC.md).
@file:Suppress("UNUSED_PARAMETER", "unused")

package com.google.ai.edge.gallery.ui.common.tts

import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Never created: [rememberChatTtsController] returns null. */
class TtsPlaybackController private constructor() {
  val isAvailable: Boolean = false
  val activeMessageId: StateFlow<String?> = MutableStateFlow(null)

  fun stop() {}
}

/** No controller: the chat has no read-aloud. */
@Composable fun rememberChatTtsController(context: Context): TtsPlaybackController? = null

@Composable
fun TtsChatEffects(
  controller: TtsPlaybackController,
  modelManagerViewModel: ModelManagerViewModel,
  selectedModelName: String,
  isResettingSession: Boolean,
  isStreaming: Boolean,
  lastAgentMessageId: String?,
  lastAgentMessageContent: String?,
  capabilityName: String = "llm_chat",
) {}

data class ChatTtsScrollState(
  val userScrollConnection: NestedScrollConnection,
  val viewportPositionModifier: Modifier,
)

/** A scroll connection that does nothing and an empty modifier. */
@Composable
fun rememberChatTtsScrollState(
  ttsController: TtsPlaybackController?,
  listState: ScrollState,
  viewportHeightPx: Int,
): ChatTtsScrollState = remember {
  ChatTtsScrollState(
    userScrollConnection = object : NestedScrollConnection {},
    viewportPositionModifier = Modifier,
  )
}

@Composable
fun ChatTtsControlBanner(ttsController: TtsPlaybackController?, modifier: Modifier = Modifier) {}

@Composable
fun TtsSpeakerButton(
  messageId: String,
  messageText: String,
  controller: TtsPlaybackController,
  modifier: Modifier = Modifier,
  capabilityName: String = "llm_chat",
) {}

@Composable
fun MessageBodyTtsView(
  content: String,
  controller: TtsPlaybackController,
  modifier: Modifier = Modifier,
) {}
