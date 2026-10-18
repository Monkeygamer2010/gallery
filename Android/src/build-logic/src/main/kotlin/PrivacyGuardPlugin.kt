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
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element

/**
 * The privacy contract's build-time rules (docs/NETWORK.md). They are constants in build-logic, not
 * settings, so no build script can loosen them; changing them is a reviewed change to this file.
 * The only exceptions are the ones the owner approves in docs/NETWORK.md.
 */
internal object PrivacyContract {
  /** Maven groups that must not ship. A group also matches its sub-groups. */
  val FORBIDDEN_GROUPS =
    listOf(
      "com.google.firebase",
      "com.google.android.gms",
      "com.google.gms",
      "com.google.mlkit",
      "com.google.android.play", // Play Core: in-app updates, reviews, asset delivery, integrity
      "com.google.ai.edge.aicore", // AICore SDK
      "com.google.android.ads",
      "com.google.ads",
      "com.crashlytics",
      "io.fabric",
      "io.sentry",
      "com.bugsnag",
      "com.microsoft.appcenter",
      "com.amplitude",
      "com.mixpanel",
      "com.segment.analytics",
      "com.facebook.android",
      "com.appsflyer",
      "com.adjust.sdk",
      "com.onesignal",
      "com.newrelic",
      "com.datadoghq",
      "io.opentelemetry",
    )

  /** Single modules that must not ship: Firebase's telemetry uploader, replaced by :telemetry-stub. */
  val FORBIDDEN_MODULES =
    setOf(
      "com.google.android.datatransport:transport-runtime",
      "com.google.android.datatransport:transport-backend-cct",
    )

  /** The configurations checked: every variant's compile and runtime classpaths. */
  val CONFIGURATION_REGEX = Regex("(debug|release|ci)(Compile|Runtime)Classpath")

  /** The configuration whose libraries are listed in the in-app licenses screen. */
  const val LICENSE_CONFIGURATION = "releaseRuntimeClasspath"

  const val EXCEPTIONS_FILE = "../../docs/NETWORK.md"
  const val LICENSE_ASSET = "src/main/assets/phoneagent/third_party_licenses.tsv"
  val ROOT_BUILD_FILES = listOf("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradle/libs.versions.toml")
}

/**
 * Enforces the fork's privacy contract at build time.
 *
 * `privacyGuard` fails when a forbidden artifact ([PrivacyContract.FORBIDDEN_GROUPS], the Firebase
 * telemetry uploader), a local file dependency, or the google-services / oss-licenses Gradle
 * plugins show up in the build, unless the owner approved it in docs/NETWORK.md. It also prints
 * every shipped library with its license (from the Maven POM) and fails when the in-app license
 * list is out of date.
 *
 * `updateThirdPartyLicenses` rewrites that in-app license list.
 *
 * `check<Variant>Apk` (see ApkPrivacyCheck.kt) inspects each built APK and runs as part of every
 * `assemble<Variant>`.
 */
class PrivacyGuardPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val rootDir = project.rootDir

    fun PrivacyGuardTask.wire(write: Boolean) {
      group = "verification"
      writeLicenseAsset.set(write)
      exceptionsFile = File(rootDir, PrivacyContract.EXCEPTIONS_FILE)
      licenseAsset = project.file(PrivacyContract.LICENSE_ASSET)
      buildFiles = PrivacyContract.ROOT_BUILD_FILES.map { File(rootDir, it) } + project.buildFile
      report = project.layout.buildDirectory.file("reports/phoneagent/privacy-guard.txt").get().asFile
      configurations = project.configurations
      dependencies = project.dependencies
      allProjects = project.rootProject.allprojects.toList()
    }
    val guard =
      project.tasks.register("privacyGuard", PrivacyGuardTask::class.java) {
        description = "Fails on Firebase/GMS/ML Kit dependencies; prints dependencies and licenses."
        wire(write = false)
      }
    val update =
      project.tasks.register("updateThirdPartyLicenses", PrivacyGuardTask::class.java) {
        description = "Regenerates the license list shown in the app's licenses screen."
        wire(write = true)
      }
    registerDebugLogStripping(project)
    registerApkPrivacyChecks(project)

    // A build script could switch the checks off; refuse to run at all if it did.
    project.gradle.taskGraph.whenReady {
      val disabled =
        allTasks.filter { (it is PrivacyGuardTask || it is ApkPrivacyCheckTask) && !it.enabled }
      if (disabled.isNotEmpty()) {
        throw GradleException(
          "Privacy guard: these checks were disabled by a build script: " +
            disabled.joinToString { it.path } + ". They cannot be turned off."
        )
      }
    }

    // The bytecode check reads the class jars of project dependencies (:telemetry-stub), so they
    // must be built first. AGP creates the variant configurations during evaluation.
    project.afterEvaluate {
      val config = project.configurations.findByName(PrivacyContract.LICENSE_CONFIGURATION) ?: return@afterEvaluate
      val classJars = classJarsView(config).files
      guard.configure { dependsOn(classJars) }
      update.configure { dependsOn(classJars) }
    }
  }
}

