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

package com.google.ai.edge.gallery.phoneagent.telemetrystub;

import android.os.Bundle;

/**
 * Stand-in for Firebase Analytics, which the fork removes (docs/NETWORK.md).
 *
 * <p>It has the two methods upstream's {@code GalleryAnalytics} wrapper calls on its delegate, so
 * the wrapper compiles unchanged. Both do nothing: no event is recorded, stored or sent. The app
 * never creates an instance either (the wrapper's delegate is always null); the class exists for
 * its type. A direct Firebase call anywhere in the app fails the build instead, because no
 * Firebase library is on the classpath (privacy guard).
 */
public final class NoOpAnalytics {
  public NoOpAnalytics() {}

  /** Does nothing. */
  public void logEvent(String name, Bundle params) {}

  /** Does nothing: there is no collection to switch on. */
  public void setAnalyticsCollectionEnabled(boolean enabled) {}
}
