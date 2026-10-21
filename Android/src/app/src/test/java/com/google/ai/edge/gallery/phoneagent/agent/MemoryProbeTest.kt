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

class MemoryProbeTest {
  @Test
  fun parsesSmapsRollup() {
    val rollup =
      """
      00400000-7fffffffff ---p 00000000 00:00 0                          [rollup]
      Rss:             4523000 kB
      Pss:             4401234 kB
      Pss_Dirty:       1200000 kB
      Pss_Anon:        1100000 kB
      Pss_File:        3200000 kB
      Pss_Shmem:        101234 kB
      SwapPss:           20480 kB
      """
        .trimIndent()
    val values = MemoryProbe.parseRollup(rollup)
    assertEquals(4401234L, values["Pss"])
    assertEquals(1100000L, values["Pss_Anon"])
    assertEquals(3200000L, values["Pss_File"])
    assertEquals(20480L, values["SwapPss"])
  }

  @Test
  fun memoryLineNamesEachPart() {
    val line = MemoryStats(pssMb = 4298, anonMb = 1074, fileMb = 3125, shmemMb = 98, swapMb = 20, graphicsMb = 900, engines = 1).line()
    assertEquals("PSS 4298 MB (anonymous 1074, file-backed 3125, shared 98, swap 20, graphics 900); engines loaded: 1", line)
    assertTrue(MemoryStats(pssMb = 1000).line().endsWith("engines loaded: 0"))
  }
}
