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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A clock that only moves when the code under test sleeps. */
internal class FakeTicker : Ticker {
  var time = 0L

  override fun now(): Long = time

  override suspend fun sleep(ms: Long) {
    time += ms
  }
}

/** Firefox in front showing [root] (or nothing), as the accessibility service would read it. */
internal fun observeFirefox(root: FakeNode?, locked: Boolean = false): Observation =
  ObservationBuilder.build(
    RawScreen(
      "org.mozilla.firefox",
      "Firefox",
      root?.let { GeckoFixtures.copy(it).root },
      GeckoFixtures.SCREEN,
      locked = locked,
    ),
    "com.google.ai.edge.gallery.phoneagent",
  )

class ScreenWaiterTest {
  private val ticker = FakeTicker()

  private fun settle(before: Observation?, wait: Wait, screenAt: (Long) -> Observation): Observation =
    runBlocking {
      ScreenWaiter.settle(look = { screenAt(ticker.now()) }, view = { it }, before = before, wait = wait, ticker = ticker)
    }

  // Phase 2.1: a results page kept adding nodes after two reads looked the same, and the old wait
  // returned at those two reads.
  @Test
  fun waitsUntilTheNodeCountHasStoppedGrowingForASecond() {
    val before = observeFirefox(GeckoFixtures.privateHomePage())
    val result =
      settle(before, ScreenWaiter.NAVIGATION) { t ->
        observeFirefox(GeckoFixtures.textPage(lines = 5, below = minOf(t / 400, 6).toInt()))
      }
    // The page stops growing at 2.4 s; the wait then needs a further second without growth.
    assertTrue("returned at ${ticker.time} ms", ticker.time >= 2_400 + ScreenWaiter.QUIET_MS)
    assertTrue(ticker.time < ScreenWaiter.PAGE_LOAD_MS)
    assertEquals(5, result.pageLines)
  }

  @Test
  fun aPageWithoutTextIsWaitedForUntilItsTextArrives() {
    val before = observeFirefox(GeckoFixtures.privateHomePage())
    val result =
      settle(before, ScreenWaiter.NAVIGATION) { t -> observeFirefox(GeckoFixtures.textPage(lines = if (t < 3_000) 0 else 4)) }
    assertEquals(4, result.pageLines)
    assertTrue(ticker.time >= 3_000 + ScreenWaiter.QUIET_MS)
  }

  @Test
  fun givesUpOnAPageWithoutTextAfterTenSeconds() {
    val before = observeFirefox(GeckoFixtures.privateHomePage())
    val result = settle(before, ScreenWaiter.NAVIGATION) { observeFirefox(GeckoFixtures.textPage(lines = 0)) }
    assertTrue(result.pageWithoutContent)
    assertTrue("returned at ${ticker.time} ms", ticker.time in ScreenWaiter.PAGE_LOAD_MS until ScreenWaiter.PAGE_LOAD_MS + 500)
  }

  @Test
  fun anUnchangedScreenComesBackAfterTheChangeWindow() {
    val page = observeFirefox(GeckoFixtures.textPage(lines = 3))
    val result = settle(page, ScreenWaiter.TAP) { observeFirefox(GeckoFixtures.textPage(lines = 3)) }
    assertEquals(page.key, result.key)
    assertTrue("returned at ${ticker.time} ms", ticker.time in 2_500 until 3_500)
  }

  @Test
  fun openUrlStopsWaitingWhenFirefoxStaysOnItsNewTabScreen() {
    val before = observeFirefox(GeckoFixtures.textPage(lines = 3))
    val result = settle(before, ScreenWaiter.OPEN_PAGE) { observeFirefox(GeckoFixtures.privateHomePage()) }
    assertFalse(result.pageArea)
    assertTrue("returned at ${ticker.time} ms", ticker.time in ScreenWaiter.NO_PAGE_MS until 4_000)
  }

  @Test
  fun aLockedPhoneEndsTheWaitAtOnce() {
    val before = observeFirefox(GeckoFixtures.textPage(lines = 3))
    val result = settle(before, ScreenWaiter.NAVIGATION) { t -> if (t < 1_000) before else observeFirefox(null, locked = true) }
    assertTrue(result.locked)
    assertTrue("returned at ${ticker.time} ms", ticker.time <= 1_200)
  }
}
