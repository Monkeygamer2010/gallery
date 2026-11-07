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

package com.google.ai.edge.gallery.phoneagent.screen

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads a spike "Copy tree" back into a tree, for fixtures recorded on the phone. */
object RecordedTree {
  fun read(json: String): UiNode {
    var nextKey = 0
    fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
    fun node(o: JsonObject): UiNode {
      val key = nextKey++
      val b = o.getAsJsonArray("b").map { it.asInt }
      val f = o.str("f")?.split(',')?.toSet().orEmpty()
      val children = o.getAsJsonArray("k")?.map { node(it.asJsonObject) }.orEmpty()
      return UiNode(
        key = key,
        className = o.str("class"),
        text = o.str("text"),
        contentDescription = o.str("desc"),
        hint = o.str("hint"),
        viewId = o.str("id"),
        roleDescription = o.str("role"),
        clickable = "click" in f,
        longClickable = "longclick" in f,
        editable = "edit" in f,
        checkable = "check" in f,
        checked = "checked" in f,
        selected = "selected" in f,
        focused = "focused" in f,
        enabled = "disabled" !in f,
        password = "password" in f,
        heading = "heading" in f,
        visible = "hidden" !in f,
        sensitive = "sensitive" in f,
        scrollable = "scroll" in f,
        canScrollForward = "forward" in f,
        canScrollBackward = "backward" in f,
        bounds = Bounds(b[0], b[1], b[2], b[3]),
        children = children,
      )
    }
    return node(JsonParser.parseString(json).asJsonObject)
  }
}

class TreeJsonTest {
  private val context = ScreenContext(appLabel = "Firefox", url = PageFixtures.WIKI_URL, privateTab = true)

  @Test
  fun aCopiedTreeReadsBackToTheSameObservation() {
    for (tree in listOf(PageFixtures.wikipediaTop(banner = true), PageFixtures.duckDuckGoResults(), GeckoFixtures.wikipediaArticle())) {
      val original = GeckoFixtures.copy(tree).root
      val copy = RecordedTree.read(TreeJson.write(original))
      assertEquals(
        ScreenSerializer.read(original, GeckoFixtures.SCREEN, context).text,
        ScreenSerializer.read(copy, GeckoFixtures.SCREEN, context).text,
      )
    }
  }

  @Test
  fun passwordValuesAreNotCopied() {
    val json = TreeJson.write(GeckoFixtures.copy(GeckoFixtures.wikipediaArticle()).root)
    assertFalse(json.contains("hunter2"))
    assertTrue(json.contains("\"hint\":\"Password\""))
  }
}
