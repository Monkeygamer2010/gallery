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

import java.net.URI
import java.util.UUID

/**
 * Download rules for the fork's privacy contract (docs/NETWORK.md).
 *
 * - The Hugging Face token is only ever sent to Hugging Face over HTTPS, never to the other hosts
 *   a model or its extra files come from.
 * - Downloads run only while the app process that the owner started them in is alive. WorkManager
 *   persists work and the system scheduler would otherwise resume it later in the background; a
 *   worker started by a later process sees a different [sessionId] and stops before connecting.
 */
object DownloadPolicy {
  /** Input-data key carrying the [sessionId] a download was queued in. */
  const val KEY_SESSION_ID = "phoneagent_download_session_id"

  /** Random per process: changes every time the app process starts. */
  val sessionId: String = UUID.randomUUID().toString()

  /** True for https URLs on huggingface.co or one of its subdomains. */
  fun isHuggingFaceHttpsUrl(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (uri.rawUserInfo != null) return false
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
    return host == "huggingface.co" || host.endsWith(".huggingface.co")
  }

  /** The Authorization header value for [url], or null when the token must not be sent there. */
  fun authorizationHeaderFor(url: String, token: String?): String? =
    if (!token.isNullOrBlank() && isHuggingFaceHttpsUrl(url)) "Bearer $token" else null

  /** Whether a worker queued with [queuedSessionId] may run in the current process. */
  fun mayRunInThisProcess(queuedSessionId: String?): Boolean = queuedSessionId == sessionId
}
