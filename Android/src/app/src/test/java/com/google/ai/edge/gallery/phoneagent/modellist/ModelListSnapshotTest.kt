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

package com.google.ai.edge.gallery.phoneagent.modellist

import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled model list must be an exact copy of upstream's list for this app version. When an
 * upstream merge bumps versionName, this fails until the snapshot is re-copied:
 * `cp model_allowlists/<version>.json Android/src/app/src/main/assets/phoneagent/model_allowlist_snapshot.json`
 */
class ModelListSnapshotTest {
  // Unit tests run with the app module (Android/src/app) as the working directory.
  private val snapshot = File("src/main/assets/$BUNDLED_MODEL_LIST_ASSET")
  private val upstream =
    File("../../../model_allowlists/${BuildConfig.VERSION_NAME.replace(".", "_")}.json")

  @Test
  fun snapshotMatchesUpstreamListForThisVersion() {
    assertTrue("missing upstream list ${upstream.path}", upstream.isFile)
    assertArrayEquals(
      "bundled snapshot differs from ${upstream.path}",
      upstream.readBytes(),
      snapshot.readBytes(),
    )
  }

  @Test
  fun snapshotHasTheGemma4ModelsPhoneAgentUses() {
    val allowlist = Gson().fromJson(snapshot.readText(), ModelAllowlist::class.java)
    val names = allowlist.models.map { it.name }
    for (required in listOf("Gemma-4-E4B-it", "Gemma-4-E2B-it")) {
      assertTrue("$required missing from the bundled model list", required in names)
    }
  }
}
