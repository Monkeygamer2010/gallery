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

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class ScrollDirection {
  UP,
  DOWN,
}

/**
 * One tool call the model asked for, after parsing. Nothing here has touched the phone yet: the
 * runner checks every action with [ToolPolicy] first.
 */
sealed interface AgentAction {
  val tool: String

  /** One line for the action history the model sees and the run log the owner sees. */
  fun describe(): String

  data object ReadScreen : AgentAction {
    override val tool = "read_screen"

    override fun describe() = "read_screen"
  }

  data class Tap(val id: Int) : AgentAction {
    override val tool = "tap"

    override fun describe() = "tap [$id]"
  }

  data class TypeText(val id: Int, val text: String, val submit: Boolean) : AgentAction {
    override val tool = "type_text"

    override fun describe() =
      "type_text [$id] \"${oneLine(text, 60)}\"" + if (submit) " + Enter" else ""
  }

  data class Scroll(val direction: ScrollDirection) : AgentAction {
    override val tool = "scroll"

    override fun describe() = "scroll ${direction.name.lowercase()}"
  }

  data object Back : AgentAction {
    override val tool = "back"

    override fun describe() = "back"
  }

  data object Home : AgentAction {
    override val tool = "home"

    override fun describe() = "home"
  }

  data class OpenApp(val name: String) : AgentAction {
    override val tool = "open_app"

    override fun describe() = "open_app \"${oneLine(name, 40)}\""
  }

  data class OpenUrl(val url: String) : AgentAction {
    override val tool = "open_url"

    override fun describe() = "open_url ${oneLine(url, 120)}"
  }

  data class Note(val text: String) : AgentAction {
    override val tool = "note"

    override fun describe() = "note \"${oneLine(text, 60)}\""
  }

  data class SaveResult(val title: String, val text: String) : AgentAction {
    override val tool = "save_result"

    override fun describe() = "save_result \"${oneLine(title, 40)}\""
  }

  data class Finish(val summary: String) : AgentAction {
    override val tool = "finish"

    override fun describe() = "finish"
  }
}

/** The result of turning a raw tool call into an [AgentAction]. */
sealed interface ParsedCall {
  data class Ok(val action: AgentAction) : ParsedCall

  /** [reason] is shown to the model in the next step, so it says how to call the tool. */
  data class Invalid(val reason: String) : ParsedCall
}

/** A tool parameter, as declared to the model. */
data class ToolParamSpec(
  val name: String,
  val type: String,
  val description: String,
  val required: Boolean = true,
  val enum: List<String>? = null,
)

/** A tool, as declared to the model. The model never gets anything else it could call. */
data class ToolSpec(val name: String, val description: String, val params: List<ToolParamSpec> = emptyList()) {
  /** The OpenAPI-style description LiteRT-LM puts into the prompt. */
  fun toJson(): String {
    val properties = JsonObject()
    val required = JsonArray()
    for (param in params) {
      properties.add(
        param.name,
        JsonObject().apply {
          addProperty("type", param.type)
          addProperty("description", param.description)
          param.enum?.let { values -> add("enum", JsonArray().apply { values.forEach { add(it) } }) }
        },
      )
      if (param.required) required.add(param.name)
    }
    val parameters =
      JsonObject().apply {
        addProperty("type", "object")
        add("properties", properties)
        if (required.size() > 0) add("required", required)
      }
    return JsonObject()
      .apply {
        addProperty("name", name)
        addProperty("description", description)
        add("parameters", parameters)
      }
      .toString()
  }
}

/**
 * The agent's fixed tool set (docs/DECISIONS.md, Architecture). None of these tools can change
 * policy, settings or this app; [ToolPolicy] decides what each call may do.
 */
object AgentTools {
  const val MAX_TYPE_CHARS = 500
  const val MAX_NOTE_CHARS = 300
  const val MAX_TITLE_CHARS = 100
  const val MAX_RESULT_CHARS = 4000
  const val MAX_SUMMARY_CHARS = 2000
  const val MAX_APP_NAME_CHARS = 60

