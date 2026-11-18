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

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element

/**
 * What a built APK may contain (privacy contract, docs/NETWORK.md). The dependency check in
 * [PrivacyGuardTask] sees Maven coordinates; this one sees what actually ships, so it also catches
 * classes copied into other jars, native code and manifest entries merged in from libraries.
 */
internal object ApkRules {
  /** Packages whose classes must not be in any dex file. */
  val FORBIDDEN_CLASS_PREFIXES =
    listOf(
      "Lcom/google/firebase/",
      "Lcom/google/android/gms/",
      "Lcom/google/mlkit/",
      "Lcom/google/android/play/core/",
      "Lcom/google/android/play/integrity/",
      "Lcom/google/ai/edge/aicore/",
      "Lcom/google/android/ads/",
      "Lcom/google/ads/",
      "Lcom/crashlytics/",
      "Lio/sentry/",
      "Lcom/bugsnag/",
      "Lcom/microsoft/appcenter/",
      "Lcom/amplitude/",
      "Lcom/mixpanel/",
      "Lcom/segment/analytics/",
      "Lcom/facebook/appevents/",
      "Lcom/appsflyer/",
      "Lcom/adjust/sdk/",
      "Lcom/onesignal/",
      "Lio/opentelemetry/",
    )

  /** Firebase's telemetry uploader packages: only the no-op classes of :telemetry-stub may exist. */
  val STUBBED_PREFIXES =
    listOf("Lcom/google/android/datatransport/runtime/", "Lcom/google/android/datatransport/cct/")

  fun isStubClass(descriptor: String): Boolean {
    val simple = descriptor.substringAfterLast('/')
    return simple.startsWith("TransportRuntime") || simple == "Destination;" || simple == "CCTDestination;"
  }

  /** Native functions that open sockets or resolve host names. */
  val NETWORK_SYMBOLS =
    setOf(
      "socket", "socketpair", "connect", "bind", "listen", "accept", "accept4", "sendto", "sendmsg",
      "sendmmsg", "recvfrom", "recvmsg", "getaddrinfo", "getnameinfo", "gethostbyname",
      "gethostbyname2", "gethostbyname_r", "gethostbyaddr", "res_query", "res_search", "res_nquery",
      "android_getaddrinfofornetwork", "android_res_nquery", "android_res_nsend",
    )

  /** Native functions that load code by name; reported, since they can reach the above indirectly. */
  val DYNAMIC_LOADING_SYMBOLS = setOf("dlopen", "android_dlopen_ext", "dlsym")

  /**
   * What the strings of a native library may contain (see [NativeStrings]): every URL, host name,
   * reference to a Java networking class and telemetry word found in a native library must match
   * one of these, for that library (file name) or for every library ("*"). MediaPipe 1.2.0's native
   * Clearcut uploader (https://play.googleapis.com/log, through java/net/HttpURLConnection over JNI)
   * is what this rule exists for: native code can reach the network without importing a socket
   * function. Adding an entry is a reviewed change to this file, with its reason.
   */
  val NATIVE_ALLOWLIST: List<NativeAllowance> =
    listOf(
      NativeAllowance("*", NativeKind.URL, prefix("https://android.googlesource.com/toolchain/llvm-project"),
        "the NDK compiler's version string (.comment section); never used at run time"),
      // LiteRT-LM bundles Dawn/WebGPU, Tint, TFLite, abseil and protobuf.
      NativeAllowance("liblitertlm_jni.so", NativeKind.URL, prefix("https://dawn.googlesource.com/dawn/"), "links in Dawn's error messages"),
      NativeAllowance("liblitertlm_jni.so", NativeKind.URL, prefix("https://gpuweb.github.io/gpuweb/"), "WebGPU spec links in Dawn's error messages"),
      NativeAllowance("liblitertlm_jni.so", NativeKind.URL, prefix("https://github.com/gpuweb/gpuweb"), "WebGPU spec links in Dawn's error messages"),
      NativeAllowance("liblitertlm_jni.so", NativeKind.URL, prefix("https://github.com/KhronosGroup/Vulkan-Docs"), "Vulkan spec link in Dawn's error messages"),
      NativeAllowance("liblitertlm_jni.so", NativeKind.URL, Regex("https?://crbug\\.com/.*"), "Chromium bug links in Dawn/Tint error messages"),
      NativeAllowance("liblitertlm_jni.so", NativeKind.URL, prefix("https://issues.chromium.org/"), "Chromium bug links in Dawn error messages"),
      NativeAllowance("liblitertlm_jni.so", NativeKind.URL, prefix("https://www.tensorflow.org/lite/"), "TFLite documentation links in error messages"),
      NativeAllowance("liblitertlm_jni.so", NativeKind.URL, Regex("https?://json-schema\\.org/draft.*"), "JSON Schema meta-schema identifiers, compared as strings"),
      NativeAllowance("liblitertlm_jni.so", NativeKind.HOST, exact("crbug.com"), "Chromium bug references in Dawn/Tint error messages"),
      NativeAllowance("liblitertlm_jni.so", NativeKind.HOST, exact("type.googleapis.com"), "protobuf Any type-URL prefix, not a host that is contacted"),
      NativeAllowance("liblitertlm_jni.so", NativeKind.HOST, exact("ugcvy23.prod.google.com"), "a build machine's name in an embedded build path"),
      // MediaPipe Tasks 1.0.0/1.1.0 (vision, retrieval).
      NativeAllowance("libmediapipe_tasks_jni.so", NativeKind.URL, prefix("https://github.com/opencv/opencv/issues/"), "an OpenCV issue link in an error message"),
      NativeAllowance("libmediapipe_tasks_jni.so", NativeKind.URL, prefix("https://www.tensorflow.org/lite/guide/"), "TFLite documentation links in error messages"),
      NativeAllowance("libmediapipe_tasks_jni.so", NativeKind.HOST, exact("type.googleapis.com"), "protobuf Any type-URL prefix, not a host that is contacted"),
      NativeAllowance("libmediapipe_tasks_jni.so", NativeKind.HOST, exact("google.golang.org"), "a Go package name in an embedded protobuf descriptor"),
      NativeAllowance("libmediapipe_tasks_jni.so", NativeKind.JAVA_NET, exact("android/net/Uri"), "the URI value class (parsing only, no I/O)"),
      NativeAllowance("libsqlite_vector_store_jni.so", NativeKind.HOST, Regex("type\\.googleapis\\.com|type\\.googleprod\\.com"), "protobuf type-URL prefixes, not hosts that are contacted"),
      NativeAllowance("libsqlite_vector_store_jni.so", NativeKind.JAVA_NET, exact("android/net/Uri"), "the URI value class (parsing only, no I/O)"),
      NativeAllowance("libsqlite_vector_store_jni.so", NativeKind.WORD,
        Regex(".*(GmsTfLiteLogger|TfLiteLoggerForJni).*|logTfLiteTelemetryEvent|getFlag(LiteRtTelemetryEnabled|NnapiSlEnableTelemetry|TfliteEnableTelemetryProfiler)"),
        "TFLite's hooks into Play services' TFLite logger: JNI calls on an object that only Play services' " +
          "TFLite Java library can hand over; that library is forbidden and absent (no Lcom/google/android/gms/ class)"),
    )

