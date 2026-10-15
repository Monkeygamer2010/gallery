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

package com.google.ai.edge.gallery.phoneagent.images

import com.halilibo.richtext.commonmark.CommonmarkAstNodeParser
import com.halilibo.richtext.markdown.node.AstImage
import com.halilibo.richtext.markdown.node.AstLink
import com.halilibo.richtext.markdown.node.AstNode
import com.halilibo.richtext.markdown.node.AstText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRemoteImagesTest {
  private fun parse(markdown: String) = replaceImagesWithLinks(CommonmarkAstNodeParser().parse(markdown))

  private fun allNodes(root: AstNode): List<AstNode> {
    val out = mutableListOf<AstNode>()
    fun walk(node: AstNode) {
      out += node
      var child = node.links.firstChild
      while (child != null) {
        assertSame("child must point back to its parent", node, child.links.parent)
        walk(child)
        child = child.links.next
      }
    }
    walk(root)
    return out
  }

  private fun textUnder(node: AstNode): String =
    allNodes(node).mapNotNull { (it.type as? AstText)?.literal }.joinToString("")

  @Test
  fun inlineAndReferenceImagesBecomeLinks() {
    val root =
      parse(
        """
        Here ![a tracking pixel](https://evil.example/p.png "t") and text.

        ![][ref] and ![second](https://host.example/2.png)

        [ref]: https://ref.example/r.png
        """
          .trimIndent()
      )
    val nodes = allNodes(root)
    assertTrue("no image node may remain", nodes.none { it.type is AstImage })
    val links = nodes.filter { it.type is AstLink }
    assertEquals(
      listOf("https://evil.example/p.png", "https://ref.example/r.png", "https://host.example/2.png"),
      links.map { (it.type as AstLink).destination },
    )
    assertEquals("a tracking pixel", textUnder(links[0]))
    assertEquals(IMAGE_LINK_FALLBACK_LABEL, textUnder(links[1]))
    assertEquals("second", textUnder(links[2]))
  }

  @Test
  fun siblingsAndOrderArePreserved() {
    val root = parse("before ![x](https://h.example/x.png) after")
    val paragraph = root.links.firstChild!!
    val texts = mutableListOf<String>()
    var child = paragraph.links.firstChild
    while (child != null) {
      texts += (child.type as? AstText)?.literal ?: (child.type::class.simpleName ?: "")
      val next = child.links.next
      if (next != null) assertSame(child, next.links.previous)
      child = next
    }
    assertEquals(listOf("before ", "AstLink", " after"), texts)
  }

  @Test
  fun textWithoutImagesIsUnchanged() {
    val root = parse("**bold** and [a link](https://example.com)")
    val nodes = allNodes(root)
    assertEquals(1, nodes.count { it.type is AstLink })
    assertTrue(nodes.none { it.type is AstImage })
  }
}
