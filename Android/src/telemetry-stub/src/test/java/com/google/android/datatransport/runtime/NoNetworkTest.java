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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.ai.edge.gallery.phoneagent.telemetrystub.NoOpAnalytics;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.Test;

/**
 * No class of the stub can reach the network: its bytecode references no networking, web,
 * reflection, process or code-loading API, and no Google service. A class's references are names
 * in its constant pool, so a call to any of these would put the name in the class file.
 */
public class NoNetworkTest {
  /** Packages: any name starting with one of these. */
  private static final String[] FORBIDDEN_PACKAGES = {
    "java/net/",
    "javax/net/",
    "okhttp3/",
    "android/net/",
    "android/webkit/",
    "java/nio/channels/",
    "java/lang/reflect/",
    "dalvik/system/",
    "com/google/android/gms/",
    "com/google/firebase/",
  };

  /** Single classes, matched as whole constant-pool names ("java/lang/Runtime", not RuntimeException). */
  private static final String[] FORBIDDEN_CLASSES = {
    "java/lang/Runtime", "java/lang/ProcessBuilder", "java/lang/ClassLoader",
  };

  private static final String[] STUB_PACKAGES = {
    "com/google/android/datatransport/",
    "com/google/ai/edge/gallery/phoneagent/telemetrystub/",
  };

  @Test
  public void noStubClassReferencesTheNetwork() throws Exception {
    TreeMap<String, byte[]> classes = new TreeMap<>();
    for (Class<?> anchor : new Class<?>[] {TransportRuntime.class, NoOpAnalytics.class}) {
      classes.putAll(stubClassesNextTo(anchor));
    }
    assertTrue(classes.containsKey("com/google/android/datatransport/runtime/TransportRuntime.class"));
    assertTrue(classes.containsKey("com/google/ai/edge/gallery/phoneagent/telemetrystub/NoOpAnalytics.class"));

    List<String> found = new ArrayList<>();
    for (String name : classes.keySet()) {
      byte[] bytes = classes.get(name);
      String text = new String(bytes, StandardCharsets.ISO_8859_1);
      for (String reference : FORBIDDEN_PACKAGES) {
        if (text.contains(reference)) found.add(name + " references " + reference);
      }
      for (String reference : FORBIDDEN_CLASSES) {
        if (text.contains(utf8Entry(reference))) found.add(name + " references " + reference);
      }
    }
    assertEquals(new ArrayList<String>(), found);
  }

  /** A CONSTANT_Utf8 entry as it appears in a class file: tag 1, two-byte length, the bytes. */
  private static String utf8Entry(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    return "\u0001" + (char) (bytes.length >> 8) + (char) (bytes.length & 0xFF) + new String(bytes, StandardCharsets.ISO_8859_1);
  }

  /** The stub's own class files, from the folder or jar the stub's classes were loaded from. */
  private static TreeMap<String, byte[]> stubClassesNextTo(Class<?> anchor)
      throws IOException, URISyntaxException {
    File location = new File(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
    TreeMap<String, byte[]> result = new TreeMap<>();
    if (location.isDirectory()) {
      List<File> files = new ArrayList<>();
      collect(location, files);
      for (File file : files) {
        String name = location.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        if (isStubClass(name)) result.put(name, Files.readAllBytes(file.toPath()));
      }
    } else {
      try (ZipFile zip = new ZipFile(location)) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
          ZipEntry entry = entries.nextElement();
          if (!isStubClass(entry.getName())) continue;
          try (InputStream in = zip.getInputStream(entry)) {
            result.put(entry.getName(), in.readAllBytes());
          }
        }
      }
    }
    return result;
  }

  private static boolean isStubClass(String name) {
    if (!name.endsWith(".class")) return false;
    for (String prefix : STUB_PACKAGES) {
      if (name.startsWith(prefix)) return true;
    }
    return false;
  }

  private static void collect(File dir, List<File> out) {
    File[] children = dir.listFiles();
    if (children == null) return;
    for (File child : children) {
      if (child.isDirectory()) collect(child, out);
      else out.add(child);
    }
  }
}
