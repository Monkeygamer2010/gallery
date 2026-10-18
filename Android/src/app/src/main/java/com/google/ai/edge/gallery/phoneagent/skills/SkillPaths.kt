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

/**
 * Checks for the paths and URLs that skills and the model hand to the app. All of them are untrusted:
 * the model picks script names, and a skill's result picks the page shown in chat.
 */
object SkillPaths {
  /** Host that the skill WebViews serve local files from; never resolved on the network. */
  const val LOCAL_HOST = "appassets.androidplatform.net"

  private const val MAX_PATH_LENGTH = 256
  private val SEGMENT = Regex("[A-Za-z0-9_][A-Za-z0-9._-]*")

  /**
   * True for a plain relative path such as "index.html" or "lib/chart.js". Rejects empty segments,
   * "." and "..", hidden files, absolute paths, schemes, backslashes, percent escapes and anything
   * outside `[A-Za-z0-9._-]`, so a path can never leave the skill's own folder.
   */
  fun isSafeRelativePath(path: String): Boolean =
    path.length in 1..MAX_PATH_LENGTH && path.split('/').all { SEGMENT.matches(it) }

  /**
   * True for a page inside the skill, optionally with a query or fragment:
   * "dashboard.html?date=2026-01-01". The part before '?' or '#' must pass [isSafeRelativePath].
   */
  fun isSafeRelativeUrl(url: String): Boolean {
    val end = url.indexOfAny(charArrayOf('?', '#')).let { if (it < 0) url.length else it }
    val rest = url.substring(end)
    return isSafeRelativePath(url.substring(0, end)) &&
      rest.length <= 4 * MAX_PATH_LENGTH &&
      rest.none { it.isISOControl() || it.isWhitespace() || it == '\\' }
  }

  /**
   * Folder name for a skill copied into app storage. Display names come from the document
   * provider the owner picked and may contain separators or "..".
   */
  fun safeDirName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trimStart('.', '-').take(64).ifEmpty { "skill" }

  /**
   * A file name from a document provider that stays inside the folder it is created in: not empty,
   * not "." or "..", and without path separators or NUL.
   */
  fun isSafeFileName(name: String?): Boolean =
    !name.isNullOrEmpty() &&
      name.length <= MAX_PATH_LENGTH &&
      name != "." &&
      name != ".." &&
      name.none { it == '/' || it == '\\' || it == '\u0000' }

  /**
   * Preferences key for a skill's network switch. It covers where the code comes from, so a
   * different skill that later reuses the name does not inherit the switch.
   */
  fun networkKey(name: String, builtIn: Boolean, importDirName: String, skillUrl: String): String =
    listOf(if (builtIn) "builtin" else "custom", name, importDirName, skillUrl).joinToString("\u0000")
}
