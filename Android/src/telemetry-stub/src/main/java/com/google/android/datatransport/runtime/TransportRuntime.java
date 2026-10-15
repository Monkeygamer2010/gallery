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

import android.content.Context;
import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.Event;
import com.google.android.datatransport.Transformer;
import com.google.android.datatransport.Transport;
import com.google.android.datatransport.TransportFactory;
import com.google.android.datatransport.TransportScheduleCallback;

/**
 * No-op stand-in for Firebase datatransport's {@code TransportRuntime}.
 *
 * <p>MediaPipe Tasks builds a usage-stats logger through this class each time a task starts. The
 * real runtime stores events in a local database and uploads them to Google from a JobService.
 * This version hands out transports that drop every event without serializing it, and registers
 * no services, receivers or jobs.
 */
public final class TransportRuntime {
  private static final TransportRuntime INSTANCE = new TransportRuntime();
  private static final TransportFactory FACTORY = new DroppingTransportFactory();

  private TransportRuntime() {}

  public static void initialize(Context context) {}

  public static TransportRuntime getInstance() {
    return INSTANCE;
  }

  public TransportFactory newFactory(Destination destination) {
    return FACTORY;
  }

  private static final class DroppingTransportFactory implements TransportFactory {
    @Override
    public <T> Transport<T> getTransport(
        String name, Class<T> payloadType, Transformer<T, byte[]> payloadTransformer) {
      return new DroppingTransport<>();
    }

    @Override
    public <T> Transport<T> getTransport(
        String name,
        Class<T> payloadType,
        Encoding payloadEncoding,
        Transformer<T, byte[]> payloadTransformer) {
      return new DroppingTransport<>();
    }
  }

  private static final class DroppingTransport<T> implements Transport<T> {
    @Override
    public void send(Event<T> event) {}

    @Override
    public void schedule(Event<T> event, TransportScheduleCallback callback) {
      callback.onSchedule(null);
    }
  }
}
