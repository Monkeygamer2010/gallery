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

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Decides when Phone Agent's model is unloaded while no task runs ([KeepLoaded]):
 * - Off: when the Phone Agent screen closes or PhoneAgent goes to the background.
 * - While PhoneAgent is open: when PhoneAgent goes to the background.
 * - For N minutes: N minutes after the last task or after leaving the screen.
 * - Always, whatever the setting: when Android reports low memory or asks a background app to
 *   trim memory (Android 14+ sends only "UI hidden" and "background").
 * Nothing is unloaded while a task runs.
 */
object ModelKeeper {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var timer: Job? = null
  @Volatile private var installed = false
  @Volatile private var screenShown = false
  @Volatile private var appForeground = true
  private lateinit var appContext: Context

  /** Registers for memory and foreground signals once per process. */
  fun install(context: Context) {
    if (installed) return
    installed = true
    appContext = context.applicationContext
    AgentSettingsStore.load(appContext)
    appContext.registerComponentCallbacks(
      object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = onTrim(level)

        override fun onConfigurationChanged(newConfig: Configuration) {}

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = unload()
      }
    )
    Handler(Looper.getMainLooper()).post {
      ProcessLifecycleOwner.get()
        .lifecycle
        .addObserver(
          object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
              appForeground = true
            }

            override fun onStop(owner: LifecycleOwner) {
              appForeground = false
              onBackground()
            }
          }
        )
    }
  }

  fun onScreenShown() {
    screenShown = true
    cancelTimer()
  }

  fun onScreenLeft() {
    screenShown = false
    if (running()) return
    when (settings().keepLoaded) {
      // A screen that comes straight back (rotation, font size) keeps the model.
      KeepLoaded.OFF -> unload(unlessScreenBack = true)
      KeepLoaded.WHILE_OPEN -> {}
      KeepLoaded.MINUTES -> startTimer()
    }
  }

  fun onRunStarted() = cancelTimer()

  fun onRunEnded() {
    when {
      !appForeground -> onBackground()
      settings().keepLoaded == KeepLoaded.MINUTES && !screenShown -> startTimer()
    }
  }

  private fun onBackground() {
    if (running()) return
    when (settings().keepLoaded) {
      KeepLoaded.OFF,
      KeepLoaded.WHILE_OPEN -> unload()
      KeepLoaded.MINUTES -> startTimer()
    }
  }

  private fun onTrim(level: Int) {
    if (running() || level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) return
    // The owner chose to keep the model for a while; honour that unless memory is actually low.
    if (settings().keepLoaded != KeepLoaded.MINUTES || lowMemory()) unload()
  }

  private fun startTimer() {
    cancelTimer()
    val minutes = settings().keepMinutes
    timer =
      scope.launch {
        delay(minutes * 60_000L)
        if (!running() && !screenShown) unload()
      }
  }

  private fun cancelTimer() {
    timer?.cancel()
    timer = null
  }

  private fun unload(unlessScreenBack: Boolean = false) {
    if (running()) return
    cancelTimer()
    // Closing an engine can wait for a load in progress: never on the main thread.
    scope.launch {
      if (unlessScreenBack) delay(SCREEN_BACK_GRACE_MS)
      if (!running() && !(unlessScreenBack && screenShown)) AgentEngine.releaseAll()
    }
  }

  /** How long a closed Phone Agent screen has to come back before "Off" unloads the model. */
  private const val SCREEN_BACK_GRACE_MS = 1_500L

  private fun running(): Boolean = AgentRunner.state.value.active

  private fun settings(): AgentSettings = AgentSettingsStore.settings.value

  private fun lowMemory(): Boolean {
    if (!::appContext.isInitialized) return false
    val info = ActivityManager.MemoryInfo()
    appContext.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info) ?: return false
    return info.lowMemory
  }
}