  private fun prefix(p: String) = Regex(Regex.escape(p) + ".*")

  private fun exact(s: String) = Regex(Regex.escape(s))

  /** Permissions the app may request. Adding one is a reviewed change to this file. */
  val APPROVED_PERMISSIONS =
    setOf(
      "android.permission.INTERNET",
      "android.permission.ACCESS_NETWORK_STATE",
      "android.permission.CAMERA",
      "android.permission.RECORD_AUDIO",
      "android.permission.READ_CALENDAR",
      "android.permission.READ_MEDIA_IMAGES",
      "android.permission.READ_MEDIA_VIDEO",
      "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
      "android.permission.ACCESS_MEDIA_LOCATION",
      "android.permission.READ_EXTERNAL_STORAGE",
      "android.permission.FOREGROUND_SERVICE",
      "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
      "android.permission.FOREGROUND_SERVICE_SPECIAL_USE", // Phase 2: Phone Agent runs
      "android.permission.POST_NOTIFICATIONS",
      "android.permission.WAKE_LOCK",
    )

  /** Components other apps may start without holding a system permission. */
  val APPROVED_EXPORTED =
    setOf(
      "com.google.ai.edge.gallery.MainActivity", // launcher only; no deep links
      "net.openid.appauth.RedirectUriReceiverActivity", // OAuth redirect, unused until HF login is configured
    )

  /** Debug-only tooling that may be exported in debug builds. */
  val APPROVED_EXPORTED_DEBUG =
    setOf(
      "androidx.compose.ui.tooling.PreviewActivity",
      "androidx.activity.ComponentActivity", // Compose UI test host (ui-test-manifest, debug only)
    )

  /** Exported components guarded by these permissions can only be reached by the system or adb. */
  val SYSTEM_PERMISSIONS =
    setOf(
      "android.permission.BIND_JOB_SERVICE",
      "android.permission.DUMP",
      "android.permission.BIND_ACCESSIBILITY_SERVICE",
      "android.permission.BIND_QUICK_SETTINGS_TILE",
    )

  /**
   * Every intent filter the manifest declares, whole (see [ManifestRules.intentFilters]): the
   * component, its actions, categories and data (scheme, host, port, path, MIME type). The set is
   * exact: a new filter, a changed one (another category, scheme or host on an approved action) and
   * a missing one all fail. Adding one (a deep link, a share target, a boot receiver) is a reviewed
   * change to this file.
   */
  val APPROVED_INTENT_FILTERS =
    setOf(
      "com.google.ai.edge.gallery.MainActivity | action=android.intent.action.MAIN | category=android.intent.category.LAUNCHER",
      "net.openid.appauth.RedirectUriReceiverActivity | action=android.intent.action.VIEW | category=android.intent.category.BROWSABLE,android.intent.category.DEFAULT | data=scheme:REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP",
      "com.google.ai.edge.gallery.phoneagent.a11y.PhoneAgentAccessibilityService | action=android.accessibilityservice.AccessibilityService",
      "com.google.ai.edge.gallery.phoneagent.tile.PhoneAgentTileService | action=android.service.quicksettings.action.QS_TILE",
      // AndroidX internals, reachable only by the system or adb (see SYSTEM_PERMISSIONS).
      "androidx.profileinstaller.ProfileInstallReceiver | action=androidx.profileinstaller.action.INSTALL_PROFILE",
      "androidx.profileinstaller.ProfileInstallReceiver | action=androidx.profileinstaller.action.SKIP_FILE",
      "androidx.profileinstaller.ProfileInstallReceiver | action=androidx.profileinstaller.action.SAVE_PROFILE",
      "androidx.profileinstaller.ProfileInstallReceiver | action=androidx.profileinstaller.action.BENCHMARK_OPERATION",
      "androidx.work.impl.background.systemalarm.RescheduleReceiver | action=android.intent.action.BOOT_COMPLETED",
      "androidx.work.impl.diagnostics.DiagnosticsReceiver | action=androidx.work.diagnostics.REQUEST_DIAGNOSTICS",
    )

  /** Manifest elements that ask for a permission. All of them count toward [APPROVED_PERMISSIONS]. */
  val PERMISSION_REQUEST_TAGS = listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")

  /** Manifest elements that declare a permission. Only the app's own receiver permission may be declared. */
  val PERMISSION_DECLARATION_TAGS = listOf("permission", "permission-group", "permission-tree")

  /** AndroidX core declares and asks for `<package>` + this, to protect the app's own receivers. */
  const val APP_RECEIVER_PERMISSION_SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

  /**
   * Types that no dex file may even refer to: the platform's speech recognition and text-to-speech
   * (`android.speech`, including `android.speech.tts.TextToSpeech`) and MediaPipe's audio tasks
   * (the option protos that tasks-core ships are data classes and allowed). A copy of excluded
   * voice code under another name or package still uses these, so it is caught here.
   */
  fun isForbiddenReference(descriptor: String): Boolean =
    descriptor.startsWith("Landroid/speech/") ||
      (descriptor.startsWith("Lcom/google/mediapipe/tasks/audio/") && !descriptor.contains("/proto/"))

  fun referenceProblems(types: Collection<String>): List<String> =
    types.filter { isForbiddenReference(it) }.sorted().map {
      "dex refers to $it (speech recognition, text-to-speech or MediaPipe audio, which are out of the build)"
    }

  /**
   * Package-visibility entries (`<queries>`) the merged manifest may declare: the Firefox builds
   * Phone Agent drives and https links. Adding one is a reviewed change to this file.
   */
  val APPROVED_QUERIES =
    setOf(
      "package org.mozilla.firefox",
      "package org.mozilla.firefox_beta",
      "package org.mozilla.fenix",
      "package org.mozilla.fennec_fdroid",
      "package io.github.forkmaintainers.iceraven",
      "intent action=android.intent.action.VIEW category=android.intent.category.BROWSABLE data=scheme:https",
    )

  /** androidx.startup initializers that must not run at startup. */
  val FORBIDDEN_INITIALIZERS = setOf("androidx.emoji2.text.EmojiCompatInitializer")

  fun isForbiddenClass(descriptor: String): Boolean = FORBIDDEN_CLASS_PREFIXES.any { descriptor.startsWith(it) }

