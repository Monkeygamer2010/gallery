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

/**
 * Checks a finish against what the run actually saw:
 * - every evidence quote appears on a screen of this run (ignoring case, spacing, quote styles
 *   and the screen's "[12]" ids);
 * - every number in the answer appears in the quotes;
 * - every name of two or more capitalized words in the answer ("Umberto Eco") that isn't in the
 *   task appears in the quotes.
 *
 * It catches answers from the model's memory (Phase 2.1 task 4: "Umberto Eco" was on no screen)
 * and garbled numbers (task 5: "19191" for "20 February 1991"). It cannot tell whether a quote
 * really answers the task; the owner judges that.
 */
object EvidenceCheck {
  /** [steps] are the steps whose screens hold the quotes. */
  data class Verdict(val ok: Boolean, val problem: String? = null, val steps: List<Int> = emptyList()) {
    /** One line for the run log and the result. */
    fun line(): String =
      when {
        ok && steps.size == 1 -> "Evidence: verified (on the screen of step ${steps[0]})"
        ok && steps.isNotEmpty() -> "Evidence: verified (on the screens of steps ${steps.joinToString()})"
        ok -> "Evidence: none needed (no number or name in the answer)"
        else -> "Evidence: NOT verified ($problem)"
      }
  }

  private val NUMBER = Regex("\\d[\\d,.]*")
  private val ADDRESS = Regex("\\S*(://|www\\.|\\.[a-z]{2,}/)\\S*", RegexOption.IGNORE_CASE)
  private val WORD = Regex("\\p{L}[\\p{L}'’-]*")
  private val ID_MARKER = Regex("\\[\\d+]")
  private val SPACES = Regex("[\\s\\p{Z}]+")
  private val INVISIBLE = Regex("[\\u200B-\\u200D\\uFEFF\\u00AD]")
  private val QUOTE_SPLIT = Regex("\\||\\n|\\.\\.\\.|…")
  private const val MIN_QUOTE_CHARS = 3

  /** Capitalized words that are not part of a name. */
  private val NOT_NAMES =
    setOf(
      "the", "a", "an", "it", "its", "this", "that", "these", "those", "in", "on", "at", "by", "for", "from",
      "according", "source", "answer", "yes", "no", "he", "she", "they", "we", "you", "and", "or", "but", "as",
      "of", "to", "was", "is", "are", "were", "i", "wikipedia", "duckduckgo", "firefox", "google", "english",
    )

  fun check(summary: String, evidence: String, task: String, screens: List<Pair<Int, String>>): Verdict {
    val quotes = quotesIn(evidence)
    val numbers = numbersIn(summary)
    val names = namesIn(summary, task)
    if (quotes.isEmpty()) {
      return if (numbers.isEmpty() && names.isEmpty()) {
        Verdict(ok = true)
      } else {
        Verdict(false, "no evidence: copy the text that shows the answer from the screen into evidence")
      }
    }
    val normalizedScreens = screens.map { (step, text) -> step to normalize(text) }
    val steps = sortedSetOf<Int>()
    for (quote in quotes) {
      val step =
        normalizedScreens.firstOrNull { (_, text) -> text.contains(quote) }?.first
          ?: return Verdict(false, "the evidence \"${oneLine(quote, 80)}\" is not on any screen you saw; copy it exactly")
      steps += step
    }
    val quoteText = quotes.joinToString(" ")
    val quoteNumbers = numbersIn(quoteText).toSet()
    numbers.firstOrNull { it !in quoteNumbers }?.let {
      return Verdict(false, "the number $it in your answer is not in your evidence")
    }
    names.firstOrNull { name -> name.split(' ').any { !quoteText.contains(it) } }?.let {
      return Verdict(false, "\"$it\" in your answer is not in your evidence")
    }
    return Verdict(ok = true, steps = steps.toList())
  }

  /** The quote pieces: split at "|", line breaks and ellipses, normalized, without wrapping quotes. */
  internal fun quotesIn(evidence: String): List<String> =
    evidence
      .split(QUOTE_SPLIT)
      .map { normalize(it).trim('\'', ' ', ',', ';') }
      .filter { it.length >= MIN_QUOTE_CHARS }

  /** Lower case, one plain space, plain quotes and dashes, no "[12]" ids. */
  internal fun normalize(text: String): String =
    text
      .replace(ID_MARKER, "")
      .replace(INVISIBLE, "")
      .replace('’', '\'')
      .replace('‘', '\'')
      .replace('“', '\'')
      .replace('”', '\'')
      .replace('"', '\'')
      .replace('–', '-')
      .replace('—', '-')
      .replace("…", "...")
      .replace(SPACES, " ")
      .trim()
      .lowercase()

  /**
   * Numbers without thousands separators or a trailing full stop ("1,604,384." -> "1604384"),
   * leaving out web addresses the answer cites.
   */
  internal fun numbersIn(text: String): List<String> =
    NUMBER.findAll(text.replace(ADDRESS, " "))
      .map { it.value.trimEnd('.', ',').replace(",", "") }
      .filter { it.isNotEmpty() }
      .toList()

  /**
   * Names the answer adds: runs of two or more capitalized words ("Umberto Eco"), without common
   * words and words of the [task]. Lower-cased, words separated by one space.
   */
  internal fun namesIn(summary: String, task: String): List<String> {
    val taskWords = WORD.findAll(task.lowercase()).map { it.value }.toSet()
    val names = mutableListOf<String>()
    val run = mutableListOf<String>()
    fun close() {
      if (run.size >= 2) names += run.joinToString(" ")
      run.clear()
    }
    val text = summary.replace(ADDRESS, " ")
    var last = -1
    for (match in WORD.findAll(text)) {
      val word = match.value
      val lower = word.lowercase().removeSuffix("'s").removeSuffix("’s")
      // Only spaces between the words of one name ("Eco, Calvino" is two names).
      val joined = last >= 0 && text.substring(last, match.range.first).isBlank()
      if (!joined) close()
      if (word.first().isUpperCase() && lower !in NOT_NAMES && lower !in taskWords) run += lower else close()
      last = match.range.last + 1
    }
    close()
    return names.distinct()
  }
}
