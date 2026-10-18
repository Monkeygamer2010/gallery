/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.tools

import android.util.Log
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.phoneagent.confirm.ConfirmToolCallAction
import com.google.ai.edge.gallery.phoneagent.confirm.ToolCallPolicy
import com.google.ai.edge.gallery.phoneagent.skills.SkillPaths
import com.google.ai.edge.gallery.phoneagent.skills.isRemote
import com.google.ai.edge.gallery.proto.Skill
import com.google.ai.edge.gallery.skills.SkillsProvider
import com.google.ai.edge.gallery.skills.getJsSkillUrl
import com.google.ai.edge.gallery.skills.getJsSkillWebviewUrl
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val TAG = "AGRunJsTool"

fun getSkillSecretKey(skillName: String): String {
  return "skill___${skillName}"
}

class RunJsTool(
  private val skillsProvider: SkillsProvider,
  private val dataStoreRepository: DataStoreRepository,
  // PhoneAgent fork: the per-skill "Allow network" switch; off unless the owner turned it on.
  private val isNetworkAllowed: (Skill) -> Boolean = { false },
) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  var resultImageToShow: CallJsSkillResultImage? = null
  var resultWebviewToShow: CallJsSkillResultWebview? = null

  /** Call JS skill */
  @Tool(description = "Runs JS script")
  fun runJs(
    @ToolParam(description = "The name of skill") skillName: String,
    @ToolParam(description = "The script name to run. Use 'index.html' if not provided by user")
    scriptName: String,
    @ToolParam(
      description = "The data to pass to the script. Use empty string if not provided by user"
    )
    data: String,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.Default) {
      // PhoneAgent fork: never log the data; it can contain screen contents.
      Log.d(TAG, "runJS tool called")

      val skill = skillsProvider.loadSkill(skillName)

      if (skill == null) {
        executionContext
          ?.actionChannel
          ?.send(
            SkillProgressToolAction(
              label = "Failed to call skill \"$scriptName\"",
              inProgress = false,
            )
          )
        return@runBlocking mapOf(
          "error" to "Skill \"${scriptName}\" not found",
          "status" to "failed",
        )
      }

      // PhoneAgent fork: the script name comes from the model; keep it inside the skill's folder.
      val allowNetwork = isNetworkAllowed(skill)
      val safeScriptName = scriptName.trim().ifEmpty { "index.html" }
      if (!SkillPaths.isSafeRelativePath(safeScriptName)) {
        return@runBlocking mapOf(
          "error" to "Invalid script name \"$scriptName\". Use a file name such as index.html.",
          "status" to "failed",
        )
      }
      if (skill.isRemote && !allowNetwork) {
        return@runBlocking mapOf(
          "error" to
            "Skill \"${skill.name}\" is loaded from the internet and network access is off for it. " +
              "Tell the user they can turn on \"Allow network\" for this skill in the skill manager.",
          "status" to "failed",
        )
      }

      // PhoneAgent fork: a skill with network access is confirmed on every call until Phase 4.
      if (ToolCallPolicy.skillNeedsConfirmation(allowNetwork)) {
        val confirmation =
          ConfirmToolCallAction(
            title = "Run \"${skill.name}\" with network access?",
            details = "Script: $safeScriptName\nData: $data",
          )
        val channel = executionContext?.actionChannel
        val allowed = channel != null && run {
          channel.send(confirmation)
          confirmation.result.await()
        }
        if (!allowed) {
          return@runBlocking mapOf("error" to "The user did not allow this skill call.", "status" to "failed")
        }
      }

      // Check secret. If a skill requires a secret and the secret is not provided, show error.
      var secret = ""
      if (skill.requireSecret) {
        // PhoneAgent fork: key the secret by the skill that runs, not by the name the model typed.
        val savedSecret =
          dataStoreRepository.readSecret(key = getSkillSecretKey(skillName = skill.name))
        if (savedSecret == null || savedSecret.isEmpty()) {
          val action =
            AskInfoToolAction(
              dialogTitle = "Enter secret",
              fieldLabel =
                skill.requireSecretDescription.ifEmpty {
                  "The JS script needs a secret (API key / token) to proceed:"
                },
            )
          executionContext?.actionChannel?.send(action)
          secret = action.result.await()
          if (secret.isNotEmpty()) {
            dataStoreRepository.saveSecret(
              key = getSkillSecretKey(skillName = skill.name),
              value = secret,
            )
            Log.d(TAG, "Got a secret from the ask info dialog.") // PhoneAgent fork: never log secrets
          } else {
            Log.d(TAG, "The ask info dialog got cancelled. No secret.")
          }
        } else {
          secret = savedSecret
        }
      }

      // Get the url for the skill.
      val url =
        skill.getJsSkillUrl(scriptName = safeScriptName)
          ?: return@runBlocking mapOf(
            "result" to "JS Skill URL not set properly or skill not found"
          )
      Log.d(TAG, "Calling JS script.")

      // Update progress.
      executionContext
        ?.actionChannel
        ?.send(
          SkillProgressToolAction(
            label = "Calling JS script \"${skillName}/${scriptName}\"",
            inProgress = true,
            addItemTitle = "Call JS script: \"${skillName}/${scriptName}\"",
            addItemDescription = "- URL: ${url.replace(LOCAL_URL_BASE, "")}\n- Data: $data",
            customData = skill,
          )
        )

      // Actually run it and wait for the result.
      val action =
        CallJsToolAction(
          url = url,
          data = data.trim().ifEmpty { "{}" },
          secret = secret,
          allowNetwork = allowNetwork,
        )
      executionContext?.actionChannel?.send(action)
      val result = action.result.await()
      val networkNote =
        if (!allowNetwork && action.blockedRequests > 0) {
          "The skill tried to use the network, which is off for it, so its result may be " +
            "incomplete. The user can turn on \"Allow network\" for skill \"${skill.name}\" in " +
            "the skill manager."
        } else null

      // Try to parse result to CallJsSkillResult.
      val moshi: Moshi = Moshi.Builder().build()
      val jsonAdapter: JsonAdapter<CallJsSkillResult> =
        moshi.adapter(CallJsSkillResult::class.java).failOnUnknown()
      val resultJson = runCatching { jsonAdapter.fromJson(result) }.getOrNull()
      val error = resultJson?.error

      // Failed to parse. Treat its whole as a result string.
      if (
        resultJson == null ||
          (resultJson.result == null && resultJson.webview == null && resultJson.image == null)
      ) {
        withNote(mapOf("result" to result, "status" to "succeeded"), networkNote)
      }
      // Error case.
      else if (error != null) {
        withNote(mapOf("error" to error, "status" to "failed"), networkNote)
      }
      // Non-error cases.
      else {
        // Handle image and webview in result.
        val image = resultJson.image
        val webview = resultJson.webview
        if (image != null) {
          Log.d(TAG, "Got an image response.")
          resultImageToShow = image
        }
        var webviewNote: String? = null
        if (webview != null) {
          Log.d(TAG, "Got an webview response.")
          // PhoneAgent fork: a skill page from the internet needs the skill's network switch, and a
          // local page must stay inside the skill's folder.
          val requested = webview.url ?: ""
          val isExternal = requested.startsWith("http://") || requested.startsWith("https://")
          if (isExternal && !(allowNetwork && requested.startsWith("https://"))) {
            webviewNote =
              "The skill wanted to show a web page from the internet, but network access is off " +
                "for it. The user can turn on \"Allow network\" for skill \"${skill.name}\" in the " +
                "skill manager."
          } else if (!isExternal && !SkillPaths.isSafeRelativeUrl(requested)) {
            webviewNote = "The skill returned an invalid page name, so nothing is shown."
          } else {
            val webviewUrl = skill.getJsSkillWebviewUrl(url = requested)
            resultWebviewToShow = webview.copy(url = webviewUrl, allowNetwork = allowNetwork)
          }
        }
        withNote(
          mapOf("result" to (resultJson.result ?: ""), "status" to "succeeded"),
          listOfNotNull(networkNote, webviewNote).joinToString(" ").ifEmpty { null },
        )
      }
    }
  }
}

private fun withNote(result: Map<String, Any>, note: String?): Map<String, Any> =
  if (note == null) result else result + ("note" to note)
