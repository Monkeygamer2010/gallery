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

import com.google.ai.edge.gallery.worker.extractDownloadedZip
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Upstream 1.0.23 unpacks downloaded archives (DownloadZipExtractor.kt). These run its extractor
 * and the fork's ZipPolicy on hostile archives: nothing may be written outside the model's folder,
 * and an archive cannot unpack to more entries or bytes than allowed.
 */
class ZipPolicyTest {
  private val root: File = Files.createTempDirectory("zip-policy").toFile()
  private val modelsDir = File(root, "models").apply { mkdirs() }
  private val modelDir = File(modelsDir, "Some_Model/abc123").apply { mkdirs() }

  @After fun cleanUp() = root.deleteRecursively().let {}

  private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
    val file = File(modelDir, name)
    ZipOutputStream(file.outputStream()).use { out ->
      for ((entryName, bytes) in entries) {
        out.putNextEntry(ZipEntry(entryName))
        out.write(bytes)
        out.closeEntry()
      }
    }
    return file
  }

  private fun unzip(archive: File, fileName: String = archive.name, mainFileName: String? = "model.litertlm", unzippedDir: String? = null) =
    runBlocking {
      extractDownloadedZip(
        originalFile = archive,
        outputDir = modelDir,
        fileName = fileName,
        mainFileName = mainFileName,
        unzippedDir = unzippedDir,
      )
    }

  /** Every file under [root] that is not inside the model's folder. */
  private fun filesOutsideModelDir(): List<String> =
    root.walkTopDown().filter { it.isFile && !it.canonicalPath.startsWith(modelDir.canonicalPath + File.separator) }
      .map { it.relativeTo(root).path }.toList()

  private fun assertRefused(block: () -> Unit) {
    try {
      block()
      fail("the archive was not refused")
    } catch (e: SecurityException) {
      // Expected: DownloadRefusedException, or upstream's own SecurityException.
    }
  }

  @Test
  fun aNormalArchiveUnpacksInsideItsFolder() {
    val archive = zip("data.zip", "data/a.bin" to ByteArray(10), "data/sub/b.bin" to ByteArray(20))
    unzip(archive)
    assertTrue(File(modelDir, "data/a.bin").isFile)
    assertTrue(File(modelDir, "data/sub/b.bin").isFile)
    assertFalse("the archive is deleted after unpacking", archive.exists())
    assertEquals(emptyList<String>(), filesOutsideModelDir())
  }

  @Test
  fun anEntryThatClimbsOutIsRefusedBeforeAnythingIsWritten() {
    for (name in listOf("../outside.txt", "data/../../outside.txt", "../../../escaped.txt")) {
      val archive = zip("evil.zip", "data/first.bin" to ByteArray(5), name to "x".toByteArray())
      assertRefused { unzip(archive) }
      assertEquals(name, emptyList<String>(), filesOutsideModelDir())
      assertFalse("$name: nothing is unpacked from a refused archive", File(modelDir, "evil/data/first.bin").exists())
      assertFalse("$name: a refused archive is deleted", archive.exists())
    }
  }

  @Test
  fun aTargetFolderOutsideTheModelFolderIsRefused() {
    // The main file's unzip folder comes from the model list.
    val archive = zip("model.zip", "weights.bin" to ByteArray(5))
    assertRefused { unzip(archive, fileName = "model.zip", mainFileName = "model.zip", unzippedDir = "../../../elsewhere") }
    assertEquals(emptyList<String>(), filesOutsideModelDir())
    assertFalse(File(root, "elsewhere").exists())
  }

  @Test
  fun tooManyEntriesAreRefused() {
    val archive = zip("many.zip", *Array(5) { "f$it" to ByteArray(1) })
    try {
      ZipPolicy.open(archive, modelDir = modelDir, destDir = File(modelDir, "many"), maxEntries = 4)
      fail("an archive with more entries than allowed was accepted")
    } catch (e: DownloadRefusedException) {
      assertTrue(e.message!!.contains("too many entries"))
    }
    assertFalse(archive.exists())
  }

  @Test
  fun anArchiveThatUnpacksToTooMuchIsRefused() {
    val archive = zip("big.zip", "a" to ByteArray(600), "b" to ByteArray(600))
    try {
      ZipPolicy.open(archive, modelDir = modelDir, destDir = File(modelDir, "big"), maxBytes = 1000)
      fail("an archive larger than allowed was accepted")
    } catch (e: DownloadRefusedException) {
      assertTrue(e.message!!.contains("more than is allowed"))
    }
    assertFalse(archive.exists())
  }

  @Test
  fun unpackingMoreThanTheArchiveDeclaresStopsAndCleansUp() {
    // The guard counts what is really written: a crafted archive whose entries hold more than its
    // directory says is stopped, and what it unpacked is removed.
    val archive = zip("liar.zip", "a.bin" to ByteArray(10))
    val dest = File(modelDir, "liar").apply { mkdirs() }
    val guard = ZipPolicy.open(archive, modelDir = modelDir, destDir = dest)
    guard.entry("a.bin")
    val written = File(dest, "a.bin").apply { writeBytes(ByteArray(10)) }
    guard.wrote(10)
    try {
      guard.wrote(1)
      fail("more bytes than declared were accepted")
    } catch (e: DownloadRefusedException) {
      assertTrue(e.message!!.contains("more than it declares"))
    }
    assertFalse(written.exists())
    assertFalse(archive.exists())
  }

  @Test
  fun moreEntriesThanDeclaredAreStopped() {
    val archive = zip("one.zip", "a.bin" to ByteArray(1))
    val guard = ZipPolicy.open(archive, modelDir = modelDir, destDir = File(modelDir, "one"))
    guard.entry("a.bin")
    try {
      guard.entry("b.bin")
      fail("an entry the archive did not declare was accepted")
    } catch (e: DownloadRefusedException) {
      assertTrue(e.message!!.contains("more entries than it declares"))
    }
  }

  @Test
  fun downloadTargetsStayInsideTheModelsFolder() {
    assertTrue(ZipPolicy.targetsStayInside(modelsDir, modelDir, listOf("model.litertlm", "extra/data.zip")))
    // A file name or folder from the model list that climbs out.
    assertFalse(ZipPolicy.targetsStayInside(modelsDir, modelDir, listOf("../../../outside.bin")))
    assertFalse(ZipPolicy.targetsStayInside(modelsDir, modelDir, listOf("model.litertlm", "../x")))
    assertFalse(ZipPolicy.targetsStayInside(modelsDir, File(modelsDir, "../elsewhere"), listOf("model.litertlm")))
    // The models folder itself, or the model folder itself, is not a file target.
    assertFalse(ZipPolicy.targetsStayInside(modelsDir, modelsDir, listOf("model.litertlm")))
    assertFalse(ZipPolicy.targetsStayInside(modelsDir, modelDir, listOf("")))
    assertFalse(ZipPolicy.targetsStayInside(modelsDir, modelDir, listOf(".")))
  }
}
