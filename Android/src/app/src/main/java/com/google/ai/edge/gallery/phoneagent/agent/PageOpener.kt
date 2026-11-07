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

package com.google.ai.edge.gallery.phoneagent.agent

import com.google.ai.edge.gallery.phoneagent.a11y.ActionOutcome

/** How an open_url went: what the model is told, and the details for the run log and the spike. */
class OpenReport(
  /** A web page with text is on screen in a private tab. */
  val loaded: Boolean,
  val forModel: String,
  val detail: String,
  /** Firefox opened the page outside private browsing: the run must stop. */
  val notPrivate: Boolean = false,
)

/**
 * Opens an address the way open_url does: a private-tab intent to Firefox, then up to 10 s for an
 * actual web page. When Firefox shows a blank private tab instead (it ignored the address), the
 * address is typed into its focused address bar and Enter pressed, the path that worked in every
 * Phase 2.1 run. A blank tab is never passed off as the page.
 */
object PageOpener {
  suspend fun <T> open(
    url: String,
    before: T,
    look: suspend () -> T,
    view: (T) -> Observation,
    send: () -> ActionOutcome,
    typeAddress: suspend (T) -> ActionOutcome,
    ticker: Ticker = Ticker.Real,
  ): Pair<OpenReport, T> {
    val start = ticker.now()
    val sent = send()
    if (!sent.ok) return OpenReport(false, "It did not work: ${sent.detail}.", sent.detail) to before
    var after = ScreenWaiter.settle(look, view, before, ScreenWaiter.OPEN_PAGE, ticker)
    var typed: ActionOutcome? = null
    val first = view(after)
    if (!loaded(first) && first.facts.isFirefox && first.facts.privateTab == true && first.read != null) {
      typed = typeAddress(after)
      if (typed.ok) after = ScreenWaiter.settle(look, view, after, ScreenWaiter.OPEN_PAGE, ticker)
    }
    val v = view(after)
    val detail = buildString {
      append(sent.detail)
      typed?.let { append("; then typed into the address bar: ").append(it.detail) }
      append("; address now: ").append(v.url ?: "not found")
      append("; private: ").append(if (v.facts.privateTab == true) "yes" else "no")
      append("; page text: ").append(v.pageLines).append(" lines")
      append("; tabs: ").append(view(before).browser?.tabCount ?: "?").append(" -> ").append(v.browser?.tabCount ?: "?")
      append("; ").append(ticker.now() - start).append(" ms")
    }
    val report =
      when {
        v.facts.isFirefox && v.facts.privateTab == false ->
          OpenReport(false, "Firefox opened the page outside private browsing, so it is not read.", detail, notPrivate = true)
        loaded(v) ->
          OpenReport(
            true,
            "Opened ${v.url ?: "the page"} in a private tab. The page is shown below: read it.",
            detail,
          )
        else ->
          OpenReport(
            false,
            "Firefox did not load the page: after ${ScreenWaiter.PAGE_LOAD_MS / 1000} s it shows " +
              "${describe(v)}. Type the address into the address bar with submit set to true.",
            detail,
          )
      }
    return report to after
  }

  private fun loaded(v: Observation): Boolean = v.pageArea && v.pageLines > 0

  private fun describe(v: Observation): String =
    when {
      v.read == null -> "a screen that is not read"
      !v.pageArea -> "no web page (a blank private tab)"
      else -> "a page without text"
    }
}
