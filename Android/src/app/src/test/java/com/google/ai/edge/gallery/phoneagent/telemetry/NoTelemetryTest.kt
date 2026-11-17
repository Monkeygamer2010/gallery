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

package com.google.ai.edge.gallery.phoneagent.telemetry

import com.google.ai.edge.gallery.GalleryAnalytics
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.phoneagent.telemetrystub.NoOpAnalytics
import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upstream 1.0.23 logs analytics events through GalleryAnalytics and adds ML Kit speech, AICore,
 * XR and MediaPipe audio libraries. In the fork the wrapper's delegate is the no-op type from
 * :telemetry-stub and always null, and none of those libraries is on the classpath, so a direct
 * call to one of them cannot compile and nothing can be sent.
 */
class NoTelemetryTest {
  @Test
  fun theAnalyticsDelegateIsTheNoOpTypeAndAlwaysNull() {
    val field = Class.forName("com.google.ai.edge.gallery.AnalyticsKt").getDeclaredField("firebaseAnalyticsDelegate")
    assertEquals(NoOpAnalytics::class.java, field.type)
    assertTrue(Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers))
    field.isAccessible = true
    assertNull(field.get(null))
  }

  @Test
  fun loggingThroughTheWrapperRecordsNothing() {
    assertNull("no analytics object exists to log to", firebaseAnalytics)
    GalleryAnalytics.logEvent("capability_select", null)
    GalleryAnalytics.setAnalyticsCollectionEnabled(true)
    assertNull("turning collection on creates nothing", firebaseAnalytics)
  }

  @Test
  fun noForbiddenLibraryIsOnTheClasspath() {
    val forbidden =
      listOf(
        "com/google/firebase/",
        "com/google/mlkit/",
        "com/google/android/gms/",
        "com/google/ai/edge/aicore/",
        "com/google/android/play/core/",
        "androidx/xr/",
        // MediaPipe's audio tasks (tasks-audio). tasks-core itself carries the audio classifier's
        // option messages (.../proto/), as it did before the sync; those are data, not audio code.
        "com/google/mediapipe/tasks/audio/",
      )
    fun isForbidden(name: String) =
      name.endsWith(".class") &&
        forbidden.any { name.startsWith(it) } &&
        !(name.startsWith("com/google/mediapipe/tasks/audio/") && "/proto/" in name)
    val entries = System.getProperty("java.class.path").split(File.pathSeparator).map(::File)
    var jars = 0
    val found = mutableListOf<String>()
    for (entry in entries) {
      val names: Sequence<String> =
        when {
          entry.isDirectory -> entry.walkTopDown().filter { it.isFile }.map { it.relativeTo(entry).invariantSeparatorsPath }
          entry.isFile && entry.extension == "jar" -> {
            jars++
            ZipFile(entry).use { zip -> zip.entries().asSequence().map { it.name }.toList() }.asSequence()
          }
          else -> emptySequence()
        }
      names.filter(::isForbidden).firstOrNull()?.let { found += "$it in ${entry.path.substringAfterLast("/transformed/").ifEmpty { entry.name }}" }
    }
    assertTrue("the scan saw the dependency jars ($jars)", jars > 50)
    assertEquals(emptyList<String>(), found)
  }

  @Test
  fun firebasesUploaderIsTheStub() {
    val runtime = Class.forName("com.google.android.datatransport.runtime.TransportRuntime")
    val location = runtime.protectionDomain.codeSource.location.path
    assertTrue("TransportRuntime comes from :telemetry-stub, not Firebase: $location", location.contains("telemetry-stub"))
  }
}
