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
   * Intent filters the merged manifest may declare, as "component action". Adding one (a deep
   * link, a share target, a boot receiver) is a reviewed change to this file.
   */
  val APPROVED_INTENT_FILTERS =
    setOf(
      "com.google.ai.edge.gallery.MainActivity android.intent.action.MAIN",
      "net.openid.appauth.RedirectUriReceiverActivity android.intent.action.VIEW",
      "com.google.ai.edge.gallery.phoneagent.a11y.PhoneAgentAccessibilityService android.accessibilityservice.AccessibilityService",
      "com.google.ai.edge.gallery.phoneagent.tile.PhoneAgentTileService android.service.quicksettings.action.QS_TILE",
      // AndroidX internals, reachable only by the system or adb (see SYSTEM_PERMISSIONS).
      "androidx.profileinstaller.ProfileInstallReceiver androidx.profileinstaller.action.INSTALL_PROFILE",
      "androidx.profileinstaller.ProfileInstallReceiver androidx.profileinstaller.action.SKIP_FILE",
      "androidx.profileinstaller.ProfileInstallReceiver androidx.profileinstaller.action.SAVE_PROFILE",
      "androidx.profileinstaller.ProfileInstallReceiver androidx.profileinstaller.action.BENCHMARK_OPERATION",
      "androidx.work.impl.background.systemalarm.RescheduleReceiver android.intent.action.BOOT_COMPLETED",
      "androidx.work.impl.diagnostics.DiagnosticsReceiver androidx.work.diagnostics.REQUEST_DIAGNOSTICS",
    )

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
        }
      project.tasks.configureEach { if (name == "assemble$cap") dependsOn(check) }
    }
  }
}

/** The exclusion list of upstream files that are not built (app/phoneagent-excluded-sources.txt). */
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

    for (apk in apks) {
      ZipFile(apk).use { zip ->
        val defined = sortedSetOf<String>()
        val logMethods = sortedSetOf<String>()
        val sourceFiles = mutableMapOf<String, String?>()
        val entries = mutableListOf<String>()
        var dexCount = 0
        for (entry in zip.entries()) {
          val name = entry.name
          entries += name
          if (name.matches(Regex("classes\\d*\\.dex"))) {
            dexCount++
            val dex = DexSummary.read(zip.getInputStream(entry).use { it.readBytes() })
            defined += dex.definedClasses
            logMethods += dex.logMethods
            sourceFiles += dex.sourceFiles
          } else if (name.startsWith("lib/") && name.endsWith(".so")) {
            val imports = ElfImports.read(zip.getInputStream(entry).use { it.readBytes() })
            if (imports == null) {
              problems += "$name: cannot read its dynamic symbol table"
              continue
            }
            val network = imports.intersect(ApkRules.NETWORK_SYMBOLS)
            if (network.isNotEmpty()) problems += "$name imports network functions $network"
            val loading = imports.intersect(ApkRules.DYNAMIC_LOADING_SYMBOLS)
            if (loading.isNotEmpty()) notes += "$name loads code by name ($loading)"
          }
        }
        if (dexCount == 0) problems += "${apk.name}: no dex files"
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
        if (type in LOG_STRIPPED_BUILD_TYPES) {
          val left = logMethods.intersect(ApkRules.STRIPPED_LOG_METHODS)
          if (left.isNotEmpty()) problems += "debug logging left in a $type build: Log.$left"
        }
        notes += "${apk.name}: $dexCount dex files, ${defined.size} classes"
      }
    }

    problems += checkManifest(mergedManifest.get().asFile, type)

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

  private fun checkManifest(file: File, type: String): List<String> {
    val problems = mutableListOf<String>()
    val factory = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
      setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    val root = factory.newDocumentBuilder().parse(file).documentElement
    fun Element.attr(name: String): String = getAttributeNS(ANDROID_NS, name)
    fun elements(tag: String) =
      root.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    // The permission set is exact: a new permission fails, and so does an approved one going away.
    problems += ManifestRules.permissionProblems(elements("uses-permission").map { it.attr("name") })
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
    for (tag in listOf("activity", "activity-alias", "service", "receiver", "provider")) {
      for (component in elements(tag)) {
        if (component.attr("exported") != "true") continue
        val name = component.attr("name")
        if (name in approvedExported || component.attr("permission") in ApkRules.SYSTEM_PERMISSIONS) continue
        problems += "$tag $name is exported"
      }
    }
    return problems
  }

  private companion object {
    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
  }
}

/** The merged manifest's permissions, intent filters and package-visibility entries. */
internal object ManifestRules {
  private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
  private val COMPONENTS = listOf("activity", "activity-alias", "service", "receiver", "provider")

  /** The requested permissions must be exactly [ApkRules.APPROVED_PERMISSIONS] (plus the app's own). */
  fun permissionProblems(requested: Collection<String>): List<String> {
    val asked = requested.filterNot { it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }.toSet()
    return (asked - ApkRules.APPROVED_PERMISSIONS).sorted().map { "permission $it is not approved" } +
      (ApkRules.APPROVED_PERMISSIONS - asked).sorted().map {
        "approved permission $it is no longer requested: the permission set changed (ApkRules.APPROVED_PERMISSIONS)"
      }
  }

  fun intentFilterProblems(filters: Collection<String>): List<String> =
    filters.filter { it !in ApkRules.APPROVED_INTENT_FILTERS }.distinct().sorted().map { "intent filter '$it' is not approved" }

  fun queryProblems(queries: Collection<String>): List<String> =
    queries.filter { it !in ApkRules.APPROVED_QUERIES }.distinct().sorted().map { "package-visibility entry '$it' is not approved" }

  /** Every intent filter of the app's components, as "component action" (one per action). */
  fun intentFilters(root: Element): List<String> {
    val application = root.childElements("application").firstOrNull() ?: return emptyList()
    val result = mutableListOf<String>()
    for (component in application.childElements().filter { it.tagName in COMPONENTS }) {
      val name = component.getAttributeNS(ANDROID_NS, "name")
      for (filter in component.childElements("intent-filter")) {
        val actions = filter.childElements("action").map { it.getAttributeNS(ANDROID_NS, "name") }
        if (actions.isEmpty()) result += "$name (no action)" else actions.forEach { result += "$name $it" }
      }
    }
    return result
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
                  val attributes = (0 until part.attributes.length).map { part.attributes.item(it) }
                    .map { "${it.localName ?: it.nodeName}:${it.nodeValue}" }.sorted()
                  if (part.tagName == "data") "data=${attributes.joinToString(",")}"
                  else "${part.tagName}=${part.getAttributeNS(ANDROID_NS, "name")}"
                }.sorted().joinToString(" ")
            else -> "<${entry.tagName}>"
          }
      }
    }
    return result
  }

  private fun Element.childElements(tag: String? = null): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { tag == null || it.tagName == tag }
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
    for ((descriptor, sourceFile) in classes) {
      val name = descriptor.removePrefix("L").removeSuffix(";")
      val pkg = name.substringBeforeLast('/', "") + "/"
      val source = sourceFile ?: classes["L${name.substringBefore('$')};"]
      when {
        wholePackages.any { name.startsWith(it) } -> problems += "class $descriptor is from excluded package ${wholePackages.first { name.startsWith(it) }}"
        source != null && "$pkg$source" in sourceFiles -> problems += "class $descriptor is compiled from excluded $pkg$source"
        source == null && standInPackages.any { name.startsWith(it) } ->
          problems += "class $descriptor has no source file name, so it cannot be told apart from excluded code"
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
      return DexSummary(defined, logMethods, sourceFiles)
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
