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

package com.google.ai.edge.gallery.phoneagent.agent

import com.google.ai.edge.gallery.phoneagent.browser.FirefoxReader
import com.google.ai.edge.gallery.phoneagent.screen.Element
import java.net.URI

/** What the policy needs to know about the app in front. Comes from the browser's own UI only. */
data class ScreenFacts(
  /** The package of the app window in front, or null if none was found. */
  val packageName: String?,
  val appLabel: String? = null,
  val isFirefox: Boolean = FirefoxReader.isFirefox(packageName),
  /** For Firefox: whether its own UI shows private mode. Null for other apps. */
  val privateTab: Boolean? = null,
  /**
   * For Firefox: neither a web page nor Firefox's home page is on screen, i.e. its own settings,
   * menus or add-on pages, which the agent never reads or operates.
   */
  val browserOwnScreen: Boolean = false,
)

sealed interface Decision {
  data object Allow : Decision

  /** [reason] goes to the model as the action's result and to the run log. */
  data class Refuse(val reason: String) : Decision
}

/**
 * The rules every tool call passes before it touches the phone. They live in code: nothing the
 * model says, and nothing on screen, can change them (SECURITY.md, Agent).
 *
 * Phase 2 scope: the agent reads and acts only in Firefox private tabs. It can open Firefox (a new
 * private tab) and https:// addresses in a private tab, press Home, and keep notes and results.
 * Phase 4's per-app allowlist widens this; until then every other app is refused.
 */
object ToolPolicy {
  /** Apps the agent never reads or operates, whatever later phases allow. */
  val NEVER_OPERATE =
    setOf(
      "com.android.settings",
      "com.samsung.accessibility", // One UI's accessibility settings pages
      "com.android.permissioncontroller",
      "com.google.android.permissioncontroller",
      "com.android.packageinstaller",
      "com.google.android.packageinstaller",
    )

  private val FIREFOX_NAMES =
    setOf("firefox", "firefox browser", "mozilla firefox", "firefox private", "firefox private tab", "browser")

  private val PRIVATE_HOST_SUFFIXES = listOf(".local", ".localhost", ".internal", ".lan", ".home.arpa", ".onion")

  /** Whether the screen in front may be read, i.e. shown to the model. */
  fun readDecision(facts: ScreenFacts, ownPackage: String): Decision {
    val pkg = facts.packageName ?: return Decision.Refuse("No app window was found.")
    if (pkg == ownPackage) {
      return Decision.Refuse("PhoneAgent itself is in front; it never reads its own screen. Use open_url or open_app.")
    }
    if (pkg in NEVER_OPERATE) return Decision.Refuse("Settings is in front; the agent never reads or acts there.")
    if (!facts.isFirefox) {
      val app = facts.appLabel ?: pkg
      return Decision.Refuse(
        "${oneLine(app, 40)} is not available to the agent; only Firefox private tabs are. Use open_url or open_app."
      )
    }
    if (facts.privateTab != true) {
      return Decision.Refuse("This Firefox tab is not private, so it is not read. Use open_url to open a private tab.")
    }
    if (facts.browserOwnScreen) {
      return Decision.Refuse("Firefox's own menus and settings are off limits. Use back, or open_url to open a page.")
    }
    return Decision.Allow
  }

  /**
   * Whether [action] may run now. [element] is the element its id names on the current screen
   * (null if there is none); the runner checks again right before acting that the id still means
   * the same element.
   */
  fun check(action: AgentAction, facts: ScreenFacts, ownPackage: String, element: Element?): Decision =
    when (action) {
      AgentAction.ReadScreen,
      is AgentAction.Note,
      is AgentAction.SaveResult,
      is AgentAction.Finish,
      AgentAction.Home -> Decision.Allow
      is AgentAction.OpenApp ->
        if (appTarget(action.name) != null) Decision.Allow
        else Decision.Refuse("Only Firefox can be opened. Use open_app(\"Firefox\") or open_url.")
      is AgentAction.OpenUrl ->
        if (safeUrl(action.url) != null) Decision.Allow
        else Decision.Refuse("Only public https:// addresses can be opened, at most ${FirefoxReader.MAX_URL_LENGTH} characters.")
      // Back may also leave Firefox's own menus, but only in private mode.
      AgentAction.Back ->
        if (facts.isFirefox && facts.privateTab == true) Decision.Allow
        else onReadableScreen(facts, ownPackage) ?: Decision.Allow
      is AgentAction.Scroll -> onReadableScreen(facts, ownPackage) ?: Decision.Allow
      is AgentAction.Tap ->
        onReadableScreen(facts, ownPackage)
          ?: if (element == null) noElement(action.id) else Decision.Allow
      is AgentAction.TypeText ->
        onReadableScreen(facts, ownPackage)
          ?: when {
            element == null -> noElement(action.id)
            "password" in element.states -> Decision.Refuse("Typing into password fields is not allowed.")
            else -> Decision.Allow
          }
    }

  /** The address to open, or null if it is not allowed: https only, public host names only. */
  fun safeUrl(input: String): String? {
    val url = FirefoxReader.safeHttpsUrl(input) ?: return null
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.trimEnd('.') ?: return null
    if (host == "localhost" || PRIVATE_HOST_SUFFIXES.any { host.endsWith(it) }) return null
    if (host.startsWith("[") || host.contains(':')) return null // IPv6 literal
    if (host.split('.').all { part -> part.isNotEmpty() && part.all { it.isDigit() } }) return null // IPv4 literal
    return url
  }

  /** The only app the agent may open in Phase 2. */
  enum class AppTarget {
    FIREFOX_PRIVATE_TAB
  }

  fun appTarget(name: String): AppTarget? {
    val normalized = name.trim().lowercase().replace(Regex("\\s+"), " ").removeSuffix(".")
    return if (normalized in FIREFOX_NAMES || FirefoxReader.isFirefox(normalized)) AppTarget.FIREFOX_PRIVATE_TAB
    else null
  }

  private fun onReadableScreen(facts: ScreenFacts, ownPackage: String): Decision.Refuse? =
    readDecision(facts, ownPackage) as? Decision.Refuse

  private fun noElement(id: Int) =
    Decision.Refuse("There is no element [$id] on the current screen. Use an id from the current screen.")
}
