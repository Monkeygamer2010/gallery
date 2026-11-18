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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 21 hostile archives of the 2026-11-18 audit, through upstream's extractor and ZipPolicy, as
 * regression tests. The archives are written byte by byte, so the local headers and the central
 * directory can disagree, names can be anything, and sizes can lie.
 */
class HostileArchivesTest {
  private val root = Files.createTempDirectory("hostile").toFile()
  private val modelDir = File(root, "models/Some_Model/v1").apply { mkdirs() }
  private val mainFile = File(modelDir, "model.litertlm").apply { writeText(MAIN) }

  @After fun cleanUp() = root.deleteRecursively().let {}

  /** One raw entry; its local and central names, sizes and attributes can differ on purpose. */
  private class E(
    val local: String,
    val central: String? = local, // null: not in the central directory
    val data: ByteArray = ByteArray(0),
    val deflate: Boolean = false,
    val declaredSize: Long? = null,
    val unixMode: Int? = null,
  )

  private fun rawZip(entries: List<E>): ByteArray {
    val body = ByteArrayOutputStream()
    val central = ByteArrayOutputStream()
    var count = 0
    fun le(n: Int, v: Long) =
      ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply { if (n == 2) putShort(v.toShort()) else putInt(v.toInt()) }.array()
    for (e in entries) {
      val payload =
        if (e.deflate) {
          val d = Deflater(Deflater.BEST_COMPRESSION, true).apply { setInput(e.data); finish() }
          val buf = ByteArrayOutputStream()
          val tmp = ByteArray(65536)
          while (!d.finished()) buf.write(tmp, 0, d.deflate(tmp))
          buf.toByteArray()
        } else e.data
      val crc = CRC32().apply { update(e.data) }.value
      val size = e.declaredSize ?: e.data.size.toLong()
      val method = if (e.deflate) 8L else 0L
      val offset = body.size().toLong()
      val ln = e.local.toByteArray(Charsets.UTF_8)
      for (v in listOf(4 to 0x04034b50L, 2 to 20L, 2 to 0x0800L, 2 to method, 2 to 0L, 2 to 0x21L, 4 to crc, 4 to payload.size.toLong(), 4 to size, 2 to ln.size.toLong(), 2 to 0L)) {
        body.write(le(v.first, v.second))
      }
      body.write(ln)
      body.write(payload)
      val cn = (e.central ?: continue).toByteArray(Charsets.UTF_8)
      val ext = (e.unixMode?.toLong() ?: 0L) shl 16
      for (v in listOf(4 to 0x02014b50L, 2 to (3L shl 8 or 20), 2 to 20L, 2 to 0x0800L, 2 to method, 2 to 0L, 2 to 0x21L, 4 to crc, 4 to payload.size.toLong(), 4 to size,
          2 to cn.size.toLong(), 2 to 0L, 2 to 0L, 2 to 0L, 2 to 0L, 4 to ext, 4 to offset)) {
        central.write(le(v.first, v.second))
      }
      central.write(cn)
      count++
    }
    val cdOffset = body.size().toLong()
    val cdSize = central.size().toLong()
    body.write(central.toByteArray())
    for (v in listOf(4 to 0x06054b50L, 2 to 0L, 2 to 0L, 2 to count.toLong(), 2 to count.toLong(), 4 to cdSize, 4 to cdOffset, 2 to 0L)) body.write(le(v.first, v.second))
    return body.toByteArray()
  }

  private class Outcome(val refused: Boolean, val reason: String?, val archiveLeft: Boolean, val outside: List<String>, val inside: List<String>, val main: String)

  /** Unpacks [entries] as [zipName] the way DownloadWorker does and reports what is on disk afterwards. */
  private fun unpack(zipName: String, entries: List<E>, mainFileName: String = "model.litertlm", unzippedDir: String? = null): Outcome {
    val archive = File(modelDir, zipName).apply { writeBytes(rawZip(entries)) }
    var reason: String? = null
    val refused =
      try {
        runBlocking {
          extractDownloadedZip(archive, modelDir, zipName, mainFileName, unzippedDir, protectedFileNames = listOf("model.litertlm", zipName))
        }
        false
      } catch (e: Exception) {
        reason = "${e.javaClass.simpleName}: ${e.message}"
        true
      }
    val keep = setOf(root, File(root, "models"), File(root, "models/Some_Model")).map { it.canonicalPath }
    val outside = root.walkTopDown().filter { !ZipPolicy.isInside(modelDir, it) && it.canonicalPath !in keep }.map { it.relativeTo(root).path }.toList()
    val inside =
      modelDir.walkTopDown().filter { it != modelDir }.map {
        (if (Files.isSymbolicLink(it.toPath())) "link " else if (it.isDirectory) "dir " else "") + it.relativeTo(modelDir).invariantSeparatorsPath
      }.sorted().toList()
    return Outcome(refused, reason, archive.exists(), outside, inside, if (mainFile.isFile) mainFile.readText() else "GONE")
  }

