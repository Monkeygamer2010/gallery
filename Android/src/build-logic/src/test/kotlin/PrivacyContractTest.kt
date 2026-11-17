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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy guard against what upstream 1.0.23 would have brought in. The coordinates and build
 * file lines below are upstream's own (Android/src/gradle/libs.versions.toml and
 * app/build.gradle.kts at tag 1.0.23); none of them is in the fork's build.
 */
class PrivacyContractTest {
  /** The owner's approved exceptions, read from the real docs/NETWORK.md as the guard reads them. */
  private val exceptions = readApprovedExceptions(File("../../../docs/NETWORK.md"))

  private val upstreamForbidden =
    listOf(
      // ML Kit GenAI: the five libraries new in 1.0.23 (voice, XR) and the prompt API (AICore).
      "com.google.mlkit:genai-common",
      "com.google.mlkit:genai-speech-recognition",
      "com.google.mlkit:genai-text-to-speech",
      "com.google.mlkit:genai-schema",
      "com.google.mlkit:genai-schema-compiler",
      "com.google.mlkit:genai-prompt",
      // AICore SDK.
      "com.google.ai.edge.aicore:aicore",
      // Firebase.
      "com.google.firebase:firebase-bom",
      "com.google.firebase:firebase-analytics",
      "com.google.firebase:firebase-messaging",
      // Play services: TFLite, and the OSS licenses screen.
      "com.google.android.gms:play-services-tflite-java",
      "com.google.android.gms:play-services-tflite-gpu",
      "com.google.android.gms:play-services-tflite-support",
      "com.google.android.gms:play-services-oss-licenses",
      // Firebase's telemetry uploader, which MediaPipe would pull in (replaced by :telemetry-stub).
      "com.google.android.datatransport:transport-runtime",
      "com.google.android.datatransport:transport-backend-cct",
    )

  @Test
  fun everyForbiddenArtifactOfUpstreamIsRejected() {
    assertTrue("docs/NETWORK.md was read", File("../../../docs/NETWORK.md").isFile)
    for (coordinate in upstreamForbidden) {
      val (group, module) = coordinate.split(':')
      assertTrue("$coordinate must be rejected", PrivacyContract.isForbidden(group, module, exceptions))
    }
  }

  @Test
  fun theLibrariesThisSyncTakesAreAllowed() {
    for (coordinate in
      listOf(
        "androidx.pdf:pdf-core",
        "androidx.pdf:pdf-document-service",
        "com.google.android.material:material",
        "com.google.mediapipe:tasks-vision",
        "com.google.ai.edge.litertlm:litertlm-android",
        "com.google.android.datatransport:transport-api",
      )) {
      val (group, module) = coordinate.split(':')
      assertFalse("$coordinate is allowed", PrivacyContract.isForbidden(group, module, exceptions))
    }
  }

  @Test
  fun upstreamsBuildFileLinesAreFlagged() {
    val upstreamLines =
      listOf(
        "firebase-bom = { group = \"com.google.firebase\", name = \"firebase-bom\", version.ref = \"firebaseBom\" }",
        "firebase-analytics = { group = \"com.google.firebase\", name = \"firebase-analytics\" }",
        "firebase-messaging = { group = \"com.google.firebase\", name = \"firebase-messaging\" }",
        "mlkit-genai-prompt = { group = \"com.google.mlkit\", name = \"genai-prompt\", version.ref = \"mlkit-genai-prompt\" }",
        "mlkit-genai-common = { group = \"com.google.mlkit\", name = \"genai-common\", version.ref = \"mlkitGenaiCommon\" }",
        "mlkit-genai-speech-recognition = { group = \"com.google.mlkit\", name = \"genai-speech-recognition\", version.ref = \"mlkitGenaiSpeechRecognition\" }",
        "mlkit-genai-text-to-speech = { group = \"com.google.mlkit\", name = \"genai-text-to-speech\", version.ref = \"mlkitGenaiTextToSpeech\" }",
        "mlkit-genai-schema = { group = \"com.google.mlkit\", name = \"genai-schema\", version.ref = \"mlkitGenaiSchema\" }",
        "mlkit-genai-schema-compiler = { group = \"com.google.mlkit\", name = \"genai-schema-compiler\", version.ref = \"mlkitGenaiSchema\" }",
        "play-services-oss-licenses = { module = \"com.google.android.gms:play-services-oss-licenses\", version.ref = \"playServicesOssLicenses\"}",
        "oss-licenses = {id = \"com.google.android.gms.oss-licenses-plugin\", version.ref = \"ossLicenses\"}",
        "google-services = { id = \"com.google.gms.google-services\", version.ref = \"googleService\" }",
      )
    val flagged = PrivacyContract.forbiddenBuildFileLines(upstreamLines, exceptions)
    assertEquals(upstreamLines.indices.map { it + 1 }, flagged.map { it.first })

    // Upstream's lines for the libraries the sync leaves out without a guard rule (their
    // features are out of the build) and for the ones it takes are not flagged.
    val other =
      listOf(
        "androidx-xr-projected = { group = \"androidx.xr.projected\", name = \"projected\", version.ref = \"xrProjected\" }",
        "tasks-audio = { group = \"com.google.mediapipe\", name = \"tasks-audio\", version.ref = \"tasksAudio\" }",
        "androidx-pdf-core = { group = \"androidx.pdf\", name = \"pdf-core\", version.ref = \"androidxPdf\" }",
        "exclude(group = \"com.google.firebase\")",
      )
    assertEquals(emptyList<Pair<Int, String>>(), PrivacyContract.forbiddenBuildFileLines(other, exceptions))
  }

  @Test
  fun classesOfEveryForbiddenLibraryAreRejectedInTheApk() {
    for (descriptor in
      listOf(
        "Lcom/google/mlkit/genai/speechrecognition/SpeechRecognizer;",
        "Lcom/google/mlkit/genai/texttospeech/TextToSpeech;",
        "Lcom/google/mlkit/genai/prompt/GenerativeModel;",
        "Lcom/google/ai/edge/aicore/GenerativeModel;",
        "Lcom/google/firebase/analytics/FirebaseAnalytics;",
        "Lcom/google/firebase/messaging/FirebaseMessaging;",
        "Lcom/google/android/gms/tflite/java/TfLite;",
        "Lcom/google/android/gms/oss/licenses/OssLicensesMenuActivity;",
      )) {
      assertTrue("$descriptor must be rejected", ApkRules.isForbiddenClass(descriptor))
    }
    assertFalse(ApkRules.isForbiddenClass("Lcom/google/android/material/button/MaterialButton;"))
    assertFalse(ApkRules.isForbiddenClass("Landroidx/pdf/viewer/fragment/PdfViewerFragment;"))
  }
}
