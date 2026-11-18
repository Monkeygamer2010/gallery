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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hugging Face login stays off in this build. With AppAuth's redirect activity removed from the
 * manifest (audit finding N7), the guard is what keeps the app from starting a login whose answer
 * could never arrive: ModelDownloadGates shows [HUGGING_FACE_LOGIN_UNAVAILABLE_MESSAGE] instead and
 * never creates AppAuth's service.
 */
class HuggingFaceLoginTest {
  @Test
  fun loginIsNotConfiguredInThisBuild() {
    assertFalse(isHuggingFaceLoginConfigured())
  }

  @Test
  fun placeholdersAndBlanksCountAsNotConfigured() {
    assertFalse(isHuggingFaceLoginConfigured(clientId = "REPLACE_WITH_ID", redirectUri = "app:/oauth"))
    assertFalse(isHuggingFaceLoginConfigured(clientId = "abc-123", redirectUri = "REPLACE_WITH_YOUR_REDIRECT_URI_IN_HUGGINGFACE_APP"))
    assertFalse(isHuggingFaceLoginConfigured(clientId = "", redirectUri = "app:/oauth"))
    assertTrue(isHuggingFaceLoginConfigured(clientId = "abc-123", redirectUri = "app:/oauth"))
  }

  @Test
  fun theRefusalSaysWhatToDoInstead() {
    assertTrue(HUGGING_FACE_LOGIN_UNAVAILABLE_MESSAGE.contains("paste a read token in Settings"))
  }
}
