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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopGuardTest {
  private fun aborted(verdict: LoopGuard.Verdict) = verdict is LoopGuard.Verdict.Abort

  @Test
  fun stopsAfterFifteenSteps() {
    val guard = LoopGuard()
    repeat(15) {
      assertEquals(LoopGuard.Verdict.Continue, guard.beforeStep())
      guard.onStep()
      assertEquals(LoopGuard.Verdict.Continue, guard.onAction("tap [$it]", "screen $it"))
    }
    assertTrue(aborted(guard.beforeStep()))
    assertEquals(15, guard.steps)
  }

  @Test
  fun stopsOnTheSameActionOnTheSameScreen() {
    val guard = LoopGuard()
    assertEquals(LoopGuard.Verdict.Continue, guard.onAction("scroll down", "A"))
    assertEquals(LoopGuard.Verdict.Continue, guard.onAction("scroll down", "B"))
    assertEquals(LoopGuard.Verdict.Continue, guard.onAction("tap [2]", "B"))
    assertTrue(aborted(guard.onAction("scroll down", "B")))
  }

  @Test
  fun catchesCyclesBetweenScreens() {
    val guard = LoopGuard()
    guard.onAction("tap [4]", "results")
    guard.onAction("back", "article")
    assertTrue(aborted(guard.onAction("tap [4]", "results")))
  }

  @Test
  fun stopsAfterThreeRepliesWithoutAToolCallInARow() {
    val guard = LoopGuard()
    assertEquals(LoopGuard.Verdict.Continue, guard.onInvalid())
    assertEquals(LoopGuard.Verdict.Continue, guard.onInvalid())
    guard.onAction("tap [1]", "A") // a usable call resets the count
    assertEquals(LoopGuard.Verdict.Continue, guard.onInvalid())
    assertEquals(LoopGuard.Verdict.Continue, guard.onInvalid())
    assertTrue(aborted(guard.onInvalid()))
  }

  @Test
  fun stopsAfterThreeRefusalsInARow() {
    val guard = LoopGuard()
    assertEquals(LoopGuard.Verdict.Continue, guard.onRefused())
    guard.onDone()
    assertEquals(LoopGuard.Verdict.Continue, guard.onRefused())
    assertEquals(LoopGuard.Verdict.Continue, guard.onRefused())
    assertTrue(aborted(guard.onRefused()))
  }
}
