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

package com.google.ai.edge.gallery.phoneagent.spike

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.google.ai.edge.gallery.phoneagent.a11y.PhoneAgentAccessibilityService
import com.google.ai.edge.gallery.phoneagent.browser.FirefoxReader

/** What the spike screen shows under "Setup". Read again each time the screen resumes. */
data class DeviceSetup(
  val serviceEnabled: Boolean,
  val batteryUnrestricted: Boolean,
  /** Installed Firefox package and version, or null. */
  val firefox: String?,
  /** Package that opens https links by default, or null when Android would ask. */
  val defaultBrowser: String?,
) {
  val firefoxIsDefault: Boolean
    get() = FirefoxReader.isFirefox(defaultBrowser)

  companion object {
    fun read(context: Context): DeviceSetup {
      val pm = context.packageManager
      val am = context.getSystemService(AccessibilityManager::class.java)
      val enabled =
        am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty().any {
          it.resolveInfo.serviceInfo.packageName == context.packageName &&
            it.resolveInfo.serviceInfo.name == PhoneAgentAccessibilityService::class.java.name
        }
      val power = context.getSystemService(PowerManager::class.java)
      val firefox =
        FirefoxReader.PACKAGES.firstNotNullOfOrNull { pkg ->
          runCatching { "$pkg ${pm.getPackageInfo(pkg, 0).versionName}" }.getOrNull()
        }
      val browser =
        pm.resolveActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
            PackageManager.MATCH_DEFAULT_ONLY,
          )
          ?.activityInfo
          ?.packageName
          ?.takeIf { it != "android" }
      return DeviceSetup(
        serviceEnabled = enabled,
        batteryUnrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true,
        firefox = firefox,
        defaultBrowser = browser,
      )
    }

    fun openAccessibilitySettings(context: Context) {
      context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** App info, where "Allow restricted settings" (menu) and Battery live. */
    fun openAppInfo(context: Context) {
      context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
      )
    }

    /** Copies [text] to the clipboard, marked sensitive so the system does not preview it. */
    fun copy(context: Context, text: String) {
      val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
      val clip = ClipData.newPlainText("PhoneAgent screen read", text)
      clip.description.extras =
        PersistableBundle().apply {
          putBoolean(
            if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE",
            true,
          )
        }
      clipboard.setPrimaryClip(clip)
    }
  }
}
