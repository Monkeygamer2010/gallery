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

/** A rectangle in screen pixels. Used only inside the app (hit tests, gestures), never shown to the model. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  val isEmpty: Boolean
    get() = right <= left || bottom <= top

  fun intersects(other: Bounds): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom

  val centerX: Int
    get() = (left + right) / 2

  val centerY: Int
    get() = (top + bottom) / 2
}

/**
 * A platform-independent copy of one accessibility node, so the screen serializer is plain Kotlin
 * and unit-testable. [key] is the node's position in the snapshot (pre-order), which the Android
 * layer uses to find the real node again when acting on an element.
 */
data class UiNode(
  val key: Int,
  val className: String? = null,
  val packageName: String? = null,
  val text: String? = null,
  val contentDescription: String? = null,
  val hint: String? = null,
  val viewId: String? = null,
  /** Role a web engine or app reports, such as "link" (`AccessibilityNodeInfo.getRoleDescription`). */
  val roleDescription: String? = null,
  val clickable: Boolean = false,
  val longClickable: Boolean = false,
  val editable: Boolean = false,
  val checkable: Boolean = false,
  val checked: Boolean = false,
  val selected: Boolean = false,
  val focused: Boolean = false,
  val enabled: Boolean = true,
  val password: Boolean = false,
  val heading: Boolean = false,
  val visible: Boolean = true,
  /** The app marked this node's data as sensitive (Android 14+ `isAccessibilityDataSensitive`). */
  val sensitive: Boolean = false,
  val scrollable: Boolean = false,
  val canScrollForward: Boolean = false,
  val canScrollBackward: Boolean = false,
  val bounds: Bounds = Bounds(0, 0, 0, 0),
  val children: List<UiNode> = emptyList(),
)

/** Calls [action] for [this] node and every descendant, parents first. */
fun UiNode.forEachNode(action: (UiNode) -> Unit) {
  val stack = ArrayDeque<UiNode>()
  stack.addLast(this)
  while (stack.isNotEmpty()) {
    val node = stack.removeLast()
    action(node)
    for (i in node.children.indices.reversed()) stack.addLast(node.children[i])
  }
}
