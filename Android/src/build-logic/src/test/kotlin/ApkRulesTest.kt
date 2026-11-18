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

  private val pkg = "io.github.monkeygamer2010.phoneagent.dev"
  private val own = "$pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

  @Test
  fun thePermissionSetIsExact() {
    val approved = ApkRules.APPROVED_PERMISSIONS.map { "uses-permission" to it }
    val ownDeclared = listOf("permission" to own)
    assertEquals(emptyList<String>(), ManifestRules.permissionProblems(approved + ("uses-permission" to own), ownDeclared, pkg))
    assertEquals(
      listOf("permission android.permission.BLUETOOTH_CONNECT is not approved (<uses-permission>)"),
      ManifestRules.permissionProblems(approved + ("uses-permission" to "android.permission.BLUETOOTH_CONNECT"), ownDeclared, pkg),
    )
    val fewer = ManifestRules.permissionProblems(approved - ("uses-permission" to "android.permission.CAMERA"), ownDeclared, pkg)
    assertEquals(1, fewer.size)
    assertTrue(fewer.single(), fewer.single().startsWith("approved permission android.permission.CAMERA is no longer requested"))
  }

  /** Audit slip 1: a permission asked for with <uses-permission-sdk-23> passed the old check. */
  @Test
  fun everyPermissionElementCounts() {
    val root =
      manifest(
        ApkRules.APPROVED_PERMISSIONS.joinToString("") { """<uses-permission android:name="$it" />""" } +
          """<uses-permission-sdk-23 android:name="android.permission.READ_CONTACTS" />
             <uses-permission-sdk-m android:name="android.permission.BODY_SENSORS" />
             <uses-permission android:name="$own" />
             <permission android:name="$own" android:protectionLevel="signature" />
             <permission android:name="com.example.SHARED_DATA" />
             <permission-tree android:name="com.example.tree" />"""
      )
    assertEquals(
      listOf(
        "permission android.permission.BODY_SENSORS is not approved (<uses-permission-sdk-m>)",
        "permission android.permission.READ_CONTACTS is not approved (<uses-permission-sdk-23>)",
        "the manifest declares <permission-tree> com.example.tree, which is not approved",
        "the manifest declares <permission> com.example.SHARED_DATA, which is not approved",
      ),
      ManifestRules.permissionProblems(ManifestRules.requestedPermissions(root), ManifestRules.declaredPermissions(root), pkg),
    )
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
               <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>
             </activity>
             <activity android:name="com.google.ai.edge.gallery.customtasks.xrlanguageteacher.GlassesTapActivity">
               <intent-filter><action android:name="com.google.android.glasses.TAP" /></intent-filter>
             </activity>
           </application>"""
      )
    val approved = setOf("com.google.ai.edge.gallery.MainActivity | action=android.intent.action.MAIN | category=android.intent.category.LAUNCHER")
    assertEquals(
      listOf("intent filter 'com.google.ai.edge.gallery.customtasks.xrlanguageteacher.GlassesTapActivity | action=com.google.android.glasses.TAP' is not approved"),
      ManifestRules.intentFilterProblems(ManifestRules.intentFilters(root), approved),
    )
    // The set is exact: an approved filter that goes away fails too.
    assertEquals(
      listOf("approved intent filter 'x | action=y' is not in the manifest: the filter set changed (ApkRules.APPROVED_INTENT_FILTERS)"),
      ManifestRules.intentFilterProblems(ManifestRules.intentFilters(root), approved + "x | action=y")
        .filter { it.startsWith("approved") },
    )
  }

  /** Audit slip 2: an extra https deep link on an approved component and action passed the old check. */
  @Test
  fun anExtraHttpsFilterOnAnApprovedActivityIsRefused() {
    val root =
      manifest(
        """<application>
             <activity android:name="com.google.ai.edge.gallery.MainActivity" android:exported="true">
               <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>
               <intent-filter android:autoVerify="true"><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" />
                 <category android:name="android.intent.category.BROWSABLE" />
                 <data android:scheme="https" android:host="audit.example.org" android:pathPrefix="/open" /></intent-filter>
             </activity>
           </application>"""
      )
    val approved = setOf("com.google.ai.edge.gallery.MainActivity | action=android.intent.action.MAIN | category=android.intent.category.LAUNCHER")
    assertEquals(
      listOf(
        "intent filter 'com.google.ai.edge.gallery.MainActivity | action=android.intent.action.MAIN | " +
          "category=android.intent.category.BROWSABLE,android.intent.category.LAUNCHER | " +
          "data=host:audit.example.org,pathPrefix:/open,scheme:https | autoVerify:true' is not approved"
      ),
      ManifestRules.intentFilterProblems(ManifestRules.intentFilters(root), approved),
    )
    // The real approved list holds no https filter at all.
    assertTrue(ApkRules.APPROVED_INTENT_FILTERS.none { it.contains("scheme:https") })
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
          // Audit slip 3: excluded code copied into its package under a new file name.
          "Lcom/x/services/tts/AuditRenamedTts;" to "AuditRenamedTts.kt",
          // Generated next to a stand-in (Hilt) and synthesized from one (D8): allowed.
          "Lcom/x/services/tts/TtsSettingsRepository_Factory;" to "TtsSettingsRepository_Factory.java",
          "Lcom/x/services/tts/TtsSettingsRepository\$\$ExternalSyntheticLambda0;" to "D8\$\$SyntheticClass",
          // Synthesized from an excluded class: counts as that class.
          "Lcom/x/ui/SpeechEngineErrorDialogKt\$\$ExternalSyntheticLambda0;" to "D8\$\$SyntheticClass",
        )
      assertEquals(
        listOf(
          "class Lcom/x/runtime/aicore/Anything; is from excluded package com/x/runtime/aicore/",
          "class Lcom/x/services/tts/AuditRenamedTts; in excluded package com/x/services/tts/ comes from AuditRenamedTts.kt, not from a stand-in",
          "class Lcom/x/services/tts/TtsPlaybackController\$stop\$\$inlined\$map\$1; is compiled from excluded com/x/services/tts/TtsPlaybackController.kt",
          "class Lcom/x/services/tts/TtsPlaybackController; is compiled from excluded com/x/services/tts/TtsPlaybackController.kt",
          "class Lcom/x/services/tts/Unnamed; has no source file name, so it cannot be told apart from excluded code",
          "class Lcom/x/ui/SpeechEngineErrorDialogKt\$\$ExternalSyntheticLambda0; is compiled from excluded com/x/ui/SpeechEngineErrorDialog.kt",
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

  /** A minimal dex file that defines no class and refers to [types] (all a type_ids table needs). */
  private fun dexReferring(vararg types: String): ByteArray {
    val strings = types.sorted()
    val header = 0x70
    val typeIdsOff = header + 4 * strings.size
    val dataOff = typeIdsOff + 4 * strings.size
    val data = java.io.ByteArrayOutputStream()
    val offsets = strings.map { str ->
      val off = dataOff + data.size()
      data.write(str.length) // uleb128 length (short ASCII strings)
      data.write(str.toByteArray(Charsets.US_ASCII)); data.write(0)
      off
    }
    val b = java.nio.ByteBuffer.allocate(dataOff + data.size()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    b.put("dex\n035\u0000".toByteArray(Charsets.US_ASCII))
    b.putInt(0x38, strings.size).putInt(0x3C, header).putInt(0x40, strings.size).putInt(0x44, typeIdsOff)
    b.putInt(0x58, 0).putInt(0x5C, 0).putInt(0x60, 0).putInt(0x64, 0)
    offsets.forEachIndexed { i, off -> b.putInt(header + 4 * i, off) }
    strings.indices.forEach { b.putInt(typeIdsOff + 4 * it, it) }
    b.position(dataOff); b.put(data.toByteArray())
    return b.array()
  }

  /** Audit slip 3, the other half: a renamed copy of voice code still refers to the platform's speech classes. */
  @Test
  fun speechAndTextToSpeechReferencesAreRefusedInAnyDex() {
    val dex =
      DexSummary.read(
        dexReferring(
          "Lcom/y/notes/Reader;",
          "Landroid/speech/tts/TextToSpeech;",
          "Landroid/speech/SpeechRecognizer;",
          "Lcom/google/mediapipe/tasks/audio/audioclassifier/AudioClassifier;",
          "Lcom/google/mediapipe/tasks/audio/audioclassifier/proto/AudioClassifierGraphOptionsProto;",
          "Landroid/media/AudioRecord;",
        )
      )
    assertEquals(
      listOf(
        "dex refers to Landroid/speech/SpeechRecognizer; (speech recognition, text-to-speech or MediaPipe audio, which are out of the build)",
        "dex refers to Landroid/speech/tts/TextToSpeech; (speech recognition, text-to-speech or MediaPipe audio, which are out of the build)",
        "dex refers to Lcom/google/mediapipe/tasks/audio/audioclassifier/AudioClassifier; (speech recognition, text-to-speech or MediaPipe audio, which are out of the build)",
      ),
      ApkRules.referenceProblems(dex.referencedTypes),
    )
  }

  /** Android binary XML for a tiny manifest: string pool, then start and end element chunks. */
  private class Node(val tag: String, val attrs: List<Triple<Boolean, String, Any>>, val children: List<Node> = emptyList())

  private fun binaryXml(root: Node): ByteArray {
    val ns = "http://schemas.android.com/apk/res/android"
    val pool = mutableListOf(ns)
    fun idx(s: String) = pool.indexOf(s).takeIf { it >= 0 } ?: run { pool += s; pool.size - 1 }
    fun collect(n: Node) { idx(n.tag); n.attrs.forEach { (_, k, v) -> idx(k); if (v is String) idx(v) }; n.children.forEach(::collect) }
    collect(root)
    val body = java.io.ByteArrayOutputStream()
    fun le(n: Int, v: Int) = java.nio.ByteBuffer.allocate(n).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { if (n == 2) putShort(v.toShort()) else putInt(v) }.array()
    fun emit(n: Node) {
      body.write(le(2, 0x0102)); body.write(le(2, 16)); body.write(le(4, 16 + 20 + 20 * n.attrs.size)); body.write(le(4, 1)); body.write(le(4, -1))
      body.write(le(4, -1)); body.write(le(4, idx(n.tag))); body.write(le(2, 20)); body.write(le(2, 20)); body.write(le(2, n.attrs.size))
      body.write(le(2, 0)); body.write(le(2, 0)); body.write(le(2, 0))
      for ((android, k, v) in n.attrs) {
        body.write(le(4, if (android) 0 else -1)); body.write(le(4, idx(k)))
        if (v is Boolean) { body.write(le(4, -1)); body.write(le(2, 8)); body.write(0); body.write(0x12); body.write(le(4, if (v) -1 else 0)) }
        else { body.write(le(4, idx(v as String))); body.write(le(2, 8)); body.write(0); body.write(0x03); body.write(le(4, idx(v))) }
      }
      n.children.forEach(::emit)
      body.write(le(2, 0x0103)); body.write(le(2, 16)); body.write(le(4, 24)); body.write(le(4, 1)); body.write(le(4, -1)); body.write(le(4, -1)); body.write(le(4, idx(n.tag)))
    }
    emit(root)
    val chars = java.io.ByteArrayOutputStream()
    val offsets = pool.map { str -> chars.size().also { chars.write(le(2, str.length)); str.forEach { c -> chars.write(le(2, c.code)) }; chars.write(le(2, 0)) } }
    while (chars.size() % 4 != 0) chars.write(0)
    val poolChunk = java.io.ByteArrayOutputStream()
    val poolSize = 28 + 4 * pool.size + chars.size()
    poolChunk.write(le(2, 0x0001)); poolChunk.write(le(2, 28)); poolChunk.write(le(4, poolSize)); poolChunk.write(le(4, pool.size)); poolChunk.write(le(4, 0))
    poolChunk.write(le(4, 0)); poolChunk.write(le(4, 28 + 4 * pool.size)); poolChunk.write(le(4, 0))
    offsets.forEach { poolChunk.write(le(4, it)) }; poolChunk.write(chars.toByteArray())
    val out = java.io.ByteArrayOutputStream()
    out.write(le(2, 0x0003)); out.write(le(2, 8)); out.write(le(4, 8 + poolSize + body.size())); out.write(poolChunk.toByteArray()); out.write(body.toByteArray())
    return out.toByteArray()
  }

  @Test
  fun thePackagedBinaryManifestIsReadLikeTheMergedOne() {
    val bytes =
      binaryXml(
        Node("manifest", listOf(Triple(false, "package", "x")), listOf(
          Node("uses-permission-sdk-23", listOf(Triple(true, "name", "android.permission.READ_CONTACTS"))),
          Node("application", listOf(Triple(true, "usesCleartextTraffic", false)), listOf(
            Node("service", listOf(Triple(true, "name", "com.x.Svc"), Triple(true, "exported", true)), listOf(
              Node("intent-filter", emptyList(), listOf(
                Node("action", listOf(Triple(true, "name", "com.x.GO"))),
                Node("data", listOf(Triple(true, "scheme", "https"), Triple(true, "host", "a.example.org"))),
              )),
            )),
          )),
        ))
      )
    val root = BinaryXml.parse(bytes).documentElement
    assertEquals("x", root.getAttribute("package"))
    assertEquals(listOf("uses-permission-sdk-23" to "android.permission.READ_CONTACTS"), ManifestRules.requestedPermissions(root))
    assertEquals(listOf("com.x.Svc | action=com.x.GO | data=host:a.example.org,scheme:https"), ManifestRules.intentFilters(root))
    assertEquals(listOf(Triple("service", "com.x.Svc", "")), ManifestRules.exportedComponents(root))
    assertTrue(checkManifest(root, "ci").contains("service com.x.Svc is exported"))
  }

  @Test
  fun onlyTheFilteredMainSourceSetAndTheStandInsAreBuilt() {
    val dir = Files.createTempDirectory("sourcesets").toFile()
    try {
      fun file(path: String, text: String = "package com.x\n") = File(dir, path).apply { parentFile.mkdirs(); writeText(text) }
      file("src/main/java/com/x/A.kt")
      file("src/main/res/values/strings.xml", "<resources/>")
      file("src/phoneagentStandins/java/com/x/tts/TtsStandIns.kt", "package com.x.tts\n")
      file("src/phoneagentStandins/java/com/x/tts/Leftover.kt", "package com.x.tts\n")
      file("src/test/java/com/x/ATest.kt")
      file("src/debug/java/com/x/runtime/aicore/Copy.kt")
      file("build/filtered/java/com/google/firebase/Fake.kt", "package com.google.firebase.analytics\n")
      val problems =
        SourceSets.problems(
          srcRoot = File(dir, "src"),
          buildDir = File(dir, "build"),
          codeDirs = listOf(File(dir, "build/filtered/java"), File(dir, "src/phoneagentStandins/java"), File(dir, "src/main/java"), File(dir, "src/release/java")),
          resourceDirs = listOf(File(dir, "src/main/res")),
          excluded = null,
        ).map { it.replace(dir.path, "<tmp>") }
      assertEquals(
        listOf(
          "source set src/debug is not allowed: only the filtered src/main, src/phoneagentStandins and test source sets are built",
          "source folder <tmp>/src/main/java is compiled or packaged unfiltered",
          "src/phoneagentStandins/java/com/x/tts/Leftover.kt is not a stand-in file (java/**/*StandIns.kt)",
          "<tmp>/build/filtered/java/com/google/firebase/Fake.kt declares forbidden package com.google.firebase.analytics",
        ),
        problems,
      )
    } finally {
      dir.deleteRecursively()
    }
  }
}