private data class Coord(val group: String, val module: String, val version: String) :
  Comparable<Coord> {
  override fun toString() = "$group:$module:$version"

  override fun compareTo(other: Coord) = toString().compareTo(other.toString())
}

private data class License(val spdx: String, val name: String, val url: String)

private data class ApprovedException(val group: String, val module: String) {
  fun matches(group: String, module: String) =
    this.group == group && (this.module == "*" || this.module == module)
}

abstract class PrivacyGuardTask : DefaultTask() {
  @get:Input abstract val writeLicenseAsset: Property<Boolean>

  @get:Internal lateinit var exceptionsFile: File
  @get:Internal lateinit var licenseAsset: File
  @get:Internal lateinit var buildFiles: List<File>
  @get:Internal lateinit var report: File
  @get:Internal lateinit var configurations: ConfigurationContainer
  @get:Internal lateinit var dependencies: DependencyHandler
  @get:Internal lateinit var allProjects: List<Project>

  init {
    notCompatibleWithConfigurationCache("Resolves configurations and POM files at execution time.")
  }

  @TaskAction
  fun run() {
    val forbidden = PrivacyContract.FORBIDDEN_GROUPS
    val exceptions = readExceptions(exceptionsFile)
    val violations = mutableListOf<String>()

    fun isForbidden(group: String, module: String): Boolean =
      (forbidden.any { group == it || group.startsWith("$it.") } ||
        "$group:$module" in PrivacyContract.FORBIDDEN_MODULES) &&
        exceptions.none { it.matches(group, module) }

    // 1. Resolved dependency graphs: the authoritative list of what ships.
    val regex = PrivacyContract.CONFIGURATION_REGEX
    val configs =
      configurations.filter { it.isCanBeResolved && regex.matches(it.name) }.sortedBy { it.name }
    if (configs.isEmpty()) {
      throw GradleException("privacyGuard: no configuration matches ${regex.pattern}")
    }
    val idsByCoord = sortedMapOf<Coord, ModuleComponentIdentifier>()
    val coordsByConfig = linkedMapOf<String, Set<Coord>>()
    for (config in configs) {
      val coords = sortedSetOf<Coord>()
      for (component in config.incoming.resolutionResult.allComponents) {
        val id = component.id as? ModuleComponentIdentifier ?: continue
        val coord = Coord(id.group, id.module, id.version)
        coords += coord
        idsByCoord[coord] = id
        if (isForbidden(id.group, id.module)) {
          val via = component.dependents.map { it.from.id.displayName }.distinct().take(3)
          violations += "${config.name}: $coord (pulled in by $via)"
        }
      }
      coordsByConfig[config.name] = coords
      // Local jars and AARs have no Maven coordinates, so nothing above would see them.
      for (dependency in config.allDependencies.withType(FileCollectionDependency::class.java)) {
        violations += "${config.name}: file dependency ${dependency.files.files.map { it.name }}"
      }
    }

    // 2. Plugins: google-services must never be applied anywhere.
    for (p in allProjects) {
      for (pluginId in listOf("com.google.gms.google-services", "com.google.android.gms.oss-licenses-plugin")) {
        if (p.pluginManager.hasPlugin(pluginId)) violations += "${p.path}: plugin $pluginId applied"
      }
    }

    // 3. Build files as text: catches declared-but-unused libraries and plugins.
    val textPatterns = forbidden.map { Regex(Regex.escape(it)) } + Regex("google-services|oss-licenses")
    for (file in buildFiles.filter { it.isFile }) {
      file.readLines().forEachIndexed { index, line ->
        if (textPatterns.none { it.containsMatchIn(line) }) return@forEachIndexed
        // Exclusions remove dependencies; they are how the build keeps Firebase out.
        if (line.trimStart().startsWith("exclude(")) return@forEachIndexed
        val excepted = exceptions.any { line.contains(it.group) && (it.module == "*" || line.contains(it.module)) }
        if (!excepted) violations += "${file.name}:${index + 1}: ${line.trim()}"
      }
    }

    // 4. Bytecode: everything MediaPipe calls in the stubbed telemetry packages must exist.
    val licenseConfig = PrivacyContract.LICENSE_CONFIGURATION
    val classesConfig =
      configs.firstOrNull { it.name == licenseConfig }
        ?: throw GradleException("privacyGuard: configuration $licenseConfig was not checked")
    val classJars =
      classJarsView(classesConfig)
        .artifacts
        .artifacts
        .map { artifact ->
          val id = artifact.id.componentIdentifier
          artifact.file to (if (id is ProjectComponentIdentifier) "project" else id.displayName)
        }
    val stub = TelemetryStubCheck(classJars).run()
    violations += stub.problems.map { "telemetry stub: $it" }
    logger.lifecycle(
      "Telemetry stub check: ${classJars.size} class jars scanned, ${stub.referenceCount} " +
        "datatransport/firebase references from ${stub.consumers.ifEmpty { setOf("nothing") }}, " +
        "${stub.problems.size} problems."
    )

    // 5. Licenses from POM metadata.
    val licenses = resolveLicenses(idsByCoord)
    val runtimeCoords = coordsByConfig.filterKeys { it.endsWith("RuntimeClasspath") }.values.flatten().toSortedSet()
    printReport(coordsByConfig, runtimeCoords, licenses)

    val shipped = coordsByConfig[licenseConfig]
      ?: throw GradleException("privacyGuard: configuration $licenseConfig was not checked")
    val asset = licenseAsset
    val expected = renderLicenseAsset(shipped, licenses)
    if (writeLicenseAsset.get()) {
      asset.parentFile.mkdirs()
      asset.writeText(expected)
      logger.lifecycle("Wrote ${shipped.size} libraries to $asset")
    }

    if (violations.isNotEmpty()) {
      throw GradleException(
        "Privacy guard: forbidden dependencies or plugins found. Remove them, or have the owner " +
          "approve an exception in docs/NETWORK.md.\n" + violations.joinToString("\n") { "  - $it" }
      )
    }
    if (!writeLicenseAsset.get() && (!asset.isFile || asset.readText() != expected)) {
      throw GradleException(
        "Privacy guard: the in-app license list is out of date ($asset). " +
          "Run ./gradlew :app:updateThirdPartyLicenses and commit the result."
      )
    }
    val passed = "Privacy guard passed: no forbidden dependencies in ${configs.joinToString { it.name }}."
    logger.lifecycle(passed)
    // CI checks this file, so a run that was skipped or never happened cannot look like a pass.
    report.parentFile.mkdirs()
    report.writeText("PASSED\n$passed\n")
  }

