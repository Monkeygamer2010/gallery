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

package com.google.ai.edge.gallery.phoneagent.agent

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.data.markInitializationStarted
import com.google.ai.edge.gallery.data.markInitialized
import com.google.ai.edge.gallery.data.resetInitialization
import com.google.ai.edge.gallery.phoneagent.os.limitNativeEngineLogging
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** How the engine of the current model was loaded, for the metrics report. */
data class EngineLoad(
  val modelName: String,
  val backend: String,
  val speculativeDecoding: String,
  val loadMs: Long,
)

/** An agent error whose message is PhoneAgent's own (no screen content) and may be shown. */
class AgentError(message: String) : Exception(message)

/** What the model produced in one step. Its text is never logged. */
data class ModelReply(val toolCalls: List<Pair<String, Map<String, Any?>>>, val text: String, val ms: Long)

/**
 * Owns Phone Agent's engine and runs one stateless step at a time.
 *
 * - One engine at a time, owned here (outside any activity): a screen, a recreated activity or a
 *   new model manager asking for the same model gets the loaded engine; asking for another model
 *   or accelerator closes the old engine first. Before this, the engine of a finished activity
 *   stayed in memory and the next launch (for example from the tile) loaded a second one.
 * - Text only: no vision or audio encoders, which saves memory and load time.
 * - Speculative decoding (multi-token prediction) is on when the model file supports it.
 * - Each step gets a fresh conversation with the system prompt and the tool declarations, and
 *   `automaticToolCalling = false`: LiteRT-LM only parses the tool call; PhoneAgent's own code
 *   checks it and carries it out. The previous step's conversation is closed first.
 */
@OptIn(ExperimentalApi::class)
object AgentEngine {
  /** Large enough for the system prompt, tools, an 800-token screen and a reply with thinking. */
  private const val MIN_CONTEXT_TOKENS = 4096
  const val MAX_OUTPUT_TOKENS = 512
  const val MAX_OUTPUT_TOKENS_THINKING = 2048

  /** Low randomness: the agent should pick the obvious next action (docs/DECISIONS.md). */
  private const val TOP_K = 40
  private const val TOP_P = 0.95
  private const val TEMPERATURE = 0.3

  private val loadFlow = MutableStateFlow<EngineLoad?>(null)

  /** The last engine load in this process, or null. */
  val lastLoad: StateFlow<EngineLoad?> = loadFlow.asStateFlow()

  private val enginesFlow = MutableStateFlow(0)

  /** Engines Phone Agent has loaded and not closed (0 or 1). */
  val loadedEngines: StateFlow<Int> = enginesFlow.asStateFlow()

  private class Loaded(val key: String, val modelName: String, val instance: LlmModelInstance, var owner: Model)

  /** Held while an engine loads (seconds): the main thread only ever tries it. */
  private val lock = ReentrantLock()
  private var loaded: Loaded? = null

  private fun keyOf(path: String, accelerator: Accelerator, maxTokens: Int, name: String) =
    "$name|$path|${accelerator.label}|$maxTokens"

  private val toolProviders by lazy {
    AgentTools.SPECS.map { spec ->
      tool(
        object : OpenApiTool {
          override fun getToolDescriptionJsonString(): String = spec.toJson()

          // Never called: automatic tool calling is off, so the runner executes every call.
          override fun execute(paramsJsonString: String): String = "{\"error\":\"not executed here\"}"
        }
      )
    }
  }

  /** Loads [model]; called by the task on a background thread. [onDone] gets "" or an error. */
  fun initialize(context: Context, model: Model, onDone: (String) -> Unit) {
    ModelKeeper.install(context)
    lock.withLock { initializeLocked(context, model, onDone) }
  }

  private fun initializeLocked(context: Context, model: Model, onDone: (String) -> Unit) {
    val accelerator = model.currentAccelerator ?: Accelerator.GPU
    val path = model.getPath(context)
    val maxTokens =
      maxOf(model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN), MIN_CONTEXT_TOKENS)
    val key = keyOf(path, accelerator, maxTokens, model.name)
    val current = loaded
    if (current != null && current.key == key) {
      // The same model is loaded: hand it over instead of loading a second engine.
      if (current.owner !== model) {
        val previous = current.owner
        current.owner = model
        if (previous.instance === current.instance) previous.resetInitialization()
      }
      model.markInitialized(current.instance)
      onDone("")
      return
    }
    // Another model or accelerator: close the old engine before loading the new one.
    if (current != null) closeLocked(current)
    if (model.instance != null) {
      // Loaded by another task's helper; use it as it is.
      onDone("")
      return
    }
    model.markInitializationStarted()
    val start = SystemClock.elapsedRealtime()
    val backend =
      when (accelerator) {
        Accelerator.CPU -> Backend.CPU()
        Accelerator.GPU -> Backend.GPU()
        Accelerator.NPU,
        Accelerator.TPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
      }
    val supportsSpeculative =
      runCatching {
          ModelInfo.from(path).use { info -> (info as? ModelInfo.Llm)?.hasSpeculativeDecodingSupport() == true }
        }
        .getOrDefault(false)

    fun create(speculative: Boolean): Engine {
      ExperimentalFlags.enableSpeculativeDecoding = speculative
      limitNativeEngineLogging()
      val engine =
        Engine(
          EngineConfig(
            modelPath = path,
            backend = backend,
            visionBackend = null,
            audioBackend = null,
            maxNumTokens = maxTokens,
            cacheDir = if (path.startsWith("/data/local/tmp")) context.getExternalFilesDir(null)?.absolutePath else null,
          )
        )
      try {
        engine.initialize()
      } catch (e: Exception) {
        runCatching { engine.close() }
        throw e
      } finally {
        ExperimentalFlags.enableSpeculativeDecoding = false
      }
      return engine
    }

