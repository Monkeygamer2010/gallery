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
 * - an archive is checked as a whole before anything is written, and while it is unpacked it may
 *   not produce more entries or more bytes than it declared, so a crafted archive cannot fill the
 *   storage;
 * - a refused archive is deleted together with whatever was unpacked from it.
 */
object ZipPolicy {
  /** More entries than any model archive has; a bound on what a crafted archive can create. */
  const val MAX_ENTRIES = 20_000

  /** More than any model archive unpacks to. */
  const val MAX_UNPACKED_BYTES = 16L * 1024 * 1024 * 1024

  /** Whether [target] is [root] or below it, once "..", "." and links are resolved. */
  fun isInside(root: File, target: File): Boolean {
    val rootPath = root.canonicalPath
    val targetPath = target.canonicalPath
    return targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
  }

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
   * [modelDir], when an entry would land outside [destDir], or when the archive declares more than
   * [maxEntries] entries or [maxBytes] bytes.
   */
  fun open(
    archive: File,
    modelDir: File,
    destDir: File,
    maxEntries: Int = MAX_ENTRIES,
    maxBytes: Long = MAX_UNPACKED_BYTES,
  ): ZipGuard {
    fun refuse(reason: String): Nothing {
      archive.delete()
      throw DownloadRefusedException(reason)
    }
    if (!isInside(modelDir, destDir)) refuse("The archive's target folder is outside the model's folder")
    var declaredEntries = 0
    var declaredBytes = 0L
    val readable =
      runCatching {
          ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
              declaredEntries++
              if (declaredEntries > maxEntries) refuse("The archive has too many entries")
              if (!isInside(destDir, File(destDir, entry.name))) {
                refuse("The archive has an entry outside its folder")
              }
              declaredBytes += entry.size.coerceAtLeast(0L)
              if (declaredBytes > maxBytes) refuse("The archive unpacks to more than is allowed")
            }
          }
        }
        .onFailure { if (it is DownloadRefusedException) throw it }
        .isSuccess
    if (!readable) refuse("The archive cannot be read")
    return ZipGuard(archive, destDir, maxEntries = declaredEntries, maxBytes = declaredBytes)
  }
}

/**
 * Watches one archive while it is unpacked: [entry] before each entry is written, [wrote] before
 * each block of bytes. What the archive declared (see [ZipPolicy.open]) is the limit, because the
 * entries are read a second time, from a part of the file that a crafted archive can make differ.
 */
class ZipGuard internal constructor(
  private val archive: File,
  private val destDir: File,
  private val maxEntries: Int,
  private val maxBytes: Long,
) {
  private val unpacked = mutableListOf<File>()
  private var entries = 0
  private var bytes = 0L

  /** Bytes unpacked so far. */
  val unpackedBytes: Long
    get() = bytes

  fun entry(name: String) {
    entries++
    if (entries > maxEntries) refuse("The archive has more entries than it declares")
    val target = File(destDir, name)
    if (!ZipPolicy.isInside(destDir, target)) refuse("The archive has an entry outside its folder")
    if (target.canonicalPath != destDir.canonicalPath) unpacked += target
  }

  fun wrote(count: Int) {
    bytes += count
    if (bytes > maxBytes) refuse("The archive unpacks to more than it declares")
  }

  private fun refuse(reason: String): Nothing {
    // Files first, then the folders they were in (a folder that still holds other files stays).
    for (file in unpacked.asReversed()) if (!file.isDirectory) file.delete()
    for (file in unpacked.asReversed()) if (file.isDirectory) file.delete()
    archive.delete()
    throw DownloadRefusedException(reason)
  }
}
