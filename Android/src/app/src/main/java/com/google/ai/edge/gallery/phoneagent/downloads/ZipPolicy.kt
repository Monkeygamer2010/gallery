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

package com.google.ai.edge.gallery.phoneagent.downloads

import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** A downloaded archive or a download target was refused by [ZipPolicy]. */
class DownloadRefusedException(reason: String) : SecurityException(reason)

/**
 * Where a download may write, and how much an archive may unpack (docs/SECURITY.md).
 *
 * A model's file names, folder names and archives come from the model list or from a repository the
 * owner imports, so they are untrusted data:
 * - every file of a model is written inside the models folder;
 * - an archive is unpacked inside its model's folder, every entry inside the folder it is unpacked
 *   to;
 * - no entry may replace a file of the model: the model's own files (main file, extra files, their
 *   partial files and markers), the archive itself, or a file already in the model's folder; nor may
 *   two entries name the same file. Names are compared ignoring case, as on the phone's shared
 *   storage, where the models folder lives;
 * - an archive is checked as a whole before anything is written: at most [MAX_ENTRIES] entries,
 *   [MAX_UNPACKED_BYTES] in all, and no entry above [RATIO_CHECK_MIN_BYTES] compressed more than
 *   [MAX_COMPRESSION_RATIO] to 1 (model files, photos and other media compress far less);
 * - while it is unpacked, no entry may be missing from the archive's directory, unpack to more
 *   than that directory declares for it, or push the total past what the archive declared;
 * - a refused archive is deleted together with the files and the folders unpacked from it.
 */
object ZipPolicy {
  /** More entries than any model archive has; a bound on what a crafted archive can create. */
  const val MAX_ENTRIES = 20_000

  /** More than any model archive unpacks to. */
  const val MAX_UNPACKED_BYTES = 16L * 1024 * 1024 * 1024

  /** The most an entry may be compressed (unpacked size over compressed size). */
  const val MAX_COMPRESSION_RATIO = 100L

  /** Entries smaller than this unpack to little whatever their ratio, so they aren't held to it. */
  const val RATIO_CHECK_MIN_BYTES = 1L * 1024 * 1024

  /** Whether [target] is [root] or below it, once "..", "." and links are resolved. */
  fun isInside(root: File, target: File): Boolean {
    val rootPath = root.canonicalPath
    val targetPath = target.canonicalPath
    return targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
  }

  /** A file's resolved path, ignoring case, for comparing names as case-folding storage does. */
  internal fun key(file: File): String = file.canonicalPath.lowercase(Locale.ROOT)

  /** Whether [key] is one of [protectedKeys] or lies below one of them. */
  internal fun isProtected(key: String, protectedKeys: Set<String>): Boolean =
    key in protectedKeys || protectedKeys.any { key.startsWith(it + File.separator) }

  /**
   * Whether a model's folder [modelDir] is below [modelsDir] (and is not the models folder itself)
   * and each of [fileNames] names a file inside [modelDir].
   */
  fun targetsStayInside(modelsDir: File, modelDir: File, fileNames: List<String>): Boolean {
    if (!isInside(modelsDir, modelDir) || modelDir.canonicalPath == modelsDir.canonicalPath) return false
    return fileNames.all { name ->
      val file = File(modelDir, name)
      isInside(modelDir, file) && file.canonicalPath != modelDir.canonicalPath
    }
  }

