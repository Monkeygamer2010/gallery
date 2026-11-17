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

// PhoneAgent stand-ins. Upstream's speech recognition code (this package) is out of the build
// (app/phoneagent-excluded-sources.txt). These inert types have the names and the few members the
// rest of upstream's code refers to, so it compiles unchanged. None of them records, recognizes or
// connects to anything. Keep them in step with upstream after a sync (docs/UPSTREAM_SYNC.md).
@file:Suppress("UNUSED_PARAMETER", "unused")

package com.google.ai.edge.gallery.services.asr

import android.content.Context
import com.google.ai.edge.gallery.data.DataStoreRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The engine names upstream's settings code refers to. No engine exists in this build. */
enum class AsrEngineType {
  SYSTEM_DEFAULT,
  MLKIT_BASIC,
  MLKIT_ADVANCED,
  SPEECH_PROCESSOR,
}

/** Never starts a recognizer. */
class SystemSpeechAsrService(context: Context)

/** Holds no settings: fixed values, nothing read or stored. */
@Singleton
class AsrSettingsRepository @Inject constructor() {
  constructor(dataStoreRepository: DataStoreRepository, scope: CoroutineScope) : this()

  val selectedEngineType: StateFlow<AsrEngineType> = MutableStateFlow(AsrEngineType.SYSTEM_DEFAULT)
  val selectedModelId: StateFlow<String> = MutableStateFlow("")
  val selectedLanguageCode: StateFlow<String> = MutableStateFlow("")
  val showInterimResults: StateFlow<Boolean> = MutableStateFlow(false)
}

/** Provides no engine: none is ever active. */
@Singleton
class AsrServiceProvider @Inject constructor() {
  constructor(engines: Map<AsrEngineType, Any>, asrSettingsRepository: AsrSettingsRepository) : this()

  val activeEngineType: StateFlow<AsrEngineType?> = MutableStateFlow(null)
}
