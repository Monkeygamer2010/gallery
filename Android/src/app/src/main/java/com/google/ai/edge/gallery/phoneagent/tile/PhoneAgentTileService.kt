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

package com.google.ai.edge.gallery.phoneagent.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.google.ai.edge.gallery.phoneagent.agent.AgentRunner

/**
 * The "Phone Agent" Quick Settings tile. Tapping it opens a small popup to type a task (after the
 * phone is unlocked). Only the system can bind it (BIND_QUICK_SETTINGS_TILE).
 */
class PhoneAgentTileService : TileService() {
  override fun onStartListening() {
    super.onStartListening()
    val tile = qsTile ?: return
    val running = AgentRunner.state.value.active
    tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    tile.subtitle = if (running) "Running" else "Type a task"
    tile.updateTile()
  }

  override fun onClick() {
    super.onClick()
    if (isLocked) unlockAndRun { openPopup() } else openPopup()
  }

  @SuppressLint("StartActivityAndCollapseDeprecated")
  private fun openPopup() {
    val intent = Intent(this, TaskPopupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
    } else {
      @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
  }
}
