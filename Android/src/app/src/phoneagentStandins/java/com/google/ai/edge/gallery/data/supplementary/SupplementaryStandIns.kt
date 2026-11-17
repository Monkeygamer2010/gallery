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

// PhoneAgent stand-ins. Upstream's speech model repository (this package) is out of the build
// (app/phoneagent-excluded-sources.txt). These empty types exist so the model manager, which
// receives them, compiles unchanged. Keep them in step with upstream after a sync
// (docs/UPSTREAM_SYNC.md).
@file:Suppress("unused")

package com.google.ai.edge.gallery.data.supplementary

import javax.inject.Inject
import javax.inject.Singleton

/** Knows no speech model. */
@Singleton class SupplementarySpeechModelRepository @Inject constructor()

/** Selects nothing. */
class SpeechSelectionUseCase @Inject constructor()
