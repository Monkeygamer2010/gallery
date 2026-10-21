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

import android.os.Debug
import java.io.File

/**
 * The app process's memory at one moment, in MB. PSS is what Android counts as the app's memory;
 * it splits into anonymous memory (heap, model buffers copied into RAM; this is what counts
 * against per-app memory limits and goes to swap), file-backed pages (for example a memory-mapped
 * model file, which the system can drop and re-read) and shared memory. Graphics is what the GPU
 * driver reports for the app.
 */
data class MemoryStats(
  val pssMb: Int,
  val anonMb: Int? = null,
  val fileMb: Int? = null,
  val shmemMb: Int? = null,
  val swapMb: Int? = null,
  val graphicsMb: Int? = null,
  val engines: Int = 0,
) {
  fun line(): String = buildString {
    append("PSS ").append(pssMb).append(" MB")
    val parts =
      listOfNotNull(
        anonMb?.let { "anonymous $it" },
        fileMb?.let { "file-backed $it" },
        shmemMb?.let { "shared $it" },
        swapMb?.let { "swap $it" },
        graphicsMb?.let { "graphics $it" },
      )
    if (parts.isNotEmpty()) append(" (").append(parts.joinToString(", ")).append(')')
    append("; engines loaded: ").append(engines)
  }
}

object MemoryProbe {
  /** Reads the process's memory. [withGraphics] adds the slower full report (about 100 ms). */
  fun read(withGraphics: Boolean = false): MemoryStats {
    val rollup = runCatching { parseRollup(File("/proc/self/smaps_rollup").readText()) }.getOrDefault(emptyMap())
    fun mb(key: String): Int? = rollup[key]?.let { (it / 1024).toInt() }
    val graphics =
      if (withGraphics) {
        runCatching {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            info.getMemoryStat("summary.graphics")?.toIntOrNull()?.div(1024)
          }
          .getOrNull()
      } else {
        null
      }
    return MemoryStats(
      pssMb = mb("Pss") ?: (Debug.getPss() / 1024).toInt(),
      anonMb = mb("Pss_Anon"),
      fileMb = mb("Pss_File"),
      shmemMb = mb("Pss_Shmem"),
      swapMb = mb("SwapPss") ?: mb("Swap"),
      graphicsMb = graphics,
      engines = AgentEngine.loadedEngines.value,
    )
  }

  /** "Pss_Anon:   123 kB" lines of /proc/self/smaps_rollup, in kB. */
  internal fun parseRollup(text: String): Map<String, Long> =
    text
      .lineSequence()
      .mapNotNull { line ->
        val key = line.substringBefore(':', "").trim()
        val value = line.substringAfter(':', "").trim().removeSuffix("kB").trim().toLongOrNull()
        if (key.isEmpty() || value == null) null else key to value
      }
      .toMap()
}
