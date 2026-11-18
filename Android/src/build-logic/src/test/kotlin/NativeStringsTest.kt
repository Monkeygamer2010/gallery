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

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The native-library string rule of the APK check, on synthetic library fixtures. */
class NativeStringsTest {
  /** A fake shared library: an ELF magic, binary noise, and [strings] as NUL-terminated C strings. */
  private fun library(vararg strings: String): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0))
    for ((i, s) in strings.withIndex()) {
      out.write(ByteArray(13 + i) { if (it % 2 == 0) 0 else 0xF3.toByte() }) // non-printable filler
      out.write(s.toByteArray(Charsets.US_ASCII))
      out.write(0)
    }
    return out.toByteArray()
  }

  private val llvm = "Android (13624864, +pgo, +bolt) clang version 21 (https://android.googlesource.com/toolchain/llvm-project 4a2b)"

  @Test
  fun mediaPipe120sUploaderStringsFail() {
    // What MediaPipe 1.2.0's libmediapipe_tasks_jni.so contained (audit, 2026-11-18).
    val bytes =
      library(
        llvm,
        "https://play.googleapis.com/log",
        "N9mediapipe5tasks4core7logging15google_internal21ClearcutLoggingClientE",
        "Error sending LogRequest to clearcut backend: ",
        "java/net/HttpURLConnection",
        "javax/net/ssl/SSLContext",
        "android/net/TrafficStats",
        "android/net/Uri", // approved for this library
      )
    val problems = NativeStrings.problems("lib/arm64-v8a/libmediapipe_tasks_jni.so", bytes)
    assertEquals(
      listOf(
        "lib/arm64-v8a/libmediapipe_tasks_jni.so contains a URL that is not approved: 'https://play.googleapis.com/log'",
        "lib/arm64-v8a/libmediapipe_tasks_jni.so contains a reference to Java networking class that is not approved: 'android/net/TrafficStats'",
        "lib/arm64-v8a/libmediapipe_tasks_jni.so contains a reference to Java networking class that is not approved: 'java/net/HttpURLConnection'",
        "lib/arm64-v8a/libmediapipe_tasks_jni.so contains a reference to Java networking class that is not approved: 'javax/net/ssl/SSLContext'",
        "lib/arm64-v8a/libmediapipe_tasks_jni.so contains a telemetry string that is not approved: 'Error sending LogRequest to clearcut backend: '",
        "lib/arm64-v8a/libmediapipe_tasks_jni.so contains a telemetry string that is not approved: 'N9mediapipe5tasks4core7logging15google_internal21ClearcutLoggingClientE'",
      ),
      problems,
    )
  }

  @Test
  fun aCleanLibraryPasses() {
    val bytes = library(llvm, "GL_OES_EGL_image_external", "failed to map %s: %d", "com.google.mediapipe.tasks.core")
    assertEquals(emptyList<String>(), NativeStrings.problems("lib/x86_64/libclean.so", bytes))
  }

  @Test
  fun bareHostNamesAndGluedUrlsAreFound() {
    val bytes =
      library(
        "upload to metrics.example.com:443 failed",
        // An approved link glued to another URL: the second one must still be reported.
        "see https://crbug.com/1161355https://collector.example.net/v1",
      )
    assertEquals(
      listOf(
        "lib/arm64-v8a/liblitertlm_jni.so contains a URL that is not approved: 'https://collector.example.net/v1'",
        "lib/arm64-v8a/liblitertlm_jni.so contains a host name that is not approved: 'metrics.example.com'",
      ),
      NativeStrings.problems("lib/arm64-v8a/liblitertlm_jni.so", bytes),
    )
  }

  @Test
  fun anApprovalHoldsOnlyForItsLibrary() {
    val hooks = arrayOf("logTfLiteTelemetryEvent", "_ZNK7gmscore6tflite8internal15GmsTfLiteLogger23LogTfLiteTelemetryEventEv", "android/net/Uri")
    assertEquals(emptyList<String>(), NativeStrings.problems("lib/arm64-v8a/libsqlite_vector_store_jni.so", library(*hooks)))
    val elsewhere = NativeStrings.problems("lib/arm64-v8a/libother.so", library(*hooks))
    assertEquals(3, elsewhere.size)
    assertTrue(elsewhere.joinToString(), elsewhere.all { it.startsWith("lib/arm64-v8a/libother.so contains") })
  }

  @Test
  fun everyApprovalHasAReason() {
    for (a in ApkRules.NATIVE_ALLOWLIST) assertTrue("$a needs a reason", a.reason.length > 10)
  }
}
