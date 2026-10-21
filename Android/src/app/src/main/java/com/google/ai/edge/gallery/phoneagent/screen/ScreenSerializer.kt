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
  /** The line comes from inside a web page (a web view's content). */
  val inPage: Boolean = false,
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

/** Why a node gave no line of its own in a screen read. For diagnostics only. */
enum class DropReason(val label: String) {
  NOT_VISIBLE("not visible to the user"),
  EMPTY_BOUNDS("empty bounds"),
  OFFSCREEN("outside the screen"),
  INSIDE_DROPPED("inside a dropped node"),
  NO_TEXT("no text or label"),
  IN_LABEL("part of an element's label"),
  REPEATS_LABEL("repeats its element's label"),
  DUPLICATE("duplicate line"),
  TOKEN_CAP("over the token cap"),
}

/** Raw properties of one dropped node, for diagnostics. Never its text, only lengths. */
data class DroppedNode(
  val reason: DropReason,
  val inPage: Boolean,
  val className: String?,
  val textLength: Int,
  val descriptionLength: Int,
  val bounds: Bounds,
  val visibleToUser: Boolean,
  val important: Boolean,
  val clickable: Boolean,
) {
  fun line(): String =
    "${reason.label} | ${className?.substringAfterLast('.') ?: "?"} | text $textLength, desc $descriptionLength | " +
      "$bounds | visibleToUser=$visibleToUser important=$important clickable=$clickable" +
      if (inPage) " | page" else ""
}

/** How a screen read treated the tree: counts and raw properties only, no screen text. */
data class ReadDiagnostics(
  val nodes: Int = 0,
  /** Nodes inside a web view (page content). */
  val pageNodes: Int = 0,
  /** Page nodes whose own "visible to user" flag is true. */
  val pageNodesFlaggedVisible: Int = 0,
  /** Lines from the page that made it into the read. */
  val pageLines: Int = 0,
  /** The on-screen part of the web view, or null when no web page is on screen. */
  val pageArea: Bounds? = null,
  val dropped: Map<DropReason, Int> = emptyMap(),
  val samples: List<DroppedNode> = emptyList(),
) {
  /** One line for the run log. */
  fun summary(): String = buildString {
    append(nodes).append(" nodes; ")
    if (pageArea == null) {
      append("no web page on screen")
    } else {
      append("page: ").append(pageNodes).append(" nodes, ").append(pageLines).append(" lines shown, ")
      append(pageNodesFlaggedVisible).append(" flagged visible")
    }
    val top = dropped.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(4)
    if (top.isNotEmpty()) append("; dropped: ").append(top.joinToString { "${it.key.label} ${it.value}" })
  }

  /** The full report for the spike's "Copy with details". */
  fun report(): String = buildString {
    append("Nodes: ").append(nodes).append('\n')
    if (pageArea == null) {
      append("Web page: none on screen\n")
    } else {
      append("Web page area: ").append(pageArea).append('\n')
      append("Page nodes: ").append(pageNodes).append(", flagged visible by the page: ").append(pageNodesFlaggedVisible)
      append(", lines shown: ").append(pageLines).append('\n')
      append("Page visibility rule: on-screen bounds (the page's own visible flag is ignored)\n")
    }
    append("Dropped by reason:\n")
    for (reason in DropReason.entries) {
      val count = dropped[reason] ?: 0
      if (count > 0) append("  ").append(reason.label).append(": ").append(count).append('\n')
    }
    if (samples.isNotEmpty()) {
      append("First dropped nodes (page first):\n")
      samples.forEachIndexed { i, node -> append("  ").append(i + 1).append(". ").append(node.line()).append('\n') }
    }
  }
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
  val diagnostics: ReadDiagnostics = ReadDiagnostics(),
) {
  val truncated: Boolean
    get() = elements.size < totalElements

  /** A web page is on screen. */
  val pageArea: Boolean
    get() = diagnostics.pageArea != null

  /** Lines from the web page in [text]. */
  val pageLines: Int
    get() = elements.count { it.inPage }

  /** The element with this id from this read, or null if there is none. */
  fun element(id: Int): Element? = elements.getOrNull(id - 1)?.takeIf { it.id == id }
}

