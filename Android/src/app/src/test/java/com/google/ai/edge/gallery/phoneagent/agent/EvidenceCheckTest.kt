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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceCheckTest {
  private val task = "When was the University of Bologna founded?"
  private val screens =
    listOf(
      1 to "App: Firefox\n[2] input \"Search or enter address\" focused",
      3 to
        "App: Firefox\nURL: en.wikipedia.org/wiki/University_of_Bologna (private tab)\n# University of Bologna\n" +
          "The University of Bologna is a public research university in [12]Bologna, Italy. Teaching began around 1088.",
      5 to "Notable alumni include Pier Paolo Pasolini and Giosuè Carducci.",
    )

  private fun check(summary: String, evidence: String, task: String = this.task) =
    EvidenceCheck.check(summary, evidence, task, screens)

  @Test
  fun aQuoteFromAScreenWithTheAnswersNumberIsVerified() {
    val v = check("The University of Bologna was founded in 1088 (en.wikipedia.org).", "Teaching began around 1088.")
    assertTrue(v.problem, v.ok)
    assertEquals(listOf(3), v.steps)
    assertEquals("Evidence: verified (on the screen of step 3)", v.line())
  }

  @Test
  fun linkIdsQuoteMarksAndSpacingDoNotMatter() {
    val v = check("It is in Bologna.", "“public research university in Bologna,  Italy”")
    assertTrue(v.problem, v.ok)
  }

  @Test
  fun aQuoteThatIsOnNoScreenIsRejected() {
    val v = check("Founded in 1088.", "The university was founded in 1088 by Irnerius.")
    assertFalse(v.ok)
    assertTrue(v.problem, v.problem!!.contains("is not on any screen you saw"))
    assertTrue(v.line().startsWith("Evidence: NOT verified ("))
  }

  // Phase 2.1 task 5: "19191" for "20 February 1991".
  @Test
  fun aNumberThatIsNotInTheQuoteIsRejected() {
    val v =
      EvidenceCheck.check(
        "Python was first released in 19191.",
        "Python was first released on 20 February 1991.",
        "When was Python first released?",
        listOf(4 to "Python 3.0 came out in 2008. Python was first released on 20 February 1991."),
      )
    assertFalse(v.ok)
    assertEquals("the number 19191 in your answer is not in your evidence", v.problem)
  }

  // Phase 2.1 task 4: a name from the model's memory, on no screen.
  @Test
  fun aNameThatIsNotInTheQuoteIsRejected() {
    val v =
      check(
        "A famous alumnus is Umberto Eco.",
        "Notable alumni include Pier Paolo Pasolini",
        task = "Name a famous alumnus of the University of Bologna.",
      )
    assertFalse(v.ok)
    assertTrue(v.problem, v.problem!!.contains("umberto eco"))
  }

  @Test
  fun namesFromTheQuoteOrTheTaskPass() {
    val v =
      check(
        "Pier Paolo Pasolini studied at the University of Bologna, in Italy.",
        "Notable alumni include Pier Paolo Pasolini",
        task = "Name a famous alumnus of the University of Bologna.",
      )
    assertTrue(v.problem, v.ok)
  }

  @Test
  fun aNotFoundAnswerNeedsNoEvidence() {
    val v = check("The pages I saw don't say when it was founded.", "")
    assertTrue(v.ok)
    assertEquals("Evidence: none needed (no number or name in the answer)", v.line())
  }

  @Test
  fun anAnswerWithANumberNeedsEvidence() {
    val v = check("It was founded in 1088.", "")
    assertFalse(v.ok)
    assertTrue(v.problem, v.problem!!.startsWith("no evidence"))
  }

  @Test
  fun quotesFromSeveralScreens() {
    val v = check("Founded in 1088; alumni include Giosuè Carducci.", "Teaching began around 1088 | “Giosuè Carducci”")
    assertTrue(v.problem, v.ok)
    assertEquals(listOf(3, 5), v.steps)
    assertEquals("Evidence: verified (on the screens of steps 3, 5)", v.line())
  }

  @Test
  fun anEllipsisSplitsAQuote() {
    val v = check("Founded in 1088.", "The University of Bologna is a public research university … Teaching began around 1088")
    assertTrue(v.problem, v.ok)
  }

  @Test
  fun numbersIgnoreThousandsSeparatorsAndCitedAddresses() {
    assertEquals(listOf("1604384"), EvidenceCheck.numbersIn("About 1,604,384. (https://en.wikipedia.org/wiki/2024_x)"))
    assertEquals(listOf("3.12"), EvidenceCheck.numbersIn("Python 3.12 (en.wikipedia.org)"))
  }

  @Test
  fun namesAreRunsOfCapitalizedWords() {
    assertEquals(listOf("umberto eco"), EvidenceCheck.namesIn("The answer is Umberto Eco's novel.", task))
    assertEquals(emptyList<String>(), EvidenceCheck.namesIn("It is in Italy, near Florence.", task))
    assertEquals(emptyList<String>(), EvidenceCheck.namesIn("Eco, Calvino.", task))
  }
}
