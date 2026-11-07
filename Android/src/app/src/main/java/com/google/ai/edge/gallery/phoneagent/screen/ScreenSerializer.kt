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

/**
 * A numbered link or control of a screen read. Plain text gets no number. [nodeKey] finds the
 * node again when acting on it.
 */
data class Element(
  val id: Int,
  val nodeKey: Int,
  val role: String,
  val label: String,
  val states: List<String>,
  /** The element comes from inside a web page (a web view's content). */
  val inPage: Boolean = false,
) {
  fun line(): String = buildString {
    append('[').append(id).append("] ").append(role)
    if (label.isNotEmpty()) append(" \"").append(label).append('"')
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
  NO_LABEL("a page control without a label of its own"),
  IN_LABEL("part of an element's label"),
  REPEATS_LABEL("repeats the label of the node it sits in"),
  LANDMARK("in a navigation or banner landmark"),
  NOTICE("in a fundraising, cookie or donation notice"),
  CITATION("a citation link"),
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
 * its node. Links and controls cut off by the token budget get no id and cannot be acted on.
 */
data class ScreenRead(
  val status: ScreenStatus,
  val text: String,
  /** The numbered links and controls in [text]; plain text lines have no number. */
  val elements: List<Element>,
  /** Lines found on screen, including those cut by the budget. */
  val totalLines: Int,
  val estimatedTokens: Int,
  val diagnostics: ReadDiagnostics = ReadDiagnostics(),
  /** Lines shown in [text], after the header. */
  val lines: Int = 0,
  /** Shown lines that come from the web page. */
  val pageLines: Int = 0,
) {
  val truncated: Boolean
    get() = lines < totalLines

  /** A web page is on screen. */
  val pageArea: Boolean
    get() = diagnostics.pageArea != null

  /** The element with this id from this read, or null if there is none. */
  fun element(id: Int): Element? = elements.getOrNull(id - 1)?.takeIf { it.id == id }
}

/**
 * Turns an accessibility tree into the compact list described in docs/DECISIONS.md
 * (Architecture), capped at about [DEFAULT_TOKEN_BUDGET] tokens. Links and controls are numbered
 * lines, `[id] role "label" state`; text is plain, headings start with `# `. Visible nodes only,
 * password values never shown.
 *
 * Rules:
 * - Native views are on screen when they report themselves visible to the user and their bounds
 *   overlap the screen; otherwise they are skipped with their children.
 * - Web pages (the subtree of a web view) are judged by bounds only: a page node is on screen
 *   when its bounds overlap the visible part of the web view. Web engines report the "visible"
 *   flag from their own caches (GeckoView from a lazily built viewport cache), which can mark a
 *   whole page offscreen while it is shown. Page nodes off screen or without bounds give no line,
 *   but their children are still checked one by one.
 * - Actionable nodes (clickable, long-clickable, editable, checkable) become numbered elements.
 *   Without a label of their own they are labelled with their descendants' text, and those
 *   descendants are not listed again.
 * - On a page, text fragments and links that sit side by side in one parent (a paragraph) become
 *   one line; each link keeps its number inline: `in [7]Bologna, [8]Italy.`
 * - On a page, a control without a label of its own (a clickable container, or one named only by
 *   its DOM id) is not an element, and neither are citation links ("[1]"). Inputs always are.
 * - Navigation and banner landmarks are skipped, except search fields, search buttons and tabs.
 *   Fundraising, cookie and consent notices (by DOM id) and donation controls are skipped.
 * - Neighbouring lines with the same label (a heading and its link, a button and its text)
 *   become one; text repeated from an earlier line is dropped.
 * - The page's main landmark comes first, then the rest of the page, then the app's own views
 *   (Firefox's toolbar), which keep a share of the budget.
 * - Ids are 1, 2, 3... in output order, so the same screen always gets the same ids.
 */
object ScreenSerializer {
  const val DEFAULT_TOKEN_BUDGET = 800
  /** Longest label of an element (button, link, input). */
  const val MAX_LABEL_CHARS = 100
  /** Longest text of one node: a paragraph's answer is often past its first 100 characters. */
  const val MAX_TEXT_CHARS = 400
  /** Longest line of merged text fragments and links. */
  const val MAX_LINE_CHARS = 1_200
  const val WEB_VIEW_CLASS = "android.webkit.WebView"
  private const val FOOTER_RESERVE_TOKENS = 25
  private const val MAX_PAGE_SAMPLES = 20
  private const val MAX_OTHER_SAMPLES = 5

  /** Page landmarks skipped with their subtree, except search fields, search buttons and tabs. */
  private val SKIPPED_LANDMARKS = setOf("navigation", "banner")

  /** DOM ids of fundraising, cookie and consent notices; skipped with their subtree. */
  private val NOTICE_ID =
    Regex("centralnotice|sitenotice|frb|fundrais|donat|cookie|consent|gdpr|onetrust", RegexOption.IGNORE_CASE)

  /** Donation controls: "Donate now", "€5", "$10", "10 €". */
  private val DONATION_LABEL = Regex("\\bdonat|^[€$£¥]\\s?\\d+([.,]\\d+)?$|^\\d+([.,]\\d+)?\\s?[€$£¥]$", RegexOption.IGNORE_CASE)

  /** Search buttons kept in a skipped landmark: "Search", "Search Wikipedia", "Submit search". */
  private val SEARCH_BUTTON = Regex("^(search|submit search|start search)\\b", RegexOption.IGNORE_CASE)

  /** Citation links: "[1]", "[a]", "[note 3]", "[citation needed]". */
  private val CITATION = Regex("^\\[\\s*(\\d+|[a-z]|note \\d+|nb \\d+|citation needed)\\s*]$", RegexOption.IGNORE_CASE)

  /** No space is put before these when fragments are joined... */
  private const val NO_SPACE_BEFORE = ",.;:!?)]}%…'’"

  /** ...or after these. */
  private const val NO_SPACE_AFTER = "([{/‘“"

  /** Where a line comes from; the output lists the page's main content first. */
  private enum class Region {
    MAIN,
    PAGE,
    NATIVE,
  }

  /** Landmark context while walking a page. */
  private enum class Zone {
    PAGE,
    MAIN,
    SKIPPED,
  }

  private class Candidate(
    val nodeKey: Int,
    val role: String,
    val label: String,
    val states: List<String>,
    val actionable: Boolean,
    val inPage: Boolean,
    val parentKey: Int,
    val region: Region,
    /** The node's raw text started / ended with a space: used when fragments are joined. */
    val spaceBefore: Boolean = false,
    val spaceAfter: Boolean = false,
  ) {
    val kind: Kind
      get() =
        when {
          actionable -> Kind.ELEMENT
          role == "heading" -> Kind.HEADING
          role == "image" || role == "graphic" -> Kind.IMAGE
          else -> Kind.TEXT
        }

    /** Text fragments and links can share a line. */
    val inline: Boolean
      get() = inPage && (kind == Kind.TEXT || (actionable && role == "link"))
  }

  private enum class Kind {
    ELEMENT,
    TEXT,
    HEADING,
    IMAGE,
  }

  /** One output line: a single element, a heading or image, or text with its inline links. */
  private class Line(val kind: Kind, val parts: List<Candidate>) {
    val region: Region
      get() = parts[0].region

    val inPage: Boolean
      get() = parts[0].inPage

    /** The line's text without ids, for comparing lines. */
    val plain: String = if (parts.size == 1) parts[0].label else ScreenSerializer.joinParts(parts, ids = null).first

    /** An element line's priority when neighbours with the same label become one. */
    val priority: Int
      get() =
        when (kind) {
          Kind.ELEMENT -> if (parts[0].role == "link") 4 else 3
          Kind.HEADING -> 2
          Kind.TEXT -> 1
          Kind.IMAGE -> 0
        }
  }

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

    fun visit(node: UiNode, pageRect: Bounds?, consumedByAncestor: Boolean, ancestorLabel: String?, parentKey: Int, zone: Zone) {
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
          for (child in node.children) visit(child, pageRect, consumedByAncestor, ancestorLabel, node.key, zone)
          return
        }
        if (node.viewId?.let { NOTICE_ID.containsMatchIn(it) } == true) {
          drop(node, DropReason.NOTICE, inPage = true)
          dropSubtree(node, inPage = true)
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
      val landmark = if (inPage) node.roleDescription?.trim()?.lowercase() else null
      val nodeZone =
        when {
          !inPage -> zone
          // A search landmark is kept, even inside a banner.
          landmark == "search" -> Zone.PAGE
          zone == Zone.SKIPPED || landmark in SKIPPED_LANDMARKS -> Zone.SKIPPED
          landmark == "main" -> Zone.MAIN
          else -> zone
        }
      val region =
        when {
          !inPage -> Region.NATIVE
          nodeZone == Zone.MAIN -> Region.MAIN
          else -> Region.PAGE
        }
      val actionable = node.clickable || node.longClickable || node.editable || node.checkable
      var consumed = consumedByAncestor
      var labelForChildren = ancestorLabel
      if (actionable) {
        val own = ownLabel(node, inPage = inPage)
        val shown: (UiNode) -> Boolean =
          if (childPageRect != null) { n -> pageShown(n, childPageRect) } else { n -> nativeShown(n, screen) }
        val aggregated = if (own == null) aggregatedText(node, shown, inPage) else null
        // Firefox's address bar shows "<address>. Search or enter address"; the header has the address.
        val label = (own ?: aggregated?.text)?.let { if (inPage) it else withoutAddress(it, context.url) }
        val role = role(node)
        val skip =
          when {
            nodeZone == Zone.SKIPPED && !(node.editable || role == "tab" || (label != null && SEARCH_BUTTON.containsMatchIn(label))) ->
              DropReason.LANDMARK
            node.editable -> null
            label == null -> DropReason.NO_LABEL
            // A clickable page container, labelled only by the start of its text.
            inPage && own == null && aggregated?.complete == false -> DropReason.NO_LABEL
            inPage && DONATION_LABEL.containsMatchIn(label) -> DropReason.NOTICE
            inPage && role == "link" && CITATION.matches(label) -> DropReason.CITATION
            else -> null
          }
        if (skip == null) {
          candidates +=
            Candidate(
              nodeKey = node.key,
              role = role,
              label = label.orEmpty(),
              states = states(node),
              actionable = true,
              inPage = inPage,
              parentKey = parentKey,
              region = region,
            )
          // Descendants are listed separately unless all of their text already forms this label
          // (a large clickable container keeps its text lines).
          consumed = aggregated?.complete == true
          labelForChildren = label
        } else {
          drop(node, skip, inPage)
          // A skipped link's or button's own text is skipped with it.
          if (skip != DropReason.NO_LABEL) labelForChildren = label
        }
      } else if (consumedByAncestor) {
        drop(node, DropReason.IN_LABEL, inPage)
      } else {
        val own = ownLabel(node, inPage = inPage, max = MAX_TEXT_CHARS)
        when {
          own == null -> drop(node, DropReason.NO_TEXT, inPage)
          ancestorLabel != null && clean(own, MAX_LABEL_CHARS) == ancestorLabel -> drop(node, DropReason.REPEATS_LABEL, inPage)
          nodeZone == Zone.SKIPPED -> drop(node, DropReason.LANDMARK, inPage)
          else -> {
            val raw = node.text ?: node.contentDescription
            candidates +=
              Candidate(
                nodeKey = node.key,
                role = role(node),
                label = own,
                states = states(node),
                actionable = false,
                inPage = inPage,
                parentKey = parentKey,
                region = region,
                spaceBefore = raw?.firstOrNull()?.isWhitespace() == true,
                spaceAfter = raw?.lastOrNull()?.isWhitespace() == true,
              )
            // A heading's or cell's text leaf repeats its name.
            labelForChildren = clean(own, MAX_LABEL_CHARS)
          }
        }
      }
      for (child in node.children) visit(child, childPageRect, consumed, labelForChildren, node.key, nodeZone)
    }
    if (root != null) visit(root, pageRect = null, consumedByAncestor = false, ancestorLabel = null, parentKey = -1, zone = Zone.PAGE)

    val lines = dedupe(collapse(merge(candidates)))
    val merged = candidates.size - lines.sumOf { it.parts.size }
    if (merged > 0) dropped[DropReason.DUPLICATE] = merged
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
    if (lines.isEmpty()) {
      val text =
        header +
          "The screen is empty or its content is hidden (for example a secure or protected " +
          "window). Stop here."
      return ScreenRead(ScreenStatus.EMPTY, text, emptyList(), 0, estimateTokens(text), diagnostics(0, 0))
    }

    // The page's main content first, then the rest of the page, then the app's own views, which
    // keep up to half of the budget so the toolbar (the address bar) is always there.
    val pagePart = lines.filter { it.region == Region.MAIN } + lines.filter { it.region == Region.PAGE }
    val nativePart = lines.filter { it.region == Region.NATIVE }
    val budget = tokenBudget - FOOTER_RESERVE_TOKENS
    var tokens = estimateTokens(header)
    fun cost(line: Line) = estimateTokens(render(line) { 99 } + "\n")
    val keptNative = mutableListOf<Line>()
    val nativeShare = if (pagePart.isEmpty()) budget else budget / 2
    for (line in nativePart) {
      val c = cost(line)
      if (tokens + c > nativeShare) break
      keptNative += line
      tokens += c
    }
    val keptPage = mutableListOf<Line>()
    for (line in pagePart) {
      val c = cost(line)
      if (tokens + c > budget) break
      keptPage += line
      tokens += c
    }
    val hiddenPage = pagePart.size - keptPage.size
    val hiddenNative = nativePart.size - keptNative.size
    val hidden = hiddenPage + hiddenNative

    val elements = mutableListOf<Element>()
    val assign: (Candidate) -> Int = { part ->
      val element = Element(elements.size + 1, part.nodeKey, part.role, part.label, part.states, part.inPage)
      elements += element
      element.id
    }
    val body = StringBuilder()
    for (line in keptPage) body.append(render(line, assign)).append('\n')
    if (hiddenPage > 0) body.append("($hiddenPage more lines not shown. Scroll to see more.)\n")
    for (line in keptNative) body.append(render(line, assign)).append('\n')
    if (hiddenNative > 0) body.append("($hiddenNative more lines not shown.)\n")
    val text = (header + body).trimEnd()
    val pageLineCount = keptPage.count { it.inPage } + keptNative.count { it.inPage }
    return ScreenRead(
      ScreenStatus.OK,
      text,
      elements,
      lines.size,
      estimateTokens(text),
      diagnostics(pageLineCount, hidden),
      lines = keptPage.size + keptNative.size,
      pageLines = pageLineCount,
    )
  }

  /** Text fragments and links side by side in one page parent become one line. */
  private fun merge(candidates: List<Candidate>): List<Line> {
    val lines = mutableListOf<Line>()
    var run = mutableListOf<Candidate>()
    fun flush() {
      if (run.isEmpty()) return
      if (run.any { !it.actionable }) {
        lines += Line(Kind.TEXT, run)
      } else {
        // Links alone stay one per line.
        for (link in run) lines += Line(Kind.ELEMENT, listOf(link))
      }
      run = mutableListOf()
    }
    for (candidate in candidates) {
      if (candidate.inline) {
        if (run.isNotEmpty() && run.last().parentKey != candidate.parentKey) flush()
        run += candidate
      } else {
        flush()
        lines += Line(candidate.kind, listOf(candidate))
      }
    }
    flush()
    return lines
  }

  /**
   * Neighbouring lines with the same label become one, kept as the more useful kind (a link over
   * a button over a heading over text). Inputs and text with links in it are never folded.
   */
  private fun collapse(lines: List<Line>): List<Line> {
    val out = mutableListOf<Line>()
    for (line in lines) {
      val previous = out.lastOrNull()
      if (previous != null && foldable(previous) && foldable(line) && previous.region == line.region &&
        previous.plain.equals(line.plain, ignoreCase = true)) {
        if (line.priority > previous.priority) out[out.size - 1] = line
        continue
      }
      out += line
    }
    return out
  }

  /** "en.wikipedia.org/wiki/X. Search or enter address" -> "Search or enter address". */
  private fun withoutAddress(label: String, url: String?): String {
    if (url.isNullOrEmpty() || !label.startsWith(url)) return label
    return label.removePrefix(url).trimStart('.', ' ').ifEmpty { label }
  }

  private fun foldable(line: Line): Boolean = line.parts.size == 1 && line.parts[0].role != "input"

  /** Drops repeated lines: the same element twice in a row, or a text line seen before. */
  private fun dedupe(lines: List<Line>): List<Line> {
    val out = mutableListOf<Line>()
    val seenTexts = HashSet<String>()
    for (line in lines) {
      val previous = out.lastOrNull()
      if (line.kind == Kind.ELEMENT && previous?.kind == Kind.ELEMENT) {
        val a = previous.parts[0]
        val b = line.parts[0]
        if (a.role == b.role && a.label == b.label && a.states == b.states) continue
      }
      if (line.kind != Kind.ELEMENT && !seenTexts.add("${line.kind}|${line.plain}")) continue
      out += line
    }
    return out
  }

  /** The line as the model sees it; [id] numbers each link or control in order. */
  private fun render(line: Line, id: (Candidate) -> Int): String =
    when (line.kind) {
      Kind.ELEMENT -> {
        val part = line.parts[0]
        Element(id(part), part.nodeKey, part.role, part.label, part.states, part.inPage).line()
      }
      Kind.HEADING -> "# " + line.parts[0].label
      Kind.IMAGE -> "image: " + line.parts[0].label
      Kind.TEXT -> joinParts(line.parts, id).first
    }

  /**
   * Joins a line's fragments with single spaces where the page had them (or where neither side
   * is punctuation), links as `[id]label`, up to [MAX_LINE_CHARS] characters of text. Returns the
   * text and the links that made it in.
   */
  private fun joinParts(parts: List<Candidate>, ids: ((Candidate) -> Int)?): Pair<String, List<Candidate>> {
    val out = StringBuilder()
    val shown = mutableListOf<Candidate>()
    var length = 0
    var previous: Candidate? = null
    for (part in parts) {
      val space = previous != null && needsSpace(previous, part)
      val room = MAX_LINE_CHARS - length - (if (space) 1 else 0)
      if (room <= 0) break
      if (space) out.append(' ')
      if (part.label.length > room) {
        // Cut text; a link that doesn't fit is left out with everything after it.
        if (!part.actionable) out.append(part.label.take(room - 1).trimEnd()).append('…')
        break
      }
      if (part.actionable && ids != null) out.append('[').append(ids(part)).append(']')
      if (part.actionable) shown += part
      out.append(part.label)
      length += part.label.length + (if (space) 1 else 0)
      previous = part
    }
    return out.toString().trim() to shown
  }

  private fun needsSpace(before: Candidate, after: Candidate): Boolean {
    if (before.spaceAfter || after.spaceBefore) return true
    return after.label.first() !in NO_SPACE_BEFORE && before.label.last() !in NO_SPACE_AFTER
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

  /**
   * The node's own label, or null. Inputs use their hint or description, never their value. On a
   * page, a DOM id is never a label; an app's view id names an input that has nothing else.
   */
  private fun ownLabel(node: UiNode, inPage: Boolean, max: Int = MAX_LABEL_CHARS): String? {
    if (node.sensitive) return "(hidden by the app)"
    if (node.editable) {
      return clean(node.hint) ?: clean(node.contentDescription) ?: if (inPage) null else viewIdName(node.viewId)
    }
    return clean(node.text, max) ?: clean(node.contentDescription, max)
  }

  private class Aggregate(val text: String?, val complete: Boolean)

  /**
   * Text of on-screen, non-actionable descendants, for a button or link without its own label.
   * [Aggregate.complete] is false when the text did not fit into one label.
   */
  private fun aggregatedText(node: UiNode, shown: (UiNode) -> Boolean, inPage: Boolean): Aggregate {
    val parts = mutableListOf<String>()
    var length = 0
    var complete = true
    fun collect(n: UiNode) {
      if (!complete) return
      if (!shown(n)) return
      if (n !== node && (n.clickable || n.longClickable || n.editable || n.checkable)) return
      if (n !== node) {
        // Measured uncut: one long text must not pass for a short, complete label.
        val text = if (n.sensitive) null else clean(n.text, Int.MAX_VALUE) ?: clean(n.contentDescription, Int.MAX_VALUE)
        if (text != null && text !in parts) {
          if (length + text.length > MAX_LABEL_CHARS) complete = false
          parts += text
          length += text.length + 1
        }
      }
      for (child in n.children) collect(child)
    }
    collect(node)
    val text = if (parts.isEmpty()) (if (inPage) null else viewIdName(node.viewId)) else clean(parts.joinToString(" "))
    return Aggregate(text, complete && parts.isNotEmpty())
  }

  internal fun role(node: UiNode): String {
    val described = node.roleDescription?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val cls = node.className?.substringAfterLast('.').orEmpty()
    return when {
      described != null && described.contains("link") -> "link"
      node.editable -> "input"
      // GeckoView: "heading level 2".
      node.heading || described?.startsWith("heading") == true -> "heading"
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
