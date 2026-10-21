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

import com.google.ai.edge.gallery.phoneagent.browser.BrowserState
import com.google.ai.edge.gallery.phoneagent.browser.FirefoxReader
import com.google.ai.edge.gallery.phoneagent.screen.Bounds
import com.google.ai.edge.gallery.phoneagent.screen.ScreenContext
import com.google.ai.edge.gallery.phoneagent.screen.ScreenRead
import com.google.ai.edge.gallery.phoneagent.screen.ScreenSerializer
import com.google.ai.edge.gallery.phoneagent.screen.ScreenStatus
import com.google.ai.edge.gallery.phoneagent.screen.UiNode

/** What the accessibility service read about the app in front, before any policy is applied. */
class RawScreen(
  val packageName: String?,
  val appLabel: String?,
  /** The converted tree, or null when the app was not converted (not Firefox, or no window). */
  val root: UiNode?,
  val screen: Bounds,
  val keyboardOpen: Boolean = false,
  /** The converter's one-line summary, for diagnostics. */
  val converter: String? = null,
)

/** What the agent loop sees in one step. Built by [ObservationBuilder]; plain data. */
data class Observation(
  val facts: ScreenFacts,
  /** The screen read, or null when the policy refused to read this screen. */
  val read: ScreenRead?,
  /** Why the screen was not read (PhoneAgent's own words, no screen text). */
  val refusal: String?,
  val browser: BrowserState? = null,
  val converter: String? = null,
) {
  /** What the model sees between the screen delimiters. */
  val modelText: String
    get() =
      read?.text
        ?: buildString {
          facts.appLabel?.let { append("App: ").append(it).append('\n') }
          append("(Not read: ").append(refusal ?: "unknown").append(')')
        }

  /** An empty, secure or protected window: the run must stop (no workaround). */
  val empty: Boolean
    get() = read?.status == ScreenStatus.EMPTY

  /** Identifies what the model saw, for the loop guard and the settle wait. */
  val key: String
    get() = modelText

  val url: String?
    get() = browser?.url

  /** A web page is on screen in a readable Firefox private tab. */
  val pageArea: Boolean
    get() = read?.pageArea == true

  /** Lines from the web page in the read. */
  val pageLines: Int
    get() = read?.pageLines ?: 0

  /** A web page is on screen, but none of its text reached the read. */
  val pageWithoutContent: Boolean
    get() = pageArea && pageLines == 0

  /** One line for the run log. */
  fun summary(): String {
    val r = read ?: return "not read: ${refusal ?: "unknown"}"
    return buildString {
      append(facts.appLabel ?: facts.packageName ?: "unknown app")
      url?.let { append(", ").append(it) }
      append(": ").append(r.elements.size).append(" elements")
      if (r.pageArea) append(" (").append(r.pageLines).append(" from the page)") else append(" (no web page)")
      append(", about ").append(r.estimatedTokens).append(" tokens")
      if (r.truncated) append(", ").append(r.totalElements - r.elements.size).append(" over the cap")
    }
  }

  /** How the nodes were classified, for the run log and the spike. */
  fun classification(): String =
    listOfNotNull(converter, read?.diagnostics?.summary()).joinToString("; ").ifEmpty { "nothing read" }
}

/**
 * Builds the agent's view of the screen. Apps other than Firefox are never serialized, even when
 * a tree is passed in; a Firefox screen is serialized only when the policy allows reading it
 * ([ToolPolicy.readDecision]): a private tab showing a web page or Firefox's home page.
 */
object ObservationBuilder {
  fun build(raw: RawScreen, ownPackage: String): Observation {
    val pkg = raw.packageName
    if (!FirefoxReader.isFirefox(pkg) || pkg == ownPackage) {
      val facts = ScreenFacts(pkg, raw.appLabel)
      return Observation(facts, null, refusal(facts, ownPackage), converter = raw.converter)
    }
    val root =
      raw.root
        ?: return Observation(
          ScreenFacts(pkg, raw.appLabel),
          null,
          "Firefox's window could not be read; it may still be opening.",
          converter = raw.converter,
        )
    val browser = FirefoxReader.read(root, raw.screen)
    val facts =
      ScreenFacts(
        pkg,
        raw.appLabel,
        privateTab = browser.isPrivate,
        browserOwnScreen = !browser.webContent && !browser.homepage,
      )
    val decision = ToolPolicy.readDecision(facts, ownPackage)
    if (decision is Decision.Refuse) return Observation(facts, null, decision.reason, browser, raw.converter)
    val context = ScreenContext(raw.appLabel, pkg, browser.url, privateTab = true, keyboardOpen = raw.keyboardOpen)
    val read = ScreenSerializer.read(root, raw.screen, context)
    return Observation(facts, read, null, browser, raw.converter)
  }

  private fun refusal(facts: ScreenFacts, ownPackage: String): String =
    (ToolPolicy.readDecision(facts, ownPackage) as? Decision.Refuse)?.reason ?: "Not read."
}
