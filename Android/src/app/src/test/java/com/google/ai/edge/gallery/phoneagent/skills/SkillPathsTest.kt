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

package com.google.ai.edge.gallery.phoneagent.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPathsTest {
  @Test
  fun scriptNamesStayInsideTheSkill() {
    for (ok in listOf("index.html", "query.html", "lib/chart.min.js", "a_b-c.v2.html")) {
      assertTrue(ok, SkillPaths.isSafeRelativePath(ok))
    }
    for (bad in
      listOf(
        "",
        "..",
        "../index.html",
        "../../other-skill/scripts/index.html",
        "a/../../b",
        "./index.html",
        ".hidden",
        "/etc/hosts",
        "a//b",
        "a\\b",
        "%2e%2e/x",
        "https://evil.example/x.html",
        "index.html?x=1",
        "index.html#top",
        "a b.html",
        "x".repeat(300),
      )) {
      assertFalse(bad, SkillPaths.isSafeRelativePath(bad))
    }
  }

  @Test
  fun webviewPagesMayCarryAQueryButNotLeaveTheSkill() {
    assertTrue(SkillPaths.isSafeRelativeUrl("dashboard.html?date=2026-01-01&days=7"))
    assertTrue(SkillPaths.isSafeRelativeUrl("webview.html?label=Hello%20world#x"))
    assertFalse(SkillPaths.isSafeRelativeUrl("../shared_prefs/x.xml"))
    assertFalse(SkillPaths.isSafeRelativeUrl("//evil.example/x"))
    assertFalse(SkillPaths.isSafeRelativeUrl("javascript:alert(1)"))
    assertFalse(SkillPaths.isSafeRelativeUrl("page.html?x=1\nHost: evil"))
    assertFalse(SkillPaths.isSafeRelativeUrl("?only=query"))
  }

  @Test
  fun dirAndFileNamesFromDocumentProviders() {
    assertEquals("my-skill", SkillPaths.safeDirName("my-skill"))
    assertEquals("skill", SkillPaths.safeDirName(".."))
    assertEquals("skill", SkillPaths.safeDirName("/"))
    assertEquals("etc-passwd", SkillPaths.safeDirName("../etc/passwd"))
    assertFalse(SkillPaths.safeDirName("a/../b").contains('/'))

    assertTrue(SkillPaths.isSafeFileName("My Image (1).png"))
    for (bad in listOf(null, "", ".", "..", "a/b", "..\\x", "a\u0000b")) {
      assertFalse("$bad", SkillPaths.isSafeFileName(bad))
    }
  }

  @Test
  fun networkKeyDependsOnWhereTheCodeComesFrom() {
    val builtIn = SkillPaths.networkKey("query-wikipedia", true, "assets/skills/query-wikipedia", "")
    val imported = SkillPaths.networkKey("query-wikipedia", false, "skills/query-wikipedia", "")
    val remote = SkillPaths.networkKey("query-wikipedia", false, "", "https://example.com/s")
    assertNotEquals(builtIn, imported)
    assertNotEquals(imported, remote)
    assertNotEquals(builtIn, remote)
    assertEquals(imported, SkillPaths.networkKey("query-wikipedia", false, "skills/query-wikipedia", ""))
  }
}