  /** A refused archive: deleted, nothing left of it, nothing written anywhere else, the model file intact. */
  private fun assertRefusedAndClean(o: Outcome) {
    assertTrue("expected a refusal", o.refused)
    assertFalse("archive deleted (refused with: ${o.reason})", o.archiveLeft)
    assertEquals(emptyList<String>(), o.outside)
    assertEquals(listOf("model.litertlm"), o.inside)
    assertEquals(MAIN, o.main)
  }

  /** An accepted archive: unpacked only inside the model folder, the model file intact. */
  private fun assertContained(o: Outcome, vararg expectedInside: String) {
    assertFalse("expected no refusal, got ${o.reason}", o.refused)
    assertEquals(emptyList<String>(), o.outside)
    assertEquals(expectedInside.toList().sorted(), o.inside)
    assertEquals(MAIN, o.main)
  }

  private val x = "x".toByteArray()

  @Test fun case01DotDotAfterAGoodEntry() = assertRefusedAndClean(unpack("evil.zip", listOf(E("ok.bin", data = x), E("../outside.txt", data = x))))

  @Test fun case02NestedDotDot() = assertRefusedAndClean(unpack("evil.zip", listOf(E("data/../../../outside2.txt", data = x))))

  @Test fun case03AbsolutePathStaysInside() =
    assertContained(unpack("evil.zip", listOf(E("/tmp/abs_evil.txt", data = x))), "dir evil", "dir evil/tmp", "evil.zip.unpacked", "evil/tmp/abs_evil.txt", "model.litertlm")

  @Test fun case04BackslashesAreAPlainName() =
    assertContained(unpack("evil.zip", listOf(E("..\\..\\..\\bs_evil.txt", data = x))), "dir evil", "evil.zip.unpacked", "evil/..\\..\\..\\bs_evil.txt", "model.litertlm")

  @Test fun case05DriveLettersArePlainNames() =
    assertContained(
      unpack("evil.zip", listOf(E("C:\\drive_evil.txt", data = x), E("C:/drive2.txt", data = x))),
      "dir evil", "dir evil/C:", "evil.zip.unpacked", "evil/C:/drive2.txt", "evil/C:\\drive_evil.txt", "model.litertlm",
    )

  @Test fun case06SymlinkEntryIsWrittenAsAPlainFile() {
    val o = unpack("evil.zip", listOf(E("link", data = "/tmp".toByteArray(), unixMode = 0xA1FF), E("link2/x.txt", data = x)))
    assertContained(o, "dir evil", "dir evil/link2", "evil.zip.unpacked", "evil/link", "evil/link2/x.txt", "model.litertlm")
  }

  @Test fun case07DuplicateNamesAreRefused() =
    assertRefusedAndClean(unpack("evil.zip", listOf(E("a.bin", data = "first".toByteArray()), E("a.bin", data = "second".toByteArray()))))

  @Test fun case08NamesThatDifferOnlyInCaseAreRefused() =
    assertRefusedAndClean(unpack("evil.zip", listOf(E("Model.bin", data = x), E("model.bin", data = x))))

  @Test fun case09UnicodeLookalikesStayInside() {
    val o = unpack("evil.zip", listOf(E("\u2024\u2024/\u2024\u2024/u1.txt", data = x), E("\uFF0E\uFF0E/u2.txt", data = x), E("\u202Etxt.exe", data = x)))
    assertFalse(o.refused)
    assertEquals(emptyList<String>(), o.outside)
    assertEquals(MAIN, o.main)
  }

  @Test fun case10NulInANameIsRefused() = assertRefusedAndClean(unpack("evil.zip", listOf(E("ok\u0000/../../../nul.txt", data = x))))