    try {
      var speculative = if (supportsSpeculative) "on" else "not supported by the model file"
      val engine =
        try {
          create(supportsSpeculative)
        } catch (e: Exception) {
          if (!supportsSpeculative) throw e
          speculative = "off (the engine did not start with it)"
          create(false)
        }
      // LlmModelInstance needs a conversation; each step replaces it with its own.
      val conversation =
        try {
          engine.createConversation(ConversationConfig(samplerConfig = sampler(accelerator)))
        } catch (e: Exception) {
          runCatching { engine.close() }
          throw e
        }
      val instance = LlmModelInstance(engine = engine, conversation = conversation, metricsTracker = null)
      loaded = Loaded(key, model.name, instance, model)
      enginesFlow.value = 1
      model.markInitialized(instance)
      loadFlow.value =
        EngineLoad(model.name, accelerator.label, speculative, SystemClock.elapsedRealtime() - start)
      onDone("")
    } catch (e: Exception) {
      // The message may contain a file path but never screen content.
      val message = e.message ?: e.javaClass.simpleName
      model.markInitializationFailed(message)
      onDone(message)
    }
  }

  /**
   * Runs one step: a fresh conversation, [prompt] as the only user message, one reply. Cancelling
   * the coroutine (Stop, timeout) cancels the engine's work.
   */
  suspend fun step(model: Model, systemPrompt: String, prompt: String, thinking: Boolean): ModelReply =
    withContext(Dispatchers.IO) {
      val instance = model.instance as? LlmModelInstance ?: throw AgentError("the model is not loaded.")
      val start = SystemClock.elapsedRealtime()
      runCatching { instance.conversation.close() }
      val conversation =
        instance.engine.createConversation(
          ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            tools = toolProviders,
            samplerConfig = sampler(model.currentAccelerator ?: Accelerator.GPU),
            automaticToolCalling = false,
            maxOutputToken = if (thinking) MAX_OUTPUT_TOKENS_THINKING else MAX_OUTPUT_TOKENS,
          )
        )
      instance.conversation = conversation
      suspendCancellableCoroutine { cont ->
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        val text = StringBuilder()
        cont.invokeOnCancellation { runCatching { conversation.cancelProcess() } }
        conversation.sendMessageAsync(
          prompt,
          object : MessageCallback {
            override fun onMessage(message: Message) {
              for (call in message.toolCalls) calls += call.name to call.arguments
              for (content in message.contents.contents) if (content is Content.Text) text.append(content.text)
            }

            override fun onDone() {
              if (cont.isActive) cont.resume(ModelReply(calls.toList(), text.toString(), SystemClock.elapsedRealtime() - start))
            }

            override fun onError(throwable: Throwable) {
              if (!cont.isActive) return
              if (throwable is CancellationException) cont.cancel(throwable)
              else cont.resumeWithException(AgentError("the model failed (${throwable.javaClass.simpleName})."))
            }
          },
          mapOf("enable_thinking" to thinking),
        )
      }
    }

  /**
   * After a run: replaces the last step's conversation (agent prompt and tools) with a plain one,
   * so another task that picks up the loaded model does not inherit them.
   */
  suspend fun endRun(model: Model) =
    withContext(Dispatchers.IO) {
      val instance = model.instance as? LlmModelInstance ?: return@withContext
      runCatching { instance.conversation.close() }
      runCatching {
        instance.conversation =
          instance.engine.createConversation(
            ConversationConfig(samplerConfig = sampler(model.currentAccelerator ?: Accelerator.GPU))
          )
      }
    }

  /**
   * Releases [model]'s engine: Phone Agent's own engine is closed here; an engine another task
   * loaded goes to upstream's clean-up.
   */
  fun release(model: Model, onDone: () -> Unit) {
    val ours =
      lock.withLock {
        val current = loaded
        if (current != null && model.instance === current.instance) {
          closeLocked(current)
          true
        } else {
          false
        }
      }
    if (ours || model.instance == null) onDone() else LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  /** Unloads Phone Agent's engine, if any (call it off the main thread). Returns whether one was closed. */
  fun releaseAll(): Boolean =
    lock.withLock {
      val current = loaded ?: return false
      closeLocked(current)
      true
    }

  /**
   * Called before any task loads a model (data/ModelManagerViewModel.initializeModel): Phone
   * Agent's engine is closed first unless it is that model's or a task is running.
   */
  fun beforeAnyModelLoad(model: Model) {
    if (AgentRunner.state.value.active) return
    // Runs on the main thread: never wait for a load in progress.
    if (!lock.tryLock()) return
    try {
      val current = loaded ?: return
      if (current.owner === model || current.modelName == model.name) return
      closeLocked(current)
    } finally {
      lock.unlock()
    }
  }

  private fun closeLocked(current: Loaded) {
    loaded = null
    runCatching { current.instance.conversation.cancelProcess() }
    runCatching { current.instance.conversation.close() }
    runCatching { current.instance.engine.close() }
    if (current.owner.instance === current.instance) current.owner.resetInitialization()
    enginesFlow.value = 0
  }

  private fun sampler(accelerator: Accelerator): SamplerConfig? =
    if (accelerator == Accelerator.NPU || accelerator == Accelerator.TPU) null
    else SamplerConfig(topK = TOP_K, topP = TOP_P, temperature = TEMPERATURE)
}
