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

/**
 * Reconstructions of the screens logged in the Phase 2.1 retest: DuckDuckGo's results for a
 * search, the top of a Wikipedia article (mobile site) with and without the fundraising banner,
 * and the same article scrolled to its infobox. They are shaped the way GeckoView exposes pages:
 * landmarks carry role descriptions ("banner", "navigation", "main"), a paragraph's text leaves
 * and links are separate children, links carry their name and repeat it in a text leaf, citations
 * are links named "[1]", DOM ids arrive as view ids, and a content container is clickable without
 * a name. Built by hand from the structure of those pages; the wording is paraphrased and the
 * numbers are illustrative, not a recording.
 */
object PageFixtures {
  const val WIKI_URL = "en.m.wikipedia.org/wiki/University_of_Bologna"
  const val DDG_URL = "duckduckgo.com/?q=University+of+Bologna+founded&ia=web"
  const val LEAD_FACT = "Teaching began around 1088"
  private const val PAGE_TOP = 220

  /** The top of the article, optionally under the fundraising banner. */
  fun wikipediaTop(banner: Boolean): FakeNode = firefox(WIKI_URL, wikipediaPage(banner, scrolledBy = 0))

  /** The article scrolled down to its infobox. */
  fun wikipediaInfobox(): FakeNode = firefox(WIKI_URL, wikipediaPage(banner = false, scrolledBy = 1_300))

  /** The top of DuckDuckGo's results for "University of Bologna founded". */
  fun duckDuckGoResults(): FakeNode = firefox(DDG_URL, duckDuckGoPage())

  private fun firefox(url: String, document: FakeNode): FakeNode =
    FakeNode(
      className = "android.widget.FrameLayout",
      bounds = GeckoFixtures.SCREEN,
      children =
        listOf(
          GeckoFixtures.toolbar(url = url),
          FakeNode(
            className = ScreenSerializer.WEB_VIEW_CLASS,
            bounds = Bounds(0, PAGE_TOP, 1080, 2340),
            scrollActions = true,
            children = listOf(document),
          ),
        ),
    )

  // ---- Wikipedia (mobile skin) ----

  private fun wikipediaPage(banner: Boolean, scrolledBy: Int): FakeNode {
    val l = Layout(scrolledBy)
    val parts = mutableListOf<FakeNode>()
    if (banner) parts += fundraisingBanner(l)
    val header = l.block(150)
    val h = header.columns(4)
    parts +=
      group(
        role = "banner",
        children =
          listOf(
            button("Open main menu", h[0], id = "mw-mf-main-menu-button"),
            link("Wikipedia", h[1], id = "mw-mf-logo"),
            input("Search Wikipedia", h[2], id = "searchInput"),
            button("Search", h[2], id = "searchIcon"),
            button("Personal tools", h[3]),
          ),
      )
    val title = heading("University of Bologna", l.block(130), level = 1, id = "firstHeading")
    val tabRow = l.block(100).columns(2)
    val tabs =
      group(
        role = "navigation",
        children =
          listOf(
            group(
              role = "tab list",
              children = listOf(tab("Article", tabRow[0], selected = true, id = "ca-nstab-main"), tab("Talk", tabRow[1], id = "ca-talk")),
            )
          ),
      )
    val actionRow = l.block(110).columns(4)
    val actions =
      group(
        role = "navigation",
        id = "page-actions",
        children =
          listOf(
            button("Language", actionRow[0], id = "language-selector"),
            link("Watch", actionRow[1], id = "ca-watch"),
            link("Edit", actionRow[2], id = "ca-edit"),
            button("More", actionRow[3], id = "page-actions-overflow"),
          ),
      )
    val lead =
      para(
        l.block(660),
        "The ",
        "University of Bologna",
        " (",
        lk("Italian"),
        ": ",
        "Alma Mater Studiorum – Università di Bologna",
        ", UNIBO) is a ",
        lk("public research university"),
        " in ",
        lk("Bologna"),
        ", ",
        lk("Italy"),
        ". $LEAD_FACT, with the university becoming organised as ",
        lk("guilds of students"),
        " (",
        "universitates scholarium",
        ") by the late 12th century.",
        cite(1),
        " It is the ",
        lk("oldest university in continuous operation"),
        " in the world, and the first degree-awarding institution of higher learning.",
        cite(2),
        cite(3),
        " At its foundation, the term ",
        "universitas",
        " was coined.",
        cite(4),
      )
    val caption = group(children = listOf(leaf("University of Bologna", l.block(60)), leaf("Università di Bologna", l.block(52))))
    val sealBox = l.block(500)
    val seal = link("Seal of the University of Bologna", sealBox, child = image("Seal of the University of Bologna", sealBox))
    val rows =
      listOf(
        row(l, "Latin", "Universitas Bononiensis"),
        row(l, "Motto", "Alma mater studiorum"),
        row(l, "Motto in English", "Nourishing mother of the studies"),
        row(l, "Type", lk("Public"), " ", lk("research university")),
        row(l, "Established", "c. 1088; 937 years ago"),
        row(l, "Budget", "€1.0 billion (2023)", cite(5)),
        row(l, "Rector", lk("Giovanni Molari")),
        row(l, "Academic staff", "3,066 (2023)", cite(6)),
        row(l, "Administrative staff", "3,082 (2023)", cite(6)),
        row(l, "Students", "90,291 (2023)", cite(7)),
        row(l, "Undergraduates", "50,413 (2023)"),
        row(l, "Postgraduates", "34,010 (2023)"),
        row(l, "Doctoral students", "1,604 (2023)"),
        row(l, "Location", lk("Bologna"), ", ", lk("Italy")),
        row(l, "Campus", lk("Urban")),
        row(l, "Colours", "Red"),
        row(l, "Affiliations", lk("Coimbra Group"), ", ", lk("EUA"), ", ", lk("Utrecht Network")),
        row(l, "Website", lk("www.unibo.it")),
      )
    val infobox = group(children = listOf(caption, seal) + rows)
    val second =
      para(
        l.block(360),
        "The university has about 90,000 students in 32 departments and five campuses: ",
        lk("Bologna"),
        ", ",
        lk("Cesena"),
        ", ",
        lk("Forlì"),
        ", ",
        lk("Ravenna"),
        " and ",
        lk("Rimini"),
        ". It also has a branch in ",
        lk("Buenos Aires"),
        ".",
        cite(8),
      )
    val history = heading("History", l.block(120), level = 2, id = "History")
    val third = para(l.block(600), "The date of its founding is uncertain, but believed by most accounts to have been 1088.", cite(9))
    // MobileFrontend attaches click handlers to the content: a clickable container without a name.
    val content =
      FakeNode(
        viewId = "mw-content-text",
        clickable = true,
        bounds = union(listOf(lead, infobox, second, history, third)),
        children =
          listOf(group(id = "mf-section-0", children = listOf(lead, infobox, second)), group(id = "mf-section-1", children = listOf(history, third))),
      )
    parts += group(role = "main", id = "content", children = listOf(title, tabs, actions, content))
    return group(children = parts)
  }