  /** Audit case 11: an extra-data archive in prefix mode replaced the main model file. */
  @Test fun case11AnArchiveCannotReplaceTheModelFile() {
    val o = unpack("data.zip", listOf(E("data/readme.txt", data = x), E("model.litertlm", data = "OVERWRITTEN".toByteArray())))
    assertRefusedAndClean(o)
    assertTrue(o.reason, o.reason!!.contains("would replace a file of the model"))
  }

  @Test fun case11bNorItsMarkerOrPartialFileOrAnotherCase() {
    for (name in listOf("MODEL.LITERTLM", "model.litertlm.gallerytmp", "data.zip.unpacked", "data.zip", "model.litertlm/inner")) {
      val o = unpack("data.zip", listOf(E("data/readme.txt", data = x), E(name, data = x)))
      assertRefusedAndClean(o)
    }
  }

  @Test fun case12CentralSaysSafeLocalSaysDotDot() = assertRefusedAndClean(unpack("evil.zip", listOf(E("../mismatch.txt", central = "safe.txt", data = x))))

  @Test fun case13LocalOnlyEntriesAreRefused() =
    assertRefusedAndClean(unpack("evil.zip", listOf(E("one.bin", data = x), E("hidden1.bin", central = null, data = x), E("hidden2.bin", central = null, data = x))))

  @Test fun case14ALyingSizeIsRefused() =
    assertRefusedAndClean(unpack("evil.zip", listOf(E("bomb.bin", data = ByteArray(8 shl 20), deflate = true, declaredSize = 1000))))

  @Test fun case15ADeclaredTotalOver16GiBIsRefused() =
    assertRefusedAndClean(unpack("evil.zip", List(5) { E("big$it.bin", data = x, declaredSize = 0xFFFFFFF0L) }))

  @Test fun case16TooManyEntriesAreRefused() = assertRefusedAndClean(unpack("evil.zip", List(20001) { E("f$it") }))

  /** Audit case 17: a 1000:1 entry was unpacked; now no entry above 1 MiB may exceed 100:1. */
  @Test fun case17AHighlyCompressedEntryIsRefusedBeforeAnythingIsWritten() {
    val o = unpack("evil.zip", listOf(E("zeros.bin", data = ByteArray(64 shl 20), deflate = true)))
    assertRefusedAndClean(o)
    assertTrue(o.reason, o.reason!!.contains("compressed more than 100 to 1"))
    // Photos, model weights and text stay far below the cap; small entries aren't held to it.
    assertContained(unpack("ok.zip", listOf(E("small.txt", data = ByteArray(512 shl 10), deflate = true))), "dir ok", "ok.zip.unpacked", "ok/small.txt", "model.litertlm")
  }

  @Test fun case18ANestedArchiveIsNotUnpacked() =
    assertContained(unpack("evil.zip", listOf(E("inner.zip", data = rawZip(listOf(E("../inner_escape.txt", data = x)))))), "dir evil", "evil.zip.unpacked", "evil/inner.zip", "model.litertlm")

  @Test fun case19AnUnzipFolderThatClimbsOutIsRefused() =
    assertRefusedAndClean(unpack("model.zip", listOf(E("w.bin", data = x)), mainFileName = "model.zip", unzippedDir = "../../../escaped"))

  @Test fun case20AnAbsoluteUnzipFolderStaysInside() =
    assertContained(unpack("model.zip", listOf(E("w.bin", data = x)), mainFileName = "model.zip", unzippedDir = "/tmp/absdir"), "dir tmp", "dir tmp/absdir", "model.litertlm", "tmp/absdir/w.bin")

  /** Audit case 21: a refusal part-way left the folders it had created. */
  @Test fun case21ARefusalPartWayRemovesTheFoldersItCreated() {
    val o = unpack("evil.zip", listOf(E("deep/a/b/c/ok.bin", data = x), E("hidden.bin", central = null, data = x)))
    assertRefusedAndClean(o)
  }

  @Test fun downloadTargetsStayInsideTheModelsFolder() {
    val models = File(root, "models")
    for ((name, inside) in listOf("../x.zip" to false, "a/../../x.zip" to false, "." to false, "sub/x.zip" to true, "/abs.zip" to true, "..\\x.zip" to true, "C:/x.zip" to true)) {
      assertEquals(name, inside, ZipPolicy.targetsStayInside(models, modelDir, listOf(name)))
    }
  }

  private companion object {
    const val MAIN = "MAIN MODEL FILE"
  }
}
