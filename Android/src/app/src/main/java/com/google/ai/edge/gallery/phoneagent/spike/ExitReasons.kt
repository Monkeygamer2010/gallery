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

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why PhoneAgent's process ended the last few times, from Android's own record
 * (`ApplicationExitInfo`). Shown in the spike tools with a Copy button; kept in memory only.
 */
object ExitReasons {
  private const val MAX_RECORDS = 5

  fun report(context: Context): String {
    val am = context.getSystemService(ActivityManager::class.java) ?: return "No activity manager."
    val records =
      runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS) }.getOrElse {
        return "Android did not return exit records (${it.javaClass.simpleName})."
      }
    if (records.isEmpty()) return "No exit records yet."
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return buildString {
      for ((i, info) in records.withIndex()) {
        if (i > 0) append('\n')
        append("#").append(i + 1).append(' ').append(time.format(Date(info.timestamp)))
        append(" (").append(info.processName).append(", pid ").append(info.pid).append(")\n")
        append("Reason: ").append(reasonName(info.reason)).append(" (").append(info.reason).append(")\n")
        if (info.status != 0) append("Status: ").append(info.status).append('\n')
        info.description?.let { append("Description: ").append(it).append('\n') }
        memoryLimitNote(info.reason, info.description)?.let { append(">> ").append(it).append('\n') }
        append("Importance at death: ").append(importanceName(info.importance)).append(" (").append(info.importance).append(")\n")
        append("PSS: ").append(info.pss / 1024).append(" MB, RSS: ").append(info.rss / 1024).append(" MB\n")
        when (info.reason) {
          ApplicationExitInfo.REASON_CRASH_NATIVE ->
            runCatching { info.traceInputStream?.use { TombstoneReader.summary(it.readBytes()) } }
              .getOrNull()
              ?.let { append("Native crash:\n").append(it) }
          ApplicationExitInfo.REASON_ANR ->
            runCatching { info.traceInputStream?.bufferedReader()?.use { r -> r.lineSequence().take(20).joinToString("\n") } }
              .getOrNull()
              ?.let { append("ANR trace (first lines):\n").append(it).append('\n') }
        }
      }
    }
  }

  /**
   * Android 17's per-app memory limit kills an app whose anonymous memory plus swap passes a
   * limit based on the device's RAM; the record says REASON_OTHER and "MemoryLimiter:AnonSwap".
   */
  internal fun memoryLimitNote(reason: Int, description: String?): String? =
    when {
      description?.contains("MemoryLimiter", ignoreCase = true) == true ->
        "Killed by Android's per-app memory limit (anonymous memory + swap)."
      reason == ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed because the phone ran low on memory."
      else -> null
    }

  internal fun reasonName(reason: Int): String =
    when (reason) {
      ApplicationExitInfo.REASON_EXIT_SELF -> "exited by itself"
      ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
      ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
      ApplicationExitInfo.REASON_CRASH -> "crash (Java/Kotlin)"
      ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
      ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
      ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
      ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
      ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
      ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user (force stop)"
      ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
      ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
      ApplicationExitInfo.REASON_OTHER -> "other (system)"
      ApplicationExitInfo.REASON_FREEZER -> "freezer"
      ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state change"
      ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
      else -> "unknown"
    }

  private fun importanceName(importance: Int): String =
    when {
      importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
      importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground service"
      importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
      importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
      importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
      importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
      else -> "gone"
    }
}

/**
 * Reads the few fields of an Android tombstone (debuggerd's protobuf format, what
 * `ApplicationExitInfo` returns for native crashes) needed to name a crash: signal, abort message
 * and the crashing thread's top frames. Best effort: unknown or malformed data gives what was
 * found so far.
 */
object TombstoneReader {
  private const val MAX_FRAMES = 10