/**
 * Turns an accessibility tree into the compact numbered list described in docs/DECISIONS.md
 * (Architecture): `[id] role "label" state`, visible nodes only, de-duplicated, password values
 * never shown, and capped at about [DEFAULT_TOKEN_BUDGET] tokens.
 *
 * Rules:
 * - Native views are on screen when they report themselves visible to the user and their bounds
 *   overlap the screen; otherwise they are skipped with their children.
 * - Web pages (the subtree of a web view) are judged by bounds only: a page node is on screen
 *   when its bounds overlap the visible part of the web view. Web engines report the "visible"
 *   flag from their own caches (GeckoView from a lazily built viewport cache), which can mark a
 *   whole page offscreen while it is shown. Page nodes off screen or without bounds give no line,
 *   but their children are still checked one by one.
 * - Actionable nodes (clickable, long-clickable, editable, checkable) become elements. Without a
 *   label of their own they are labelled with their descendants' text, and those descendants are
 *   not listed again.
 * - Other nodes with text become `text`, `heading` or `image` lines unless they repeat the label
 *   of the element they sit in, or an earlier text line.
 * - Ids are 1, 2, 3... in reading (tree) order, so the same screen always gets the same ids.
 */
object ScreenSerializer {
  const val DEFAULT_TOKEN_BUDGET = 800
  /** Longest label of an element (button, link, input). */
  const val MAX_LABEL_CHARS = 100
  /** Longest text line: a paragraph's answer is often past its first 100 characters. */
  const val MAX_TEXT_CHARS = 400
  const val WEB_VIEW_CLASS = "android.webkit.WebView"
  private const val FOOTER_RESERVE_TOKENS = 25
  private const val MAX_PAGE_SAMPLES = 20
  private const val MAX_OTHER_SAMPLES = 5

  private class Candidate(
    val nodeKey: Int,
    val role: String,
    val label: String,
    val states: List<String>,
    val actionable: Boolean,
    val inPage: Boolean,
  )

  /** Native views: the platform's visibility flag and bounds overlapping the screen. */
  private fun nativeShown(node: UiNode, screen: Bounds): Boolean =
    node.visible && !node.bounds.isEmpty && node.bounds.intersects(screen)

  /** Page nodes: bounds overlapping the visible part of the web view; the flag is ignored. */
  private fun pageShown(node: UiNode, pageRect: Bounds): Boolean =
    !node.bounds.isEmpty && node.bounds.intersects(pageRect)

