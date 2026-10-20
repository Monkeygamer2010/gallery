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

package com.google.ai.edge.gallery.phoneagent.os

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.contentcapture.ContentCaptureManager
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Keeps the system's own capture features away from what the app shows (docs/NETWORK.md, "Traffic
 * made by other apps").
 *
 * Call it at the end of onCreate, after `installSplashScreen()`. It reads `window.decorView`, which
 * builds the window from the current theme; before the splash library switches to the app theme
 * that is the splash theme (`Theme.DeviceDefault.DayNight` on API 31+), whose action bar would
 * then be drawn over the app's own top bar.
 *
 * What it turns off:
 * - content capture: the system intelligence service would receive the text on screen;
 * - autofill: autofill services (password managers) would receive the window's text fields;
 * - the Recents screen would keep a screenshot of the last screen (Android 13+).
 * Taking a screenshot yourself still works.
 */
fun optOutOfOsCapture(activity: Activity) {
  val decor = activity.window.decorView
  decor.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    decor.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
  }
  activity.getSystemService(ContentCaptureManager::class.java)?.setContentCaptureEnabled(false)
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    activity.setRecentsScreenshotEnabled(false)
  }
}

/**
 * Asks keyboards not to learn from or store anything typed in [content]
 * (`IME_FLAG_NO_PERSONALIZED_LEARNING`, the flag behind "incognito" typing). Keyboards that sync
 * learned words to the cloud would otherwise keep task prompts.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoKeyboardLearning(content: @Composable () -> Unit) {
  InterceptPlatformTextInput(
    interceptor = { request, nextHandler ->
      val noLearning = PlatformTextInputMethodRequest { outAttrs ->
        request.createInputConnection(outAttrs).also {
          outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
      }
      nextHandler.startInputMethod(noLearning)
    },
    content = content,
  )
}
