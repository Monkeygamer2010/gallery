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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.phoneagent.agent.AgentEngine
import com.google.ai.edge.gallery.phoneagent.agent.AgentRunner
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope

const val PHONE_AGENT_TASK_ID = "phone_agent"

/** Gemma 4 E4B is the default; E2B is offered with a warning (smaller, less reliable). */
const val DEFAULT_AGENT_MODEL = "Gemma-4-E4B-it"
const val SMALL_AGENT_MODEL = "Gemma-4-E2B-it"

/**
 * The Phone Agent task: the owner types a task, and the on-device model carries it out in Firefox
 * private tabs through PhoneAgent's accessibility service. The model stays loaded when the owner
 * leaves the screen, because the run goes on in other apps.
 */
class PhoneAgentTask(private val context: Context) : CustomTask {
  override val task =
    Task(
      id = PHONE_AGENT_TASK_ID,
      label = context.getString(R.string.phoneagent_task_label),
      category = Category.LLM,
      icon = Icons.Outlined.TouchApp,
      description =
        "Type a task, such as a question to look up on the web. Gemma reads Firefox private tabs " +
          "and taps, types and scrolls until it has the answer. Everything runs on this phone.",
      shortDescription = "Let Gemma look things up in Firefox for you",
      // The allowlist gives a new task no models; these come from it by name. Imported .litertlm
      // models are added too (data/ModelUtils.kt).
      modelNames = listOf(DEFAULT_AGENT_MODEL, SMALL_AGENT_MODEL),
      models = mutableListOf(),
      experimental = true,
    )

  override val keepModelAlive = true

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    systemInstruction: Contents?,
    onDone: (String) -> Unit,
  ) {
    // The system prompt is fixed in code (PromptBuilder), so systemInstruction is not used.
    AgentEngine.initialize(context = context, model = model, onDone = onDone)
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    AgentRunner.stop("Stopped: the model was unloaded.")
    AgentEngine.release(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    PhoneAgentScreen(task = task, data = data as CustomTaskData)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object PhoneAgentModule {
  @Provides
  @IntoSet
  fun provideTask(@ApplicationContext context: Context): CustomTask = PhoneAgentTask(context)
}
