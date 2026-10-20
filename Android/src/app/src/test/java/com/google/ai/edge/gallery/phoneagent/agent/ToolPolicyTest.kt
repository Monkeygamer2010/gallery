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

import com.google.ai.edge.gallery.phoneagent.screen.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OWN = "com.google.ai.edge.gallery.phoneagent"
private val PRIVATE_FIREFOX = ScreenFacts("org.mozilla.firefox", "Firefox", privateTab = true)
private val NORMAL_FIREFOX = ScreenFacts("org.mozilla.firefox", "Firefox", privateTab = false)
private val UNKNOWN_FIREFOX = ScreenFacts("org.mozilla.firefox", "Firefox", privateTab = null)
private val LINK = Element(1, 10, "link", "Munich - Wikipedia", emptyList())
private val INPUT = Element(2, 11, "input", "Search", listOf("empty"))
private val PASSWORD = Element(3, 12, "input", "Password", listOf("password"))

class ToolPolicyTest {
  private fun allowed(action: AgentAction, facts: ScreenFacts = PRIVATE_FIREFOX, element: Element? = null) =
    ToolPolicy.check(action, facts, OWN, element) == Decision.Allow

  @Test
  fun readsOnlyFirefoxPrivateTabs() {
    assertEquals(Decision.Allow, ToolPolicy.readDecision(PRIVATE_FIREFOX, OWN))
    for (facts in
      listOf(
        NORMAL_FIREFOX,
        UNKNOWN_FIREFOX,
        ScreenFacts(OWN, "PhoneAgent"),
        ScreenFacts("com.android.settings", "Settings"),
        ScreenFacts("com.samsung.accessibility", "Accessibility"),
        ScreenFacts("com.google.android.apps.messaging", "Messages"),
        ScreenFacts("com.sec.android.app.launcher", "One UI Home"),
        ScreenFacts(null),
      )) {
      assertTrue("$facts must not be read", ToolPolicy.readDecision(facts, OWN) is Decision.Refuse)
    }
  }

  @Test
  fun actsOnElementsOnlyInPrivateTabsAndOnlyOnCurrentIds() {
    assertTrue(allowed(AgentAction.Tap(1), element = LINK))
    assertTrue(allowed(AgentAction.Scroll(ScrollDirection.DOWN)))
    assertTrue(allowed(AgentAction.Back))
    assertTrue(!allowed(AgentAction.Tap(1), NORMAL_FIREFOX, LINK))
    assertTrue(!allowed(AgentAction.Tap(1), ScreenFacts("com.android.settings"), LINK))
    assertTrue(!allowed(AgentAction.Tap(1), ScreenFacts(OWN), LINK))
    assertTrue(!allowed(AgentAction.Back, ScreenFacts("com.android.settings")))
    assertTrue(!allowed(AgentAction.Scroll(ScrollDirection.UP), ScreenFacts("com.google.android.gm")))
    assertTrue(!allowed(AgentAction.Tap(7), element = null))
  }

  @Test
  fun neverReadsOrOperatesFirefoxsOwnSettingsAndMenus() {
    val settings = PRIVATE_FIREFOX.copy(browserOwnScreen = true)
    assertTrue(ToolPolicy.readDecision(settings, OWN) is Decision.Refuse)
    assertTrue(!allowed(AgentAction.Tap(1), settings, LINK))
    assertTrue(!allowed(AgentAction.Scroll(ScrollDirection.DOWN), settings))
    // Back leaves a menu or settings page, but only in private mode.
    assertTrue(allowed(AgentAction.Back, settings))
    assertTrue(!allowed(AgentAction.Back, NORMAL_FIREFOX.copy(browserOwnScreen = true)))
  }

  @Test
  fun neverTypesIntoPasswordFields() {
    assertTrue(allowed(AgentAction.TypeText(2, "munich", submit = true), element = INPUT))
    val decision = ToolPolicy.check(AgentAction.TypeText(3, "hunter2", submit = false), PRIVATE_FIREFOX, OWN, PASSWORD)
    assertTrue(decision is Decision.Refuse)
    assertTrue(!allowed(AgentAction.TypeText(2, "x", submit = false), NORMAL_FIREFOX, INPUT))
  }

  @Test
  fun appAndUrlToolsWorkFromAnyScreen() {
    val anywhere = ScreenFacts(OWN)
    assertTrue(allowed(AgentAction.OpenApp("Firefox"), anywhere))
    assertTrue(allowed(AgentAction.OpenApp("firefox browser"), anywhere))
    assertTrue(allowed(AgentAction.OpenApp("org.mozilla.firefox"), anywhere))
    assertTrue(allowed(AgentAction.OpenUrl("https://en.wikipedia.org/wiki/Munich"), anywhere))
    assertTrue(allowed(AgentAction.Home, anywhere))
    assertTrue(allowed(AgentAction.Finish("done"), anywhere))
    assertTrue(allowed(AgentAction.Note("n"), anywhere))
    assertTrue(!allowed(AgentAction.OpenApp("Settings"), anywhere))
    assertTrue(!allowed(AgentAction.OpenApp("Messages"), anywhere))
    assertTrue(!allowed(AgentAction.OpenApp("Chrome"), anywhere))
  }

  @Test
  fun opensOnlyPublicHttpsAddresses() {
    assertEquals("https://duckduckgo.com/?q=munich+weather", ToolPolicy.safeUrl("https://duckduckgo.com/?q=munich+weather"))
    assertEquals("https://en.wikipedia.org", ToolPolicy.safeUrl("en.wikipedia.org"))
    for (bad in
      listOf(
        "http://example.org",
        "javascript:alert(1)",
        "file:///sdcard/x",
        "intent://scan/#Intent;scheme=zxing;end",
        "content://contacts/people",
        "https://user:pw@example.org",
        "https://localhost/x",
        "https://192.168.1.1/",
        "https://10.0.0.1",
        "https://[::1]/",
        "https://printer.local/",
        "https://router.lan",
        "https://example.org/" + "a".repeat(2100),
        "https://example.org/a b",
      )) {
      assertNull(bad, ToolPolicy.safeUrl(bad))
    }
  }
}
