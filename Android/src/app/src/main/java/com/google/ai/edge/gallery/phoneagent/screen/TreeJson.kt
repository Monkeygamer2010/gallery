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

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * A converted tree as JSON, for the spike's "Copy tree": the owner can hand over a real screen,
 * from which unit-test fixtures are built. Only on the owner's tap, to the clipboard (marked
 * sensitive); never saved or logged. Password fields and app-hidden views carry no text. At most
 * [MAX_NODES] nodes.
 */
object TreeJson {
  const val MAX_NODES = 3_000

  fun write(root: UiNode): String {
    var count = 0
    fun node(n: UiNode): JsonObject? {
      if (count >= MAX_NODES) return null
      count++
      val o = JsonObject()
      o.add("b", JsonArray().apply { listOf(n.bounds.left, n.bounds.top, n.bounds.right, n.bounds.bottom).forEach { add(it) } })
      n.className?.let { o.addProperty("class", it) }
      if (!n.password && !n.sensitive) n.text?.let { o.addProperty("text", it) }
      if (!n.sensitive) n.contentDescription?.let { o.addProperty("desc", it) }
      n.hint?.let { o.addProperty("hint", it) }
      n.viewId?.let { o.addProperty("id", it) }
      n.roleDescription?.let { o.addProperty("role", it) }
      val flags =
        listOfNotNull(
          "click".takeIf { n.clickable },
          "longclick".takeIf { n.longClickable },
          "edit".takeIf { n.editable },
          "check".takeIf { n.checkable },
          "checked".takeIf { n.checked },
          "selected".takeIf { n.selected },
          "focused".takeIf { n.focused },
          "disabled".takeIf { !n.enabled },
          "password".takeIf { n.password },
          "sensitive".takeIf { n.sensitive },
          "heading".takeIf { n.heading },
          "hidden".takeIf { !n.visible },
          "scroll".takeIf { n.scrollable },
          "forward".takeIf { n.canScrollForward },
          "backward".takeIf { n.canScrollBackward },
        )
      if (flags.isNotEmpty()) o.addProperty("f", flags.joinToString(","))
      if (n.children.isNotEmpty()) {
        val kids = JsonArray()
        for (child in n.children) node(child)?.let { kids.add(it) }
        o.add("k", kids)
      }
      return o
    }
    return node(root)?.toString() ?: "{}"
  }
}
