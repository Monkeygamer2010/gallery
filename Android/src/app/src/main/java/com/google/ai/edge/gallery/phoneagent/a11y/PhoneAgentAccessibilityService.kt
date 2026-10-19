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
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import com.google.ai.edge.gallery.phoneagent.browser.BrowserState
import com.google.ai.edge.gallery.phoneagent.browser.FirefoxReader
import com.google.ai.edge.gallery.phoneagent.screen.Bounds
import com.google.ai.edge.gallery.phoneagent.screen.Element
import com.google.ai.edge.gallery.phoneagent.screen.ScreenContext
import com.google.ai.edge.gallery.phoneagent.screen.ScreenRead
import com.google.ai.edge.gallery.phoneagent.screen.ScreenSerializer
import com.google.ai.edge.gallery.phoneagent.screen.forEachNode
import com.google.ai.edge.gallery.phoneagent.spike.ReadSummary
import com.google.ai.edge.gallery.phoneagent.spike.SpikeActivity
import com.google.ai.edge.gallery.phoneagent.spike.SpikeCommand
import com.google.ai.edge.gallery.phoneagent.spike.SpikeEntry
import com.google.ai.edge.gallery.phoneagent.spike.SpikeSession
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * PhoneAgent's AccessibilityService (Phase 1 spike: no model).
 *
 * It does nothing on its own: it reads the screen and acts only when the owner starts a test in
 * the spike screen. Screen contents stay in memory ([SpikeSession]) and are never logged, saved or
 * sent anywhere. Actions use node actions first, then text paste, and gestures only as a last
 * resort. It never acts on PhoneAgent itself or in Settings.
 */
