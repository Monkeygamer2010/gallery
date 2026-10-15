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

package com.google.android.datatransport.runtime;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.Event;
import com.google.android.datatransport.Transport;
import com.google.android.datatransport.TransportFactory;
import com.google.android.datatransport.cct.CCTDestination;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** Mirrors the calls MediaPipe's RemoteLoggingClient makes and checks that nothing happens. */
public class TransportRuntimeTest {
  @Test
  public void eventsAreDroppedWithoutSerializing() {
    AtomicBoolean serialized = new AtomicBoolean(false);
    TransportRuntime.initialize(null);
    TransportFactory factory = TransportRuntime.getInstance().newFactory(CCTDestination.INSTANCE);
    Transport<String> transport =
        factory.getTransport(
            "COREML_ON_DEVICE_SOLUTIONS",
            String.class,
            Encoding.of("proto"),
            payload -> {
              serialized.set(true);
              return payload.getBytes();
            });

    transport.send(Event.ofData("usage stats"));

    assertTrue("payload must never be serialized", !serialized.get());
  }

  @Test
  public void scheduleReportsSuccessWithoutWork() {
    Transport<String> transport =
        TransportRuntime.getInstance()
            .newFactory(CCTDestination.INSTANCE)
            .getTransport("x", String.class, Encoding.of("proto"), String::getBytes);
    AtomicBoolean called = new AtomicBoolean(false);

    transport.schedule(
        Event.ofData("x"),
        error -> {
          assertNull(error);
          called.set(true);
        });

    assertTrue(called.get());
  }

  @Test
  public void runtimeIsASingleton() {
    assertSame(TransportRuntime.getInstance(), TransportRuntime.getInstance());
  }
}
