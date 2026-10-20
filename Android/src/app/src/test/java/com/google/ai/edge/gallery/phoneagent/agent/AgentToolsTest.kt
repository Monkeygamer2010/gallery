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

package com.google.ai.edge.gallery.phoneagent.agent

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsTest {
  private fun ok(name: String, args: Map<String, Any?> = emptyMap()): AgentAction {
    val parsed = AgentTools.parse(name, args)
    assertTrue("expected Ok, got $parsed", parsed is ParsedCall.Ok)
    return (parsed as ParsedCall.Ok).action
  }

  private fun invalid(name: String?, args: Map<String, Any?> = emptyMap()): String {
    val parsed = AgentTools.parse(name, args)
    assertTrue("expected Invalid, got $parsed", parsed is ParsedCall.Invalid)
    return (parsed as ParsedCall.Invalid).reason
  }

  @Test
  fun declaresAtMostTwelveToolsWithValidJson() {
    assertTrue(AgentTools.SPECS.size <= 12)
    assertEquals(
      setOf("read_screen", "tap", "type_text", "scroll", "back", "home", "open_app", "open_url", "note", "save_result", "finish"),
      AgentTools.NAMES,
    )
    for (spec in AgentTools.SPECS) {
      val json = JsonParser.parseString(spec.toJson()).asJsonObject
      assertEquals(spec.name, json.get("name").asString)
      assertEquals("object", json.getAsJsonObject("parameters").get("type").asString)
    }
    val typeText = JsonParser.parseString(AgentTools.SPECS.first { it.name == "type_text" }.toJson()).asJsonObject
    val required = typeText.getAsJsonObject("parameters").getAsJsonArray("required").map { it.asString }
    assertEquals(listOf("id", "text"), required)
  }

  @Test
  fun parsesElementIdsInTheFormsModelsUse() {
    assertEquals(AgentAction.Tap(3), ok("tap", mapOf("id" to 3)))
    assertEquals(AgentAction.Tap(3), ok("tap", mapOf("id" to 3L)))
    assertEquals(AgentAction.Tap(3), ok("tap", mapOf("id" to 3.0)))
    assertEquals(AgentAction.Tap(3), ok("tap", mapOf("id" to "3")))
    assertEquals(AgentAction.Tap(3), ok("tap", mapOf("id" to "[3]")))
    assertEquals(AgentAction.Tap(3), ok("TAP", mapOf("ID" to 3)))
    invalid("tap", mapOf("id" to 0))
    invalid("tap", mapOf("id" to -2))
    invalid("tap", mapOf("id" to 2.5))
    invalid("tap", mapOf("id" to "three"))
    invalid("tap")
  }

  @Test
  fun parsesEveryTool() {
    assertEquals(AgentAction.ReadScreen, ok("read_screen"))
    assertEquals(AgentAction.TypeText(2, "munich weather", submit = true), ok("type_text", mapOf("id" to 2, "text" to "munich weather", "submit" to true)))
    assertEquals(AgentAction.TypeText(2, "x", submit = false), ok("type_text", mapOf("id" to 2, "text" to "x")))
    assertEquals(AgentAction.TypeText(2, "x", submit = true), ok("type_text", mapOf("id" to 2, "text" to "x", "submit" to "true")))
    assertEquals(AgentAction.Scroll(ScrollDirection.DOWN), ok("scroll", mapOf("direction" to "down")))
    assertEquals(AgentAction.Scroll(ScrollDirection.UP), ok("scroll", mapOf("direction" to "UP")))
    assertEquals(AgentAction.Scroll(ScrollDirection.DOWN), ok("scroll"))
    assertEquals(AgentAction.Back, ok("back"))
    assertEquals(AgentAction.Home, ok("home"))
    assertEquals(AgentAction.OpenApp("Firefox"), ok("open_app", mapOf("name" to " Firefox ")))
    assertEquals(AgentAction.OpenUrl("https://example.org"), ok("open_url", mapOf("url" to "https://example.org")))
    assertEquals(AgentAction.Note("Population 1.5 million"), ok("note", mapOf("text" to "Population 1.5 million")))
    assertEquals(AgentAction.SaveResult("Result", "a, b"), ok("save_result", mapOf("text" to "a, b")))
    assertEquals(AgentAction.Finish("It is 12 °C."), ok("finish", mapOf("summary" to "It is 12 °C.")))
  }

  @Test
  fun rejectsUnknownToolsAndMissingArguments() {
    assertTrue(invalid("set_policy", mapOf("allow" to "everything")).contains("Unknown tool"))
    invalid(null)
    invalid("  ")
    invalid("scroll", mapOf("direction" to "sideways"))
    invalid("type_text", mapOf("id" to 1))
    invalid("type_text", mapOf("id" to 1, "text" to "x".repeat(AgentTools.MAX_TYPE_CHARS + 1)))
    invalid("open_url", mapOf("url" to " "))
    invalid("finish", mapOf("summary" to ""))
    invalid("open_app", mapOf("name" to "x".repeat(AgentTools.MAX_APP_NAME_CHARS + 1)))
  }

  @Test
  fun capsLongNotesResultsAndSummaries() {
    val note = ok("note", mapOf("text" to "n".repeat(1000))) as AgentAction.Note
    assertEquals(AgentTools.MAX_NOTE_CHARS, note.text.length)
    val finish = ok("finish", mapOf("summary" to "s".repeat(5000))) as AgentAction.Finish
    assertEquals(AgentTools.MAX_SUMMARY_CHARS, finish.summary.length)
  }

  @Test
  fun fallsBackToAJsonToolCallInText() {
    val parsed = AgentTools.parseFromText("Sure. {\"name\": \"tap\", \"arguments\": {\"id\": 4}} done")
    assertEquals(ParsedCall.Ok(AgentAction.Tap(4)), parsed)
    val nested = AgentTools.parseFromText("{\"name\":\"type_text\",\"parameters\":{\"id\":\"2\",\"text\":\"a {b}\",\"submit\":true}}")
    assertEquals(ParsedCall.Ok(AgentAction.TypeText(2, "a {b}", submit = true)), nested)
    assertNull(AgentTools.parseFromText("I will tap the first result."))
    assertNull(AgentTools.parseFromText("{not json"))
  }

  @Test
  fun describesActionsOnOneLine() {
    assertEquals("type_text [2] \"two lines\" + Enter", AgentAction.TypeText(2, "two\nlines", submit = true).describe())
    assertEquals("open_url https://example.org", AgentAction.OpenUrl("https://example.org").describe())
    assertTrue(AgentAction.OpenUrl("https://example.org/" + "a".repeat(300)).describe().length < 140)
  }
}
