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

import kotlin.math.ceil

/** What the reader knows about the screen besides the node tree. */
data class ScreenContext(
  val appLabel: String? = null,
  val packageName: String? = null,
  /** The address shown by a browser, when the foreground app is one. */
  val url: String? = null,
  /** True or false when the browser's private mode could be determined, null otherwise. */
  val privateTab: Boolean? = null,
  val keyboardOpen: Boolean = false,
)

/** One numbered line of a screen read. [nodeKey] finds the node again when acting on it. */
data class Element(
  val id: Int,
  val nodeKey: Int,
  val role: String,
  val label: String,
  val states: List<String>,
) {
  fun line(): String = buildString {
    append('[').append(id).append("] ").append(role).append(" \"").append(label).append('"')
    for (state in states) append(' ').append(state)
  }

  /** Role and label, used to check that an id still means what the reader saw. */
  val signature: String
    get() = "$role \"$label\""
}

enum class ScreenStatus {
  OK,
  /** No readable content: an empty, secure or protected window. The caller must stop. */
  EMPTY,
}

/**
 * A compact screen read: [text] is what a model would see; [elements] maps each shown id back to
 * its node. Elements cut off by the token budget get no id and cannot be acted on.
 */
data class ScreenRead(
  val status: ScreenStatus,
  val text: String,
  val elements: List<Element>,
  /** Elements found on screen, including those not shown because of the budget. */
  val totalElements: Int,
  val estimatedTokens: Int,
) {
  val truncated: Boolean
    get() = elements.size < totalElements

  /** The element with this id from this read, or null if there is none. */
  fun element(id: Int): Element? = elements.getOrNull(id - 1)?.takeIf { it.id == id }
}

/**
 * Turns an accessibility tree into the compact numbered list described in docs/DECISIONS.md
 * (Architecture): `[id] role "label" state`, visible nodes only, de-duplicated, password values
 * never shown, and capped at about [DEFAULT_TOKEN_BUDGET] tokens.
 *
 * Rules:
 * - Actionable nodes (clickable, long-clickable, editable, checkable) become elements. Without a
 *   label of their own they are labelled with their descendants' text, and those descendants are
 *   not listed again.
 * - Other nodes with text become `text`, `heading` or `image` lines unless they repeat the label
 *   of the element they sit in, or an earlier text line.
 * - Invisible nodes, empty or off-screen bounds and their subtrees are skipped.
 * - Ids are 1, 2, 3... in reading (tree) order, so the same screen always gets the same ids.
 */
object ScreenSerializer {
  const val DEFAULT_TOKEN_BUDGET = 800
  const val MAX_LABEL_CHARS = 100
  private const val FOOTER_RESERVE_TOKENS = 25

  private class Candidate(
    val nodeKey: Int,
    val role: String,
    val label: String,
    val states: List<String>,
    val actionable: Boolean,
  )

  fun read(
    root: UiNode?,
    screen: Bounds,
    context: ScreenContext,
    tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
  ): ScreenRead {
    val candidates = mutableListOf<Candidate>()
    var canScrollForward = false
    var canScrollBackward = false

    fun visit(node: UiNode, consumedByAncestor: Boolean, ancestorLabel: String?) {
      if (!node.visible || node.bounds.isEmpty || !node.bounds.intersects(screen)) return
      if (node.scrollable) {
        canScrollForward = canScrollForward || node.canScrollForward
        canScrollBackward = canScrollBackward || node.canScrollBackward
      }
      val actionable = node.clickable || node.longClickable || node.editable || node.checkable
      var consumed = consumedByAncestor
      var labelForChildren = ancestorLabel
      if (actionable) {
        val own = ownLabel(node)
        val aggregated = if (own == null) aggregatedText(node, screen) else null
        val label = own ?: aggregated?.text
        candidates += Candidate(node.key, role(node), label.orEmpty(), states(node), actionable = true)
        // Descendants are listed separately unless all of their text already forms this label
        // (a large clickable container keeps its text lines).
        consumed = aggregated?.complete == true
        labelForChildren = label
      } else if (!consumedByAncestor) {
        val own = ownLabel(node)
        if (own != null && own != ancestorLabel) {
          candidates += Candidate(node.key, role(node), own, states(node), actionable = false)
        }
      }
      for (child in node.children) visit(child, consumed, labelForChildren)
    }
    if (root != null) visit(root, consumedByAncestor = false, ancestorLabel = null)

    val deduped = dedupe(candidates)
    val header = header(context, canScrollForward, canScrollBackward)
    if (deduped.isEmpty()) {
      val text =
        header +
          "The screen is empty or its content is hidden (for example a secure or protected " +
          "window). Stop here."
      return ScreenRead(ScreenStatus.EMPTY, text, emptyList(), 0, estimateTokens(text))
    }

    val body = StringBuilder()
    val elements = mutableListOf<Element>()
    var tokens = estimateTokens(header)
    for (candidate in deduped) {
      val element =
        Element(elements.size + 1, candidate.nodeKey, candidate.role, candidate.label, candidate.states)
      val line = element.line() + "\n"
      val lineTokens = estimateTokens(line)
      if (tokens + lineTokens > tokenBudget - FOOTER_RESERVE_TOKENS) break
      body.append(line)
      tokens += lineTokens
      elements += element
    }
    val hidden = deduped.size - elements.size
    if (hidden > 0) {
      body.append("($hidden more elements not shown. Scroll to see more.)\n")
    }
    val text = (header + body).trimEnd()
    return ScreenRead(ScreenStatus.OK, text, elements, deduped.size, estimateTokens(text))
  }