  /**
   * Checks [archive] before it is unpacked to [destDir] and returns the guard for unpacking it.
   *
   * Throws [DownloadRefusedException], after deleting [archive], when [destDir] is not inside
   * [modelDir], when an entry would land outside [destDir] or on one of [protectedFiles] (or a file
   * of an earlier entry), or when the archive declares more than [maxEntries] entries, more than
   * [maxBytes] bytes, or an entry compressed more than [maxRatio] to 1.
   */
  fun open(
    archive: File,
    modelDir: File,
    destDir: File,
    protectedFiles: Collection<File> = emptyList(),
    maxEntries: Int = MAX_ENTRIES,
    maxBytes: Long = MAX_UNPACKED_BYTES,
    maxRatio: Long = MAX_COMPRESSION_RATIO,
  ): ZipGuard {
    fun refuse(reason: String): Nothing {
      archive.delete()
      throw DownloadRefusedException(reason)
    }
    if (!isInside(modelDir, destDir)) refuse("The archive's target folder is outside the model's folder")
    val protectedKeys = (protectedFiles + archive).map { key(it) }.toSet()
    var declaredEntries = 0
    var declaredBytes = 0L
    val declaredSizes = mutableMapOf<String, Long>()
    val targets = mutableSetOf<String>()
    val readable =
      runCatching {
          ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
              declaredEntries++
              if (declaredEntries > maxEntries) refuse("The archive has too many entries")
              val target = File(destDir, entry.name)
              if (!isInside(destDir, target)) refuse("The archive has an entry outside its folder")
              val targetKey = key(target)
              if (isProtected(targetKey, protectedKeys)) refuse("The archive would replace a file of the model")
              if (!entry.isDirectory && !targets.add(targetKey)) refuse("The archive has two entries for the same file")
              if (entry.size < 0) refuse("The archive does not declare the size of an entry")
              if (
                !entry.isDirectory &&
                  entry.size > RATIO_CHECK_MIN_BYTES &&
                  (entry.compressedSize <= 0 || entry.size / entry.compressedSize > maxRatio)
              ) {
                refuse("An entry of the archive is compressed more than $maxRatio to 1")
              }
              declaredSizes[entry.name] = entry.size
              declaredBytes += entry.size
              if (declaredBytes > maxBytes) refuse("The archive unpacks to more than is allowed")
            }
          }
        }
        .onFailure { if (it is DownloadRefusedException) throw it }
        .isSuccess
    if (!readable) refuse("The archive cannot be read")
    // destDir and the folders above it that unpacking will create, so a refusal removes them.
    val createdDirs = generateSequence(destDir.canonicalFile) { it.parentFile }.takeWhile { !it.exists() && isInside(modelDir, it) }.toList()
    return ZipGuard(archive, destDir, declaredEntries, declaredBytes, declaredSizes, protectedKeys, createdDirs)
  }
}

/**
 * Watches one archive while it is unpacked: [entry] before each entry is written, [wrote] before
 * each block of bytes. What the archive's directory declared (see [ZipPolicy.open]) is the limit,
 * because the entries are read a second time, from a part of the file that a crafted archive can
 * make differ.
 */
class ZipGuard internal constructor(
  private val archive: File,
  private val destDir: File,
  private val maxEntries: Int,
  private val maxBytes: Long,
  private val declaredSizes: Map<String, Long>,
  private val protectedKeys: Set<String>,
  createdDirs: List<File>,
) {
  private val unpacked = mutableListOf<File>()
  private val created = createdDirs.toMutableList()
  private val seen = mutableSetOf<String>()
  private var entries = 0
  private var bytes = 0L
  private var entryBytes = 0L
  private var entryLimit = 0L

  /** Bytes unpacked so far. */
  val unpackedBytes: Long
    get() = bytes

  fun entry(name: String) {
    entries++
    if (entries > maxEntries) refuse("The archive has more entries than it declares")
    val target = File(destDir, name)
    if (!ZipPolicy.isInside(destDir, target)) refuse("The archive has an entry outside its folder")
    entryLimit = declaredSizes[name] ?: refuse("The archive has an entry that its directory does not list")
    entryBytes = 0L
    val targetKey = ZipPolicy.key(target)
    if (ZipPolicy.isProtected(targetKey, protectedKeys)) refuse("The archive would replace a file of the model")
    val isDir = name.endsWith("/")
    if (!isDir && !seen.add(targetKey)) refuse("The archive has two entries for the same file")
    // The folders this entry creates, so that a refusal removes them as well.
    var dir: File? = (if (isDir) target else target.parentFile)?.canonicalFile
    while (dir != null && ZipPolicy.isInside(destDir, dir) && !dir.exists()) {
      if (dir !in created) created += dir
      dir = dir.parentFile
    }
    if (target.canonicalPath != destDir.canonicalPath) unpacked += target
  }

  fun wrote(count: Int) {
    bytes += count
    entryBytes += count
    if (bytes > maxBytes) refuse("The archive unpacks to more than it declares")
    if (entryBytes > entryLimit) refuse("An entry of the archive unpacks to more than it declares")
  }

  private fun refuse(reason: String): Nothing {
    // Files first, then every folder the archive created, deepest first. A folder that still holds
    // other files stays (File.delete leaves a folder that isn't empty).
    for (file in unpacked.asReversed()) if (!file.isDirectory) file.delete()
    (unpacked.filter { it.isDirectory }.map { it.canonicalFile } + created).distinct().sortedByDescending { it.path.length }.forEach { it.delete() }
    archive.delete()
    throw DownloadRefusedException(reason)
  }
}
