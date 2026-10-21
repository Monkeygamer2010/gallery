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

package com.google.ai.edge.gallery.phoneagent.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Phase 2 failure: a loaded web page gave no text to the model. */
class PageReadingTest {
  private val context = ScreenContext(appLabel = "Firefox", url = GeckoFixtures.URL, privateTab = true)

  private fun read(root: FakeNode, budget: Int = ScreenSerializer.DEFAULT_TOKEN_BUDGET): ScreenRead =
    ScreenSerializer.read(GeckoFixtures.copy(root).root, GeckoFixtures.SCREEN, context, budget)

  @Test
  fun containersKeepTheirOwnBoundsNotTheirLastDescendants() {
    val copied = GeckoFixtures.copy(GeckoFixtures.wikipediaArticle(), prune = false).root
    assertEquals(GeckoFixtures.SCREEN, copied.bounds)
    val gecko = copied.children[1]
    assertEquals(ScreenSerializer.WEB_VIEW_CLASS, gecko.className)
    assertEquals(Bounds(0, 220, 1080, 2340), gecko.bounds)
    // The document reaches the footer, but its own bounds start under the toolbar.
    assertEquals(220, gecko.children.single().bounds.top)
  }

  @Test
  fun farPagePartsAreSkippedWhileCopyingButNearOnesAreKept() {
    val copied = GeckoFixtures.copy(GeckoFixtures.wikipediaArticle())
    // "Notable alumni" (5200) and the footer (9000) lie more than a screen below; "History" (2600) does not.
    assertEquals(2, copied.prunedPageSubtrees)
    val texts = mutableListOf<String>()
    copied.root.forEachNode { node -> node.text?.let { texts += it } }
    assertTrue(texts.any { it.startsWith("History section") })
    assertFalse(texts.any { it.startsWith("Footer") })
  }

  @Test
  fun pageTextLinksAndInputsReachTheReadAlthoughThePageSaysNotVisible() {
    val result = read(GeckoFixtures.wikipediaArticle())
    val text = result.text
    assertTrue(text, text.contains("heading \"University of Bologna\""))
    assertTrue(text, text.contains("Teaching began around 1088."))
    assertTrue(text, text.contains("link \"Bologna\""))
    assertTrue(text, text.contains("text \"Motto: Alma mater studiorum\""))
    assertTrue(text, text.contains("input \"Search Wikipedia\" empty"))
    assertTrue(result.pageArea)
    assertTrue(result.pageLines >= 5)
    // Toolbar elements are still there and are not page lines.
    assertTrue(text.contains("button \"Site information\""))
    assertFalse(result.elements.first { it.label == "Site information" }.inPage)
  }

  @Test
  fun onlyTheVisiblePartOfThePageIsRead() {
    val text = read(GeckoFixtures.wikipediaArticle()).text
    assertFalse(text.contains("History section"))
    assertFalse(text.contains("Footer"))
    assertTrue(text.contains("Scroll: more below"))
    assertFalse(text.contains("more above"))
  }

  @Test
  fun scrollingShowsTheNextPartOfThePage() {
    val text = read(GeckoFixtures.wikipediaArticle(scrolledBy = 2300)).text
    assertTrue(text, text.contains("History section text further down the page."))
    assertFalse(text.contains("Teaching began"))
    assertTrue(text.contains("more above"))
  }

  @Test
  fun passwordValuesOnPagesNeverAppear() {
    val text = read(GeckoFixtures.wikipediaArticle()).text
    assertTrue(text.contains("input \"Password\" password"))
    assertFalse(text.contains("hunter2"))
  }

  @Test
  fun aPageThatReportsItselfVisibleReadsTheSame() {
    assertEquals(
      read(GeckoFixtures.wikipediaArticle()).text,
      read(GeckoFixtures.wikipediaArticle(pageFlaggedVisible = true)).text,
    )
  }

  @Test
  fun theTokenCapStillApplies() {
    val result = read(GeckoFixtures.wikipediaArticle(), budget = 120)
    assertTrue(result.truncated)
    assertTrue(result.estimatedTokens <= 120)
    assertTrue(result.text.contains("more elements not shown"))
    assertTrue((result.diagnostics.dropped[DropReason.TOKEN_CAP] ?: 0) > 0)
  }

  @Test
  fun diagnosticsCountDroppedNodesByReason() {
    val d = read(GeckoFixtures.wikipediaArticle()).diagnostics
    assertNotNull(d.pageArea)
    assertEquals(0, d.pageNodesFlaggedVisible)
    assertTrue(d.pageNodes > 5)
    assertTrue((d.dropped[DropReason.OFFSCREEN] ?: 0) >= 1)
    assertTrue((d.dropped[DropReason.EMPTY_BOUNDS] ?: 0) >= 1)
    assertTrue((d.dropped[DropReason.NO_TEXT] ?: 0) >= 1)
    assertTrue(d.samples.isNotEmpty())
    assertTrue(d.samples.first().inPage)
    val report = d.report()
    assertTrue(report, report.contains("Page visibility rule: on-screen bounds"))
    assertTrue(report.contains("outside the screen"))
    // Diagnostics never carry text, only lengths.
    assertFalse(report.contains("History section"))
    assertTrue(d.summary().contains("flagged visible"))
  }

  @Test
  fun nativeViewsStillNeedTheirVisibleFlag() {
    val hiddenPanel =
      FakeNode(
        className = "android.widget.FrameLayout",
        bounds = GeckoFixtures.SCREEN,
        children = listOf(FakeNode(text = "Hidden tab tray", visible = false, bounds = Bounds(0, 300, 1080, 400))),
      )
    val result = read(hiddenPanel)
    assertFalse(result.text.contains("Hidden tab tray"))
    assertEquals(1, result.diagnostics.dropped[DropReason.NOT_VISIBLE])
  }
}
