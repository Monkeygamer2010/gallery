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

package com.google.ai.edge.gallery.phoneagent.task

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Hands a task typed in the Quick Settings popup to the Phone Agent screen. The text stays in this
 * process's memory; it never travels in an intent, so no other app can supply or read it.
 */
object PhoneAgentLaunch {
  private val pendingFlow = MutableStateFlow<String?>(null)

  /** A task typed in the popup that the Phone Agent screen has not picked up yet. */
  val pending: StateFlow<String?> = pendingFlow.asStateFlow()

  /** True while the Phone Agent screen is shown; it then picks up requests itself. */
  @Volatile var screenShown: Boolean = false

  /** Remembers [goal] and brings PhoneAgent to the front, as it was left, from the popup. */
  fun request(context: Context, goal: String) {
    pendingFlow.value = goal
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
  }

  /** Takes the pending task, if any. */
  fun take(): String? = pendingFlow.getAndUpdate { null }

  /** The model the popup opens: the first downloaded one of the task (E4B first), else E4B. */
  fun preferredModel(task: Task, downloaded: (Model) -> Boolean): Model? =
    task.models.firstOrNull(downloaded) ?: task.models.firstOrNull()
}

/**
 * Opens the Phone Agent screen when the popup asked for it and another screen is showing. Placed
 * in the navigation graph; [navigate] gets the task id and the model name.
 */
@Composable
fun FollowPhoneAgentRequests(
  modelManagerViewModel: ModelManagerViewModel,
  navigate: (taskId: String, modelName: String) -> Unit,
) {
  val pending by PhoneAgentLaunch.pending.collectAsState()
  val uiState by modelManagerViewModel.uiState.collectAsState()
  val tasksReady = uiState.tasks.isNotEmpty() && !uiState.loadingModelAllowlist
  LaunchedEffect(pending, tasksReady) {
    if (pending == null || !tasksReady || PhoneAgentLaunch.screenShown) return@LaunchedEffect
    val task = uiState.tasks.firstOrNull { it.id == PHONE_AGENT_TASK_ID } ?: return@LaunchedEffect
    val model =
      PhoneAgentLaunch.preferredModel(task) {
        uiState.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
      } ?: return@LaunchedEffect
    navigate(task.id, model.name)
  }
}
