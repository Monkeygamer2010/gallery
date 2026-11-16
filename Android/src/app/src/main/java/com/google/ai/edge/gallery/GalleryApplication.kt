/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery

import android.app.Application
import android.os.Process
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.phoneagent.images.installOfflineImageLoader
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class GalleryApplication : Application() {

  @Inject lateinit var dataStoreRepository: DataStoreRepository
  // PhoneAgent fork: no scheduled notifications (Phase 0) and no text-to-speech (upstream sync
  // 1.0.23: voice features are out of the build), so neither is injected or started here.

  override fun onCreate() {
    // androidx.pdf parses PDFs in an isolated service process, which also instantiates this
    // Application. Isolated processes have no access to storage or most system services (e.g.
    // Firebase initialization crashes there), so skip all app initialization in them.
    if (Process.isIsolated()) {
      return
    }
    super.onCreate()
    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    // PhoneAgent fork: no remote images anywhere in the app (docs/NETWORK.md).
    installOfflineImageLoader(this)
  }
}