  /** Log levels stripped from release and ci builds by StripDebugLogs.kt. */
  val STRIPPED_LOG_METHODS = setOf("v", "d", "i")
}

internal fun registerApkPrivacyChecks(project: Project) {
  project.plugins.withId("com.android.application") {
    val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
    components.onVariants(components.selector().all()) { variant ->
      val cap = variant.name.replaceFirstChar { it.uppercase() }
      val check =
        project.tasks.register("check${cap}Apk", ApkPrivacyCheckTask::class.java) {
          group = "verification"
          description = "Checks the ${variant.name} APK and merged manifest against the privacy contract."
          apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
          mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
          buildType.set(variant.buildType ?: variant.name)
          report.set(project.layout.buildDirectory.file("reports/phoneagent/apk-check-${variant.name}.txt"))
          // Upstream features kept in the tree but out of the build (docs/UPSTREAM_SYNC.md).
          val excluded = project.file(EXCLUDED_LIST)
          if (excluded.isFile) {
            excludedList.set(excluded)
            excludedJavaRoot.set(project.layout.projectDirectory.dir("src/main/java"))
            excludedAssetsRoot.set(project.layout.projectDirectory.dir("src/main/assets"))
            standInsRoot.set(project.layout.projectDirectory.dir("src/phoneagentStandins/java"))
          }
          // Every source folder the variant compiles or packages, and the source sets on disk.
          sourceRoot.set(project.layout.projectDirectory.dir("src"))
          buildRoot.set(project.layout.buildDirectory)
          sourceSetNames.set(project.provider { project.file("src").listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted() })
          val sources = variant.sources
          listOfNotNull(sources.java, sources.kotlin).forEach { flat ->
            codeDirectories.addAll(flat.static.map { dirs -> dirs.map { it.asFile.absolutePath } })
          }
          sources.assets?.let { layered -> codeDirectories.addAll(layered.static.map { l -> l.flatten().map { it.asFile.absolutePath } }) }
          listOfNotNull(sources.res, sources.jniLibs).forEach { layered ->
            resourceDirectories.addAll(layered.static.map { l -> l.flatten().map { it.asFile.absolutePath } })
          }
        }
      // Every way to get an APK or bundle out of the build runs the check first.
      val gated = setOf("assemble$cap", "install$cap", "bundle$cap")
      project.tasks.configureEach { if (name in gated) dependsOn(check) }
    }
  }
}

/** The exclusion list of upstream files that are not built (app/phoneagent-excluded-sources.txt). */

/** The source file name D8 gives the classes it synthesizes (lambdas, backports). */
internal const val D8_SYNTHETIC = "D8\$\$SyntheticClass"
internal const val EXCLUDED_LIST = "phoneagent-excluded-sources.txt"

abstract class ApkPrivacyCheckTask : DefaultTask() {
  @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val apkDirectory: DirectoryProperty
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val mergedManifest: RegularFileProperty
  @get:Input abstract val buildType: Property<String>
  @get:OutputFile abstract val report: RegularFileProperty
  @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val excludedList: RegularFileProperty
  @get:Internal abstract val excludedJavaRoot: DirectoryProperty
  @get:Internal abstract val excludedAssetsRoot: DirectoryProperty
  @get:Internal abstract val standInsRoot: DirectoryProperty
  @get:Internal abstract val sourceRoot: DirectoryProperty
  @get:Internal abstract val buildRoot: DirectoryProperty
  @get:Input abstract val sourceSetNames: ListProperty<String>
  @get:Input abstract val codeDirectories: ListProperty<String>
  @get:Input abstract val resourceDirectories: ListProperty<String>

  @TaskAction
  fun run() {
    val apks = apkDirectory.get().asFile.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
    if (apks.isEmpty()) throw GradleException("APK check: no APK in ${apkDirectory.get().asFile}")
    val type = buildType.get()
    val problems = mutableListOf<String>()
    val notes = mutableListOf<String>()
    val excluded =
      excludedList.orNull?.asFile?.let { list ->
        try {
          ExcludedFeatures.read(list, excludedJavaRoot.get().asFile, excludedAssetsRoot.get().asFile, standInsRoot.get().asFile)
        } catch (e: IllegalArgumentException) {
          throw GradleException("APK check: ${e.message}")
        }
      }
    if (excluded != null) {
      excluded.stalePatterns.forEach { problems += "exclusion pattern '$it' matches no file: fix or remove it in $EXCLUDED_LIST" }
      if (excluded.sourceFiles.isEmpty()) problems += "$EXCLUDED_LIST names no source file"
    }
    problems +=
      SourceSets.problems(
        srcRoot = sourceRoot.get().asFile,
        buildDir = buildRoot.get().asFile,
        codeDirs = codeDirectories.get().map(::File),
        resourceDirs = resourceDirectories.get().map(::File),
        excluded = excluded,
      )

    val mergedRoot = parseXml(mergedManifest.get().asFile)
    var packagedRoot: Element? = null
    for (apk in apks) {
      ZipFile(apk).use { zip ->
        val defined = sortedSetOf<String>()
        val referenced = sortedSetOf<String>()
        val logMethods = sortedSetOf<String>()
        val sourceFiles = mutableMapOf<String, String?>()
        val entries = mutableListOf<String>()
        var dexCount = 0
        var nativeLibraries = 0
        for (entry in zip.entries()) {
          val name = entry.name
          entries += name
          if (name.matches(Regex("classes\\d*\\.dex"))) {
            dexCount++
            val dex = DexSummary.read(zip.getInputStream(entry).use { it.readBytes() })
            defined += dex.definedClasses
            referenced += dex.referencedTypes
            logMethods += dex.logMethods
            sourceFiles += dex.sourceFiles
          } else if (name.endsWith(".so")) {
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            nativeLibraries++
            problems += NativeStrings.problems(name, bytes)
            val imports = ElfImports.read(bytes)
            if (imports == null) {
              problems += "$name: cannot read its dynamic symbol table"
              continue
            }
            val network = imports.intersect(ApkRules.NETWORK_SYMBOLS)
            if (network.isNotEmpty()) problems += "$name imports network functions $network"
            val loading = imports.intersect(ApkRules.DYNAMIC_LOADING_SYMBOLS)
            if (loading.isNotEmpty()) notes += "$name loads code by name ($loading)"
          } else if (name == "AndroidManifest.xml") {
            val root = BinaryXml.parse(zip.getInputStream(entry).use { it.readBytes() }).documentElement
            packagedRoot = root
            problems += checkManifest(root, type).map { "packaged manifest (${apk.name}): $it" }
            val differences = ManifestRules.summary(mergedRoot).toSet().let { merged ->
              val packaged = ManifestRules.summary(root).toSet()
              (merged - packaged).map { "only in the merged manifest: $it" } + (packaged - merged).map { "only in the packaged manifest: $it" }
            }
            problems += differences.sorted().take(20).map { "packaged manifest (${apk.name}) differs from the merged one, $it" }
          }
        }
        if (dexCount == 0) problems += "${apk.name}: no dex files"
        if (zip.getEntry("AndroidManifest.xml") == null) problems += "${apk.name}: no AndroidManifest.xml"
        if (excluded != null) {
          problems += excluded.classProblems(sourceFiles)
          problems += excluded.assetProblems(entries)
          val standIns = sourceFiles.count { (cls, src) -> src != null && src.endsWith("StandIns.kt") && cls.startsWith("Lcom/google/ai/edge/gallery/") }
          notes +=
            "excluded upstream features: ${excluded.sourceFiles.size} source files and ${excluded.assetFiles.size} " +
              "assets not in the APK; $standIns classes from the fork's stand-ins"
        }
        for (cls in defined) {
          if (ApkRules.isForbiddenClass(cls)) problems += "forbidden class $cls"
          if (ApkRules.STUBBED_PREFIXES.any { cls.startsWith(it) } && !ApkRules.isStubClass(cls)) {
            problems += "telemetry class $cls is not from :telemetry-stub"
          }
        }
        problems += ApkRules.referenceProblems(referenced)
        if (type in LOG_STRIPPED_BUILD_TYPES) {
          val left = logMethods.intersect(ApkRules.STRIPPED_LOG_METHODS)
          if (left.isNotEmpty()) problems += "debug logging left in a $type build: Log.$left"
        }
        notes += "${apk.name}: $dexCount dex files, ${defined.size} classes, $nativeLibraries native libraries (strings scanned)"
      }
    }

    problems += checkManifest(mergedRoot, type)
    if (packagedRoot != null) notes += "the packaged manifest was checked as well and matches the merged one"

    val text = buildString {
      append(if (problems.isEmpty()) "PASSED\n" else "FAILED\n")
      problems.forEach { append("problem: $it\n") }
      notes.forEach { append("note: $it\n") }
    }
    report.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
    notes.forEach { logger.lifecycle("APK check: $it") }
    if (problems.isNotEmpty()) {
      throw GradleException(
        "APK check ($type) failed:\n" + problems.joinToString("\n") { "  - $it" }
      )
    }
    logger.lifecycle("APK check passed for the $type build.")
  }

