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

package com.google.ai.edge.gallery.phoneagent.task

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAgentLaunchTest {
  @After
  fun clear() {
    PhoneAgentLaunch.take(PhoneAgentLaunch.pending.value?.model ?: "")
  }

  // Phase 2.1: the open screen (E4B) took the tile's task although the tile model was E2B.
  @Test
  fun aTileTaskIsTakenOnlyByTheScreenOfTheModelItResolvedTo() {
    PhoneAgentLaunch.offer(TileRequest("What is the population of Munich?", "Gemma-4-E2B"))
    assertNull("not resolved yet", PhoneAgentLaunch.take("Gemma-4-E2B"))
    PhoneAgentLaunch.resolve("Gemma-4-E2B")
    assertNull(PhoneAgentLaunch.take("Gemma-4-E4B"))
    val taken = PhoneAgentLaunch.take("Gemma-4-E2B")
    assertEquals("What is the population of Munich?", taken?.goal)
    assertNull(PhoneAgentLaunch.pending.value)
  }

  @Test
  fun theReplacedScreenLeavingLastDoesNotHideTheNewOne() {
    PhoneAgentLaunch.onScreenShown("Gemma-4-E4B")
    PhoneAgentLaunch.onScreenShown("Gemma-4-E2B")
    PhoneAgentLaunch.onScreenLeft("Gemma-4-E4B")
    assertEquals("Gemma-4-E2B", PhoneAgentLaunch.shownModel)
    PhoneAgentLaunch.onScreenLeft("Gemma-4-E2B")
    assertNull(PhoneAgentLaunch.shownModel)
  }

  @Test
  fun theRunLogSaysWhichModelTheTileAskedFor() {
    val request = TileRequest("goal", "Gemma-4-E2B", model = "Gemma-4-E2B")
    assertEquals("Started from the Quick Settings tile, which asked for Gemma-4-E2B; running Gemma-4-E2B.", request.note("Gemma-4-E2B"))
    assertTrue(request.note("Gemma-4-E4B").endsWith("(Gemma-4-E2B is not downloaded.)"))
  }
}
