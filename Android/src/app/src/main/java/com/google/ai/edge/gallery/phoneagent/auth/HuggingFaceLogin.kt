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

package com.google.ai.edge.gallery.phoneagent.auth

import com.google.ai.edge.gallery.common.ProjectConfig

/**
 * Whether the Hugging Face OAuth login has real credentials in this build.
 *
 * Upstream ships placeholders. Creating AppAuth's AuthorizationService binds the default browser's
 * Custom Tabs service and warms it up, so the app only does that when the login can actually work
 * and the owner asked for it.
 */
fun isHuggingFaceLoginConfigured(
  clientId: String = ProjectConfig.clientId,
  redirectUri: String = ProjectConfig.redirectUri,
): Boolean =
  clientId.isNotBlank() &&
    redirectUri.isNotBlank() &&
    !clientId.startsWith("REPLACE_WITH") &&
    !redirectUri.startsWith("REPLACE_WITH")

const val HUGGING_FACE_LOGIN_UNAVAILABLE_MESSAGE =
  "This model needs a Hugging Face token. Hugging Face login isn't set up in this build: " +
    "paste a read token in Settings, or import the model file."