  private fun parseXml(file: File): Element {
    val factory = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    return factory.newDocumentBuilder().parse(file).documentElement
  }
}

/** The manifest rules, applied to the merged manifest and to the one packaged in the APK. */
internal fun checkManifest(root: Element, type: String): List<String> {
  val problems = mutableListOf<String>()
  fun Element.attr(name: String): String = getAttributeNS(ManifestRules.ANDROID_NS, name)
  fun elements(tag: String) =
    root.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

  // The permission set is exact: a new permission fails, and so does an approved one going away.
  problems += ManifestRules.permissionProblems(ManifestRules.requestedPermissions(root), ManifestRules.declaredPermissions(root), root.getAttribute("package"))
  problems += ManifestRules.intentFilterProblems(ManifestRules.intentFilters(root))
  problems += ManifestRules.queryProblems(ManifestRules.queries(root))

  val application = elements("application").firstOrNull() ?: return problems + "no <application>"
  if (application.attr("usesCleartextTraffic") != "false") problems += "cleartext traffic is not off"
  if (application.attr("allowBackup") != "false") problems += "backups are not off"
  if (type != "debug" && application.attr("debuggable") == "true") problems += "$type build is debuggable"

  val metaData = elements("meta-data").associate { it.attr("name") to it.attr("value") }
  if (metaData["android.webkit.WebView.MetricsOptOut"] != "true") problems += "WebView metrics are not opted out"
  if (metaData["android.webkit.WebView.EnableSafeBrowsing"] != "false") problems += "WebView Safe Browsing is on"
  for (name in metaData.keys) {
    if (name in ApkRules.FORBIDDEN_INITIALIZERS) problems += "startup initializer $name is merged in"
    if (name.contains("com.google.android.gms") || name.contains("firebase", ignoreCase = true)) {
      problems += "meta-data $name"
    }
  }

  val approvedExported =
    ApkRules.APPROVED_EXPORTED + if (type == "debug") ApkRules.APPROVED_EXPORTED_DEBUG else emptySet()
  for ((tag, name, permission) in ManifestRules.exportedComponents(root)) {
    if (name in approvedExported || permission in ApkRules.SYSTEM_PERMISSIONS) continue
    problems += "$tag $name is exported"
  }
  return problems
}

/** A manifest's permissions, intent filters, exported components and package-visibility entries. */
internal object ManifestRules {
  const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
  private val COMPONENTS = listOf("activity", "activity-alias", "service", "receiver", "provider")

  /** Every permission the manifest asks for, as (element, name), from every requesting element. */
  fun requestedPermissions(root: Element): List<Pair<String, String>> =
    ApkRules.PERMISSION_REQUEST_TAGS.flatMap { tag -> root.descendants(tag).map { tag to it.getAttributeNS(ANDROID_NS, "name") } }

  /** Every permission, permission group and permission tree the manifest declares, as (element, name). */
  fun declaredPermissions(root: Element): List<Pair<String, String>> =
    ApkRules.PERMISSION_DECLARATION_TAGS.flatMap { tag -> root.descendants(tag).map { tag to it.getAttributeNS(ANDROID_NS, "name") } }

  /**
   * The requested permissions must be exactly [ApkRules.APPROVED_PERMISSIONS], whichever element
   * asks for them, plus AndroidX's receiver permission of the app itself; that is also the only
   * permission the app may declare.
   */
  fun permissionProblems(
    requested: Collection<Pair<String, String>>,
    declared: Collection<Pair<String, String>>,
    packageName: String,
  ): List<String> {
    val own = packageName + ApkRules.APP_RECEIVER_PERMISSION_SUFFIX
    val asked = requested.filterNot { it.second == own }
    return asked.filter { it.second !in ApkRules.APPROVED_PERMISSIONS }.map { "permission ${it.second} is not approved (<${it.first}>)" }.distinct().sorted() +
      (ApkRules.APPROVED_PERMISSIONS - asked.map { it.second }.toSet()).sorted().map {
        "approved permission $it is no longer requested: the permission set changed (ApkRules.APPROVED_PERMISSIONS)"
      } +
      declared.filterNot { it.first == "permission" && it.second == own }.map { "the manifest declares <${it.first}> ${it.second}, which is not approved" }.distinct().sorted()
  }

