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

package com.google.ai.edge.gallery.phoneagent.a11y

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.ai.edge.gallery.phoneagent.screen.Bounds
import com.google.ai.edge.gallery.phoneagent.screen.NodeAccess
import com.google.ai.edge.gallery.phoneagent.screen.TreeCopier
import com.google.ai.edge.gallery.phoneagent.screen.UiNode

/**
 * A converted tree plus the live nodes, indexed by [UiNode.key], for acting on elements.
 * [prunedPageSubtrees] counts web-page parts skipped because they lay more than a screen away.
 */
class ConvertedTree(
  val root: UiNode,
  val nodes: List<AccessibilityNodeInfo>,
  val truncated: Boolean,
  val prunedPageSubtrees: Int = 0,
) {
  /** One line for diagnostics. */
  fun summary(): String =
    "Converted ${nodes.size} nodes" + (if (truncated) " (stopped at the limit)" else "") +
      if (prunedPageSubtrees > 0) ", skipped $prunedPageSubtrees page parts more than a screen away" else ""
}

/** Copies an accessibility tree into [UiNode]s ([TreeCopier] does the walking). */
object NodeConverter {
  const val MAX_NODES = 5000
  const val MAX_DEPTH = 80

  private object Access : NodeAccess<AccessibilityNodeInfo> {
    override fun bounds(node: AccessibilityNodeInfo): Bounds {
      val rect = Rect()
      node.getBoundsInScreen(rect)
      return Bounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    override fun className(node: AccessibilityNodeInfo): String? = node.className?.toString()

    override fun childCount(node: AccessibilityNodeInfo): Int = node.childCount

    override fun child(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? = node.getChild(index)

    @Suppress("DEPRECATION")
    override fun toUiNode(node: AccessibilityNodeInfo, key: Int, bounds: Bounds, children: List<UiNode>): UiNode {
      val actions = node.actionList
      return UiNode(
        key = key,
        className = node.className?.toString(),
        packageName = node.packageName?.toString(),
        text = node.text?.toString(),
        contentDescription = node.contentDescription?.toString(),
        hint = node.hintText?.toString(),
        viewId = node.viewIdResourceName,
        roleDescription = AccessibilityNodeInfoCompat.wrap(node).roleDescription?.toString(),
        clickable = node.isClickable,
        longClickable = node.isLongClickable,
        editable = node.isEditable,
        checkable = node.isCheckable,
        checked = node.isChecked,
        selected = node.isSelected,
        focused = node.isFocused,
        enabled = node.isEnabled,
        password = node.isPassword,
        heading = node.isHeading,
        visible = node.isVisibleToUser,
        important = node.isImportantForAccessibility,
        sensitive = Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive,
        scrollable = node.isScrollable,
        canScrollForward =
          actions.any {
            it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || it.id == AccessibilityAction.ACTION_SCROLL_DOWN.id
          },
        canScrollBackward =
          actions.any {
            it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD || it.id == AccessibilityAction.ACTION_SCROLL_UP.id
          },
        bounds = bounds,
        children = children,
      )
    }
  }

  /** With [screen], web-page parts more than a screen height away are skipped (agent reads). */
  fun convert(
    root: AccessibilityNodeInfo,
    maxNodes: Int = MAX_NODES,
    maxDepth: Int = MAX_DEPTH,
    screen: Bounds? = null,
  ): ConvertedTree {
    val copied = TreeCopier.copy(root, Access, maxNodes, maxDepth, screen)
    return ConvertedTree(copied.root, copied.nodes, copied.truncated, copied.prunedPageSubtrees)
  }
}
