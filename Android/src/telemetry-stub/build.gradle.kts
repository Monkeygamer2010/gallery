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

// No-op replacement for the Firebase datatransport runtime that MediaPipe uses to upload usage
// stats. Only the classes and members MediaPipe references exist here; the privacyGuard task
// fails the build if MediaPipe starts referencing anything else. See docs/NETWORK.md.
plugins { alias(libs.plugins.android.library) }

android {
  namespace = "com.google.ai.edge.gallery.phoneagent.telemetrystub"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }
  defaultConfig { minSdk = 31 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  api(libs.transport.api)
  testImplementation(libs.junit)
}