  private fun fundraisingBanner(l: Layout): FakeNode {
    val close = button("Close", l.block(90))
    val title = heading("Please don't skip this 1-minute read.", l.block(140), level = 2)
    val first =
      para(
        l.block(420),
        "Dear reader in Italy, ",
        "we'll get straight to the point: ",
        "this Monday we ask you to support Wikipedia's independence. ",
        "Most readers don't give. ",
        "If you donate just €3, or whatever you can this Monday, Wikipedia could keep thriving for years.",
      )
    val second = para(l.block(240), "We ask you, humbly: please don't scroll away. If Wikipedia has given you useful knowledge this year, please give back.")
    val amountRow = l.block(110).columns(6)
    val amounts =
      group(children = listOf("€3", "€5", "€10", "€20", "€30", "Other amount").mapIndexed { i, a -> button(a, amountRow[i]) })
    val donate = button("Donate now", l.block(120))
    val later = l.block(80).columns(2)
    val links = group(children = listOf(link("Maybe later", later[0]), link("I already donated", later[1])))
    return group(
      id = "siteNotice",
      children = listOf(group(id = "centralNotice", children = listOf(group(children = listOf(close, title, first, second, amounts, donate, links))))),
    )
  }

  private fun row(l: Layout, header: String, vararg value: Any): FakeNode {
    val b = l.block(80)
    val cells = b.columns(2)
    return group(children = listOf(group(children = listOf(leaf(header, cells[0]))), para(cells[1], *value)))
  }

  // ---- DuckDuckGo ----

