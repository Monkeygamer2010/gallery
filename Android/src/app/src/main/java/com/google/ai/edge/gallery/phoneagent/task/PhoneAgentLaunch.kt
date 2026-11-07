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
import com.google.ai.edge.gallery.phoneagent.agent.AgentEngine
import com.google.ai.edge.gallery.phoneagent.agent.AgentSettingsStore
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A task typed in the Quick Settings popup. [tileModel] is the model the owner chose for the tile
 * under Model memory; [model] is the downloaded model it resolved to (null until resolved).
 */
data class TileRequest(val goal: String, val tileModel: String, val model: String? = null) {
  /** The run log's first line. */
  fun note(running: String): String =
    "Started from the Quick Settings tile, which asked for $tileModel; running $running." +
      if (tileModel != running) " ($tileModel is not downloaded.)" else ""
}

/**
 * Hands a task typed in the Quick Settings popup to the Phone Agent screen of the tile's model.
 * The text stays in this process's memory; it never travels in an intent, so no other app can
 * supply or read it.
 */
object PhoneAgentLaunch {
  private val pendingFlow = MutableStateFlow<TileRequest?>(null)
  private val shown = mutableListOf<String>()

  /** A task typed in the popup that a Phone Agent screen has not picked up yet. */
  val pending: StateFlow<TileRequest?> = pendingFlow.asStateFlow()

  /** The model of the Phone Agent screen in front, or null when none is shown. */
  val shownModel: String?
    get() = synchronized(shown) { shown.lastOrNull() }

  /** A Phone Agent screen for [model] is shown; a replaced screen may leave after the new one came. */
  fun onScreenShown(model: String) = synchronized(shown) { shown += model }

  fun onScreenLeft(model: String) = synchronized(shown) { shown.remove(model) }

  /** Remembers [goal] for the tile's model and brings PhoneAgent to the front, as it was left. */
  fun request(context: Context, goal: String) {
    offer(TileRequest(goal, AgentSettingsStore.load(context).tileModel))
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
  }

  internal fun offer(request: TileRequest) {
    pendingFlow.value = request
  }

  /** Records the downloaded model the request runs on. */
  fun resolve(model: String) {
    pendingFlow.update { it?.copy(model = model) }
  }

  /** Takes the pending task if it was resolved to [model]; a screen of another model leaves it. */
  fun take(model: String): TileRequest? {
    var taken: TileRequest? = null
    pendingFlow.update { current ->
      if (current?.model == model) {
        taken = current
        null
      } else {
        current
      }
    }
    return taken
  }

  /**
   * The model the tile's task runs on: the tile model from the settings if it is downloaded, else
   * the first downloaded one of the task (E4B first), else the tile model (to download it).
   */
  fun preferredModel(task: Task, tileModel: String, downloaded: (Model) -> Boolean): Model? {
    val chosen = task.models.firstOrNull { it.name == tileModel }
    return chosen?.takeIf(downloaded) ?: task.models.firstOrNull(downloaded) ?: chosen ?: task.models.firstOrNull()
  }
}

/**
 * Opens the Phone Agent screen of the tile's model when the popup asked for it: from another
 * screen, on a cold start, or in place of a Phone Agent screen showing another model (Phase 2.1:
 * the open screen ran its own model). Placed in the navigation graph; [navigate] gets the task
 * id, the model name and whether to replace the screen in front.
 */
@Composable
fun FollowPhoneAgentRequests(
  modelManagerViewModel: ModelManagerViewModel,
  navigate: (taskId: String, modelName: String, replaceCurrent: Boolean) -> Unit,
) {
  val pending by PhoneAgentLaunch.pending.collectAsState()
  val uiState by modelManagerViewModel.uiState.collectAsState()
  val tasksReady = uiState.tasks.isNotEmpty() && !uiState.loadingModelAllowlist
  LaunchedEffect(pending, tasksReady) {
    val request = pending ?: return@LaunchedEffect
    if (!tasksReady || request.model != null) return@LaunchedEffect
    val task = uiState.tasks.firstOrNull { it.id == PHONE_AGENT_TASK_ID } ?: return@LaunchedEffect
    val model =
      PhoneAgentLaunch.preferredModel(task, request.tileModel) {
        uiState.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
      } ?: return@LaunchedEffect
    PhoneAgentLaunch.resolve(model.name)
    val shown = PhoneAgentLaunch.shownModel
    if (shown == model.name) return@LaunchedEffect
    navigate(task.id, model.name, shown != null)
  }
}

/**
 * Before Phone Agent loads [keep], closes every other loaded model (for example AI Chat's), so a
 * run never shares memory with a second engine. Called from the model manager's load hook.
 */
fun closeOtherModels(viewModel: ModelManagerViewModel, context: Context, keep: Model) {
  val seen = HashSet<Model>()
  for (task in viewModel.uiState.value.tasks) {
    for (model in task.models) {
      if (model.instance == null || model.name == keep.name || AgentEngine.owns(model) || !seen.add(model)) continue
      viewModel.cleanupModel(context = context, task = task, model = model)
    }
  }
}
