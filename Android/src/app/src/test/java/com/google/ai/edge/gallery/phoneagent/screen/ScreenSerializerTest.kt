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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val SCREEN = Bounds(0, 0, 1080, 2340)

/** Builds trees with pre-order keys, like the Android converter does. */
private class TreeBuilder {
  private var nextKey = 0

  fun node(
    className: String = "android.view.View",
    text: String? = null,
    desc: String? = null,
    hint: String? = null,
    viewId: String? = null,
    role: String? = null,
    clickable: Boolean = false,
    editable: Boolean = false,
    checkable: Boolean = false,
    checked: Boolean = false,
    password: Boolean = false,
    heading: Boolean = false,
    visible: Boolean = true,
    enabled: Boolean = true,
    scrollable: Boolean = false,
    forward: Boolean = false,
    bounds: Bounds = Bounds(0, 0, 100, 100),
    children: TreeBuilder.() -> List<UiNode> = { emptyList() },
  ): UiNode {
    val key = nextKey++
    return UiNode(
      key = key,
      className = className,
      text = text,
      contentDescription = desc,
      hint = hint,
      viewId = viewId,
      roleDescription = role,
      clickable = clickable,
      editable = editable,
      checkable = checkable,
      checked = checked,
      password = password,
      heading = heading,
      visible = visible,
      enabled = enabled,
      scrollable = scrollable,
      canScrollForward = forward,
      bounds = bounds,
      children = children(),
    )
  }
}

private fun tree(build: TreeBuilder.() -> UiNode): UiNode = TreeBuilder().build()

private fun read(root: UiNode?, context: ScreenContext = ScreenContext(appLabel = "Test"), budget: Int = 800) =
  ScreenSerializer.read(root, SCREEN, context, budget)

private fun lines(read: ScreenRead) = read.elements.map { it.line() }

class ScreenSerializerTest {
  @Test
  fun buttonsLinksInputsAndTextGetTheirRolesAndStates() {
    val root = tree {
      node(className = "android.widget.FrameLayout") {
        listOf(
          node(className = "android.widget.TextView", text = "Munich", heading = true),
          node(className = "android.widget.TextView", text = "Munich is the capital of Bavaria."),
          node(text = "Bavaria", role = "link", clickable = true),
          node(className = "android.widget.EditText", hint = "Search", text = "munich", editable = true),
          node(className = "android.widget.EditText", hint = "Password", text = "hunter2", editable = true, password = true),
          node(className = "android.widget.CheckBox", text = "Remember me", checkable = true, checked = true),
          node(className = "android.widget.Button", text = "Sign in", clickable = true, enabled = false),
        )
      }
    }
    assertEquals(
      listOf(
        "[1] heading \"Munich\"",
        "[2] text \"Munich is the capital of Bavaria.\"",
        "[3] link \"Bavaria\"",
        "[4] input \"Search\" value=\"munich\"",
        "[5] input \"Password\" password",
        "[6] checkbox \"Remember me\" checked",
        "[7] button \"Sign in\" disabled",
      ),
      lines(read(root)),
    )
  }

  @Test
  fun passwordValuesNeverAppear() {
    val root = tree {
      node(className = "android.widget.EditText", text = "hunter2", editable = true, password = true)
    }
    val result = read(root)
    assertFalse(result.text.contains("hunter2"))
    assertFalse(ScreenSerializer.details(root, SCREEN).contains("hunter2"))
  }

  @Test
  fun invisibleAndOffScreenNodesAreSkippedWithTheirChildren() {
    val root = tree {
      node {
        listOf(
          node(text = "Shown"),
          node(text = "Hidden", visible = false) { listOf(node(text = "Hidden child")) },
          node(text = "Off screen", bounds = Bounds(0, 3000, 100, 3100)),
          node(text = "Zero size", bounds = Bounds(10, 10, 10, 10)),
        )
      }
    }
    assertEquals(listOf("[1] text \"Shown\""), lines(read(root)))
  }

  @Test
  fun buttonWithoutLabelTakesItsChildrenTextAndTheyAreNotRepeated() {
    val root = tree {
      node(clickable = true) {
        listOf(node(text = "Open"), node(text = "settings"))
      }
    }
    assertEquals(listOf("[1] button \"Open settings\""), lines(read(root)))
  }

  @Test
  fun largeClickableContainerKeepsItsTextLines() {
    val paragraph = "A long paragraph of article text that goes on and on. ".repeat(3)
    val root = tree {
      node(clickable = true) {
        listOf(node(text = paragraph), node(text = "Second paragraph"))
      }
    }
    val result = read(root)
    // The container is labelled with the start of its text; the rest stays as its own lines.
    assertEquals(listOf("button", "text"), result.elements.map { it.role })
    assertTrue(result.text.contains("Second paragraph"))
  }

