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

package com.google.ai.edge.gallery.phoneagent.sync

/**
 * Switches read by the fork's marked blocks in upstream files, for upstream features that are in
 * the source tree but not in the build (app/phoneagent-excluded-sources.txt, docs/UPSTREAM_SYNC.md).
 *
 * These are not settings. Turning one on does not bring the feature back: its code is not compiled.
 */
object ExcludedFeatures {
  /** The "Supplementary" (speech models) tab of the model manager. */
  const val SPEECH_MODELS_TAB = false
}
