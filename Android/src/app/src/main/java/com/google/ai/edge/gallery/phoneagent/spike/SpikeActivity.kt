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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.google.ai.edge.gallery.phoneagent.os.NoKeyboardLearning
import com.google.ai.edge.gallery.phoneagent.os.optOutOfOsCapture
import com.google.ai.edge.gallery.ui.theme.GalleryTheme

/**
 * Phase 1 spike tools: the Screen Inspector and the tap/type/scroll and Firefox tests. Not
 * exported; opened from Settings. Results live in [SpikeSession] (memory only).
 */
class SpikeActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // Like MainActivity: never restore saved state; this screen shows other apps' text.
    super.onCreate(null)
    optOutOfOsCapture(this)
    enableEdgeToEdge()
    setContent { GalleryTheme { NoKeyboardLearning { SpikeScreen(onClose = { finish() }) } } }
  }
}
