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

package com.google.ai.edge.gallery.phoneagent.browser

import com.google.ai.edge.gallery.phoneagent.screen.Bounds
import com.google.ai.edge.gallery.phoneagent.screen.UiNode
import java.net.URI

/** What the spike could tell about Firefox's state from its accessibility tree. */
data class BrowserState(
  /** The address shown in the toolbar, or null if none was found. */
  val url: String?,
  /** Where [url] came from (a view id or a heuristic), for the spike report. */
  val urlSource: String?,
  /** Browser UI elements (never page content) that mention private browsing. */
  val privateEvidence: List<String>,
) {
  val isPrivate: Boolean
    get() = privateEvidence.isNotEmpty()

  val domain: String?
    get() = url?.let { FirefoxReader.domainOf(it) }
}

/**
 * Firefox-specific helpers for the Phase 1 spike. Everything here reads the browser's own UI; page
 * content (the subtree of the web view) is never used as evidence, because a page could simply
 * contain the word "private".
 */
object FirefoxReader {
  /** Firefox for Android and close builds that share its code (Fenix). */
  val PACKAGES =
    listOf(
      "org.mozilla.firefox",
      "org.mozilla.firefox_beta",
      "org.mozilla.fenix",
      "org.mozilla.fennec_fdroid",
      "io.github.forkmaintainers.iceraven",
    )

  /** Fenix's private-tab action, handled by its IntentReceiverActivity (spike option b). */
  const val ACTION_OPEN_PRIVATE_TAB = "org.mozilla.fenix.OPEN_PRIVATE_TAB"
  const val INTENT_RECEIVER_ACTIVITY = "org.mozilla.fenix.IntentReceiverActivity"

  const val MAX_URL_LENGTH = 2048

  /** Toolbar view ids that hold the address (display and edit mode). */
  private val URL_VIEW_IDS =
    listOf(
      "mozac_browser_toolbar_url_view",
      "mozac_browser_toolbar_edit_url_view",
      "url_bar_title",
      "toolbar_url",
    )

  private val URL_LIKE = Regex("^(https?://)?[a-z0-9-]+(\\.[a-z0-9-]+)+(:\\d+)?(/\\S*)?$", RegexOption.IGNORE_CASE)
  private val PRIVATE_WORD = Regex("\\bprivate\\b", RegexOption.IGNORE_CASE)

  fun isFirefox(packageName: String?): Boolean = packageName in PACKAGES

  /**
   * The URL to open, or null if it is not acceptable: `https://` only, no user info, no
   * whitespace or control characters, at most [MAX_URL_LENGTH] characters. A bare host such as
   * "en.wikipedia.org" gets `https://` added.
   */
  fun safeHttpsUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null
    if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    if (uri.rawUserInfo != null) return null
    val host = uri.host ?: return null
    if (!host.contains('.')) return null
    return withScheme
  }

  /** "https://en.wikipedia.org/wiki/X" or "en.wikipedia.org/wiki/X" -> "en.wikipedia.org". */
  fun domainOf(url: String): String? {
    val withScheme = if ("://" in url) url else "https://$url"
    return runCatching { URI(withScheme).host }.getOrNull()?.lowercase()?.removePrefix("www.")
  }

  /** Reads the address and private-mode evidence from Firefox's tree. */
  fun read(root: UiNode, screen: Bounds): BrowserState {
    var url: String? = null
    var urlSource: String? = null
    val evidence = mutableListOf<String>()
    var fallbackUrl: String? = null
    var fallbackSource: String? = null

    fun visit(node: UiNode) {
      if (!node.visible || node.bounds.isEmpty || !node.bounds.intersects(screen)) return
      // Page content: never used as evidence.
      if (node.className == "android.webkit.WebView") return
      val idName = node.viewId?.substringAfterLast('/')
      val text = node.text?.trim()?.takeIf { it.isNotEmpty() }
      val desc = node.contentDescription?.trim()?.takeIf { it.isNotEmpty() }
      if (url == null && idName != null && idName in URL_VIEW_IDS && text != null) {
        url = text
        urlSource = "view id $idName"
      }
      if (fallbackUrl == null && text != null && !node.password && URL_LIKE.matches(text) &&
        (idName?.contains("url") == true || idName?.contains("toolbar") == true || node.editable)) {
        fallbackUrl = text
        fallbackSource = "url-like text in ${idName ?: node.className ?: "a node"}"
      }
      val idMentionsPrivate = idName?.contains("private", ignoreCase = true) == true
      val textMentionsPrivate =
        (text != null && PRIVATE_WORD.containsMatchIn(text)) || (desc != null && PRIVATE_WORD.containsMatchIn(desc))
      if (idMentionsPrivate || textMentionsPrivate) {
        evidence += buildString {
          append(idName ?: node.className?.substringAfterLast('.') ?: "node")
          (desc ?: text)?.let { append(": \"").append(it.take(60)).append('"') }
        }
      }
      for (child in node.children) visit(child)
    }
    visit(root)
    if (url == null) {
      url = fallbackUrl
      urlSource = fallbackSource
    }
    return BrowserState(url, urlSource, evidence.distinct().take(10))
  }
}