  /** The intent filters must be exactly [approved]: a new or changed one fails, and so does one going away. */
  fun intentFilterProblems(filters: Collection<String>, approved: Set<String> = ApkRules.APPROVED_INTENT_FILTERS): List<String> =
    filters.filter { it !in approved }.distinct().sorted().map { "intent filter '$it' is not approved" } +
      (approved - filters.toSet()).sorted().map { "approved intent filter '$it' is not in the manifest: the filter set changed (ApkRules.APPROVED_INTENT_FILTERS)" }

  fun queryProblems(queries: Collection<String>): List<String> =
    queries.filter { it !in ApkRules.APPROVED_QUERIES }.distinct().sorted().map { "package-visibility entry '$it' is not approved" }

  /**
   * Every intent filter of the app's components, whole: "component | action=... | category=... |
   * data=attr:value,...;..." with every value sorted, so a new category, scheme, host, path or
   * MIME type on an approved component and action is a different filter.
   */
  fun intentFilters(root: Element): List<String> {
    val application = root.childElements("application").firstOrNull() ?: return emptyList()
    val result = mutableListOf<String>()
    for (component in application.childElements().filter { it.tagName in COMPONENTS }) {
      val name = component.getAttributeNS(ANDROID_NS, "name")
      for (filter in component.childElements("intent-filter")) {
        val actions = filter.childElements("action").map { it.getAttributeNS(ANDROID_NS, "name") }.sorted()
        val categories = filter.childElements("category").map { it.getAttributeNS(ANDROID_NS, "name") }.sorted()
        val data = filter.childElements("data").map { it.attributeList().joinToString(",") }.sorted()
        val own = filter.attributeList()
        result +=
          buildString {
            append(name).append(" | action=").append(actions.joinToString(","))
            if (categories.isNotEmpty()) append(" | category=").append(categories.joinToString(","))
            if (data.isNotEmpty()) append(" | data=").append(data.joinToString(";"))
            if (own.isNotEmpty()) append(" | ").append(own.joinToString(","))
          }
      }
    }
    return result
  }

  /** Every component that is exported, as (tag, name, permission). */
  fun exportedComponents(root: Element): List<Triple<String, String, String>> =
    COMPONENTS.flatMap { tag -> root.descendants(tag) }.filter { it.getAttributeNS(ANDROID_NS, "exported") == "true" }.map {
      Triple(it.tagName, it.getAttributeNS(ANDROID_NS, "name"), it.getAttributeNS(ANDROID_NS, "permission"))
    }

  /** Every `<queries>` entry, as "package NAME", "provider AUTHORITIES" or "intent action=... data=...". */
  fun queries(root: Element): List<String> {
    val result = mutableListOf<String>()
    for (queries in root.childElements("queries")) {
      for (entry in queries.childElements()) {
        result +=
          when (entry.tagName) {
            "package" -> "package ${entry.getAttributeNS(ANDROID_NS, "name")}"
            "provider" -> "provider ${entry.getAttributeNS(ANDROID_NS, "authorities")}"
            "intent" ->
              "intent " +
                entry.childElements().map { part ->
                  if (part.tagName == "data") "data=${part.attributeList().joinToString(",")}"
                  else "${part.tagName}=${part.getAttributeNS(ANDROID_NS, "name")}"
                }.sorted().joinToString(" ")
            else -> "<${entry.tagName}>"
          }
      }
    }
    return result
  }

  /** What the privacy rules look at, one line each, to compare the merged and the packaged manifest. */
  fun summary(root: Element): List<String> =
    (requestedPermissions(root).map { "<${it.first}> ${it.second}" } +
      declaredPermissions(root).map { "<${it.first}> ${it.second}" } +
      intentFilters(root).map { "intent filter $it" } +
      exportedComponents(root).map { "exported ${it.first} ${it.second} ${it.third}" } +
      queries(root).map { "query $it" }).sorted()

  private fun Element.descendants(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

  private fun Element.attributeList(): List<String> =
    (0 until attributes.length).map { attributes.item(it) }.filterNot { it.nodeName.startsWith("xmlns") }
      .map { "${it.localName ?: it.nodeName}:${it.nodeValue}" }.sorted()

  private fun Element.childElements(tag: String? = null): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { tag == null || it.tagName == tag }
}

/**
 * Which source sets may feed the APK. The build compiles only a filtered copy of src/main (under
 * the build folder) plus src/phoneagentStandins; any other source set (src/debug, src/ci, ...) or
 * source folder would bypass the exclusion list, so it fails the check. Test source sets never
 * ship and are allowed.
 */
internal object SourceSets {
  val SHIPPED = setOf("main", "phoneagentStandins")

  fun isTestSet(name: String): Boolean =
    name == "test" || name == "androidTest" || name == "testFixtures" ||
      Regex("(test|androidTest|testFixtures)[A-Z].*").matches(name)

  fun problems(srcRoot: File, buildDir: File, codeDirs: Collection<File>, resourceDirs: Collection<File>, excluded: ExcludedFeatures?): List<String> {
    val problems = mutableListOf<String>()
    for (dir in srcRoot.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
      if (dir.name !in SHIPPED && !isTestSet(dir.name)) {
        problems += "source set src/${dir.name} is not allowed: only the filtered src/main, src/phoneagentStandins and test source sets are built"
      }
    }
    val build = buildDir.canonicalFile
    val standIns = File(srcRoot, "phoneagentStandins").canonicalFile
    val main = File(srcRoot, "main").canonicalFile
    fun File.isUnder(parent: File) = canonicalFile.toPath().startsWith(parent.toPath())
    for (dir in codeDirs.filter { it.exists() }.distinct()) {
      // Code and assets: the filtered copy of src/main lives under the build folder.
      if (!dir.isUnder(build) && !dir.isUnder(standIns)) problems += "source folder $dir is compiled or packaged unfiltered"
    }
    for (dir in resourceDirs.filter { it.exists() }.distinct()) {
      if (!dir.isUnder(build) && !dir.isUnder(standIns) && !dir.isUnder(main)) problems += "resource folder $dir is not from an allowed source set"
    }
    // Stand-ins: only *StandIns.kt files, none named like an excluded file.
    if (standIns.isDirectory) {
      for (file in standIns.walkTopDown().filter { it.isFile }) {
        val rel = file.relativeTo(standIns).invariantSeparatorsPath
        if (!(rel.startsWith("java/") && rel.endsWith("StandIns.kt"))) problems += "src/phoneagentStandins/$rel is not a stand-in file (java/**/*StandIns.kt)"
        if (excluded != null && rel.removePrefix("java/") in excluded.sourceFiles) problems += "src/phoneagentStandins/$rel has the name of an excluded file"
      }
    }
    // Forbidden packages, in every compiled source file.
    for (dir in codeDirs.filter { it.isDirectory }.distinct()) {
      for (file in dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }) {
        val pkg = file.useLines { lines -> lines.take(80).mapNotNull { Regex("^\\s*package\\s+([A-Za-z0-9_.]+)").find(it)?.groupValues?.get(1) }.firstOrNull() } ?: continue
        if (ApkRules.isForbiddenClass("L${pkg.replace('.', '/')}/")) problems += "$file declares forbidden package $pkg"
      }
    }
    return problems
  }
}

