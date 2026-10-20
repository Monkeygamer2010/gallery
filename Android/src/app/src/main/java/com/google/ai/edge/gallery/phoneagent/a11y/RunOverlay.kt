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

package com.google.ai.edge.gallery.phoneagent.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.google.ai.edge.gallery.phoneagent.agent.RunState
import kotlinx.coroutines.delay

/**
 * The Stop chip and step counter shown over other apps during a run. It is an accessibility
 * overlay window (`TYPE_ACCESSIBILITY_OVERLAY`), which an enabled accessibility service may add
 * without the "Display over other apps" permission. Screen reads skip it: they take application
 * windows only.
 */
internal class RunOverlay(private val service: AccessibilityService, private val onStop: () -> Unit) {
  private val windowManager by lazy { service.getSystemService(WindowManager::class.java) }
  private var root: LinearLayout? = null
  private var counter: TextView? = null
  private var params: WindowManager.LayoutParams? = null

  fun show(state: RunState) {
    if (root == null) create()
    counter?.text = if (state.step == 0) "Phone Agent: starting" else "Phone Agent: step ${state.step}/${state.maxSteps}"
  }

  fun hide() {
    root?.let { view -> runCatching { windowManager?.removeView(view) } }
    root = null
    counter = null
    params = null
  }

  /**
   * Runs [block] (a gesture) with the chip hidden and letting touches through, so a tap or swipe
   * meant for the app below can never land on Stop.
   */
  suspend fun <T> passThrough(block: suspend () -> T): T {
    val view = root
    val lp = params
    if (view == null || lp == null) return block()
    view.visibility = View.INVISIBLE
    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    runCatching { windowManager?.updateViewLayout(view, lp) }
    delay(50)
    try {
      return block()
    } finally {
      if (root === view) {
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        view.visibility = View.VISIBLE
        runCatching { windowManager?.updateViewLayout(view, lp) }
      }
    }
  }

  private fun create() {
    val wm = windowManager ?: return
    fun dp(value: Int) =
      TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), service.resources.displayMetrics).toInt()
    val text =
      TextView(service).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(12), dp(8), dp(8), dp(8))
      }
    val stop =
      TextView(service).apply {
        this.text = "Stop"
        contentDescription = "Stop Phone Agent"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply {
          cornerRadius = dp(16).toFloat()
          setColor(Color.rgb(0xB3, 0x26, 0x1E))
        }
        isClickable = true
        setOnClickListener { onStop() }
      }
    val chip =
      LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(2), dp(4), dp(2))
        background = GradientDrawable().apply {
          cornerRadius = dp(20).toFloat()
          setColor(Color.argb(0xE6, 0x20, 0x21, 0x24))
        }
        addView(text)
        addView(stop)
      }
    val lp =
      WindowManager.LayoutParams(
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
          PixelFormat.TRANSLUCENT,
        )
        .apply {
          gravity = Gravity.END or Gravity.CENTER_VERTICAL
          x = dp(8)
        }
    if (runCatching { wm.addView(chip, lp) }.isSuccess) {
      root = chip
      counter = text
      params = lp
    }
  }
}
