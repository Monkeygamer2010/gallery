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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val SCREEN = Bounds(0, 0, 1080, 2340)
private val BOX = Bounds(0, 0, 100, 100)

private fun n(
  key: Int,
  className: String = "android.view.View",
  text: String? = null,
  desc: String? = null,
  viewId: String? = null,
  editable: Boolean = false,
  children: List<UiNode> = emptyList(),
) =
  UiNode(
    key = key,
    className = className,
    text = text,
    contentDescription = desc,
    viewId = viewId,
    editable = editable,
    bounds = BOX,
    children = children,
  )

private fun firefox(toolbar: List<UiNode>, page: List<UiNode>) =
  n(0, "android.widget.FrameLayout", children = toolbar + n(99, "android.webkit.WebView", children = page))

class FirefoxReaderTest {
  @Test
  fun readsTheAddressFromTheToolbarViewId() {
    val root =
      firefox(
        toolbar = listOf(n(1, text = "en.wikipedia.org/wiki/Munich", viewId = "org.mozilla.firefox:id/mozac_browser_toolbar_url_view")),
        page = listOf(n(2, text = "Munich")),
      )
    val state = FirefoxReader.read(root, SCREEN)
    assertEquals("en.wikipedia.org/wiki/Munich", state.url)
    assertEquals("en.wikipedia.org", state.domain)
    assertEquals("view id mozac_browser_toolbar_url_view", state.urlSource)
  }

  @Test
  fun fallsBackToUrlLikeTextInTheToolbarButNotInThePage() {
    val root =
      firefox(
        toolbar = listOf(n(1, text = "duckduckgo.com/?q=munich", viewId = "org.mozilla.firefox:id/toolbar_title")),
        page = listOf(n(2, text = "evil.example/phish")),
      )
    assertEquals("duckduckgo.com/?q=munich", FirefoxReader.read(root, SCREEN).url)
    val pageOnly = firefox(toolbar = emptyList(), page = listOf(n(2, text = "evil.example/phish")))
    assertNull(FirefoxReader.read(pageOnly, SCREEN).url)
  }

  @Test
  fun privateEvidenceComesFromBrowserUiOnly() {
    val privateTab =
      firefox(
        toolbar = listOf(n(1, desc = "Private browsing", viewId = "org.mozilla.firefox:id/private_browsing_button")),
        page = emptyList(),
      )
    assertTrue(FirefoxReader.read(privateTab, SCREEN).isPrivate)

    val normalTabWithPrivatePage =
      firefox(
        toolbar = listOf(n(1, desc = "Tabs: 3 open", viewId = "org.mozilla.firefox:id/counter_box")),
        page = listOf(n(2, text = "Private browsing - Wikipedia"), n(3, text = "This is private.")),
      )
    val state = FirefoxReader.read(normalTabWithPrivatePage, SCREEN)
    assertFalse(state.isPrivate)
    assertTrue(state.privateEvidence.isEmpty())
  }

  @Test
  fun privateWordMustBeAWord() {
    val root = firefox(toolbar = listOf(n(1, desc = "Deprivatized menu")), page = emptyList())
    assertFalse(FirefoxReader.read(root, SCREEN).isPrivate)
  }

  @Test
  fun onlyHttpsUrlsAreAccepted() {
    assertEquals("https://en.wikipedia.org/wiki/Munich", FirefoxReader.safeHttpsUrl(" https://en.wikipedia.org/wiki/Munich "))
    assertEquals("https://duckduckgo.com/?q=munich", FirefoxReader.safeHttpsUrl("duckduckgo.com/?q=munich"))
    for (bad in
      listOf(
        "",
        "http://example.com",
        "javascript:alert(1)",
        "file:///sdcard/x.html",
        "intent://scan/#Intent;scheme=zxing;end",
        "https://user:pw@example.com",
        "https://exa mple.com",
        "https://example.com/\nx",
        "https://localhost/admin",
        "https://example.com/" + "a".repeat(FirefoxReader.MAX_URL_LENGTH),
      )) {
      assertNull(bad, FirefoxReader.safeHttpsUrl(bad))
    }
  }

  @Test
  fun recognisesFirefoxBuilds() {
    assertTrue(FirefoxReader.isFirefox("org.mozilla.firefox"))
    assertTrue(FirefoxReader.isFirefox("org.mozilla.fenix"))
    assertFalse(FirefoxReader.isFirefox("com.android.chrome"))
    assertFalse(FirefoxReader.isFirefox(null))
  }

  @Test
  fun tellsWebPagesAndTheHomePageFromFirefoxsOwnScreens() {
    val page = FirefoxReader.read(firefox(toolbar = emptyList(), page = listOf(n(2, text = "Munich"))), SCREEN)
    assertTrue(page.webContent)
    assertFalse(page.homepage)
    val home =
      FirefoxReader.read(
        n(0, children = listOf(n(1, desc = "Private browsing", viewId = "org.mozilla.firefox:id/private.browsing.homepage.button"))),
        SCREEN,
      )
    assertTrue(home.homepage)
    assertFalse(home.webContent)
    val settings = FirefoxReader.read(n(0, children = listOf(n(1, text = "Private browsing"), n(2, text = "Search"))), SCREEN)
    assertFalse(settings.webContent)
    assertFalse(settings.homepage)
    assertTrue(settings.isPrivate) // the word alone; the policy still refuses this screen
  }
}
