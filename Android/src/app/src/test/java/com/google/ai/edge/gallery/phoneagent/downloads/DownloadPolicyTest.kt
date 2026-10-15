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

package com.google.ai.edge.gallery.phoneagent.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {
  private val token = "hf_secret"

  @Test
  fun tokenGoesOnlyToHuggingFaceOverHttps() {
    val hf = "https://huggingface.co/litert-community/x/resolve/abc/model.litertlm?download=true"
    assertEquals("Bearer hf_secret", DownloadPolicy.authorizationHeaderFor(hf, token))
    assertEquals(
      "Bearer hf_secret",
      DownloadPolicy.authorizationHeaderFor("https://cdn-lfs.huggingface.co/repos/x", token),
    )
  }

  @Test
  fun tokenNeverGoesToOtherHosts() {
    val others =
      listOf(
        "https://dl.google.com/google-ai-edge-gallery/android/smart_album/samples.zip",
        "https://storage.googleapis.com/mediapipe-models/x.task",
        "https://huggingface.co.evil.example/x",
        "https://evilhuggingface.co/x",
        "https://huggingface.co@evil.example/x",
        "http://huggingface.co/x",
        "huggingface.co/x",
        "ftp://huggingface.co/x",
        "not a url",
        "",
      )
    for (url in others) {
      assertNull("token must not be sent to '$url'", DownloadPolicy.authorizationHeaderFor(url, token))
    }
  }

  @Test
  fun noHeaderWithoutToken() {
    assertNull(DownloadPolicy.authorizationHeaderFor("https://huggingface.co/x", null))
    assertNull(DownloadPolicy.authorizationHeaderFor("https://huggingface.co/x", " "))
  }

  @Test
  fun workFromAnotherProcessMayNotRun() {
    assertTrue(DownloadPolicy.mayRunInThisProcess(DownloadPolicy.sessionId))
    assertFalse(DownloadPolicy.mayRunInThisProcess("some-earlier-session"))
    assertFalse(DownloadPolicy.mayRunInThisProcess(null))
  }
}
