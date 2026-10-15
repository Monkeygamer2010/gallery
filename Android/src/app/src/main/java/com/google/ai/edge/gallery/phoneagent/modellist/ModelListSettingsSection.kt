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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.BuildConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings section for the model list. Shows where the list comes from and offers the only
 * owner-initiated way to fetch a new one from GitHub.
 */
@Composable
fun ModelListSettingsSection() {
  val context = LocalContext.current
  val store = remember { ModelListStore(context.applicationContext) }
  val scope = rememberCoroutineScope()
  var downloadedAt by remember { mutableStateOf(store.downloadedAtMs()) }
  var busy by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }
  val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()) }

  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      "Model list",
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
    val at = downloadedAt
    Text(
      if (at == null) "Using the list bundled with this app (${BuildConfig.VERSION_NAME})."
      else "Using the list downloaded on ${formatter.format(Instant.ofEpochMilli(at))}.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      OutlinedButton(
        enabled = !busy,
        onClick = {
          busy = true
          message = null
          scope.launch {
            val result = withContext(Dispatchers.IO) { store.refresh() }
            busy = false
            downloadedAt = store.downloadedAtMs()
            message =
              result.fold(
                onSuccess = { "Downloaded $it models. Restart the app to use the new list." },
                onFailure = { "Couldn't download the model list. Check the connection and retry." },
              )
          }
        },
      ) {
        Text(if (busy) "Downloading..." else "Refresh model list")
      }
      if (downloadedAt != null) {
        TextButton(
          enabled = !busy,
          onClick = {
            store.resetToBundled()
            downloadedAt = store.downloadedAtMs()
            message = "Restart the app to go back to the bundled list."
          },
        ) {
          Text("Use bundled list")
        }
      }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text(
      "Refreshing connects to raw.githubusercontent.com. Nothing else is sent.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
