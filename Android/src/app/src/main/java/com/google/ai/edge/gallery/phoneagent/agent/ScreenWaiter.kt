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

import android.os.SystemClock
import kotlinx.coroutines.delay

/** Time for [ScreenWaiter]; tests use a virtual clock. */
interface Ticker {
  fun now(): Long

  suspend fun sleep(ms: Long)

  object Real : Ticker {
    override fun now(): Long = SystemClock.elapsedRealtime()

    override suspend fun sleep(ms: Long) = delay(ms)
  }
}

/**
 * How to wait after an action: for the screen to change (within [changeWithinMs]; a screen that
 * doesn't change by then is returned as it is), then for it to settle, at most [maxMs] in all.
 * With [requirePage], only a web page with text counts as settled (used after open_url).
 */
class Wait(val expectChange: Boolean, val changeWithinMs: Long, val maxMs: Long, val requirePage: Boolean = false)

/**
 * Waits for the screen to settle after an action. Settled means: it changed (when expected), the
 * last two reads are the same, the node count hasn't grown for [QUIET_MS] (pages keep adding
 * nodes while they render: a DuckDuckGo results page grew from 246 to about 580), and a web page
 * on screen shows text. Returns the last read either way, which the next step uses, and at once
 * when the phone is locked.
 */
object ScreenWaiter {
  const val PAGE_LOAD_MS = 10_000L
  private const val FIRST_LOOK_MS = 400L
  private const val POLL_MS = 400L
  const val QUIET_MS = 1_000L

  /**
   * With [Wait.requirePage]: a changed screen that has held still this long without any web view
   * (Firefox's new-tab screen) means no page is coming; open_url then types the address instead.
   */
  const val NO_PAGE_MS = 2_500L

  val START = Wait(expectChange = false, changeWithinMs = 0, maxMs = PAGE_LOAD_MS)
  val NAVIGATION = Wait(expectChange = true, changeWithinMs = 4_000, maxMs = PAGE_LOAD_MS)
  val OPEN_PAGE = Wait(expectChange = true, changeWithinMs = 4_000, maxMs = PAGE_LOAD_MS, requirePage = true)
  val TAP = Wait(expectChange = true, changeWithinMs = 2_500, maxMs = PAGE_LOAD_MS)
  val SMALL = Wait(expectChange = true, changeWithinMs = 1_500, maxMs = 3_000)
  val QUICK = Wait(expectChange = false, changeWithinMs = 0, maxMs = 3_000)

  /** [look] reads the screen; [view] gives a read's observation (the reads carry live nodes too). */
  suspend fun <T> settle(
    look: suspend () -> T,
    view: (T) -> Observation,
    before: T?,
    wait: Wait,
    ticker: Ticker = Ticker.Real,
  ): T {
    val start = ticker.now()
    val beforeKey = before?.let { view(it).key }
    ticker.sleep(FIRST_LOOK_MS)
    var last = look()
    if (view(last).locked) return last
    var count = nodes(view(last))
    var countSince = ticker.now()
    var keySince = ticker.now()
    var changed = !wait.expectChange || beforeKey == null || view(last).key != beforeKey
    while (true) {
      val elapsed = ticker.now() - start
      if (elapsed >= wait.maxMs) return last
      // Nothing happened: don't wait for a page that isn't coming.
      if (!changed && elapsed >= wait.changeWithinMs) return last
      ticker.sleep(POLL_MS)
      val next = look()
      val nextView = view(next)
      // A locked phone shows nothing to wait for: the run stops.
      if (nextView.locked) return next
      if (!changed && nextView.key != beforeKey) changed = true
      val nextCount = nodes(nextView)
      if (nextCount != count) {
        count = nextCount
        countSince = ticker.now()
      }
      val sameKey = nextView.key == view(last).key
      if (!sameKey) keySince = ticker.now()
      val stable = sameKey && ticker.now() - countSince >= QUIET_MS
      last = next
      if (changed && stable && ready(nextView, wait)) return next
      if (wait.requirePage && changed && !nextView.pageArea && ticker.now() - keySince >= NO_PAGE_MS) return next
    }
  }

  private fun nodes(observation: Observation): Int = observation.read?.diagnostics?.nodes ?: 0

  private fun ready(observation: Observation, wait: Wait): Boolean =
    if (wait.requirePage) observation.pageArea && observation.pageLines > 0 else !observation.pageWithoutContent
}
