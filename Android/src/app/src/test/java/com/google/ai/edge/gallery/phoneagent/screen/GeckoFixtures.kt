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

/** A stand-in for a live accessibility node, for testing [TreeCopier] and everything after it. */
class FakeNode(
  val className: String = "android.view.View",
  val text: String? = null,
  val desc: String? = null,
  val hint: String? = null,
  val viewId: String? = null,
  val role: String? = null,
  val bounds: Bounds = Bounds(0, 0, 0, 0),
  val visible: Boolean = true,
  val clickable: Boolean = false,
  val editable: Boolean = false,
  val password: Boolean = false,
  val heading: Boolean = false,
  val scrollActions: Boolean = false,
  val children: List<FakeNode> = emptyList(),
)

/** Maps [FakeNode]s the way the Android converter maps accessibility nodes. */
object FakeAccess : NodeAccess<FakeNode> {
  /** How often [bounds] was asked for, to check the copier reads each node once per visit. */
  var boundsCalls = 0

  override fun bounds(node: FakeNode): Bounds {
    boundsCalls++
    return node.bounds
  }

  override fun className(node: FakeNode): String? = node.className

  override fun childCount(node: FakeNode): Int = node.children.size

  override fun child(node: FakeNode, index: Int): FakeNode? = node.children.getOrNull(index)

  override fun toUiNode(node: FakeNode, key: Int, bounds: Bounds, children: List<UiNode>): UiNode =
    UiNode(
      key = key,
      className = node.className,
      text = node.text,
      contentDescription = node.desc,
      hint = node.hint,
      viewId = node.viewId,
      roleDescription = node.role,
      clickable = node.clickable,
      editable = node.editable,
      password = node.password,
      heading = node.heading,
      visible = node.visible,
      scrollable = node.scrollActions,
      canScrollForward = node.scrollActions,
      canScrollBackward = node.scrollActions,
      bounds = bounds,
      children = children,
    )
}

/**
 * Trees shaped like what Firefox 158 exposed on the owner's phone (One UI 9 / Android 17) for a
 * Wikipedia article in a private tab, from the Inspect report: a Compose toolbar whose address
 * and hint are one text, a private-tabs counter with a test-tag id, and a GeckoView whose page
 * nodes report "not visible to the user" and whose document ends with a footer far below the
 * screen. Built by hand from that report; not a byte-for-byte recording.
 */
object GeckoFixtures {
  val SCREEN = Bounds(0, 0, 1080, 2340)
  const val URL = "en.wikipedia.org/wiki/University_of_Bologna"
  const val LEAD =
    "The University of Bologna is a public research university in Bologna, Italy. Teaching began around 1088."

  private fun toolbar(): FakeNode =
    FakeNode(
      className = "android.view.View",
      bounds = Bounds(0, 80, 1080, 220),
      children =
        listOf(
          FakeNode(desc = "Site information", clickable = true, bounds = Bounds(0, 90, 120, 210)),
          FakeNode(text = "$URL. Search or enter address", clickable = true, bounds = Bounds(120, 90, 700, 210)),
          FakeNode(desc = "Reader view", clickable = true, bounds = Bounds(700, 90, 800, 210)),
          FakeNode(className = "android.widget.Button", text = "New private tab", clickable = true, bounds = Bounds(800, 90, 900, 210)),
          FakeNode(
            viewId = "TabCounterTestTags.tabCounter",
            desc = "Private Tabs Open: 5. Tap to switch tabs.",
            clickable = true,
            bounds = Bounds(900, 90, 990, 210),
          ),
          FakeNode(desc = "More options", clickable = true, bounds = Bounds(990, 90, 1080, 210)),
        ),
    )