  val SPECS: List<ToolSpec> =
    listOf(
      ToolSpec(
        "read_screen",
        "Wait a moment and read the screen again, for example while a page is still loading.",
      ),
      ToolSpec(
        "tap",
        "Tap an element of the current screen.",
        listOf(ToolParamSpec("id", "integer", "The element's number from the current screen, e.g. 3 for [3].")),
      ),
      ToolSpec(
        "type_text",
        "Replace the text in an input field of the current screen.",
        listOf(
          ToolParamSpec("id", "integer", "The input field's number from the current screen."),
          ToolParamSpec("text", "string", "The text to type."),
          ToolParamSpec(
            "submit",
            "boolean",
            "true to press Enter after typing, e.g. to run a search. Default false.",
            required = false,
          ),
        ),
      ),
      ToolSpec(
        "scroll",
        "Scroll the current screen to see more.",
        listOf(ToolParamSpec("direction", "string", "down or up.", enum = listOf("down", "up"))),
      ),
      ToolSpec("back", "Press the Back button."),
      ToolSpec("home", "Go to the home screen."),
      ToolSpec(
        "open_app",
        "Open an app by name. Only Firefox is available; it opens a new private tab.",
        listOf(ToolParamSpec("name", "string", "The app's name, e.g. Firefox.")),
      ),
      ToolSpec(
        "open_url",
        "Open a web address in a new Firefox private tab. https:// addresses only.",
        listOf(ToolParamSpec("url", "string", "The full https:// address.")),
      ),
      ToolSpec(
        "note",
        "Remember a fact for later steps. Notes are all you remember between steps.",
        listOf(ToolParamSpec("text", "string", "A short fact, e.g. a number or a name you found.")),
      ),
      ToolSpec(
        "save_result",
        "Keep a piece of the result for the owner, e.g. a table or a list you collected.",
        listOf(
          ToolParamSpec("title", "string", "A short title."),
          ToolParamSpec("text", "string", "The result text."),
        ),
      ),
      ToolSpec(
        "finish",
        "End the task. Call it when you have the answer, or when the task cannot be done.",
        listOf(ToolParamSpec("summary", "string", "The answer for the owner, or why the task could not be done.")),
      ),
    )

  val NAMES: Set<String> = SPECS.map { it.name }.toSet()

  /** Parses a tool call from the model. Unknown tools and bad arguments become [ParsedCall.Invalid]. */
  fun parse(name: String?, arguments: Map<String, Any?>): ParsedCall {
    val tool = name?.trim()?.lowercase()?.replace('-', '_')
    if (tool.isNullOrEmpty()) return ParsedCall.Invalid("The tool call had no name.")
    val args = arguments.mapKeys { it.key.trim().lowercase() }
    fun invalid(reason: String) = ParsedCall.Invalid("$tool: $reason")
    return when (tool) {
      "read_screen" -> ParsedCall.Ok(AgentAction.ReadScreen)
      "tap" -> elementId(args["id"])?.let { ParsedCall.Ok(AgentAction.Tap(it)) } ?: invalid(ID_HELP)
      "type_text" -> {
        val id = elementId(args["id"]) ?: return invalid(ID_HELP)
        val text = text(args["text"]) ?: return invalid("text is missing.")
        if (text.length > MAX_TYPE_CHARS) return invalid("text is longer than $MAX_TYPE_CHARS characters.")
        ParsedCall.Ok(AgentAction.TypeText(id, text, flag(args["submit"])))
      }
      "scroll" ->
        when (text(args["direction"])?.trim()?.lowercase()) {
          "down", "forward", null -> ParsedCall.Ok(AgentAction.Scroll(ScrollDirection.DOWN))
          "up", "backward" -> ParsedCall.Ok(AgentAction.Scroll(ScrollDirection.UP))
          else -> invalid("direction must be down or up.")
        }
      "back" -> ParsedCall.Ok(AgentAction.Back)
      "home" -> ParsedCall.Ok(AgentAction.Home)
      "open_app" -> {
        val appName = text(args["name"])?.trim()?.takeIf { it.isNotEmpty() } ?: return invalid("name is missing.")
        if (appName.length > MAX_APP_NAME_CHARS) return invalid("name is too long.")
        ParsedCall.Ok(AgentAction.OpenApp(appName))
      }
      "open_url" -> {
        val url = text(args["url"])?.trim()?.takeIf { it.isNotEmpty() } ?: return invalid("url is missing.")
        ParsedCall.Ok(AgentAction.OpenUrl(url))
      }
      "note" -> {
        val note = text(args["text"])?.trim()?.takeIf { it.isNotEmpty() } ?: return invalid("text is missing.")
        ParsedCall.Ok(AgentAction.Note(note.take(MAX_NOTE_CHARS)))
      }
      "save_result" -> {
        val body = text(args["text"])?.trim()?.takeIf { it.isNotEmpty() } ?: return invalid("text is missing.")
        val title = text(args["title"])?.trim()?.takeIf { it.isNotEmpty() } ?: "Result"
        ParsedCall.Ok(AgentAction.SaveResult(title.take(MAX_TITLE_CHARS), body.take(MAX_RESULT_CHARS)))
      }
      "finish" -> {
        val summary = text(args["summary"])?.trim()?.takeIf { it.isNotEmpty() } ?: return invalid("summary is missing.")
        ParsedCall.Ok(AgentAction.Finish(summary.take(MAX_SUMMARY_CHARS)))
      }
      else -> ParsedCall.Invalid("Unknown tool \"${oneLine(tool, 40)}\". Use one of: ${NAMES.joinToString()}.")
    }
  }

