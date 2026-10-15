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

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyLicensesTest {
  @Test
  fun parsesLinesAndGroupsMultipleLicenses() {
    val text =
      listOf(
          "# comment",
          "androidx.core:core:1.16.0\tApache-2.0\tThe Apache Software License, Version 2.0\t" +
            "https://www.apache.org/licenses/LICENSE-2.0.txt",
          "org.example:dual:2.0\tMIT\tMIT License\thttps://opensource.org/licenses/MIT",
          "org.example:dual:2.0\tApache-2.0\tApache License 2.0\t",
          "org.example:nolicense:1.0\tUNKNOWN\tNo license declared in the POM\t",
          "not-a-coordinate\tx\ty\tz",
        )
        .joinToString("\n")

    val libraries = parseThirdPartyLicenses(text)

    assertEquals(listOf("core", "dual", "nolicense"), libraries.map { it.artifact })
    assertEquals("1.16.0", libraries[0].version)
    assertEquals(listOf("MIT", "Apache-2.0"), libraries[1].licenses.map { it.id })
    assertEquals("", libraries[1].licenses[1].url)
    assertEquals("UNKNOWN", libraries[2].licenses.single().id)
  }

  @Test
  fun bundledAssetParsesAndHasNoProprietaryGroups() {
    // Unit tests run with the module directory as the working directory.
    val asset = File("src/main/assets/$THIRD_PARTY_LICENSES_ASSET")
    val libraries = parseThirdPartyLicenses(asset.readText())

    assertTrue("license list is empty", libraries.size > 50)
    val forbidden = listOf("com.google.firebase", "com.google.android.gms", "com.google.mlkit")
    val offenders = libraries.filter { lib -> forbidden.any { lib.group.startsWith(it) } }
    assertTrue("proprietary libraries shipped: $offenders", offenders.isEmpty())
  }
}
