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
import com.google.ai.edge.gallery.phoneagent.screen.UiNode

/** A converted tree plus the live nodes, indexed by [UiNode.key], for acting on elements. */
class ConvertedTree(val root: UiNode, val nodes: List<AccessibilityNodeInfo>, val truncated: Boolean)

/**
 * Copies an accessibility tree into [UiNode]s. Keys are assigned in pre-order, matching the
 * serializer's reading order. Stops after [maxNodes] nodes or [maxDepth] levels, so a huge page
 * cannot stall the service.
 */
object NodeConverter {
  const val MAX_NODES = 2500
  const val MAX_DEPTH = 80

  fun convert(root: AccessibilityNodeInfo, maxNodes: Int = MAX_NODES, maxDepth: Int = MAX_DEPTH): ConvertedTree {
    val nodes = mutableListOf<AccessibilityNodeInfo>()
    var truncated = false
    val rect = Rect()

    fun copy(node: AccessibilityNodeInfo, depth: Int): UiNode {
      val key = nodes.size
      nodes += node
      node.getBoundsInScreen(rect)
      val actions = node.actionList
      val children = mutableListOf<UiNode>()
      if (depth < maxDepth) {
        for (i in 0 until node.childCount) {
          if (nodes.size >= maxNodes) {
            truncated = true
            break
          }
          val child = node.getChild(i) ?: continue
          children += copy(child, depth + 1)
        }
      } else if (node.childCount > 0) {
        truncated = true
      }
      @Suppress("DEPRECATION")
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
        sensitive = Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive,
        scrollable = node.isScrollable,
        canScrollForward =
          actions.any {
            it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
              it.id == AccessibilityAction.ACTION_SCROLL_DOWN.id
          },
        canScrollBackward =
          actions.any {
            it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ||
              it.id == AccessibilityAction.ACTION_SCROLL_UP.id
          },
        bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
        children = children,
      )
    }

    val tree = copy(root, 0)
    return ConvertedTree(tree, nodes, truncated)
  }
}