  fun read(
    root: UiNode?,
    screen: Bounds,
    context: ScreenContext,
    tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
  ): ScreenRead {
    val candidates = mutableListOf<Candidate>()
    val dropped = HashMap<DropReason, Int>()
    val pageSamples = mutableListOf<DroppedNode>()
    val otherSamples = mutableListOf<DroppedNode>()
    var nodeCount = 0
    var pageNodes = 0
    var pageFlaggedVisible = 0
    var pageArea: Bounds? = null
    var canScrollForward = false
    var canScrollBackward = false

    fun drop(node: UiNode, reason: DropReason, inPage: Boolean) {
      dropped[reason] = (dropped[reason] ?: 0) + 1
      val samples = if (inPage) pageSamples else otherSamples
      val max = if (inPage) MAX_PAGE_SAMPLES else MAX_OTHER_SAMPLES
      if (samples.size < max && (inPage || reason != DropReason.INSIDE_DROPPED)) {
        samples +=
          DroppedNode(
            reason = reason,
            inPage = inPage,
            className = node.className,
            textLength = node.text?.length ?: 0,
            descriptionLength = node.contentDescription?.length ?: 0,
            bounds = node.bounds,
            visibleToUser = node.visible,
            important = node.important,
            clickable = node.clickable,
          )
      }
    }

    fun count(node: UiNode, inPage: Boolean) {
      nodeCount++
      if (inPage) {
        pageNodes++
        if (node.visible) pageFlaggedVisible++
      }
    }

    fun dropSubtree(node: UiNode, inPage: Boolean) {
      for (child in node.children) {
        val childInPage = inPage || node.className == WEB_VIEW_CLASS
        count(child, childInPage)
        drop(child, DropReason.INSIDE_DROPPED, childInPage)
        dropSubtree(child, childInPage)
      }
    }

    fun visit(node: UiNode, pageRect: Bounds?, consumedByAncestor: Boolean, ancestorLabel: String?) {
      val inPage = pageRect != null
      count(node, inPage)
      if (inPage) {
        if (!node.bounds.isEmpty) {
          // Page content beyond the visible part means there is more to scroll to.
          if (node.bounds.top >= pageRect!!.bottom) canScrollForward = true
          if (node.bounds.bottom <= pageRect.top) canScrollBackward = true
        }
        val reason =
          when {
            node.bounds.isEmpty -> DropReason.EMPTY_BOUNDS
            !pageShown(node, pageRect!!) -> DropReason.OFFSCREEN
            else -> null
          }
        if (reason != null) {
          drop(node, reason, inPage = true)
          // An off-screen or bound-less page node can still hold on-screen children.
          for (child in node.children) visit(child, pageRect, consumedByAncestor, ancestorLabel)
          return
        }
      } else {
        val reason =
          when {
            !node.visible -> DropReason.NOT_VISIBLE
            node.bounds.isEmpty -> DropReason.EMPTY_BOUNDS
            !node.bounds.intersects(screen) -> DropReason.OFFSCREEN
            else -> null
          }
        if (reason != null) {
          drop(node, reason, inPage = false)
          dropSubtree(node, inPage = false)
          return
        }
      }
      val isPageRoot = !inPage && node.className == WEB_VIEW_CLASS
      val childPageRect = if (isPageRoot) node.bounds.intersect(screen) else pageRect
      if (isPageRoot && pageArea == null) pageArea = childPageRect
      // A web view always offers both scroll actions; its page's geometry says where content is.
      if (!inPage && !isPageRoot && node.scrollable) {
        canScrollForward = canScrollForward || node.canScrollForward
        canScrollBackward = canScrollBackward || node.canScrollBackward
      }
      val actionable = node.clickable || node.longClickable || node.editable || node.checkable
      var consumed = consumedByAncestor
      var labelForChildren = ancestorLabel
      if (actionable) {
        val own = ownLabel(node)
        val shown: (UiNode) -> Boolean =
          if (childPageRect != null) { n -> pageShown(n, childPageRect) } else { n -> nativeShown(n, screen) }
        val aggregated = if (own == null) aggregatedText(node, shown) else null
        val label = own ?: aggregated?.text
        candidates += Candidate(node.key, role(node), label.orEmpty(), states(node), actionable = true, inPage = inPage)
        // Descendants are listed separately unless all of their text already forms this label
        // (a large clickable container keeps its text lines).
        consumed = aggregated?.complete == true
        labelForChildren = label
      } else if (consumedByAncestor) {
        drop(node, DropReason.IN_LABEL, inPage)
      } else {
        val own = ownLabel(node, MAX_TEXT_CHARS)
        when {
          own == null -> drop(node, DropReason.NO_TEXT, inPage)
          ancestorLabel != null && clean(own, MAX_LABEL_CHARS) == ancestorLabel ->
            drop(node, DropReason.REPEATS_LABEL, inPage)
          else -> candidates += Candidate(node.key, role(node), own, states(node), actionable = false, inPage = inPage)
        }
      }
      for (child in node.children) visit(child, childPageRect, consumed, labelForChildren)
    }
    if (root != null) visit(root, pageRect = null, consumedByAncestor = false, ancestorLabel = null)

    val deduped = dedupe(candidates)
    if (candidates.size > deduped.size) dropped[DropReason.DUPLICATE] = candidates.size - deduped.size
    val header = header(context, canScrollForward, canScrollBackward)
    fun diagnostics(pageLines: Int, capped: Int): ReadDiagnostics {
      if (capped > 0) dropped[DropReason.TOKEN_CAP] = capped
      return ReadDiagnostics(
        nodes = nodeCount,
        pageNodes = pageNodes,
        pageNodesFlaggedVisible = pageFlaggedVisible,
        pageLines = pageLines,
        pageArea = pageArea,
        dropped = dropped.toMap(),
        samples = pageSamples + otherSamples,
      )
    }
    if (deduped.isEmpty()) {
      val text =
        header +
          "The screen is empty or its content is hidden (for example a secure or protected " +
          "window). Stop here."
      return ScreenRead(ScreenStatus.EMPTY, text, emptyList(), 0, estimateTokens(text), diagnostics(0, 0))
    }

    val body = StringBuilder()
    val elements = mutableListOf<Element>()
    var tokens = estimateTokens(header)
    for (candidate in deduped) {
      val element =
        Element(
          elements.size + 1,
          candidate.nodeKey,
          candidate.role,
          candidate.label,
          candidate.states,
          candidate.inPage,
        )
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
    return ScreenRead(
      ScreenStatus.OK,
      text,
      elements,
      deduped.size,
      estimateTokens(text),
      diagnostics(elements.count { it.inPage }, hidden),
    )
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
  private fun ownLabel(node: UiNode, max: Int = MAX_LABEL_CHARS): String? {
    if (node.sensitive) return "(hidden by the app)"
    if (node.editable) {
      return clean(node.hint) ?: clean(node.contentDescription) ?: viewIdName(node.viewId)
    }
    return clean(node.text, max) ?: clean(node.contentDescription, max)
  }

  private class Aggregate(val text: String?, val complete: Boolean)

  /**
   * Text of on-screen, non-actionable descendants, for a button or link without its own label.
   * [Aggregate.complete] is false when the text did not fit into one label.
   */
  private fun aggregatedText(node: UiNode, shown: (UiNode) -> Boolean): Aggregate {
    val parts = mutableListOf<String>()
    var length = 0
    var complete = true
    fun collect(n: UiNode) {
      if (!complete) return
      if (!shown(n)) return
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
   * A developer view of every on-screen node (class, view id, text, flags), for the spike only,
   * with the same visibility rules as [read]. It is shown on screen and never saved. Limited to
   * [maxNodes] lines.
   */
  fun details(root: UiNode?, screen: Bounds, maxNodes: Int = 400): String = buildString {
    var count = 0
    fun visit(node: UiNode, depth: Int, pageRect: Bounds?) {
      if (count >= maxNodes) return
      val inPage = pageRect != null
      val shown = if (inPage) pageShown(node, pageRect!!) else nativeShown(node, screen)
      if (!shown) {
        // Off-screen page nodes can still hold on-screen children; native ones can't.
        if (inPage) for (child in node.children) visit(child, depth, pageRect)
        return
      }
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
          "flagged-not-visible".takeIf { !node.visible },
        )
      if (flags.isNotEmpty()) append(" [").append(flags.joinToString(",")).append(']')
      append('\n')
      val isPageRoot = !inPage && node.className == WEB_VIEW_CLASS
      val childRect = if (isPageRoot) node.bounds.intersect(screen) else pageRect
      for (child in node.children) visit(child, depth + 1, childRect)
    }
    if (root != null) visit(root, 0, null)
    if (count >= maxNodes) append("(stopped after $maxNodes nodes)\n")
  }
}