  /**
   * The page: everything reports not visible to the user, like GeckoView's stale viewport cache.
   * [scrolledBy] moves the content up, as after scrolling.
   */
  private fun gecko(scrolledBy: Int = 0, pageFlaggedVisible: Boolean = false): FakeNode {
    fun y(top: Int, bottom: Int) = Bounds(0, top - scrolledBy, 1080, bottom - scrolledBy)
    val v = pageFlaggedVisible
    val article =
      listOf(
        FakeNode(desc = "University of Bologna", heading = true, role = "heading", bounds = y(260, 360), visible = v),
        FakeNode(
          bounds = y(380, 760),
          visible = v,
          children =
            listOf(
              FakeNode(text = LEAD, bounds = y(380, 640), visible = v),
              FakeNode(desc = "Bologna", role = "link", clickable = true, bounds = y(640, 700), visible = v),
            ),
        ),
        // A container without a frame (display: contents) still holds on-screen text.
        FakeNode(
          bounds = Bounds(0, 0, 0, 0),
          visible = v,
          children = listOf(FakeNode(text = "Motto: Alma mater studiorum", bounds = y(780, 840), visible = v)),
        ),
        FakeNode(
          bounds = y(900, 1000),
          visible = v,
          children =
            listOf(
              FakeNode(hint = "Search Wikipedia", text = "", editable = true, bounds = y(900, 960), visible = v),
              FakeNode(hint = "Password", text = "hunter2", editable = true, password = true, bounds = y(960, 1000), visible = v),
            ),
        ),
        FakeNode(text = "History section text further down the page.", bounds = y(2600, 2700), visible = v),
        FakeNode(text = "Notable alumni far below.", bounds = y(5200, 5300), visible = v),
        FakeNode(text = "Footer: content is available under CC BY-SA.", bounds = y(9000, 9100), visible = v),
      )
    val document = FakeNode(className = "android.view.View", bounds = y(220, 9100), visible = v, children = article)
    return FakeNode(
      className = ScreenSerializer.WEB_VIEW_CLASS,
      bounds = Bounds(0, 220, 1080, 2340),
      scrollActions = true,
      children = listOf(document),
    )
  }

  /** Firefox showing a Wikipedia article in a private tab. */
  fun wikipediaArticle(scrolledBy: Int = 0, pageFlaggedVisible: Boolean = false): FakeNode =
    FakeNode(
      className = "android.widget.FrameLayout",
      bounds = SCREEN,
      children = listOf(toolbar(), gecko(scrolledBy, pageFlaggedVisible)),
    )

  /** Firefox's own settings list in private mode: no web view, no home page. */
  fun firefoxSettings(): FakeNode =
    FakeNode(
      className = "android.widget.FrameLayout",
      bounds = SCREEN,
      children =
        listOf(
          FakeNode(text = "Settings", heading = true, bounds = Bounds(0, 80, 1080, 200)),
          FakeNode(text = "Private browsing", clickable = true, bounds = Bounds(0, 300, 1080, 400)),
          FakeNode(text = "Search", clickable = true, bounds = Bounds(0, 400, 1080, 500)),
          FakeNode(text = "Delete browsing data", clickable = true, bounds = Bounds(0, 500, 1080, 600)),
        ),
    )

  /** Firefox's private home page (Phase 1: detected by its homepage button id). */
  fun privateHomePage(): FakeNode =
    FakeNode(
      className = "android.widget.FrameLayout",
      bounds = SCREEN,
      children =
        listOf(
          toolbar(),
          FakeNode(
            viewId = "private.browsing.homepage.button",
            desc = "Private browsing",
            clickable = true,
            bounds = Bounds(400, 1000, 680, 1100),
          ),
        ),
    )

  /** Any other app's screen, with text that must never reach the model. */
  fun otherApp(secret: String): FakeNode =
    FakeNode(
      className = "android.widget.FrameLayout",
      bounds = SCREEN,
      children = listOf(FakeNode(text = secret, bounds = Bounds(0, 300, 1080, 400))),
    )

  fun copy(root: FakeNode, prune: Boolean = true): CopiedTree<FakeNode> =
    TreeCopier.copy(root, FakeAccess, maxNodes = 5000, maxDepth = 80, screen = if (prune) SCREEN else null)
}
