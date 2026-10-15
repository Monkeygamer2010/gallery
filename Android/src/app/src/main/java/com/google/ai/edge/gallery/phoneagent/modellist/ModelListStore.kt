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

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.common.getJsonResponse
import com.google.ai.edge.gallery.common.getModelStorageDir
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.gson.Gson
import java.io.File

private const val TAG = "PAModelListStore"

/** Same file name upstream uses, so a list cached by either code path is picked up. */
private const val CACHE_FILE_NAME = "model_allowlist.json"

/** Generated from upstream `model_allowlists/<versionName>.json`; see ModelListSnapshotTest. */
const val BUNDLED_MODEL_LIST_ASSET = "phoneagent/model_allowlist_snapshot.json"

private const val ALLOWLIST_BASE_URL =
  "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists"

/**
 * The upstream model-list version for an app [versionName]: "1.0.20" and "1.0.20-ci" both map to
 * "1_0_20". Build-type suffixes such as "-ci" are not part of upstream's file names.
 */
fun modelListVersion(versionName: String): String =
  versionName.trim().substringBefore('-').replace(".", "_")

/** The app version the model list belongs to, without build-type suffixes ("1.0.20"). */
val baseVersionName: String
  get() = BuildConfig.VERSION_NAME.substringBefore('-')

enum class ModelListSource {
  /** Downloaded by the owner with "Refresh model list". */
  DOWNLOADED,
  /** Shipped inside the APK. */
  BUNDLED,
}

data class LoadedModelList(val allowlist: ModelAllowlist, val source: ModelListSource)

/**
 * Where the model list comes from in this fork (privacy contract, docs/NETWORK.md).
 *
 * Upstream fetches the list from GitHub on every launch. Here, startup reads the copy saved by the
 * last explicit refresh, falling back to the snapshot bundled in the APK. [refresh] is the only
 * code that contacts GitHub, and it runs only when the owner taps "Refresh model list".
 */
class ModelListStore(private val context: Context) {
  private val cacheFile: File
    get() = File(getModelStorageDir(context), CACHE_FILE_NAME)

  /** Reads the model list without any network access. */
  fun load(): LoadedModelList? {
    parse(readCacheText())?.let {
      return LoadedModelList(it, ModelListSource.DOWNLOADED)
    }
    parse(readBundledText())?.let {
      return LoadedModelList(it, ModelListSource.BUNDLED)
    }
    return null
  }

  /** When the downloaded copy was saved, or null when the bundled snapshot is in use. */
  fun downloadedAtMs(): Long? = cacheFile.takeIf { it.isFile }?.lastModified()

  /**
   * Downloads the list for this app version from GitHub, validates it and saves it. The new list
   * takes effect the next time the app starts. Returns the number of models, or an error message.
   * Blocking: call from a background thread.
   */
  fun refresh(): Result<Int> {
    val url = "$ALLOWLIST_BASE_URL/${modelListVersion(BuildConfig.VERSION_NAME)}.json"
    Log.i(TAG, "Owner requested a model list refresh: $url")
    val response = getJsonResponse<ModelAllowlist>(url = url)
    val allowlist = response?.jsonObj
    if (response == null || allowlist == null || allowlist.models.isNullOrEmpty()) {
      return Result.failure(IllegalStateException("Couldn't download the model list"))
    }
    return runCatching {
      cacheFile.parentFile?.mkdirs()
      cacheFile.writeText(response.textContent)
      allowlist.models.size
    }
  }

  /** Deletes the downloaded copy so the bundled snapshot is used from the next start. */
  fun resetToBundled(): Boolean = !cacheFile.exists() || cacheFile.delete()

  private fun readCacheText(): String? =
    runCatching { cacheFile.takeIf { it.isFile }?.readText() }.getOrNull()

  private fun readBundledText(): String? =
    runCatching { context.assets.open(BUNDLED_MODEL_LIST_ASSET).bufferedReader().use { it.readText() } }
      .onFailure { Log.e(TAG, "Bundled model list is missing", it) }
      .getOrNull()

  private fun parse(text: String?): ModelAllowlist? {
    if (text.isNullOrBlank()) return null
    return runCatching { Gson().fromJson(text, ModelAllowlist::class.java) }
      .getOrNull()
      ?.takeIf { !it.models.isNullOrEmpty() }
  }
}
