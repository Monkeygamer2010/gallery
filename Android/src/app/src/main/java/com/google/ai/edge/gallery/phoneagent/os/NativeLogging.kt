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

import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.LogSeverity

/**
 * LiteRT-LM's native code logs to logcat directly, past the Kotlin log stripping (StripDebugLogs).
 * Release and ci builds keep only its errors; debug builds keep warnings. Call before creating an
 * [Engine], so the native library is loaded only when a model is used.
 */
fun limitNativeEngineLogging() {
  Engine.setNativeMinLogSeverity(if (BuildConfig.DEBUG) LogSeverity.WARNING else LogSeverity.ERROR)
}
