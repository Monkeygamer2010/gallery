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

// PhoneAgent stand-ins. Upstream's text-to-speech code (this package) is out of the build
// (app/phoneagent-excluded-sources.txt). These inert types have the names the rest of upstream's
// code refers to, so it compiles unchanged. Nothing here speaks, plays audio or connects to
// anything. Keep them in step with upstream after a sync (docs/UPSTREAM_SYNC.md).
@file:Suppress("UNUSED_PARAMETER", "unused")

package com.google.ai.edge.gallery.services.tts

import com.google.ai.edge.gallery.data.DataStoreRepository
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

/** Holds no settings and knows no engine. */
@Singleton
class TtsSettingsRepository @Inject constructor() {
  constructor(
    dataStoreRepository: DataStoreRepository,
    ttsServiceProviderLazy: Optional<Any>,
    ioDispatcher: CoroutineDispatcher,
  ) : this()
}

/** Empty: there are no read-aloud settings to show. */
class TtsSettingsUiState
