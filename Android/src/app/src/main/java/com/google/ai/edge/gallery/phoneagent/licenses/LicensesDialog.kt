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

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Offline list of the open-source licenses that apply to this app and its libraries.
 *
 * Replaces Play services' oss-licenses activity. Everything shown comes from bundled assets; no
 * link is opened and nothing is fetched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesDialog(onDismissed: () -> Unit) {
  val context = LocalContext.current
  val libraries = remember { parseThirdPartyLicenses(readAsset(context, THIRD_PARTY_LICENSES_ASSET)) }
  val apacheText = remember { readAsset(context, APACHE_LICENSE_ASSET) }
  var showApacheText by rememberSaveable { mutableStateOf(false) }

  Dialog(
    onDismissRequest = onDismissed,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      topBar = {
        TopAppBar(
          title = { Text("Open-source licenses") },
          actions = {
            IconButton(onClick = onDismissed) {
              Icon(Icons.Rounded.Close, contentDescription = "Close")
            }
          },
        )
      },
    ) { innerPadding ->
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      ) {
        item {
          Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text("This app", style = MaterialTheme.typography.titleMedium)
            Text(
              "PhoneAgent is a fork of Google AI Edge Gallery. Both are licensed under the " +
                "Apache License, Version 2.0.",
              style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { showApacheText = !showApacheText }) {
              Text(if (showApacheText) "Hide license text" else "Show license text")
            }
            if (showApacheText) {
              Text(
                apacheText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
              )
            }
            Text(
              "Third-party libraries (${libraries.size})",
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.padding(top = 12.dp),
            )
          }
        }
        items(libraries, key = { "${it.group}:${it.artifact}" }) { library ->
          LibraryRow(library)
          HorizontalDivider()
        }
      }
    }
  }
}

@Composable
private fun LibraryRow(library: LibraryLicenses) {
  Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
    Text(
      "${library.group}:${library.artifact}",
      style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
    )
    Text(
      "Version ${library.version}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (license in library.licenses) {
      Text(
        listOf(license.name.ifEmpty { license.id }, license.url).filter { it.isNotEmpty() }
          .joinToString(" - "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun readAsset(context: Context, path: String): String =
  runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("")