  /**
   * Fallback for a reply whose tool call the engine did not recognise: a JSON object with "name"
   * and "arguments" (or "parameters") somewhere in the text. Returns null if there is none.
   */
  fun parseFromText(text: String): ParsedCall? {
    var start = text.indexOf('{')
    while (start >= 0) {
      val end = matchingBrace(text, start)
      if (end > start) {
        val obj = runCatching { JsonParser.parseString(text.substring(start, end + 1)).asJsonObject }.getOrNull()
        val name = obj?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        if (obj != null && name != null) {
          val argsJson = (obj.get("arguments") ?: obj.get("parameters") ?: obj.get("args"))
          val args =
            if (argsJson != null && argsJson.isJsonObject) {
              argsJson.asJsonObject.entrySet().associate { (key, value) ->
                key to
                  when {
                    value.isJsonNull -> null
                    value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
                    value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asNumber
                    value.isJsonPrimitive -> value.asString
                    else -> value.toString()
                  }
              }
            } else {
              emptyMap()
            }
          return parse(name, args)
        }
      }
      start = text.indexOf('{', start + 1)
    }
    return null
  }

  private const val ID_HELP = "id must be the number of an element on the current screen, e.g. 3 for [3]."

  private fun elementId(value: Any?): Int? {
    val id =
      when (value) {
        is Int -> value
        is Long -> value.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
        is Number -> value.toDouble().takeIf { it % 1.0 == 0.0 && it >= 1 && it <= Int.MAX_VALUE }?.toInt()
        is String -> value.trim().removePrefix("[").removeSuffix("]").trim().toIntOrNull()
        else -> null
      }
    return id?.takeIf { it >= 1 }
  }

  private fun text(value: Any?): String? =
    when (value) {
      null -> null
      is String -> value
      is Number, is Boolean -> value.toString()
      else -> null
    }

  private fun flag(value: Any?): Boolean =
    when (value) {
      is Boolean -> value
      is String -> value.trim().equals("true", ignoreCase = true)
      is Number -> value.toInt() != 0
      else -> false
    }

  private fun matchingBrace(text: String, start: Int): Int {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
      val c = text[i]
      if (inString) {
        when {
          escaped -> escaped = false
          c == '\\' -> escaped = true
          c == '"' -> inString = false
        }
        continue
      }
      when (c) {
        '"' -> inString = true
        '{' -> depth++
        '}' -> if (--depth == 0) return i
      }
    }
    return -1
  }
}

/** Collapses whitespace and control characters to single spaces and truncates with "...". */
internal fun oneLine(text: String, max: Int): String {
  val flat = text.map { if (it.isWhitespace() || it.isISOControl()) ' ' else it }.joinToString("")
    .replace(Regex(" +"), " ").trim()
  return if (flat.length <= max) flat else flat.take(max - 3).trimEnd() + "..."
}
