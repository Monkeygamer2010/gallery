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

package com.google.ai.edge.gallery.phoneagent.confirm

import com.google.ai.edge.gallery.customtasks.mobileactions.ActionType
import com.google.ai.edge.gallery.intents.IntentAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallPolicyTest {
  @Test
  fun calendarAndMessagingIntentsAreConfirmed() {
    val confirmed = IntentAction.entries.filter { ToolCallPolicy.intentNeedsConfirmation(it.action) }
    assertEquals(
      setOf(
        IntentAction.SEND_EMAIL,
        IntentAction.SEND_SMS,
        IntentAction.CREATE_CALENDAR_EVENT,
        IntentAction.READ_CALENDAR_EVENTS,
      ),
      confirmed.toSet(),
    )
    assertTrue("unknown intents are confirmed", ToolCallPolicy.intentNeedsConfirmation("call_phone"))
  }

  @Test
  fun intentsRunOnlyWhileAnEnabledSkillOffersThem() {
    val sendEmailSkill = "Call the `run_intent` tool with intent `send_email` and the parameters."
    assertTrue(ToolCallPolicy.intentOfferedBySkills("send_email", listOf(sendEmailSkill)))
    assertFalse(ToolCallPolicy.intentOfferedBySkills("send_sms", listOf(sendEmailSkill)))
    assertFalse("no skills enabled", ToolCallPolicy.intentOfferedBySkills("send_email", emptyList()))
    assertFalse(
      "a longer name is not a match",
      ToolCallPolicy.intentOfferedBySkills("send_email", listOf("use send_email_draft")),
    )
    assertTrue(
      "reading the clock needs no skill",
      ToolCallPolicy.intentOfferedBySkills("get_current_date_and_time", emptyList()),
    )
  }

  @Test
  fun skillsWithNetworkAreConfirmed() {
    assertTrue(ToolCallPolicy.skillNeedsConfirmation(networkAllowed = true))
    assertFalse(ToolCallPolicy.skillNeedsConfirmation(networkAllowed = false))
  }

  @Test
  fun mobileActionsWithPersonalDataOrNetworkAreConfirmed() {
    val confirmed =
      ActionType.entries.filter { ToolCallPolicy.mobileActionNeedsConfirmation(it.name) }.toSet()
    assertEquals(
      setOf(
        ActionType.ACTION_CREATE_CONTACT,
        ActionType.ACTION_SEND_EMAIL,
        ActionType.ACTION_SHOW_LOCATION_ON_MAP,
        ActionType.ACTION_CREATE_CALENDAR_EVENT,
      ),
      confirmed,
    )
  }
}