  // Field numbers from system/core/debuggerd/proto/tombstone.proto.
  private const val TOMBSTONE_TID = 6
  private const val TOMBSTONE_SIGNAL = 10
  private const val TOMBSTONE_ABORT_MESSAGE = 14
  private const val TOMBSTONE_THREADS = 16
  private const val SIGNAL_NAME = 2
  private const val SIGNAL_CODE_NAME = 4
  private const val THREAD_ID = 1
  private const val THREAD_NAME = 2
  private const val THREAD_BACKTRACE = 4
  private const val FRAME_REL_PC = 1
  private const val FRAME_FUNCTION = 4
  private const val FRAME_FUNCTION_OFFSET = 5
  private const val FRAME_FILE = 6

  private class Field(val number: Int, val varint: Long?, val bytes: ByteArray?)

  /** Fields of one protobuf message; stops quietly at the first malformed byte. */
  private fun fields(data: ByteArray): List<Field> {
    val out = mutableListOf<Field>()
    var pos = 0
    fun varint(): Long? {
      var result = 0L
      var shift = 0
      while (pos < data.size && shift < 64) {
        val b = data[pos++].toInt() and 0xFF
        result = result or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) return result
        shift += 7
      }
      return null
    }
    while (pos < data.size) {
      val key = varint() ?: break
      val number = (key ushr 3).toInt()
      when ((key and 7).toInt()) {
        0 -> out += Field(number, varint() ?: break, null)
        1 -> pos += 8
        2 -> {
          val length = varint()?.toInt() ?: break
          if (length < 0 || pos + length > data.size) break
          out += Field(number, null, data.copyOfRange(pos, pos + length))
          pos += length
        }
        5 -> pos += 4
        else -> break
      }
    }
    return out
  }

  private fun Field.text(): String? = bytes?.toString(Charsets.UTF_8)

  fun summary(tombstone: ByteArray): String = buildString {
    val top = fields(tombstone)
    val tid = top.firstOrNull { it.number == TOMBSTONE_TID }?.varint
    top.firstOrNull { it.number == TOMBSTONE_SIGNAL }?.bytes?.let { signal ->
      val s = fields(signal)
      val name = s.firstOrNull { it.number == SIGNAL_NAME }?.text()
      val code = s.firstOrNull { it.number == SIGNAL_CODE_NAME }?.text()
      if (name != null) append("Signal: ").append(name).append(code?.let { " ($it)" } ?: "").append('\n')
    }
    top.firstOrNull { it.number == TOMBSTONE_ABORT_MESSAGE }?.text()?.takeIf { it.isNotBlank() }?.let {
      append("Abort message: ").append(it.take(300)).append('\n')
    }
    // threads is a map<uint32, Thread>: entries with key (1) and value (2).
    val threads =
      top.filter { it.number == TOMBSTONE_THREADS }.mapNotNull { entry ->
        entry.bytes?.let { fields(it).firstOrNull { f -> f.number == 2 }?.bytes }
      }
    val crashing =
      threads.firstOrNull { t -> fields(t).firstOrNull { it.number == THREAD_ID }?.varint == tid } ?: threads.firstOrNull()
    if (crashing != null) {
      val t = fields(crashing)
      t.firstOrNull { it.number == THREAD_NAME }?.text()?.let { append("Thread: ").append(it).append('\n') }
      t.filter { it.number == THREAD_BACKTRACE }.take(MAX_FRAMES).forEachIndexed { i, frame ->
        val f = fields(frame.bytes ?: return@forEachIndexed)
        val file = f.firstOrNull { it.number == FRAME_FILE }?.text()?.substringAfterLast('/') ?: "?"
        val function = f.firstOrNull { it.number == FRAME_FUNCTION }?.text()?.takeIf { it.isNotEmpty() }
        val offset = f.firstOrNull { it.number == FRAME_FUNCTION_OFFSET }?.varint
        val pc = f.firstOrNull { it.number == FRAME_REL_PC }?.varint
        append("  #").append(i.toString().padStart(2, '0')).append(' ').append(file)
        if (function != null) append(" (").append(function).append(offset?.let { "+$it" } ?: "").append(')')
        else if (pc != null) append(" pc 0x").append(pc.toString(16))
        append('\n')
      }
    }
    if (isEmpty()) append("(no readable details)\n")
  }
}