  private fun header(context: ScreenContext, forward: Boolean, backward: Boolean): String =
    buildString {
      val app = context.appLabel ?: context.packageName
      if (app != null) append("App: ").append(clean(app, 60)).append('\n')
      if (context.url != null) {
        append("URL: ").append(clean(context.url, 200))
        when (context.privateTab) {
          true -> append(" (private tab)")
          false -> append(" (not a private tab)")
          null -> {}
        }
        append('\n')
      }
      if (context.keyboardOpen) append("Keyboard: open\n")
      if (forward || backward) {
        append("Scroll: ")
        append(listOfNotNull("more below".takeIf { forward }, "more above".takeIf { backward }).joinToString(", "))
        append('\n')
      }
    }

  /** Drops repeated lines: the same element twice in a row, or a text line seen before. */
  private fun dedupe(candidates: List<Candidate>): List<Candidate> {
    val out = mutableListOf<Candidate>()
    val seenTexts = HashSet<String>()
    for (candidate in candidates) {
      val previous = out.lastOrNull()
      if (previous != null && previous.role == candidate.role && previous.label == candidate.label &&
        previous.states == candidate.states) {
        continue
      }
      if (!candidate.actionable && !seenTexts.add("${candidate.role}|${candidate.label}")) continue
      out += candidate
    }
    return out
  }

  /** The node's own label, or null. Inputs use their hint or description, never their value. */
  private fun ownLabel(node: UiNode): String? {
    if (node.sensitive) return "(hidden by the app)"
    if (node.editable) {
      return clean(node.hint) ?: clean(node.contentDescription) ?: viewIdName(node.viewId)
    }
    return clean(node.text) ?: clean(node.contentDescription)
  }

  private class Aggregate(val text: String?, val complete: Boolean)

  /**
   * Text of visible, non-actionable descendants, for a button or link without its own label.
   * [Aggregate.complete] is false when the text did not fit into one label.
   */
  private fun aggregatedText(node: UiNode, screen: Bounds): Aggregate {
    val parts = mutableListOf<String>()
    var length = 0
    var complete = true
    fun collect(n: UiNode) {
      if (!complete) return
      if (!n.visible || n.bounds.isEmpty || !n.bounds.intersects(screen)) return
      if (n !== node && (n.clickable || n.longClickable || n.editable || n.checkable)) return
      if (n !== node) {
        val text = if (n.sensitive) null else clean(n.text) ?: clean(n.contentDescription)
        if (text != null && text !in parts) {
          if (length + text.length > MAX_LABEL_CHARS) complete = false
          parts += text
          length += text.length + 1
        }
      }
      for (child in n.children) collect(child)
    }
    collect(node)
    val text = if (parts.isEmpty()) viewIdName(node.viewId) else clean(parts.joinToString(" "))
    return Aggregate(text, complete && parts.isNotEmpty())
  }