  @Test
  fun repeatedTextIsListedOnce() {
    val root = tree {
      node {
        listOf(
          node(text = "Results"),
          node(text = "Results"),
          node(className = "android.widget.Button", text = "Share", clickable = true),
          node(className = "android.widget.Button", text = "Share", clickable = true),
          node(text = "x"),
          node(className = "android.widget.Button", text = "Share", clickable = true),
        )
      }
    }
    assertEquals(
      listOf("[1] text \"Results\"", "[2] button \"Share\"", "[3] text \"x\"", "[4] button \"Share\""),
      lines(read(root)),
    )
  }

  @Test
  fun labelsAreCleanedAndTruncated() {
    val root = tree {
      node {
        listOf(
          node(text = "  two\n\nlines \"quoted\"  "),
          node(text = "y".repeat(300)),
        )
      }
    }
    val result = read(root)
    assertEquals("[1] text \"two lines 'quoted'\"", result.elements[0].line())
    assertEquals(ScreenSerializer.MAX_LABEL_CHARS, result.elements[1].label.length)
    assertTrue(result.elements[1].label.endsWith("…"))
  }

  @Test
  fun budgetCutsTheListAndSaysHowManyAreMissing() {
    val root = tree { node { (1..300).map { node(text = "Line number $it with some words") } } }
    val result = read(root, budget = 200)
    assertTrue(result.truncated)
    assertTrue(result.estimatedTokens <= 200)
    assertEquals(300, result.totalElements)
    assertTrue(result.text.contains("(${300 - result.elements.size} more elements not shown"))
  }

  @Test
  fun typicalViewportFitsTheDefaultBudget() {
    val root = tree { node { (1..40).map { node(text = "Search result $it: a short title") } } }
    val result = read(root)
    assertFalse(result.truncated)
    assertTrue(result.estimatedTokens <= ScreenSerializer.DEFAULT_TOKEN_BUDGET)
  }

  @Test
  fun headerShowsAppUrlPrivateModeKeyboardAndScroll() {
    val root = tree { node(scrollable = true, forward = true) { listOf(node(text = "Body")) } }
    val context =
      ScreenContext(
        appLabel = "Firefox",
        url = "en.wikipedia.org/wiki/Munich",
        privateTab = true,
        keyboardOpen = true,
      )
    val text = read(root, context).text
    assertTrue(text.startsWith("App: Firefox\nURL: en.wikipedia.org/wiki/Munich (private tab)\n"))
    assertTrue(text.contains("Keyboard: open\n"))
    assertTrue(text.contains("Scroll: more below\n"))
  }

  @Test
  fun emptyOrHiddenScreenSaysSoAndHasNoElements() {
    for (root in listOf(null, tree { node(visible = false) { listOf(node(text = "secret")) } })) {
      val result = read(root)
      assertEquals(ScreenStatus.EMPTY, result.status)
      assertTrue(result.elements.isEmpty())
      assertTrue(result.text.contains("empty or its content is hidden"))
      assertFalse(result.text.contains("secret"))
    }
  }

  @Test
  fun tokenEstimateCountsLatinByFoursAndOtherScriptsPerCharacter() {
    assertEquals(0, ScreenSerializer.estimateTokens(""))
    assertEquals(1, ScreenSerializer.estimateTokens("abcd"))
    assertEquals(2, ScreenSerializer.estimateTokens("abcde"))
    assertEquals(3, ScreenSerializer.estimateTokens("東京都"))
    assertEquals(2, ScreenSerializer.estimateTokens("Müncheñ"))
  }
}

class IdMappingTest {
  private val root = tree {
    node {
      listOf(
        node(text = "Title"), // key 1
        node(className = "android.widget.Button", text = "OK", clickable = true), // key 2
        node(visible = false, text = "invisible"), // key 3
        node(clickable = true) { listOf(node(text = "Cancel")) }, // keys 4, 5
      )
    }
  }

  @Test
  fun idsAreSequentialAndPointAtTheRightNodes() {
    val result = read(root)
    assertEquals(listOf(1, 2, 3), result.elements.map { it.id })
    assertEquals(listOf(1, 2, 4), result.elements.map { it.nodeKey })
    assertEquals("button \"Cancel\"", result.element(3)?.signature)
  }

  @Test
  fun unknownIdsResolveToNothing() {
    val result = read(root)
    assertNull(result.element(0))
    assertNull(result.element(4))
    assertNull(result.element(-1))
  }

  @Test
  fun theSameScreenAlwaysGetsTheSameIds() {
    assertEquals(read(root).elements, read(root).elements)
  }

  @Test
  fun elementsCutByTheBudgetHaveNoId() {
    val big = tree { node { (1..200).map { node(className = "android.widget.Button", text = "Button $it", clickable = true) } } }
    val result = read(big, budget = 150)
    val lastShown = result.elements.size
    assertTrue(lastShown in 1 until 200)
    assertNull(result.element(lastShown + 1))
  }

  @Test
  fun signatureDetectsThatAnIdNowMeansSomethingElse() {
    val before = read(root)
    val changed = tree {
      node {
        listOf(
          node(text = "Title"),
          node(className = "android.widget.Button", text = "Delete", clickable = true),
        )
      }
    }
    val after = read(changed)
    assertFalse(before.element(2)?.signature == after.element(2)?.signature)
  }
}
