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

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The manifest and exclusion rules of the APK check (checkCiApk, run by every assembleCi). */
class ApkRulesTest {
  private fun manifest(body: String): Element =
    DocumentBuilderFactory.newInstance()
      .apply { isNamespaceAware = true }
      .newDocumentBuilder()
      .parse(
        """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="x">$body</manifest>"""
          .byteInputStream()
      )
      .documentElement

  @Test
  fun thePermissionSetIsExact() {
    val approved = ApkRules.APPROVED_PERMISSIONS.toList()
    val own = "io.github.monkeygamer2010.phoneagent.dev.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    assertEquals(emptyList<String>(), ManifestRules.permissionProblems(approved + own))
    assertEquals(
      listOf("permission android.permission.BLUETOOTH_CONNECT is not approved"),
      ManifestRules.permissionProblems(approved + "android.permission.BLUETOOTH_CONNECT"),
    )
    val fewer = ManifestRules.permissionProblems(approved - "android.permission.CAMERA")
    assertEquals(1, fewer.size)
    assertTrue(fewer.single(), fewer.single().startsWith("approved permission android.permission.CAMERA is no longer requested"))
  }

  @Test
  fun upstreamsPackageVisibilityEntriesForTextToSpeechAndGlassesAreRefused() {
    // The <queries> block upstream 1.0.23 adds to its manifest.
    val root =
      manifest(
        """<queries>
             <package android:name="org.mozilla.firefox" />
             <intent><action android:name="android.intent.action.VIEW" />
               <category android:name="android.intent.category.BROWSABLE" /><data android:scheme="https" /></intent>
             <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
             <package android:name="com.google.android.tts" />
             <package android:name="com.google.android.glasses.core" />
           </queries>"""
      )
    assertEquals(
      listOf(
        "package-visibility entry 'intent action=android.intent.action.TTS_SERVICE' is not approved",
        "package-visibility entry 'package com.google.android.glasses.core' is not approved",
        "package-visibility entry 'package com.google.android.tts' is not approved",
      ),
      ManifestRules.queryProblems(ManifestRules.queries(root)),
    )
  }

  @Test
  fun aNewIntentFilterIsRefused() {
    val root =
      manifest(
        """<application>
             <activity android:name="com.google.ai.edge.gallery.MainActivity" android:exported="true">
               <intent-filter><action android:name="android.intent.action.MAIN" /></intent-filter>
               <intent-filter><action android:name="android.intent.action.VIEW" /><data android:scheme="https" /></intent-filter>
             </activity>
             <activity android:name="com.google.ai.edge.gallery.customtasks.xrlanguageteacher.GlassesTapActivity">
               <intent-filter><action android:name="com.google.android.glasses.TAP" /></intent-filter>
             </activity>
           </application>"""
      )
    assertEquals(
      listOf(
        "intent filter 'com.google.ai.edge.gallery.MainActivity android.intent.action.VIEW' is not approved",
        "intent filter 'com.google.ai.edge.gallery.customtasks.xrlanguageteacher.GlassesTapActivity com.google.android.glasses.TAP' is not approved",
      ),
      ManifestRules.intentFilterProblems(ManifestRules.intentFilters(root)),
    )
  }

  @Test
  fun excludedUpstreamCodeAndAssetsAreFoundInAnApk() {
    val dir = Files.createTempDirectory("excluded").toFile()
    try {
      fun file(path: String) = File(dir, path).apply { parentFile.mkdirs(); writeText("x") }
      file("java/com/x/services/tts/TtsPlaybackController.kt")
      file("java/com/x/runtime/aicore/AICoreModelHelper.kt")
      file("java/com/x/ui/SpeechEngineErrorDialog.kt")
      file("java/com/x/ui/ChatPanel.kt")
      file("assets/xr/onboarding1.png")
      file("standins/com/x/services/tts/TtsStandIns.kt")
      val list =
        File(dir, "list.txt").apply {
          writeText(
            """
            # comment
            java/com/x/services/tts/**
            java/com/x/runtime/aicore/**
            java/com/x/ui/SpeechEngineErrorDialog.kt
            assets/xr/**
            java/com/x/gone/**
            """
              .trimIndent()
          )
        }
      val excluded = ExcludedFeatures.read(list, File(dir, "java"), File(dir, "assets"), File(dir, "standins"))
      assertEquals(listOf("java/com/x/gone/**"), excluded.stalePatterns)
      assertEquals(setOf("com/x/runtime/aicore/"), excluded.wholePackages)
      assertEquals(setOf("com/x/services/tts/"), excluded.standInPackages)

      val classes =
        mapOf(
          "Lcom/x/services/tts/TtsPlaybackController;" to "TtsPlaybackController.kt", // excluded
          "Lcom/x/services/tts/TtsPlaybackController\$stop\$\$inlined\$map\$1;" to null, // its helper
          "Lcom/x/services/tts/TtsSettingsRepository;" to "TtsStandIns.kt", // stand-in
          "Lcom/x/services/tts/Unnamed;" to null, // no name in a stand-in package
          "Lcom/x/runtime/aicore/Anything;" to "Generated.java", // whole package
          "Lcom/x/ui/SpeechEngineErrorDialogKt;" to "SpeechEngineErrorDialog.kt", // excluded file
          "Lcom/x/ui/ChatPanelKt;" to "ChatPanel.kt", // compiled upstream code
          "Lcom/x/ui/ChatPanelKt\$x\$\$inlined\$map\$1;" to null,
        )
      assertEquals(
        listOf(
          "class Lcom/x/runtime/aicore/Anything; is from excluded package com/x/runtime/aicore/",
          "class Lcom/x/services/tts/TtsPlaybackController\$stop\$\$inlined\$map\$1; is compiled from excluded com/x/services/tts/TtsPlaybackController.kt",
          "class Lcom/x/services/tts/TtsPlaybackController; is compiled from excluded com/x/services/tts/TtsPlaybackController.kt",
          "class Lcom/x/services/tts/Unnamed; has no source file name, so it cannot be told apart from excluded code",
          "class Lcom/x/ui/SpeechEngineErrorDialogKt; is compiled from excluded com/x/ui/SpeechEngineErrorDialog.kt",
        ),
        excluded.classProblems(classes),
      )
      assertEquals(
        listOf("asset xr/onboarding1.png is from an excluded feature", "asset xr/other.webp is from an excluded feature"),
        excluded.assetProblems(listOf("assets/xr/onboarding1.png", "assets/xr/other.webp", "assets/skills/a.md", "classes.dex")),
      )
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test(expected = IllegalArgumentException::class)
  fun aPatternTheCheckCannotVerifyIsRefused() {
    val dir = Files.createTempDirectory("excluded").toFile()
    try {
      val list = File(dir, "list.txt").apply { writeText("java/com/x/**/Tts*.kt\n") }
      ExcludedFeatures.read(list, File(dir, "java"), File(dir, "assets"), File(dir, "standins"))
    } finally {
      dir.deleteRecursively()
    }
  }
}
