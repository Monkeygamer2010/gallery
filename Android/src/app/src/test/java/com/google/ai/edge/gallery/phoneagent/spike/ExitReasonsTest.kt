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

package com.google.ai.edge.gallery.phoneagent.spike

import android.app.ApplicationExitInfo
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minimal protobuf writer for building test tombstones. */
private class Proto {
  private val out = ByteArrayOutputStream()

  private fun varint(value: Long) {
    var v = value
    while (true) {
      if (v and 0x7FL.inv() == 0L) {
        out.write(v.toInt())
        return
      }
      out.write(((v and 0x7F) or 0x80).toInt())
      v = v ushr 7
    }
  }

  fun int(field: Int, value: Long) = apply {
    varint((field shl 3).toLong())
    varint(value)
  }

  fun bytes(field: Int, value: ByteArray) = apply {
    varint(((field shl 3) or 2).toLong())
    varint(value.size.toLong())
    out.write(value)
  }

  fun text(field: Int, value: String) = bytes(field, value.toByteArray())

  fun msg(field: Int, value: Proto) = bytes(field, value.build())

  fun build(): ByteArray = out.toByteArray()
}

class ExitReasonsTest {
  private fun frame(file: String, function: String, offset: Long) =
    Proto().int(1, 0x1234).text(4, function).int(5, offset).text(6, file)

  @Test
  fun readsSignalAbortMessageAndTheCrashingThreadsFrames() {
    val crashing =
      Proto()
        .int(1, 42)
        .text(2, "DefaultDispatch")
        .msg(4, frame("/data/app/x/lib/arm64/liblitertlm_jni.so", "litert::Engine::Run", 16))
        .msg(4, frame("/system/lib64/libc.so", "abort", 4))
    val other = Proto().int(1, 7).text(2, "main").msg(4, frame("/system/lib64/libc.so", "epoll_wait", 8))
    val tombstone =
      Proto()
        .int(6, 42)
        .msg(10, Proto().text(2, "SIGABRT").text(4, "SI_TKILL"))
        .text(14, "Out of GPU memory")
        .msg(16, Proto().int(1, 7).msg(2, other))
        .msg(16, Proto().int(1, 42).msg(2, crashing))
        .build()
    val summary = TombstoneReader.summary(tombstone)
    assertTrue(summary, summary.contains("Signal: SIGABRT (SI_TKILL)"))
    assertTrue(summary.contains("Abort message: Out of GPU memory"))
    assertTrue(summary.contains("Thread: DefaultDispatch"))
    assertTrue(summary.contains("#00 liblitertlm_jni.so (litert::Engine::Run+16)"))
    assertTrue(summary.contains("#01 libc.so (abort+4)"))
    assertTrue(!summary.contains("epoll_wait"))
  }

  @Test
  fun malformedDataGivesWhatWasFound() {
    assertEquals("(no readable details)\n", TombstoneReader.summary(byteArrayOf(0x7A, 0x7F)))
    assertEquals("(no readable details)\n", TombstoneReader.summary(ByteArray(0)))
  }

  @Test
  fun recognisesAndroid17sMemoryLimiter() {
    val note = ExitReasons.memoryLimitNote(ApplicationExitInfo.REASON_OTHER, "MemoryLimiter:AnonSwap limit exceeded")
    assertTrue(note!!.contains("per-app memory limit"))
    assertTrue(ExitReasons.memoryLimitNote(ApplicationExitInfo.REASON_LOW_MEMORY, null)!!.contains("low on memory"))
    assertNull(ExitReasons.memoryLimitNote(ApplicationExitInfo.REASON_CRASH, "boom"))
  }
}
