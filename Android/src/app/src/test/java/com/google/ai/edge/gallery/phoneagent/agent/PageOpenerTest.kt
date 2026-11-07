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

import com.google.ai.edge.gallery.phoneagent.a11y.ActionOutcome
import com.google.ai.edge.gallery.phoneagent.screen.FakeNode
import com.google.ai.edge.gallery.phoneagent.screen.GeckoFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageOpenerTest {
  private val ticker = FakeTicker()
  private var screen: FakeNode = GeckoFixtures.privateHomePage()
  private var typed = 0

  private fun open(
    send: () -> ActionOutcome,
    type: () -> ActionOutcome = {
      typed++
      ActionOutcome(true, "Typed with set-text, then Enter")
    },
  ): Pair<OpenReport, Observation> = runBlocking {
    PageOpener.open(
      url = "https://${GeckoFixtures.URL}",
      before = observeFirefox(screen),
      look = { observeFirefox(screen) },
      view = { it },
      send = send,
      typeAddress = { type() },
      ticker = ticker,
    )
  }

  @Test
  fun theIntentOpensThePageInAPrivateTab() {
    val (report, after) =
      open(
        send = {
          screen = GeckoFixtures.wikipediaArticle()
          ActionOutcome(true, "Sent to Firefox")
        }
      )
    assertTrue(report.loaded)
    assertEquals(0, typed)
    assertTrue(report.forModel, report.forModel.startsWith("Opened ${GeckoFixtures.URL} in a private tab."))
    assertTrue(after.pageLines > 0)
    assertTrue(report.detail, report.detail.contains("private: yes"))
  }

  // Phase 2.1: Firefox showed a blank private tab and never loaded the address.
  @Test
  fun whenFirefoxIgnoresTheAddressItIsTypedIntoTheAddressBar() {
    val (report, after) =
      open(
        send = { ActionOutcome(true, "Sent to Firefox") },
        type = {
          typed++
          screen = GeckoFixtures.wikipediaArticle()
          ActionOutcome(true, "Typed with set-text, then Enter")
        },
      )
    assertEquals(1, typed)
    assertTrue(report.loaded)
    assertTrue(after.pageLines > 0)
    assertTrue(report.detail, report.detail.contains("then typed into the address bar"))
  }

  @Test
  fun aBlankTabIsNeverPassedOffAsThePage() {
    val (report, after) = open(send = { ActionOutcome(true, "Sent to Firefox") })
    assertEquals(1, typed)
    assertFalse(report.loaded)
    assertFalse(after.pageArea)
    assertTrue(report.forModel, report.forModel.startsWith("Firefox did not load the page: after 10 s"))
    assertTrue(report.forModel, report.forModel.contains("a blank private tab"))
  }

  @Test
  fun aPageOutsidePrivateBrowsingIsNotReadAndStopsTheRun() {
    val (report, after) =
      open(
        send = {
          screen = GeckoFixtures.textPage(lines = 3, private = false)
          ActionOutcome(true, "Sent to Firefox")
        }
      )
    assertTrue(report.notPrivate)
    assertFalse(report.loaded)
    assertNull(after.read)
    assertEquals(0, typed)
  }

  @Test
  fun aFailedIntentIsReportedAsItIs() {
    val (report, _) = open(send = { ActionOutcome(false, "Firefox is not installed") })
    assertFalse(report.loaded)
    assertEquals("It did not work: Firefox is not installed.", report.forModel)
    assertEquals(0, typed)
  }

  @Test
  fun theReportShowsTheAddressAndTheTabCount() {
    val (report, _) =
      open(
        send = {
          screen = GeckoFixtures.textPage(lines = 3, tabs = 6)
          ActionOutcome(true, "Sent to Firefox")
        }
      )
    assertTrue(report.loaded)
    assertTrue(report.detail, report.detail.contains("address now: ${GeckoFixtures.URL}"))
    assertTrue(report.detail, report.detail.contains("tabs: 5 -> 6"))
  }
}