/**
 * Upstream files that stay in the tree but are not built (app/phoneagent-excluded-sources.txt), and
 * how to tell whether one of them is in an APK anyway.
 *
 * A class is from an excluded source file when its package and source file name (kept in the dex)
 * name that file; classes without a name (Kotlin's inline helpers, R) count as their outer class.
 * Packages that are excluded whole and hold no stand-in may have no class at all. Stand-ins live
 * in files of their own (`*StandIns.kt`), so they never match an excluded file.
 */
internal class ExcludedFeatures(
  /** Excluded source files, relative to src/main/java. */
  val sourceFiles: Set<String>,
  /** Excluded packages without stand-ins, like "com/google/ai/edge/gallery/runtime/aicore/". */
  val wholePackages: Set<String>,
  /** Excluded packages that hold stand-ins: a class there must say which file it is from. */
  val standInPackages: Set<String>,
  /** Excluded assets, relative to src/main/assets. */
  val assetFiles: Set<String>,
  /** Excluded asset folders, like "xr/". */
  val assetDirs: Set<String>,
  /** Patterns that match no file. */
  val stalePatterns: List<String>,
) {
  /** [classes] maps dex class descriptors to their source file names (null when the dex has none). */
  fun classProblems(classes: Map<String, String?>): List<String> {
    val problems = mutableListOf<String>()
    // Stand-in types per package, for the classes Hilt and Dagger generate next to them.
    val standInTypes =
      classes.filterValues { it != null && it.endsWith("StandIns.kt") }.keys
        .map { it.removePrefix("L").removeSuffix(";").substringBefore('$') }
        .groupBy({ it.substringBeforeLast('/', "") + "/" }, { it.substringAfterLast('/') })
    for ((descriptor, sourceFile) in classes) {
      val name = descriptor.removePrefix("L").removeSuffix(";")
      val pkg = name.substringBeforeLast('/', "") + "/"
      // Classes without a source name, and D8's synthetic classes, count as their outer class.
      val source = if (sourceFile == null || sourceFile == D8_SYNTHETIC) classes["L${name.substringBefore('$')};"] else sourceFile
      val generatedFromStandIn = source != null && standInTypes[pkg].orEmpty().any { source.startsWith("${it}_") && source.endsWith(".java") }
      when {
        wholePackages.any { name.startsWith(it) } -> problems += "class $descriptor is from excluded package ${wholePackages.first { name.startsWith(it) }}"
        source != null && "$pkg$source" in sourceFiles -> problems += "class $descriptor is compiled from excluded $pkg$source"
        source == null && standInPackages.any { name.startsWith(it) } ->
          problems += "class $descriptor has no source file name, so it cannot be told apart from excluded code"
        // An excluded package keeps only its stand-ins: any other file there is excluded code under
        // another name (or new code that would need a review).
        source != null && !source.endsWith("StandIns.kt") && !generatedFromStandIn && standInPackages.any { name.startsWith(it) } ->
          problems += "class $descriptor in excluded package ${standInPackages.first { name.startsWith(it) }} comes from $source, not from a stand-in"
      }
    }
    return problems.sorted()
  }

  /** [entries] are the APK's entry names. */
  fun assetProblems(entries: Collection<String>): List<String> =
    entries.filter { it.startsWith("assets/") }.map { it.removePrefix("assets/") }
      .filter { asset -> asset in assetFiles || assetDirs.any { asset.startsWith(it) } }
      .sorted().map { "asset $it is from an excluded feature" }

  companion object {
    /**
     * Reads [list] (one pattern per line under src/main: `java/` or `assets/`, then a file, or a
     * folder followed by a slash and two asterisks) against the source tree. Other patterns are refused, so
     * the list stays something this check can verify.
     */
    fun read(list: File, javaRoot: File, assetsRoot: File, standInsRoot: File): ExcludedFeatures {
      val sourceFiles = sortedSetOf<String>()
      val wholePackages = sortedSetOf<String>()
      val standInPackages = sortedSetOf<String>()
      val assetFiles = sortedSetOf<String>()
      val assetDirs = sortedSetOf<String>()
      val stale = mutableListOf<String>()
      fun filesUnder(root: File, dir: String): List<String> =
        File(root, dir).walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
      for (pattern in list.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
        val (kind, path) =
          when {
            pattern.startsWith("java/") -> "java" to pattern.removePrefix("java/")
            pattern.startsWith("assets/") -> "assets" to pattern.removePrefix("assets/")
            else -> throw IllegalArgumentException("$EXCLUDED_LIST: '$pattern' must start with java/ or assets/")
          }
        val dir = path.removeSuffix("/**").takeIf { path.endsWith("/**") }
        if ((dir ?: path).any { it == '*' || it == '?' } || path.contains("..") || path.startsWith("/")) {
          throw IllegalArgumentException("$EXCLUDED_LIST: '$pattern' is not a file or a <folder>/** pattern")
        }
        val root = if (kind == "java") javaRoot else assetsRoot
        val matched = if (dir != null) filesUnder(root, dir) else listOf(path).filter { File(root, it).isFile }
        if (matched.isEmpty()) stale += pattern
        if (kind == "java") {
          sourceFiles += matched
          if (dir != null && matched.isNotEmpty()) {
            if (filesUnder(standInsRoot, dir).isEmpty()) wholePackages += "$dir/" else standInPackages += "$dir/"
          }
        } else {
          assetFiles += matched
          if (dir != null) assetDirs += "$dir/"
        }
      }
      return ExcludedFeatures(sourceFiles, wholePackages, standInPackages, assetFiles, assetDirs, stale)
    }
  }
}

