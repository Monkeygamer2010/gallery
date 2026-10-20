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

package com.google.ai.edge.gallery.phoneagent.browser

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.google.ai.edge.gallery.phoneagent.agent.ToolPolicy

/** Intents that open Firefox. Every page the agent opens goes into a new private tab. */
object FirefoxLaunch {
  /** The installed Firefox package, or null. */
  fun installedPackage(pm: PackageManager): String? =
    FirefoxReader.PACKAGES.firstOrNull { pm.getLaunchIntentForPackage(it) != null }

  /**
   * Firefox's own "new private tab" intent (the one behind its launcher shortcut), with [url] or,
   * when [url] is null, Firefox's private home page. Returns null when Firefox is missing or the
   * address is not allowed ([ToolPolicy.safeUrl]).
   */
  fun privateTab(pm: PackageManager, url: String?): Intent? {
    val pkg = installedPackage(pm) ?: return null
    val safe = url?.let { ToolPolicy.safeUrl(it) ?: return null }
    return Intent(FirefoxReader.ACTION_OPEN_PRIVATE_TAB)
      .setClassName(pkg, FirefoxReader.INTENT_RECEIVER_ACTIVITY)
      .apply { if (safe != null) data = Uri.parse(safe) }
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }
}
