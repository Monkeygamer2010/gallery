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

package com.google.ai.edge.gallery.phoneagent.licenses

/** Asset generated at build time by `./gradlew :app:updateThirdPartyLicenses`. */
const val THIRD_PARTY_LICENSES_ASSET = "phoneagent/third_party_licenses.tsv"

/** Full text of the Apache License 2.0, which covers this app. */
const val APACHE_LICENSE_ASSET = "phoneagent/LICENSE-2.0.txt"

data class LicenseInfo(val id: String, val name: String, val url: String)

data class LibraryLicenses(
  val group: String,
  val artifact: String,
  val version: String,
  val licenses: List<LicenseInfo>,
)

/**
 * Parses the tab-separated license list. Each line is `group:artifact:version`, license id,
 * license name and license URL; a library with several licenses has several lines. Lines starting
 * with '#' are comments. Malformed lines are skipped.
 */
fun parseThirdPartyLicenses(text: String): List<LibraryLicenses> {
  val byCoordinate = linkedMapOf<String, MutableList<LicenseInfo>>()
  for (line in text.lineSequence()) {
    if (line.isBlank() || line.startsWith("#")) continue
    val columns = line.split('\t')
    val coordinate = columns[0].trim()
    if (coordinate.split(':').size != 3) continue
    val license =
      LicenseInfo(
        id = columns.getOrElse(1) { "" }.trim(),
        name = columns.getOrElse(2) { "" }.trim(),
        url = columns.getOrElse(3) { "" }.trim(),
      )
    byCoordinate.getOrPut(coordinate) { mutableListOf() }.add(license)
  }
  return byCoordinate.map { (coordinate, licenses) ->
    val (group, artifact, version) = coordinate.split(':')
    LibraryLicenses(group = group, artifact = artifact, version = version, licenses = licenses)
  }
}