/** The classes a dex file defines, their source file names and the `android.util.Log` methods it calls. */
internal class DexSummary(
  val definedClasses: List<String>,
  val logMethods: Set<String>,
  /** Class descriptor to source file name, or null when the dex has none for the class. */
  val sourceFiles: Map<String, String?> = emptyMap(),
  /** Every type the dex defines or refers to (its type_ids). */
  val referencedTypes: Set<String> = emptySet(),
) {
  companion object {
    fun read(bytes: ByteArray): DexSummary {
      val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      require(String(bytes, 0, 3, Charsets.US_ASCII) == "dex") { "not a dex file" }
      val strings = IntArray(b.getInt(0x38)) { b.getInt(b.getInt(0x3C) + 4 * it) }
      fun string(index: Int): String {
        var pos = strings[index]
        while (bytes[pos].toInt() and 0x80 != 0) pos++ // skip the uleb128 UTF-16 length
        pos++
        val end = generateSequence(pos) { it + 1 }.first { bytes[it].toInt() == 0 }
        return decodeMutf8(bytes, pos, end)
      }
      val typeIdsOff = b.getInt(0x44)
      val types = Array(b.getInt(0x40)) { string(b.getInt(typeIdsOff + 4 * it)) }
      val classDefsOff = b.getInt(0x64)
      val defined = List(b.getInt(0x60)) { types[b.getInt(classDefsOff + 32 * it)] }
      // class_def_item: class_idx, access_flags, superclass_idx, interfaces_off, source_file_idx, ...
      val sourceFiles =
        defined.indices.associate { i ->
          val sourceIndex = b.getInt(classDefsOff + 32 * i + 16)
          defined[i] to (if (sourceIndex == -1) null else string(sourceIndex)) // -1: NO_INDEX
        }
      val methodIdsOff = b.getInt(0x5C)
      val logMethods = mutableSetOf<String>()
      for (i in 0 until b.getInt(0x58)) {
        val off = methodIdsOff + 8 * i
        if (types[b.getShort(off).toInt() and 0xFFFF] == "Landroid/util/Log;") {
          logMethods += string(b.getInt(off + 4))
        }
      }
      return DexSummary(defined, logMethods, sourceFiles, types.toSet())
    }

    /** Modified UTF-8 as used by dex string data. */
    private fun decodeMutf8(bytes: ByteArray, start: Int, end: Int): String {
      val out = StringBuilder(end - start)
      var i = start
      while (i < end) {
        val a = bytes[i++].toInt() and 0xFF
        when {
          a < 0x80 -> out.append(a.toChar())
          a and 0xE0 == 0xC0 && i < end -> out.append((((a and 0x1F) shl 6) or (bytes[i++].toInt() and 0x3F)).toChar())
          a and 0xF0 == 0xE0 && i + 1 < end -> {
            val c = ((a and 0x0F) shl 12) or ((bytes[i].toInt() and 0x3F) shl 6) or (bytes[i + 1].toInt() and 0x3F)
            i += 2
            out.append(c.toChar())
          }
          else -> out.append('?')
        }
      }
      return out.toString()
    }
  }
}

/**
 * Reads Android's binary XML (the AndroidManifest.xml packaged in an APK) into a DOM document, so
 * the packaged manifest goes through the same rules as the merged one. Typed values become text:
 * booleans "true"/"false", integers in decimal, references as "@0x7f...".
 */
internal object BinaryXml {
  private const val RES_XML = 0x0003
  private const val STRING_POOL = 0x0001
  private const val START_ELEMENT = 0x0102
  private const val END_ELEMENT = 0x0103
  private const val NO_INDEX = -1

  fun parse(bytes: ByteArray): org.w3c.dom.Document {
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(bytes.size >= 8 && (b.getShort(0).toInt() and 0xFFFF) == RES_XML) { "not an Android binary XML file" }
    val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().newDocument()
    val stack = ArrayDeque<Element>()
    var strings = emptyList<String>()
    var pos = b.getShort(2).toInt() and 0xFFFF
    while (pos + 8 <= bytes.size) {
      val type = b.getShort(pos).toInt() and 0xFFFF
      val headerSize = b.getShort(pos + 2).toInt() and 0xFFFF
      val size = b.getInt(pos + 4)
      require(size >= 8 && pos + size <= bytes.size) { "broken chunk at $pos" }
      when (type) {
        STRING_POOL -> strings = readStrings(b, pos)
        START_ELEMENT -> {
          val ext = pos + headerSize
          val nameIndex = b.getInt(ext + 4)
          val attrStart = b.getShort(ext + 8).toInt() and 0xFFFF
          val attrSize = b.getShort(ext + 10).toInt() and 0xFFFF
          val attrCount = b.getShort(ext + 12).toInt() and 0xFFFF
          val element = doc.createElementNS(null, strings[nameIndex])
          for (i in 0 until attrCount) {
            val a = ext + attrStart + i * attrSize
            val ns = b.getInt(a)
            val name = strings[b.getInt(a + 4)]
            val raw = b.getInt(a + 8)
            val dataType = bytes[a + 15].toInt() and 0xFF
            val data = b.getInt(a + 16)
            val value =
              when {
                raw != NO_INDEX -> strings[raw]
                dataType == 0x03 -> strings[data]
                dataType == 0x12 -> if (data != 0) "true" else "false"
                dataType == 0x10 -> data.toString()
                dataType == 0x11 -> "0x" + Integer.toHexString(data)
                dataType == 0x01 -> "@0x" + "%08x".format(data)
                dataType == 0x02 -> "?0x" + "%08x".format(data)
                else -> "(type 0x${Integer.toHexString(dataType)})0x${Integer.toHexString(data)}"
              }
            if (ns == NO_INDEX) {
              element.setAttribute(name, value)
            } else {
              val uri = strings[ns]
              element.setAttributeNS(uri, (if (uri == ManifestRules.ANDROID_NS) "android:" else "ns$ns:") + name, value)
            }
          }
          (stack.lastOrNull() ?: doc).appendChild(element)
          stack.addLast(element)
        }
        END_ELEMENT -> stack.removeLastOrNull()
      }
      pos += size
    }
    require(doc.documentElement != null) { "no element in the binary XML" }
    return doc
  }

  private fun readStrings(b: ByteBuffer, chunk: Int): List<String> {
    val count = b.getInt(chunk + 8)
    val utf8 = (b.getInt(chunk + 16) and 0x100) != 0
    val stringsStart = chunk + b.getInt(chunk + 20)
    return List(count) { i ->
      var p = stringsStart + b.getInt(chunk + (b.getShort(chunk + 2).toInt() and 0xFFFF) + 4 * i)
      if (utf8) {
        // UTF-16 length, then UTF-8 length (each one or two bytes), then the bytes.
        p += if ((b.get(p).toInt() and 0x80) != 0) 2 else 1
        val first = b.get(p).toInt() and 0xFF
        val len = if ((first and 0x80) != 0) ((first and 0x7F) shl 8) or (b.get(p + 1).toInt() and 0xFF) else first
        p += if ((first and 0x80) != 0) 2 else 1
        String(ByteArray(len) { b.get(p + it) }, Charsets.UTF_8)
      } else {
        val first = b.getShort(p).toInt() and 0xFFFF
        val len = if ((first and 0x8000) != 0) ((first and 0x7FFF) shl 16) or (b.getShort(p + 2).toInt() and 0xFFFF) else first
        p += if ((first and 0x8000) != 0) 4 else 2
        String(CharArray(len) { b.getChar(p + 2 * it) })
      }
    }
  }
}