  private fun readExceptions(file: File): List<ApprovedException> {
    if (!file.isFile) return emptyList()
    val text = file.readText()
    val start = text.indexOf("<!-- privacy-guard-exceptions:start -->")
    val end = text.indexOf("<!-- privacy-guard-exceptions:end -->")
    if (start < 0 || end < start) return emptyList()
    return Regex("`([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-*]+)`")
      .findAll(text.substring(start, end))
      .map { ApprovedException(it.groupValues[1], it.groupValues[2]) }
      .toList()
  }

  private fun resolveLicenses(ids: Map<Coord, ModuleComponentIdentifier>): Map<Coord, List<License>> {
    val pomByCoord = mutableMapOf<Coord, File>()
    val result =
      dependencies
        .createArtifactResolutionQuery()
        .forComponents(ids.values)
        .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
        .execute()
    for (component in result.resolvedComponents) {
      val id = component.id as? ModuleComponentIdentifier ?: continue
      val pom =
        component.getArtifacts(MavenPomArtifact::class.java).filterIsInstance<ResolvedArtifactResult>().firstOrNull()
      if (pom != null) pomByCoord[Coord(id.group, id.module, id.version)] = pom.file
    }
    val parentCache = mutableMapOf<Coord, File?>()
    return ids.keys.associateWith { coord ->
      pomByCoord[coord]?.let { licensesFromPom(it, parentCache, depth = 0) }.orEmpty()
    }
  }