class PhoneAgentAccessibilityService : AccessibilityService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var job: Job? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    instanceFlow.value = this
  }

  // Events are not needed for the spike; nothing from them is kept.
  override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

  override fun onInterrupt() {}

  override fun onUnbind(intent: Intent?): Boolean {
    stop()
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    stop()
    super.onDestroy()
  }

  private fun stop() {
    if (instanceFlow.value === this) instanceFlow.value = null
    scope.cancel()
    SpikeSession.setStatus(null)
  }

  /** Runs [command] in the app in front after [COMMAND_DELAY_MS], then optionally returns to the spike screen. */
  fun run(command: SpikeCommand, returnToApp: Boolean) {
    job?.cancel()
    job =
      scope.launch {
        for (seconds in (COMMAND_DELAY_MS / 1000).toInt() downTo 1) {
          SpikeSession.setStatus("${command.title} in $seconds s")
          delay(1000)
        }
        SpikeSession.setStatus("${command.title}: running")
        val entry =
          try {
            execute(command)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            // The exception text could quote screen content; report only its type.
            SpikeEntry(command.title, "Failed: ${e.javaClass.simpleName}", null, null)
          }
        SpikeSession.add(entry)
        SpikeSession.setStatus(null)
        if (returnToApp) bringSpikeScreenBack()
      }
  }

  fun cancelPending() {
    job?.cancel()
    SpikeSession.setStatus(null)
  }

  private suspend fun execute(command: SpikeCommand): SpikeEntry =
    when (command) {
      SpikeCommand.Inspect -> {
        val shot = snapshot()
        SpikeEntry(command.title, shot.outcome(), shot.summary, null)
      }
      is SpikeCommand.Tap -> act(command, command.id, command.expected) { shot, element -> tap(shot, element) }
      is SpikeCommand.Type ->
        act(command, command.id, command.expected) { shot, element ->
          type(shot, element, command.text, command.pressEnter)
        }
      is SpikeCommand.Scroll -> {
        val shot = snapshot()
        refusal(shot)?.let { return SpikeEntry(command.title, it, shot.summary, null) }
        val outcome = scroll(shot, command.down)
        delay(SETTLE_MS)
        SpikeEntry(command.title, outcome, shot.summary, snapshot().summary)
      }
      SpikeCommand.Back -> {
        val shot = snapshot()
        refusal(shot)?.let { return SpikeEntry(command.title, it, shot.summary, null) }
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        delay(SETTLE_MS)
        SpikeEntry(command.title, if (ok) "Back pressed" else "Back failed", shot.summary, snapshot().summary)
      }
      is SpikeCommand.OpenUrl -> {
        val outcome = openUrl(command.url, command.privateIntent)
        delay(LAUNCH_SETTLE_MS)
        SpikeEntry(command.title, outcome, null, snapshot().summary)
      }
      SpikeCommand.LaunchFirefox -> {
        val outcome = launchFirefox()
        delay(LAUNCH_SETTLE_MS)
        SpikeEntry(command.title, outcome, null, snapshot().summary)
      }
      SpikeCommand.LaunchFirefoxFromHome -> launchFirefoxFromHome(command)
    }

  /** Reads the screen, checks [id] still means [expected], acts, waits, reads again. */
  private suspend fun act(
    command: SpikeCommand,
    id: Int,
    expected: String?,
    action: suspend (Snapshot, Element) -> String,
  ): SpikeEntry {
    val shot = snapshot()
    refusal(shot)?.let { return SpikeEntry(command.title, it, shot.summary, null) }
    val element =
      shot.read.element(id)
        ?: return SpikeEntry(command.title, "No element [$id] on the current screen. Nothing done.", shot.summary, null)
    if (expected != null && expected != element.signature) {
      return SpikeEntry(
        command.title,
        "The screen changed: [$id] is now ${element.signature}, not $expected. Nothing done. Inspect again.",
        shot.summary,
        null,
      )
    }
    val outcome = action(shot, element)
    delay(SETTLE_MS)
    return SpikeEntry("${command.title}: ${element.signature}", outcome, shot.summary, snapshot().summary)
  }

  /** The spike never acts on PhoneAgent itself or in Settings (where accessibility is switched). */
  private fun refusal(shot: Snapshot): String? =
    when (shot.packageName) {
      null -> "No app window found. Nothing done."
      packageName -> "PhoneAgent itself is in front. Actions on it are refused."
      SETTINGS_PACKAGE -> "Settings is in front. The spike never acts in Settings."
      else -> null
    }

  // ---- Reading ----

  private class Snapshot(
    val packageName: String?,
    val screen: Bounds,
    val nodes: List<AccessibilityNodeInfo>,
    val root: com.google.ai.edge.gallery.phoneagent.screen.UiNode?,
    val read: ScreenRead,
    val summary: ReadSummary,
  ) {
    fun outcome(): String =
      "Read ${read.elements.size} of ${read.totalElements} elements, about ${read.estimatedTokens} tokens"
  }

  private suspend fun snapshot(): Snapshot =
    withContext(Dispatchers.Default) {
      val start = SystemClock.elapsedRealtime()
      val allWindows = runCatching { windows }.getOrDefault(emptyList())
      val keyboardOpen = allWindows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
      val appWindows = allWindows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
      val window =
        appWindows.firstOrNull { it.isActive } ?: appWindows.firstOrNull { it.isFocused } ?: appWindows.firstOrNull()
      val rootNode = window?.root ?: rootInActiveWindow
      val pkg = rootNode?.packageName?.toString()
      val rect = Rect()
      window?.getBoundsInScreen(rect)
      val screen =
        if (rect.isEmpty) Bounds(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        else Bounds(rect.left, rect.top, rect.right, rect.bottom)
      val ownApp = pkg == packageName
      val tree = if (rootNode != null && !ownApp) NodeConverter.convert(rootNode) else null
      val browser = if (tree != null && FirefoxReader.isFirefox(pkg)) FirefoxReader.read(tree.root, screen) else null
      val context =
        ScreenContext(
          appLabel = pkg?.let { appLabel(it) },
          packageName = pkg,
          url = browser?.url,
          privateTab = browser?.isPrivate,
          keyboardOpen = keyboardOpen,
        )
      val read = ScreenSerializer.read(tree?.root, screen, context)
      val uncapped = ScreenSerializer.read(tree?.root, screen, context, tokenBudget = Int.MAX_VALUE)
      val elapsed = SystemClock.elapsedRealtime() - start
      val stats =
        "Nodes read: ${tree?.nodes?.size ?: 0}${if (tree?.truncated == true) " (stopped at the limit)" else ""}\n" +
          "Elements: ${read.elements.size} shown of ${read.totalElements}\n" +
          "Tokens (estimate): ${read.estimatedTokens} shown, ${uncapped.estimatedTokens} for the whole viewport\n" +
          "Read time: $elapsed ms"
      val details =
        when {
          ownApp -> "PhoneAgent itself is in front. It never reads its own screen."
          tree == null -> "No window content."
          else -> ScreenSerializer.details(tree.root, screen)
        }
      Snapshot(
        packageName = pkg,
        screen = screen,
        nodes = tree?.nodes.orEmpty(),
        root = tree?.root,
        read = read,
        summary =
          ReadSummary(
            compact = read.text,
            details = details,
            stats = stats,
            browser = browser?.let { describe(it) },
            signatures = read.elements.associate { it.id to it.signature },
          ),
      )
    }

  private fun describe(state: BrowserState): String =
    "Address: ${state.url ?: "not found"}${state.urlSource?.let { " (from $it)" } ?: ""}\n" +
      "Private tab: ${if (state.isPrivate) "yes" else "no evidence"}\n" +
      if (state.privateEvidence.isEmpty()) "Evidence: none" else "Evidence:\n" + state.privateEvidence.joinToString("\n") { "- $it" }

  private fun appLabel(pkg: String): String =
    runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
      .getOrDefault(pkg)

  // ---- Acting ----

  private suspend fun tap(shot: Snapshot, element: Element): String {
    val node = shot.nodes.getOrNull(element.nodeKey) ?: return "Failed: element not found"
    node.refresh()
    var target: AccessibilityNodeInfo? = node
    var hops = 0
    while (target != null && !target.isClickable && hops < MAX_PARENT_HOPS) {
      target = target.parent
      hops++
    }
    if (target != null && target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
      return if (hops == 0) "Tapped with a node click" else "Tapped with a node click on a parent ($hops up)"
    }
    val rect = Rect()
    node.getBoundsInScreen(rect)
    if (rect.isEmpty) return "Failed: the element has no area on screen"
    val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
    return if (gesture(path, 60)) "Tapped with a gesture (fallback; no clickable node)" else "Failed: node click and gesture"
  }

  private suspend fun type(shot: Snapshot, element: Element, text: String, pressEnter: Boolean): String {
    val node = shot.nodes.getOrNull(element.nodeKey) ?: return "Failed: element not found"
    node.refresh()
    val input = if (node.isEditable) node else findEditable(node) ?: return "Failed: [${element.id}] is not an input"
    input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    val args =
      Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
    val setOk = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    delay(300)
    var method =
      if (setOk && hasText(input, text)) {
        "Typed with set-text"
      } else if (paste(input, text)) {
        "Typed with paste (set-text ${if (setOk) "did not stick" else "was refused"})"
      } else {
        return "Failed: set-text and paste were both refused"
      }
    if (pressEnter) {
      val entered = input.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
      method += if (entered) ", then Enter" else ", but Enter was refused"
    }
    return method
  }

  private fun hasText(node: AccessibilityNodeInfo, text: String): Boolean {
    node.refresh()
    // Password fields report masked text; trust the action result there.
    return node.isPassword || node.text?.toString()?.trim() == text.trim()
  }

  /** Paste fallback: puts [text] on the clipboard (marked sensitive), pastes, then clears it. */
  private suspend fun paste(input: AccessibilityNodeInfo, text: String): Boolean {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
    val clip = ClipData.newPlainText("PhoneAgent", text)
    clip.description.extras =
      PersistableBundle().apply {
        putBoolean(
          if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE",
          true,
        )
      }
    clipboard.setPrimaryClip(clip)
    val length = input.text?.length ?: 0
    input.performAction(
      AccessibilityNodeInfo.ACTION_SET_SELECTION,
      Bundle().apply {
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
      },
    )
    val pasted = input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    delay(300)
    runCatching { clipboard.clearPrimaryClip() }
    return pasted && hasText(input, text)
  }

  private fun findEditable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
    if (depth > 4) return null
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      if (child.isEditable) return child
      findEditable(child, depth + 1)?.let { return it }
    }
    return null
  }

  private suspend fun scroll(shot: Snapshot, down: Boolean): String {
    var bestKey = -1
    var bestArea = -1L
    shot.root?.forEachNode { n ->
      val can = if (down) n.canScrollForward else n.canScrollBackward
      if (n.visible && n.scrollable && can && !n.bounds.isEmpty) {
        val area = (n.bounds.right - n.bounds.left).toLong() * (n.bounds.bottom - n.bounds.top)
        if (area > bestArea) {
          bestArea = area
          bestKey = n.key
        }
      }
    }
    val node = shot.nodes.getOrNull(bestKey)
    if (node != null) {
      val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
      if (node.performAction(action)) return "Scrolled with a node action"
    }
    val s = shot.screen
    val x = s.centerX.toFloat()
    val low = s.top + (s.bottom - s.top) * 0.7f
    val high = s.top + (s.bottom - s.top) * 0.3f
    val path = Path().apply {
      moveTo(x, if (down) low else high)
      lineTo(x, if (down) high else low)
    }
    val how = if (node == null) "no scrollable node" else "node action refused"
    return if (gesture(path, 350)) "Scrolled with a swipe gesture (fallback: $how)" else "Failed: $how, and the swipe failed"
  }

  private suspend fun gesture(path: Path, durationMs: Long): Boolean =
    suspendCancellableCoroutine { cont ->
      val description =
        GestureDescription.Builder()
          .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
          .build()
      val dispatched =
        dispatchGesture(
          description,
          object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
              if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
              if (cont.isActive) cont.resume(false)
            }
          },
          null,
        )
      if (!dispatched && cont.isActive) cont.resume(false)
    }

  // ---- Launching ----

  private fun firefoxPackage(): String? =
    FirefoxReader.PACKAGES.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }

  private fun launchFirefox(): String {
    val pkg = firefoxPackage() ?: return "Failed: Firefox is not installed"
    val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return "Failed: no launch intent"
    return startFromService(intent, "Started $pkg from the service")
  }

  private fun openUrl(url: String, privateIntent: Boolean): String {
    val safe = FirefoxReader.safeHttpsUrl(url) ?: return "Refused: only https:// addresses are opened"
    val pkg = firefoxPackage() ?: return "Failed: Firefox is not installed"
    val intent =
      if (privateIntent) {
        Intent(FirefoxReader.ACTION_OPEN_PRIVATE_TAB, Uri.parse(safe))
          .setClassName(pkg, FirefoxReader.INTENT_RECEIVER_ACTIVITY)
      } else {
        Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg)
      }
    return startFromService(intent, "Sent to $pkg from the service")
  }

  private fun startFromService(intent: Intent, success: String): String =
    try {
      startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      success
    } catch (e: Exception) {
      "Failed: ${e.javaClass.simpleName}"
    }

  private suspend fun launchFirefoxFromHome(command: SpikeCommand): SpikeEntry {
    performGlobalAction(GLOBAL_ACTION_HOME)
    delay(LAUNCH_SETTLE_MS / 2)
    val home = snapshot()
    val label = firefoxPackage()?.let { appLabel(it) } ?: "Firefox"
    val icon =
      home.read.elements.firstOrNull { it.label.equals(label, ignoreCase = true) }
        ?: home.read.elements.firstOrNull { it.label.equals("Firefox", ignoreCase = true) }
        ?: return SpikeEntry(command.title, "No \"$label\" icon on the current home screen page", home.summary, null)
    val outcome = tap(home, icon)
    delay(LAUNCH_SETTLE_MS)
    return SpikeEntry(command.title, "Found ${icon.signature}. $outcome", home.summary, snapshot().summary)
  }

  /** Brings the spike screen back to the front (allowed for a bound accessibility service). */
  private fun bringSpikeScreenBack() {
    runCatching {
      startActivity(
        Intent(this, SpikeActivity::class.java)
          .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
          )
      )
    }
  }

  companion object {
    const val COMMAND_DELAY_MS = 5000L
    private const val SETTLE_MS = 1500L
    private const val LAUNCH_SETTLE_MS = 3000L
    private const val MAX_PARENT_HOPS = 5
    private const val SETTINGS_PACKAGE = "com.android.settings"

    private val instanceFlow = MutableStateFlow<PhoneAgentAccessibilityService?>(null)

    /** The connected service, or null while it is off. */
    val instance: StateFlow<PhoneAgentAccessibilityService?> = instanceFlow.asStateFlow()
  }
}