internal enum class NativeKind(val label: String) {
  URL("URL"),
  HOST("host name"),
  JAVA_NET("reference to Java networking class"),
  WORD("telemetry string"),
}

/** One approved finding in the strings of a native library ([ApkRules.NATIVE_ALLOWLIST]). */
internal data class NativeAllowance(val library: String, val kind: NativeKind, val match: Regex, val reason: String) {
  fun allows(libraryName: String, finding: NativeStrings.Finding) =
    (library == "*" || library == libraryName) && kind == finding.kind && match.matches(finding.value)
}

/**
 * The printable strings of a native library, searched for what would let it reach the network
 * without importing a socket function: URLs, host names, Java networking classes it could call
 * through JNI (java/net, javax/net, android/net) and telemetry words such as "clearcut". This
 * shows what is in the file, not what the code does at run time.
 */
internal object NativeStrings {
  data class Finding(val kind: NativeKind, val value: String)

  private val printable = Regex("[\\x20-\\x7e]{6,}")
  private val scheme = Regex("(?i)(?:https?|wss?|ftp)://")
  private val urlChars = Regex("[^\\s\"'<>\\\\`{}|^]*")
  private val host =
    Regex(
      "(?<![A-Za-z0-9_.\\-/])(?:[a-z0-9](?:[a-z0-9\\-]{0,61}[a-z0-9])?\\.)+" +
        "(?:com|net|org|io|dev|app|ai|co|info|me|cloud)(?![A-Za-z0-9_\\-]|\\.[A-Za-z0-9])"
    )
  private val javaNet = Regex("(?:java|javax)[/.]net[/.][A-Za-z0-9_$]+(?:[/.][A-Z][A-Za-z0-9_$]*)?|android[/.]net[/.][A-Za-z0-9_$]+")
  private val words = Regex("(?i)clearcut|playlog|firebase|crashlytics|app-?measurement|google-analytics|telemetry")

  fun scan(bytes: ByteArray): Set<Finding> {
    val found = sortedSetOf<Finding>(compareBy({ it.kind }, { it.value }))
    // ISO-8859-1 maps every byte to one char, so offsets and printable runs stay exact.
    for (run in printable.findAll(String(bytes, Charsets.ISO_8859_1))) {
      val s = StringBuilder(run.value)
      // URLs: each starts at a scheme and ends at the next scheme or a character URLs don't contain,
      // so a URL glued to another can't hide behind an approved one.
      val starts = scheme.findAll(run.value).map { it.range.first }.toList()
      for ((i, start) in starts.withIndex()) {
        val end = starts.getOrNull(i + 1) ?: run.value.length
        val raw = urlChars.matchAt(run.value.substring(start, end), 0)?.value.orEmpty()
        val url = raw.trimEnd('.', ',', ';', ':', ')')
        if (url.length > url.indexOf("://") + 3) found += Finding(NativeKind.URL, url)
        for (k in start until start + raw.length) s.setCharAt(k, ' ') // host names outside URLs only
      }
      host.findAll(s).forEach { found += Finding(NativeKind.HOST, it.value) }
      javaNet.findAll(run.value).forEach { found += Finding(NativeKind.JAVA_NET, it.value.replace('.', '/')) }
      if (words.containsMatchIn(run.value)) found += Finding(NativeKind.WORD, run.value)
    }
    return found
  }

  /** The findings in [bytes] that [ApkRules.NATIVE_ALLOWLIST] doesn't approve, as problem texts. */
  fun problems(entryName: String, bytes: ByteArray, allowlist: List<NativeAllowance> = ApkRules.NATIVE_ALLOWLIST): List<String> {
    val library = entryName.substringAfterLast('/')
    return scan(bytes).filterNot { f -> allowlist.any { it.allows(library, f) } }.map { f ->
      "$entryName contains a ${f.kind.label} that is not approved: '${f.value.take(160)}'"
    }
  }
}

/** Undefined (imported) symbols in an ELF shared library's dynamic symbol table. */
internal object ElfImports {
  private const val SHT_DYNSYM = 11

  fun read(bytes: ByteArray): Set<String>? {
    if (bytes.size < 0x40 || bytes[0].toInt() != 0x7F || String(bytes, 1, 3, Charsets.US_ASCII) != "ELF") return null
    val is64 = bytes[4].toInt() == 2
    val b = ByteBuffer.wrap(bytes).order(if (bytes[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
    val shOff = if (is64) b.getLong(0x28) else (b.getInt(0x20).toLong() and 0xFFFFFFFFL)
    val shEntSize = (if (is64) b.getShort(0x3A) else b.getShort(0x2E)).toInt() and 0xFFFF
    val shNum = (if (is64) b.getShort(0x3C) else b.getShort(0x30)).toInt() and 0xFFFF
    if (shOff <= 0 || shNum == 0) return null

    fun section(i: Int): LongArray { // type, offset, size, link, entsize
      val o = (shOff + i.toLong() * shEntSize).toInt()
      return if (is64) {
        longArrayOf(b.getInt(o + 4).toLong(), b.getLong(o + 0x18), b.getLong(o + 0x20), b.getInt(o + 0x28).toLong(), b.getLong(o + 0x38))
      } else {
        longArrayOf(b.getInt(o + 4).toLong(), b.getInt(o + 0x10).toLong(), b.getInt(o + 0x14).toLong(), b.getInt(o + 0x18).toLong(), b.getInt(o + 0x24).toLong())
      }
    }

    val dynsymIndex = (0 until shNum).firstOrNull { section(it)[0] == SHT_DYNSYM.toLong() } ?: return null
    val dynsym = section(dynsymIndex)
    val strtab = section(dynsym[3].toInt())
    val entSize = if (dynsym[4] > 0) dynsym[4].toInt() else if (is64) 24 else 16
    val imports = mutableSetOf<String>()
    for (i in 1 until (dynsym[2] / entSize).toInt()) {
      val o = dynsym[1].toInt() + i * entSize
      val nameOff = b.getInt(o)
      val shndx = (if (is64) b.getShort(o + 6) else b.getShort(o + 14)).toInt() and 0xFFFF
      if (shndx != 0 || nameOff == 0) continue
      val start = strtab[1].toInt() + nameOff
      var end = start
      while (end < bytes.size && bytes[end].toInt() != 0) end++
      imports += String(bytes, start, end - start, Charsets.US_ASCII).substringBefore('@')
    }
    return imports
  }
}
