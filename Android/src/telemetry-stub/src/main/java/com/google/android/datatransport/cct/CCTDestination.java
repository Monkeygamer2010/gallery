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

package com.google.android.datatransport.cct;

import com.google.android.datatransport.runtime.Destination;

/**
 * Stand-in for the Clearcut (CCT) upload destination that MediaPipe's usage logger names. It
 * carries no endpoint: the real class encodes Google's logging URL, this one has none.
 */
public final class CCTDestination implements Destination {
  public static final CCTDestination INSTANCE = new CCTDestination();

  private CCTDestination() {}

  @Override
  public String getName() {
    return "phoneagent-noop";
  }

  @Override
  public byte[] getExtras() {
    return null;
  }
}