  internal fun role(node: UiNode): String {
    val described = node.roleDescription?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val cls = node.className?.substringAfterLast('.').orEmpty()
    return when {
      described != null && described.contains("link") -> "link"
      node.editable -> "input"
      node.heading -> "heading"
      described != null && described.length <= 20 && described.all { it.isLetter() || it == ' ' } ->
        described
      cls.contains("CheckBox") -> "checkbox"
      cls.contains("Switch") || cls.contains("ToggleButton") -> "switch"
      cls.contains("RadioButton") -> "radio"
      cls.endsWith("Button") -> "button"
      cls.contains("Spinner") -> "dropdown"
      cls.contains("SeekBar") -> "slider"
      node.clickable || node.longClickable -> "button"
      node.checkable -> "checkbox"
      cls.contains("ImageView") || cls == "Image" -> "image"
      else -> "text"
    }
  }

  private fun states(node: UiNode): List<String> = buildList {
    if (node.editable) {
      val value = clean(node.text)?.takeIf { it != clean(node.hint) }
      when {
        node.password -> add("password")
        value != null && !node.sensitive -> add("value=\"$value\"")
        else -> add("empty")
      }
    }
    if (node.checkable) add(if (node.checked) "checked" else "unchecked")
    if (node.selected) add("selected")
    if (node.focused && node.editable) add("focused")
    if (!node.enabled) add("disabled")
  }

  /** The readable part of a view id: "org.mozilla.firefox:id/url_view" -> "url view". */
  private fun viewIdName(viewId: String?): String? =
    viewId?.substringAfterLast('/')?.replace('_', ' ')?.let { clean(it, 40) }

  /** Collapses whitespace, removes control characters and quotes, and truncates. */
  internal fun clean(value: String?, max: Int = MAX_LABEL_CHARS): String? {
    if (value == null) return null
    val collapsed =
      value
        .filterNot { it.isISOControl() && it != '\n' && it != '\t' }
        .replace(Regex("\\s+"), " ")
        .replace('"', '\'')
        .trim()
    if (collapsed.isEmpty()) return null
    return if (collapsed.length <= max) collapsed else collapsed.take(max - 1).trimEnd() + "…"
  }

  /**
   * Rough token count for budgeting: about 4 characters per token for Latin text and one per
   * character for other scripts. Phase 2 replaces it with the model's real tokenizer count.
   */
  fun estimateTokens(text: String): Int {
    var latin = 0
    var other = 0
    for (c in text) if (c.code < 0x250) latin++ else other++
    return ceil(latin / 4.0).toInt() + other
  }

  /**
   * A developer view of every visible node (class, view id, text, flags), for the spike only.
   * It is shown on screen and never saved. Limited to [maxNodes] lines.
   */
  fun details(root: UiNode?, screen: Bounds, maxNodes: Int = 400): String = buildString {
    var count = 0
    fun visit(node: UiNode, depth: Int) {
      if (count >= maxNodes) return
      if (!node.visible || node.bounds.isEmpty || !node.bounds.intersects(screen)) return
      count++
      append("  ".repeat(depth.coerceAtMost(12)))
      append(node.className?.substringAfterLast('.') ?: "?")
      node.viewId?.let { append(" id=").append(it.substringAfter(":id/")) }
      node.roleDescription?.let { append(" role=").append(clean(it, 30)) }
      if (!node.password && !node.sensitive) clean(node.text, 60)?.let { append(" text=\"").append(it).append('"') }
      clean(node.contentDescription, 60)?.let { append(" desc=\"").append(it).append('"') }
      clean(node.hint, 40)?.let { append(" hint=\"").append(it).append('"') }
      val flags =
        listOfNotNull(
          "click".takeIf { node.clickable },
          "edit".takeIf { node.editable },
          "check".takeIf { node.checkable },
          "scroll".takeIf { node.scrollable },
          "password".takeIf { node.password },
          "heading".takeIf { node.heading },
          "disabled".takeIf { !node.enabled },
        )
      if (flags.isNotEmpty()) append(" [").append(flags.joinToString(",")).append(']')
      append('\n')
      for (child in node.children) visit(child, depth + 1)
    }
    if (root != null) visit(root, 0)
    if (count >= maxNodes) append("(stopped after $maxNodes nodes)\n")
  }
}
