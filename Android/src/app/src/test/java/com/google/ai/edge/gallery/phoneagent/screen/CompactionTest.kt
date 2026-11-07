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

import com.google.ai.edge.gallery.phoneagent.agent.EvidenceCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compact observation on reconstructions of the Phase 2.1 screens ([PageFixtures]).
 *
 * Estimated tokens before this change, from the previous serializer on the same trees with the
 * same header: DuckDuckGo results 593 (55 lines), article top 471 (56), article top under the
 * fundraising banner 580 (63), infobox 481 (65).
 */
class CompactionTest {
  private fun read(tree: FakeNode, url: String): ScreenRead =
    ScreenSerializer.read(
      GeckoFixtures.copy(tree).root,
      GeckoFixtures.SCREEN,
      ScreenContext(appLabel = "Firefox", packageName = "org.mozilla.firefox", url = url, privateTab = true),
    )

  private val ddg by lazy { read(PageFixtures.duckDuckGoResults(), PageFixtures.DDG_URL) }
  private val top by lazy { read(PageFixtures.wikipediaTop(banner = false), PageFixtures.WIKI_URL) }
  private val banner by lazy { read(PageFixtures.wikipediaTop(banner = true), PageFixtures.WIKI_URL) }
  private val infobox by lazy { read(PageFixtures.wikipediaInfobox(), PageFixtures.WIKI_URL) }

  private fun body(read: ScreenRead) = read.text.lines().filterNot { it.startsWith("App: ") || it.startsWith("URL: ") || it.startsWith("Scroll: ") }

  @Test
  fun everyLoggedScreenIsAtLeastAQuarterSmaller() {
    val before = mapOf("ddg" to 593, "top" to 471, "banner" to 580, "infobox" to 481)
    val after = mapOf("ddg" to ddg, "top" to top, "banner" to banner, "infobox" to infobox)
    for ((name, read) in after) {
      val was = before.getValue(name)
      assertTrue("$name: ${read.estimatedTokens} tokens, before $was", read.estimatedTokens * 4 <= was * 3)
      assertFalse("$name was cut", read.truncated)
    }
  }

  @Test
  fun aParagraphIsOneLineWithItsLinksNumberedInline() {
    val line = top.text.lines().single { it.startsWith("The University of Bologna (") }
    assertTrue(
      line,
      line.startsWith(
        "The University of Bologna ([1]Italian: Alma Mater Studiorum – Università di Bologna, UNIBO) is a " +
          "[2]public research university in [3]Bologna, [4]Italy. ${PageFixtures.LEAD_FACT}, with the university"
      ),
    )
    assertEquals("link \"Bologna\"", top.element(3)?.signature)
    assertTrue(top.element(3)!!.inPage)
    // Plain text has no id: the only numbered page parts are links and controls.
    assertTrue(top.elements.all { it.role == "link" || it.role == "input" || it.role == "button" || it.role == "tab" })
  }

  @Test
  fun inlineIdsPointAtTheLinkNodes() {
    val copied = GeckoFixtures.copy(PageFixtures.wikipediaTop(banner = false))
    for (element in top.elements.filter { it.inPage && it.role == "link" }) {
      assertEquals(element.label, copied.nodes[element.nodeKey].desc)
    }
  }

  @Test
  fun citationsDomIdsAndUnnamedContainersAreGone() {
    for (read in listOf(top, infobox)) {
      assertFalse(read.text, read.text.contains("link \"["))
      assertFalse(read.text.contains("mw-content-text") || read.text.contains("firstHeading") || read.text.contains("mf-section"))
      // The clickable content container used to be a button labelled with the start of the text.
      assertFalse(read.text, read.elements.any { it.role == "button" && it.inPage && it.label.length > 40 })
    }
  }

  @Test
  fun navigationAndBannerAreSkippedButTheSearchFieldAndTabsStay() {
    val text = top.text
    for (gone in listOf("Open main menu", "Personal tools", "\"Watch\"", "\"Edit\"", "\"Language\"", "link \"Wikipedia\"")) {
      assertFalse(gone, text.contains(gone))
    }
    assertTrue(text, text.contains("input \"Search Wikipedia\" empty"))
    assertTrue(text, text.contains("button \"Search\""))
    assertTrue(text, text.contains("tab \"Article\" selected"))
    assertTrue(text, text.contains("tab \"Talk\""))
    val results = ddg.text
    for (gone in listOf("\"Images\"", "\"News\"", "All regions", "Safe search", "Open menu", "link \"DuckDuckGo\"")) {
      assertFalse(gone, results.contains(gone))
    }
    assertTrue(results, results.contains("input \"Search without being tracked\" value=\"University of Bologna founded\""))
  }

  @Test
  fun theFundraisingBannerIsDropped() {
    for (gone in listOf("Please don't skip", "Dear reader", "€3", "€5", "Donate", "Maybe later", "I already donated", "Close")) {
      assertFalse(gone, banner.text.contains(gone))
    }
    assertTrue(banner.text, banner.text.contains(PageFixtures.LEAD_FACT))
    assertTrue((banner.diagnostics.dropped[DropReason.NOTICE] ?: 0) >= 1)
  }

  @Test
  fun mainContentComesFirstAndFirefoxsToolbarLast() {
    val lines = body(top)
    assertEquals("# University of Bologna", lines.first())
    assertEquals("[19] button \"More options\"", lines.last())
    // The address bar no longer repeats the address from the header.
    assertTrue(top.text, top.text.contains("button \"Search or enter address\""))
    assertFalse(top.text.contains("University_of_Bologna. Search"))
  }

  @Test
  fun aResultTitleIsOneLinkAndItsSnippetOneLine() {
    val text = ddg.text
    assertTrue(text, text.contains("link \"University of Bologna - Wikipedia\""))
    assertFalse(text, text.contains("# University of Bologna - Wikipedia"))
    assertTrue(
      text,
      text.contains(
        "\nThe University of Bologna (Italian: Università di Bologna, UNIBO) is a public research university in Bologna, Italy. " +
          "${PageFixtures.LEAD_FACT}, with the university becoming organised"
      ),
    )
    assertTrue(text, text.contains("More at [1]Wikipedia\n"))
  }

  @Test
  fun infoboxRowsKeepTheirFactsAndLinks() {
    val lines = body(infobox)
    assertEquals("c. 1088; 937 years ago", lines[lines.indexOf("Established") + 1])
    assertEquals("90,291 (2023)", lines[lines.indexOf("Students") + 1])
    val location = lines[lines.indexOf("Location") + 1]
    assertTrue(location, Regex("\\[\\d+]Bologna, \\[\\d+]Italy").matches(location))
    assertTrue(infobox.text.contains("link \"Giovanni Molari\""))
  }

  @Test
  fun aQuoteFromTheCompactTextPassesTheEvidenceCheck() {
    val verdict =
      EvidenceCheck.check(
        "Teaching at the University of Bologna began around 1088.",
        "is a public research university in Bologna, Italy. Teaching began around 1088",
        "When did teaching begin at the University of Bologna?",
        listOf(2 to top.text),
      )
    assertTrue(verdict.problem, verdict.ok)
  }
}
