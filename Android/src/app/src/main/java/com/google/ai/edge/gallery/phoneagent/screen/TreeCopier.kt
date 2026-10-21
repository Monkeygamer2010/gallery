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

/** How [TreeCopier] reads a live tree (accessibility nodes on Android, fakes in tests). */
interface NodeAccess<N> {
  fun bounds(node: N): Bounds

  fun className(node: N): String?

  fun childCount(node: N): Int

  fun child(node: N, index: Int): N?

  /** Builds the [UiNode] for [node]; [bounds] and [children] are already resolved. */
  fun toUiNode(node: N, key: Int, bounds: Bounds, children: List<UiNode>): UiNode
}

/** The copy plus the live nodes, indexed by [UiNode.key]. */
class CopiedTree<N>(val root: UiNode, val nodes: List<N>, val truncated: Boolean, val prunedPageSubtrees: Int)

/**
 * Copies a live tree into [UiNode]s in pre-order (the serializer's reading order), at most
 * [maxNodes] nodes and [maxDepth] levels. With [screen], parts of a web page that lie entirely
 * more than one screen height above or below the screen are skipped, so the nodes on screen
 * after scrolling a long article can't fall beyond [maxNodes].
 *
 * Each node's bounds are read before its children are visited and kept in its own value: a
 * Phase 1 bug read them after the children into a shared rectangle, so every container got the
 * bounds of its last descendant (for a web page, the footer, off screen) and whole pages were
 * dropped as off screen.
 */
object TreeCopier {
  fun <N> copy(root: N, access: NodeAccess<N>, maxNodes: Int, maxDepth: Int, screen: Bounds? = null): CopiedTree<N> {
    val nodes = mutableListOf<N>()
    var truncated = false
    var pruned = 0

    fun farFromScreen(bounds: Bounds): Boolean {
      val s = screen ?: return false
      if (bounds.isEmpty) return false
      val height = s.bottom - s.top
      return bounds.top >= s.bottom + height || bounds.bottom <= s.top - height
    }

    fun visit(node: N, bounds: Bounds, depth: Int, inPage: Boolean): UiNode {
      val key = nodes.size
      nodes += node
      val childrenInPage = inPage || access.className(node) == ScreenSerializer.WEB_VIEW_CLASS
      val children = mutableListOf<UiNode>()
      val count = access.childCount(node)
      if (depth < maxDepth) {
        for (i in 0 until count) {
          if (nodes.size >= maxNodes) {
            truncated = true
            break
          }
          val child = access.child(node, i) ?: continue
          val childBounds = access.bounds(child)
          if (childrenInPage && farFromScreen(childBounds)) {
            pruned++
            continue
          }
          children += visit(child, childBounds, depth + 1, childrenInPage)
        }
      } else if (count > 0) {
        truncated = true
      }
      return access.toUiNode(node, key, bounds, children)
    }

    val tree = visit(root, access.bounds(root), 0, inPage = false)
    return CopiedTree(tree, nodes, truncated, pruned)
  }
}
