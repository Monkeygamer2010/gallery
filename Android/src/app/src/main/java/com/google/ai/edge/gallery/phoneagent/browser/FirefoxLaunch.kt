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
   * The intent that opens [url] in a private tab, or, when [url] is null, a new private tab with
   * the address bar focused. Returns null when Firefox is missing or the address is not allowed
   * ([ToolPolicy.safeUrl]).
   *
   * An address goes as a VIEW intent to Firefox's IntentReceiverActivity with the private-mode
   * extra, which Firefox hands to its private tab processor (`selectOrAddTab(url, private =
   * true)`). Firefox's "New private tab" shortcut action (OPEN_PRIVATE_TAB), which Phase 2 used,
   * ignores the address: it only opens a blank private tab.
   */
  fun privateTab(pm: PackageManager, url: String?): Intent? {
    val pkg = installedPackage(pm) ?: return null
    if (url == null) {
      return Intent(FirefoxReader.ACTION_OPEN_PRIVATE_TAB)
        .setClassName(pkg, FirefoxReader.INTENT_RECEIVER_ACTIVITY)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val safe = ToolPolicy.safeUrl(url) ?: return null
    return Intent(Intent.ACTION_VIEW, Uri.parse(safe))
      .setClassName(pkg, FirefoxReader.INTENT_RECEIVER_ACTIVITY)
      .putExtra(FirefoxReader.EXTRA_PRIVATE_BROWSING_MODE, true)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }

  /** What [privateTab] sends, for the spike's open_url test (no address). */
  fun describe(intent: Intent): String = buildString {
    append("action ").append(intent.action)
    append(", component ").append(intent.component?.flattenToShortString())
    append(", data ").append(if (intent.data != null) "the address" else "none")
    if (intent.hasExtra(FirefoxReader.EXTRA_PRIVATE_BROWSING_MODE)) {
      append(", extra ").append(FirefoxReader.EXTRA_PRIVATE_BROWSING_MODE).append("=true")
    }
    append(", flags 0x").append(Integer.toHexString(intent.flags))
  }
}
