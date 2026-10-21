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

import com.google.ai.edge.gallery.phoneagent.screen.FakeNode
import com.google.ai.edge.gallery.phoneagent.screen.GeckoFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val OWN = "com.google.ai.edge.gallery.phoneagent"
private const val FIREFOX = "org.mozilla.firefox"
private const val SECRET = "Your code is 482913"

class ObservationBuilderTest {
  private fun observe(pkg: String?, label: String?, root: FakeNode?): Observation =
    ObservationBuilder.build(
      RawScreen(pkg, label, root?.let { GeckoFixtures.copy(it).root }, GeckoFixtures.SCREEN),
      OWN,
    )

  private fun assertNotRead(obs: Observation) {
    assertNull(obs.read)
    assertFalse(obs.modelText.contains(SECRET))
    assertTrue(obs.modelText.contains("(Not read:"))
  }

  @Test
  fun aWikipediaArticleInAPrivateTabReachesTheModelWithItsText() {
    val obs = observe(FIREFOX, "Firefox", GeckoFixtures.wikipediaArticle())
    assertTrue(obs.pageArea)
    assertFalse(obs.pageWithoutContent)
    assertTrue(obs.pageLines >= 5)
    assertTrue(obs.modelText, obs.modelText.contains("Teaching began around 1088."))
    assertTrue(obs.modelText.startsWith("App: Firefox\nURL: ${GeckoFixtures.URL} (private tab)\n"))
    assertEquals(GeckoFixtures.URL, obs.url)
    assertTrue(obs.summary().contains("from the page"))
    assertTrue(obs.classification().contains("page:"))
  }

  @Test
  fun firefoxsPrivateHomePageIsReadButHasNoPage() {
    val obs = observe(FIREFOX, "Firefox", GeckoFixtures.privateHomePage())
    assertTrue(obs.read != null)
    assertFalse(obs.pageArea)
    assertFalse(obs.pageWithoutContent)
  }

  // Checklist E3: other apps are never read, even if a tree is handed in.
  @Test
  fun otherAppsAreNeverRead() {
    for ((pkg, label) in
      listOf(
        "com.google.android.apps.messaging" to "Messages",
        "com.samsung.android.messaging" to "Messages",
        "com.google.android.gm" to "Gmail",
        "com.sec.android.app.launcher" to "One UI Home",
      )) {
      val obs = observe(pkg, label, GeckoFixtures.otherApp(SECRET))
      assertNotRead(obs)
      assertTrue(obs.refusal!!.contains("only Firefox private tabs"))
    }
  }

  // Checklist E4: Android Settings (and One UI's accessibility pages) are never read.
  @Test
  fun settingsAreNeverRead() {
    for (pkg in listOf("com.android.settings", "com.samsung.accessibility", "com.google.android.permissioncontroller")) {
      val obs = observe(pkg, "Settings", GeckoFixtures.otherApp(SECRET))
      assertNotRead(obs)
      assertTrue(obs.refusal!!.contains("Settings is in front"))
    }
  }

  // Checklist E5: Firefox's own settings and menus are never read, even in private mode.
  @Test
  fun firefoxsOwnSettingsAreNeverRead() {
    val obs = observe(FIREFOX, "Firefox", GeckoFixtures.firefoxSettings())
    assertNotRead(obs)
    assertTrue(obs.facts.privateTab == true)
    assertTrue(obs.facts.browserOwnScreen)
    assertTrue(obs.refusal!!.contains("Firefox's own menus and settings are off limits"))
    assertFalse(obs.modelText.contains("Delete browsing data"))
  }

  // Checklist E6: the lock screen (System UI) is never read, and nothing in front means nothing read.
  @Test
  fun theLockScreenAndMissingWindowsAreNeverRead() {
    assertNotRead(observe("com.android.systemui", "System UI", GeckoFixtures.otherApp(SECRET)))
    val none = observe(null, null, null)
    assertNull(none.read)
    assertTrue(none.refusal!!.contains("No app window"))
  }

  @Test
  fun aNormalFirefoxTabIsNotRead() {
    val normal =
      FakeNode(
        className = "android.widget.FrameLayout",
        bounds = GeckoFixtures.SCREEN,
        children =
          listOf(
            FakeNode(desc = "Open tabs: 3", clickable = true, bounds = com.google.ai.edge.gallery.phoneagent.screen.Bounds(900, 90, 990, 210)),
            FakeNode(
              className = "android.webkit.WebView",
              bounds = com.google.ai.edge.gallery.phoneagent.screen.Bounds(0, 220, 1080, 2340),
              children = listOf(FakeNode(text = SECRET, bounds = com.google.ai.edge.gallery.phoneagent.screen.Bounds(0, 300, 1080, 400))),
            ),
          ),
      )
    val obs = observe(FIREFOX, "Firefox", normal)
    assertNotRead(obs)
    assertTrue(obs.refusal!!.contains("not private"))
  }

  @Test
  fun phoneAgentNeverReadsItself() {
    val obs = observe(OWN, "PhoneAgent", GeckoFixtures.otherApp(SECRET))
    assertNotRead(obs)
    assertTrue(obs.refusal!!.contains("PhoneAgent itself"))
  }

  @Test
  fun aFirefoxWindowWithoutATreeSaysSo() {
    val obs = observe(FIREFOX, "Firefox", null)
    assertNull(obs.read)
    assertTrue(obs.refusal!!.contains("could not be read"))
  }
}
