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

import android.content.Context
import android.net.Uri
import coil.Coil
import coil.ImageLoader
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import com.halilibo.richtext.markdown.node.AstImage
import com.halilibo.richtext.markdown.node.AstLink
import com.halilibo.richtext.markdown.node.AstNode
import com.halilibo.richtext.markdown.node.AstNodeLinks
import com.halilibo.richtext.markdown.node.AstText
import java.io.IOException

/**
 * Privacy contract (docs/NETWORK.md): text the app renders never makes it fetch an image.
 *
 * Model replies, user messages and model descriptions are Markdown. A Markdown image such as
 * `![alt](https://host/x.png)` would otherwise be downloaded as soon as it is rendered, which a
 * model (or a page it read) could use to make the app contact any host. Images are turned into
 * ordinary links showing their alt text; tapping one opens it in the browser.
 */
fun replaceImagesWithLinks(root: AstNode): AstNode {
  val pending = ArrayDeque<AstNode>()
  pending.add(root)
  while (pending.isNotEmpty()) {
    val node = pending.removeLast()
    var child = node.links.firstChild
    while (child != null) {
      val next = child.links.next
      val type = child.type
      pending.add(if (type is AstImage) imageToLink(child, type) else child)
      child = next
    }
  }
  return root
}

/** Label used when an image has no alt text. */
const val IMAGE_LINK_FALLBACK_LABEL = "image"

private fun imageToLink(image: AstNode, type: AstImage): AstNode {
  val links = image.links
  val link = AstNode(AstLink(destination = type.destination, title = type.title), links)
  links.parent?.links?.let {
    if (it.firstChild === image) it.firstChild = link
    if (it.lastChild === image) it.lastChild = link
  }
  links.previous?.links?.next = link
  links.next?.links?.previous = link
  var child = links.firstChild
  while (child != null) {
    child.links.parent = link
    child = child.links.next
  }
  if (links.firstChild == null) {
    val label = AstNode(AstText(IMAGE_LINK_FALLBACK_LABEL), AstNodeLinks(parent = link))
    links.firstChild = label
    links.lastChild = label
  }
  return link
}

/**
 * Installs an app-wide Coil image loader that refuses http/https images, as a second line of
 * defence behind [replaceImagesWithLinks]. Local images (files, content URIs, video frames) still
 * load normally.
 */
fun installOfflineImageLoader(context: Context) {
  val appContext = context.applicationContext
  Coil.setImageLoader {
    ImageLoader.Builder(appContext).components { add(RefuseRemoteImages.Factory()) }.build()
  }
}

private class RefuseRemoteImages : Fetcher {
  override suspend fun fetch(): FetchResult = throw IOException("Remote images are disabled")

  class Factory : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
      if (data.scheme.equals("http", true) || data.scheme.equals("https", true)) RefuseRemoteImages()
      else null
  }
}
