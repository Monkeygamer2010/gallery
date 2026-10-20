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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Keeps the app's process in the foreground while a task runs in other apps, so Android does not
 * stop the model mid-run. Type `specialUse` ("an on-device agent run the owner started"): none of
 * the specific types fit. Started only from visible UI; stops itself when the run ends. Its
 * notification has a Stop action and never shows the task or anything from the screen.
 */
class AgentRunService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var watcher: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      AgentRunner.stop()
      // Started only to deliver Stop: leave right away if no run kept the service going.
      if (watcher == null) stopSelf()
      return START_NOT_STICKY
    }
    ensureChannel()
    val notification = notification(AgentRunner.state.value)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    if (watcher == null) {
      watcher =
        scope.launch {
          // Only the step counter and the end of the run change the notification.
          AgentRunner.state.distinctUntilChangedBy { it.active to it.step }.collect { state ->
            if (state.active) {
              getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(state))
            } else {
              stopForeground(STOP_FOREGROUND_REMOVE)
              stopSelf()
            }
          }
        }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun notification(state: RunState): Notification {
    val open =
      packageManager.getLaunchIntentForPackage(packageName)?.let {
        PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      }
    val stop =
      PendingIntent.getService(
        this,
        1,
        Intent(this, AgentRunService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val text = if (state.step == 0) "Starting" else "Step ${state.step} of ${state.maxSteps}"
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.agent)
      .setContentTitle("Phone Agent is operating your phone")
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .apply { if (open != null) setContentIntent(open) }
      .addAction(0, "Stop", stop)
      .build()
  }

  private fun ensureChannel() {
    val manager = getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    manager.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "Phone Agent runs", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Shown while Phone Agent operates your phone, with a Stop button."
        setShowBadge(false)
      }
    )
  }

  companion object {
    private const val CHANNEL_ID = "phone_agent_run"
    private const val NOTIFICATION_ID = 4201
    private const val ACTION_STOP = "com.google.ai.edge.gallery.phoneagent.STOP_RUN"

    /** Starts the service; call it from visible UI (Android's foreground-service start rules). */
    fun start(context: Context) {
      ContextCompat.startForegroundService(context, Intent(context, AgentRunService::class.java))
    }
  }
}
