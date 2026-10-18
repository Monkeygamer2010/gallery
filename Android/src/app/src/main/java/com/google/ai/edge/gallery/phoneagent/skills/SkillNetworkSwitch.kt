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

package com.google.ai.edge.gallery.phoneagent.skills

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.proto.Skill

/** The "Allow network" switch shown under each skill in the skill manager. Off by default. */
@Composable
fun SkillNetworkSwitch(skill: Skill, enabled: Boolean) {
  val context = LocalContext.current
  val policy = remember { SkillNetworkPolicy(context) }
  var allowed by remember(skill) { mutableStateOf(policy.isAllowed(skill)) }
  val detail =
    if (skill.isRemote) {
      val host = runCatching { Uri.parse(skill.skillUrl).host }.getOrNull() ?: skill.skillUrl
      "Needed to run it: this skill's code is downloaded from $host each time it runs."
    } else {
      "Lets this skill's pages contact the internet. Off: everything stays on the phone."
    }

  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
      Text("Allow network", style = MaterialTheme.typography.labelLarge)
      Text(
        detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(
      checked = allowed,
      enabled = enabled,
      onCheckedChange = {
        allowed = it
        policy.setAllowed(skill, it)
      },
      modifier = Modifier.semantics { contentDescription = "Allow network for ${skill.name}" },
    )
  }
}