  private fun duckDuckGoPage(): FakeNode {
    val l = Layout(0)
    val top = l.block(150).columns(4)
    val header =
      group(
        role = "banner",
        children =
          listOf(
            link("DuckDuckGo", top[0]),
            group(
              role = "search",
              children =
                listOf(
                  input("Search without being tracked", top[1], value = "University of Bologna founded", id = "search_form_input"),
                  button("Clear search input", top[2], id = "search_form_input_clear"),
                  button("Search", top[2], id = "search_button"),
                ),
            ),
            button("Open menu", top[3]),
          ),
      )
    val tabRow = l.block(100).columns(6)
    val filterRow = l.block(100).columns(3)
    val nav =
      group(
        role = "navigation",
        children =
          listOf("All", "Images", "Videos", "News", "Maps", "Shopping").mapIndexed { i, t -> link(t, tabRow[i]) } +
            listOf(button("All regions", filterRow[0]), button("Safe search: moderate", filterRow[1]), button("Any time", filterRow[2])),
      )
    val about =
      group(
        children =
          listOf(
            heading("University of Bologna", l.block(100), level = 2),
            image("University of Bologna", l.block(200)),
            para(
              l.block(300),
              "The University of Bologna is a public research university in Bologna, Italy. $LEAD_FACT, with the university " +
                "becoming organised as guilds of students by the late 12th century. ",
              "More at ",
              lk("Wikipedia"),
            ),
          )
      )
    val results =
      listOf(
        result(
          l,
          "Wikipedia",
          "https://en.wikipedia.org › wiki › University_of_Bologna",
          "University of Bologna - Wikipedia",
          "The ", "University", " ", "of", " ", "Bologna", " (Italian: Università di ", "Bologna", ", UNIBO) is a public research ",
          "university", " in ", "Bologna", ", Italy. $LEAD_FACT, with the ", "university",
          " becoming organised as guilds of students by the late 12th century. It is the oldest ", "university",
          " in continuous operation in the world.",
        ),
        result(
          l,
          "unibo.it",
          "https://www.unibo.it › en › university › who-we-are › our-history",
          "Our History — University of Bologna",
          "The ", "University", " ", "of", " ", "Bologna", " is considered the oldest ", "university",
          " in the Western world. Its ", "foundation", " date of 1088 was set by a committee of historians.",
        ),
        result(
          l,
          "Britannica",
          "https://www.britannica.com › topic › University-of-Bologna",
          "University of Bologna | History, Ranking, & Facts | Britannica",
          "University", " ", "of", " ", "Bologna", ", Italian Università di ", "Bologna", ", ", "founded",
          " in the 11th century; it is the oldest ", "university", " in Europe.",
        ),
        result(
          l,
          "Top Universities",
          "https://www.topuniversities.com › universities › university-bologna",
          "Alma Mater Studiorum - University of Bologna",
          "The ", "University", " ", "of", " ", "Bologna", " is the oldest ", "university", " in the Western world, ",
          "founded", " in 1088.",
        ),
      )
    val main = group(role = "main", children = listOf(about, group(children = results)))
    return group(children = listOf(header, nav, main))
  }

  private fun result(l: Layout, source: String, address: String, title: String, vararg snippet: String): FakeNode {
    val sourceRow = l.block(70).columns(3)
    val titleBox = l.block(110)
    return group(
      children =
        listOf(
          link(source, sourceRow[0]),
          link(address, sourceRow[1]),
          button("Result options", sourceRow[2]),
          heading(title, titleBox, level = 2, child = link(title, titleBox)),
          para(l.block(280), *snippet),
        )
    )
  }

  // ---- Builders (GeckoView shapes) ----

  /** Lays page blocks out top to bottom; [scrolledBy] moves everything up, as after scrolling. */
  private class Layout(private val scrolledBy: Int) {
    private var y = PAGE_TOP + 4

    fun block(height: Int): Bounds {
      val b = Bounds(0, y - scrolledBy, 1080, y + height - scrolledBy)
      y += height + 8
      return b
    }
  }

  private fun Bounds.columns(n: Int): List<Bounds> {
    val w = (right - left) / n
    return (0 until n).map { Bounds(left + it * w, top, left + (it + 1) * w, bottom) }
  }

  private class Link(val label: String)

  private fun lk(label: String) = Link(label)

  private fun cite(n: Int) = Link("[$n]")

  private fun leaf(text: String, b: Bounds) = FakeNode(text = text, bounds = b)

  private fun link(label: String, b: Bounds, id: String? = null, child: FakeNode = leaf(label, b)) =
    FakeNode(desc = label, role = "link", clickable = true, viewId = id, bounds = b, children = listOf(child))

  private fun button(label: String, b: Bounds, id: String? = null) =
    FakeNode(className = "android.widget.Button", desc = label, role = "button", clickable = true, viewId = id, bounds = b)

  private fun tab(label: String, b: Bounds, selected: Boolean = false, id: String? = null) =
    FakeNode(desc = label, role = "tab", clickable = true, selected = selected, viewId = id, bounds = b, children = listOf(leaf(label, b)))

  private fun input(hint: String, b: Bounds, value: String = "", id: String? = null) =
    FakeNode(className = "android.widget.EditText", hint = hint, text = value, editable = true, clickable = true, viewId = id, bounds = b)

  private fun image(alt: String, b: Bounds) = FakeNode(className = "android.widget.Image", desc = alt, role = "graphic", bounds = b)

  private fun heading(text: String, b: Bounds, level: Int, id: String? = null, child: FakeNode = leaf(text, b)) =
    FakeNode(desc = text, heading = true, role = "heading level $level", viewId = id, bounds = b, children = listOf(child))

  /** A paragraph: text leaves (strings) and links are separate children, all in its box. */
  private fun para(b: Bounds, vararg parts: Any): FakeNode =
    FakeNode(
      bounds = b,
      children =
        parts.map { part ->
          when (part) {
            is String -> leaf(part, b)
            is Link -> link(part.label, b)
            is FakeNode -> part
            else -> error("unknown part $part")
          }
        },
    )

  private fun group(role: String? = null, id: String? = null, children: List<FakeNode>): FakeNode =
    FakeNode(role = role, viewId = id, bounds = union(children), children = children)

  private fun union(nodes: List<FakeNode>): Bounds {
    val boxes = nodes.map { it.bounds }.filterNot { it.isEmpty }
    if (boxes.isEmpty()) return Bounds(0, 0, 0, 0)
    return Bounds(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
  }
}