  private fun licensesFromPom(pom: File, parentCache: MutableMap<Coord, File?>, depth: Int): List<License> {
    val root = parseXml(pom) ?: return emptyList()
    val licenses =
      root.child("licenses")?.children("license").orEmpty().map { license ->
        val name = license.child("name")?.textContent?.trim().orEmpty()
        val url = license.child("url")?.textContent?.trim().orEmpty()
        License(spdx = normalizeLicense(name, url), name = name, url = url)
      }
    if (licenses.isNotEmpty() || depth >= 5) return licenses
    // Many POMs inherit their license from a parent POM.
    val parent = root.child("parent") ?: return emptyList()
    val coord =
      Coord(
        parent.child("groupId")?.textContent?.trim() ?: return emptyList(),
        parent.child("artifactId")?.textContent?.trim() ?: return emptyList(),
        parent.child("version")?.textContent?.trim() ?: return emptyList(),
      )
    val parentPom =
      parentCache.getOrPut(coord) {
        runCatching {
            configurations
              .detachedConfiguration(dependencies.create("$coord@pom"))
              .apply { isTransitive = false }
              .singleFile
          }
          .getOrNull()
      } ?: return emptyList()
    return licensesFromPom(parentPom, parentCache, depth + 1)
  }

  private fun parseXml(file: File): Element? =
    runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.newDocumentBuilder().parse(file).documentElement
      }
      .getOrNull()

  private fun Element.children(name: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter {
      it.tagName == name
    }

  private fun Element.child(name: String): Element? = children(name).firstOrNull()

  private fun printReport(
    coordsByConfig: Map<String, Set<Coord>>,
    runtimeCoords: Set<Coord>,
    licenses: Map<Coord, List<License>>,
  ) {
    logger.lifecycle("== Privacy guard: dependency and license report ==")
    for ((name, coords) in coordsByConfig) logger.lifecycle("$name: ${coords.size} modules")
    logger.lifecycle("-- Packaged libraries (all runtime classpaths) --")
    for (coord in runtimeCoords) {
      val l = licenses[coord].orEmpty()
      val text = if (l.isEmpty()) "UNKNOWN (no license in POM)" else l.joinToString(" / ") { it.spdx }
      logger.lifecycle("$coord  [$text]")
    }
    logger.lifecycle("-- License summary --")
    runtimeCoords
      .flatMap { coord -> licenses[coord].orEmpty().map { it.spdx }.ifEmpty { listOf("UNKNOWN") }.distinct() }
      .groupingBy { it }
      .eachCount()
      .toSortedMap()
      .forEach { (spdx, count) -> logger.lifecycle("$spdx: $count") }
  }

  private fun renderLicenseAsset(coords: Set<Coord>, licenses: Map<Coord, List<License>>): String =
    buildString {
      append("# Third-party libraries in the PhoneAgent release build.\n")
      append("# Generated from Maven POM metadata by ./gradlew :app:updateThirdPartyLicenses.\n")
      append("# Do not edit by hand. Columns: artifact, license id, license name, license URL.\n")
      for (coord in coords.sorted()) {
        val l = licenses[coord].orEmpty()
        if (l.isEmpty()) {
          append("$coord\tUNKNOWN\tNo license declared in the POM\t\n")
        } else {
          for (license in l.distinct()) {
            append("$coord\t${license.spdx}\t${license.name.tsv()}\t${license.url.tsv()}\n")
          }
        }
      }
    }

  private fun String.tsv() = replace('\t', ' ').replace('\n', ' ')
}

/** Maps the free-form POM license names to SPDX-like ids for the summary. */
internal fun normalizeLicense(name: String, url: String): String {
  val s = "$name $url".lowercase()
  return when {
    "apache" in s -> "Apache-2.0"
    Regex("\\bmit\\b").containsMatchIn(s) -> "MIT"
    "bsd" in s && ("3-clause" in s || "new" in s || "revised" in s || "bsd-3" in s) -> "BSD-3-Clause"
    "bsd" in s && ("2-clause" in s || "simplified" in s || "bsd-2" in s) -> "BSD-2-Clause"
    "bsd" in s -> "BSD"
    "eclipse public license" in s && "2.0" in s -> "EPL-2.0"
    "eclipse public license" in s -> "EPL-1.0"
    "classpath" in s && "gpl" in s -> "GPL-2.0-with-classpath-exception"
    "lgpl" in s || "lesser general public" in s -> "LGPL"
    "mozilla public" in s -> "MPL-2.0"
    "android software development kit" in s -> "Android-SDK-License"
    "unicode" in s || Regex("\\bicu\\b").containsMatchIn(s) -> "Unicode"
    "cc0" in s || "creative commons zero" in s -> "CC0-1.0"
    "public domain" in s -> "Public-Domain"
    else -> name.ifEmpty { "UNKNOWN" }
  }
}

/** The compiled classes (one jar per library or project) on [config], as AGP produces them. */
internal fun classJarsView(config: org.gradle.api.artifacts.Configuration) =
  config.incoming.artifactView {
    attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
  }
