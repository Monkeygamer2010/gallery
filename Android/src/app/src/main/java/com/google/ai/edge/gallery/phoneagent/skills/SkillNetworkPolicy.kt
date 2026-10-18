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

import android.content.Context
import com.google.ai.edge.gallery.proto.Skill

private const val PREFS_NAME = "phoneagent_skill_network"

/**
 * The per-skill "Allow network" switch (docs/NETWORK.md). Off for every skill until the owner turns
 * it on in the skill manager. Skills loaded from a URL cannot run while it is off, because their
 * code itself comes from the network.
 */
class SkillNetworkPolicy(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun isAllowed(skill: Skill): Boolean = prefs.getBoolean(keyFor(skill), false)

  fun setAllowed(skill: Skill, allowed: Boolean) {
    prefs.edit().apply { if (allowed) putBoolean(keyFor(skill), true) else remove(keyFor(skill)) }.apply()
  }

  /** Called when a skill is deleted or renamed, so its switch is not inherited. */
  fun forget(skill: Skill) {
    prefs.edit().remove(keyFor(skill)).apply()
  }

  private fun keyFor(skill: Skill): String =
    SkillPaths.networkKey(skill.name, skill.builtIn, skill.importDirName, skill.skillUrl)
}

/** True when the skill's code is downloaded from a URL each time it runs. */
val Skill.isRemote: Boolean
  get() = importDirName.isEmpty() && skillUrl.isNotEmpty()
