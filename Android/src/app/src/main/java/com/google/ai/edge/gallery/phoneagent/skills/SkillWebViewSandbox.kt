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

package com.google.ai.edge.gallery.phoneagent.skills

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Content-Security-Policy added to every local skill file while the skill has no network access.
 * `'self'` is the local host only, so fetch, XHR, WebSocket, EventSource, images, scripts, fonts
 * and frames from the internet are refused by the page itself, in addition to
 * [SkillWebViewSandbox.intercept] and `blockNetworkLoads`.
 */
const val OFFLINE_SKILL_CSP =
  "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; form-action 'self'; base-uri 'self'"

/** WebRTC can open UDP connections that no request hook sees; no skill needs it. */
private const val REMOVE_WEBRTC_SCRIPT =
  """(() => {
  for (const name of ['RTCPeerConnection', 'webkitRTCPeerConnection']) {
    try { Object.defineProperty(window, name, { value: undefined, writable: false, configurable: false }); } catch (e) {}
  }
})();"""

/**
 * Sandbox for the WebViews that run skills (docs/NETWORK.md, "Skills").
 * - Serves only the built-in skills (APK assets/skills) and imported skills (files/skills), never
 *   the rest of the app's private folder. Anything else on the local host is a 404.
 * - Without [allowNetwork], every other request is refused and counted, top-level navigation away
 *   is cancelled, and local pages carry [OFFLINE_SKILL_CSP].
 * - Always: no file:// or content:// access, no WebRTC, no service-worker network loads.
 */
class SkillWebViewSandbox(context: Context) {
  /** The per-skill "Allow network" switch for the page currently loaded. */
  @Volatile var allowNetwork: Boolean = false

  private val blockedCount = AtomicInteger(0)

  private val assets = WebViewAssetLoader.AssetsPathHandler(context)
  private val loader =
    WebViewAssetLoader.Builder()
      .setDomain(SkillPaths.LOCAL_HOST)
      .addPathHandler("/assets/skills/") { path ->
        if (path.split('/').any { it == ".." || it == "." }) null else assets.handle("skills/$path")
      }
      .addPathHandler(
        "/skills/",
        WebViewAssetLoader.InternalStoragePathHandler(context, File(context.filesDir, "skills")),
      )
      .build()

  /** Number of requests refused since the last [resetBlockedCount]. */
  val blockedRequests: Int
    get() = blockedCount.get()

  fun resetBlockedCount() {
    blockedCount.set(0)
  }

  /** For [android.webkit.WebViewClient.shouldInterceptRequest]. */
  fun intercept(request: WebResourceRequest?): WebResourceResponse? {
    val url = request?.url ?: return refused()
    if (isLocal(url)) {
      val response = loader.shouldInterceptRequest(url) ?: return emptyResponse(404, "Not Found")
      if (!allowNetwork) {
        response.responseHeaders =
          HashMap(response.responseHeaders ?: emptyMap()).apply {
            put("Content-Security-Policy", OFFLINE_SKILL_CSP)
          }
      }
      return response
    }
    if (allowNetwork && url.scheme == "https") return null
    return refused()
  }

  /** For [android.webkit.WebViewClient.shouldOverrideUrlLoading]: true cancels the navigation. */
  fun shouldCancelNavigation(request: WebResourceRequest?): Boolean {
    val url = request?.url ?: return true
    if (isLocal(url) || (allowNetwork && url.scheme == "https")) return false
    blockedCount.incrementAndGet()
    return true
  }

  /** Applies the settings for the current [allowNetwork] value. Call on the UI thread. */
  fun applyTo(webView: WebView) {
    webView.settings.apply {
      allowFileAccess = false
      allowContentAccess = false
      blockNetworkLoads = !allowNetwork
      setGeolocationEnabled(false)
    }
  }

  private fun refused(): WebResourceResponse {
    blockedCount.incrementAndGet()
    return emptyResponse(403, "Blocked")
  }

  companion object {
    @Volatile private var serviceWorkersLocked = false

    fun isLocal(url: Uri): Boolean = url.scheme == "https" && url.host == SkillPaths.LOCAL_HOST

    /**
     * One-time setup for a new skill WebView: removes WebRTC from every frame and stops service
     * workers (which bypass the request hook) from loading anything over the network.
     */
    fun install(webView: WebView) {
      if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        WebViewCompat.addDocumentStartJavaScript(webView, REMOVE_WEBRTC_SCRIPT, setOf("*"))
      }
      if (!serviceWorkersLocked) {
        serviceWorkersLocked = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
          val settings = ServiceWorkerControllerCompat.getInstance().serviceWorkerWebSettings
          if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS)) {
            settings.blockNetworkLoads = true
          }
          if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_FILE_ACCESS)) {
            settings.allowFileAccess = false
          }
          if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_CONTENT_ACCESS)) {
            settings.allowContentAccess = false
          }
        }
      }
    }

    fun emptyResponse(status: Int, reason: String): WebResourceResponse =
      WebResourceResponse(
        "text/plain",
        "UTF-8",
        status,
        reason,
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
      )
  }
}
