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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.os.Bundle
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.mcp.McpServerState
import com.google.ai.edge.gallery.mcp.McpServersProvider
import com.google.ai.edge.gallery.proto.McpAuth
import com.google.ai.edge.gallery.proto.McpServer
import com.google.ai.edge.gallery.proto.McpServers
import com.google.ai.edge.gallery.proto.McpTool
import com.google.ai.edge.gallery.proto.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "AGMcpManagerVM"

/** Builds the JSON schema stored for an MCP tool. It always has `properties` and `required`. */
@VisibleForTesting
internal fun toolInputSchemaJson(schema: ToolSchema): String {
  // A tool with no arguments may omit `properties`.
  val propertiesJson = schema.properties?.toString() ?: "{}"
  val requiredJson =
    schema.required?.joinToString(prefix = "[", postfix = "]") { "\"$it\"" } ?: "[]"
  return """{"type":"object","properties":$propertiesJson,"required":$requiredJson}"""
}

data class McpManagerUiState(
  val mcpServers: List<McpServerState> = emptyList(),
  val loadingMcpServer: Boolean = false,
  val error: String? = null,
)

@HiltViewModel
class McpManagerViewModel
@Inject
constructor(
  private val mcpServersDataStore: DataStore<McpServers>,
  private val userDataDataStore: DataStore<UserData>,
) : ViewModel(), McpServersProvider {
  override val mcpServers: List<McpServerState>
    get() = uiState.value.mcpServers

  private val _uiState = MutableStateFlow(McpManagerUiState())
  val uiState = _uiState.asStateFlow()

  private val httpClient = HttpClient(Android) { install(SSE) }

  // PhoneAgent fork (docs/NETWORK.md, "MCP"): connections are opened only for servers the owner
  // enabled, only while Agent Chat is on screen, and closed when it leaves. The lock keeps a
  // reconnect and a disconnect from racing.
  private val connectionLock = Mutex()
  private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * Loads the persisted MCP servers from the DataStore and initializes their client connections.
   */
  suspend fun loadMcpServers() = connectionLock.withLock {
    // 1. Set the loading indicator to signal background restoration is in progress.
    _uiState.update { it.copy(loadingMcpServer = true) }
    withContext(Dispatchers.IO) {
      try {
        // PhoneAgent fork: replace, never duplicate, any connections that are still open.
        closeClients(_uiState.value.mcpServers.mapNotNull { it.client })
        // 2. Retrieve the list of configured MCP servers previously saved in DataStore.
        val savedServers = mcpServersDataStore.data.first().mcpServerList
        val loadedStates = savedServers.map { serverProto ->
          // PhoneAgent fork: a disabled server is never contacted, so its stored auth header is
          // never sent. It keeps the tools saved from its last connection.
          if (!serverProto.enabled) {
            return@map McpServerState(mcpServer = serverProto, client = null, error = null)
          }
          try {
            // Map existing tool enablement configurations to preserve user preferences upon
            // reconnect.
            val savedToolsMap = serverProto.toolsList.associate { it.name to it.enabled }
            val savedAlwaysAllowMap = serverProto.toolsList.associate { it.name to it.alwaysAllow }
            val (client, mcpTools) =
              initializeClientAndLoadTools(serverProto.url, savedToolsMap, savedAlwaysAllowMap)
            val serverVersion = client.serverVersion
            val updatedServerProto =
              serverProto
                .toBuilder()
                .clearTools()
                .addAllTools(mcpTools)
                .setEnabled(serverProto.enabled)
                .apply {
                  serverVersion?.name?.let { setName(it) }
                  serverVersion?.version?.let { setVersion(it) }
                  val desc = mcpTools.joinToString(", ") { it.name }
                  if (desc.isNotEmpty()) {
                    setDescription("Tools: $desc")
                  }
                }
                .build()
            McpServerState(mcpServer = updatedServerProto, client = client, error = null)
          } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error loading MCP server: ${serverProto.url}", e)
            // Fallback: If connection fails during startup, load the server in a disabled state.
            McpServerState(
              mcpServer = serverProto.toBuilder().setEnabled(false).build(),
              client = null,
              error = e.message ?: "Failed to connect",
            )
          }
        }
        // 3. Populate the live UI state with the freshly loaded client connections and tool
        // schemas.
        _uiState.update { it.copy(mcpServers = loadedStates, loadingMcpServer = false) }
        // 4. Re-sync the DataStore to reflect any newly discovered tool structures or disabled
        // fallback flags.
        mcpServersDataStore.updateData {
          McpServers.newBuilder()
            .addAllMcpServer(
              loadedStates.mapIndexed { index, state ->
                // If loading failed, persist the original state instead of the disabled state
                // used in memory, allowing retries on the next app start.
                if (state.error != null) savedServers[index] else state.mcpServer
              }
            )
            .build()
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error reading saved MCP servers", e)
        _uiState.update { it.copy(loadingMcpServer = false) }
      }
    }
  }

  /**
   * Adds a new MCP server by URL, attempts to connect and discover its tools, and persists the
   * updated configuration.
   */
  fun addMcpServer(
    url: String,
    authType: McpAuth.AuthMethodCase,
    headerName: String,
    headerValue: String,
  ) {
    // Immediately display a loading spinner to the user while connecting, and clear any prior
    // error.
    _uiState.update { it.copy(loadingMcpServer = true, error = null) }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        // Construct McpAuth for this call to pass to initializeClientAndLoadTools
        val currentAuthBuilder = McpAuth.newBuilder()
        when (authType) {
          McpAuth.AuthMethodCase.REQUEST_HEADER -> {
            currentAuthBuilder.setRequestHeader(
              McpAuth.RequestHeader.newBuilder()
                .setHeaderName(headerName)
                .setHeaderValue(headerValue)
                .build()
            )
          }
          McpAuth.AuthMethodCase.OAUTH -> {
            currentAuthBuilder.setOauth(McpAuth.OAuth.getDefaultInstance())
          }
          McpAuth.AuthMethodCase.NONE -> {
            currentAuthBuilder.setNone(true)
          }
          else -> {
            currentAuthBuilder.setNone(true)
          }
        }
        val currentAuth = currentAuthBuilder.build()

        // 1. Connect to the remote MCP server and fetch its list of exported tools.
        val (client, mcpTools) = initializeClientAndLoadTools(url, mcpAuth = currentAuth)
        val serverVersion = client.serverVersion
        val mcpServerProto =
          McpServer.newBuilder()
            .setUrl(url)
            .addAllTools(mcpTools)
            .setEnabled(true)
            .apply {
              serverVersion?.name?.let { setName(it) }
              serverVersion?.version?.let { setVersion(it) }
              val desc = mcpTools.joinToString(", ") { it.name }
              if (desc.isNotEmpty()) {
                setDescription("Tools: $desc")
              }
            }
            .build()

        val newState = McpServerState(mcpServer = mcpServerProto, client = client, error = null)

        // 2. Persist the server and tooling configurations to disk, deduplicating by URL.
        mcpServersDataStore.updateData { currentServers ->
          val filtered = currentServers.mcpServerList.filter { it.url != url }
          McpServers.newBuilder().addAllMcpServer(filtered + mcpServerProto).build()
        }

        // Persist auth data
        userDataDataStore.updateData { currentUserData ->
          currentUserData.toBuilder().putMcpAuths(url, currentAuth).build()
        }

        // 3. Update the local StateFlow so the UI updates dynamically.
        _uiState.update { currentState ->
          val filteredStates = currentState.mcpServers.filter { it.mcpServer.url != url }
          currentState.copy(mcpServers = filteredStates + newState, loadingMcpServer = false)
        }
        Log.d(TAG, "Analytics: mcp_management, action=add_server, status=success")
        firebaseAnalytics?.logEvent(
          GalleryEvent.MCP_MANAGEMENT.id,
          Bundle().apply {
            putString("action", "add_server")
            putString("status", "success")
          },
        )
      } catch (e: Exception) {
        Log.e(TAG, "Error adding MCP server: $url", e)
        // Fallback: Update the UI state with the error message without preserving the server.
        _uiState.update { currentState ->
          currentState.copy(error = e.message ?: "Failed to connect", loadingMcpServer = false)
        }
        Log.d(
          TAG,
          "Analytics: mcp_management, action=add_server, status=failed, error_type=${e.javaClass.simpleName}",
        )
        firebaseAnalytics?.logEvent(
          GalleryEvent.MCP_MANAGEMENT.id,
          Bundle().apply {
            putString("action", "add_server")
            putString("status", "failed")
            putString("error_type", e.javaClass.simpleName)
          },
        )
      }
    }
  }

  /** Clears any connection error. */
  fun clearError() {
    _uiState.update { it.copy(error = null) }
  }

  /** Removes an MCP server by its URL from both the current UI state and persistent DataStore. */
  fun removeMcpServer(url: String) {
    viewModelScope.launch(Dispatchers.IO) {
      // PhoneAgent fork: close its connection and forget its stored auth header.
      closeClients(_uiState.value.mcpServers.filter { it.mcpServer.url == url }.mapNotNull { it.client })
      userDataDataStore.updateData { it.toBuilder().removeMcpAuths(url).build() }
      mcpServersDataStore.updateData { currentServers ->
        val filtered = currentServers.mcpServerList.filter { it.url != url }
        McpServers.newBuilder().addAllMcpServer(filtered).build()
      }
      _uiState.update { currentState ->
        currentState.copy(mcpServers = currentState.mcpServers.filter { it.mcpServer.url != url })
      }
    }
  }

  /** Returns true if an MCP server with the given URL is already loaded. */
  fun hasMcpServer(url: String): Boolean {
    return _uiState.value.mcpServers.any { it.mcpServer.url == url }
  }

  /** Generates a textual prompt listing all enabled tools from all enabled MCP servers. */
  fun getToolsPrompt(): String {
    return _uiState.value.mcpServers
      .filter { it.mcpServer.enabled }
      .flatMap { it.mcpServer.toolsList }
      .filter { it.enabled }
      .joinToString("\n\n") { tool ->
        "MCP tool name: \"${tool.name}\"\n- Description: ${tool.description}\n- Input schema: ${tool.inputSchema}"
      }
  }

  /** Generates a summary of enablement state for all servers and tools. */
  fun getSelectedMcpsAndToolsSummary(): String = buildString {
    for (state in _uiState.value.mcpServers) {
      append("${state.mcpServer.url}:${state.mcpServer.enabled};")
      for (tool in state.mcpServer.toolsList) {
        append("${tool.name}:${tool.enabled};")
      }
    }
  }

  /** Enables or disables an entire MCP server, updating both UI state and DataStore persistence. */
  fun setMcpServerEnabled(url: String, enabled: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.mcpServer.url == url) {
            state.copy(mcpServer = state.mcpServer.toBuilder().setEnabled(enabled).build())
          } else {
            state
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
    updateConnection(url, enabled)
  }

  /** Enables or disables a specific tool under a given MCP server URL. */
  fun setMcpToolEnabled(url: String, toolName: String, enabled: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.mcpServer.url == url) {
            val updatedTools =
              state.mcpServer.toolsList.map { tool ->
                if (tool.name == toolName) {
                  tool.toBuilder().setEnabled(enabled).build()
                } else {
                  tool
                }
              }
            state.copy(
              mcpServer = state.mcpServer.toBuilder().clearTools().addAllTools(updatedTools).build()
            )
          } else {
            state
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
  }

  /** Enables or disables all loaded MCP servers in batch. */
  fun setAllMcpServerEnabled(enabled: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.error != null) {
            state
          } else {
            state.copy(mcpServer = state.mcpServer.toBuilder().setEnabled(enabled).build())
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
    _uiState.value.mcpServers.filter { it.error == null }.forEach {
      updateConnection(it.mcpServer.url, enabled)
    }
  }

  /** Enables or disables all tools under a specific MCP server URL. */
  fun setAllMcpToolsEnabled(url: String, enabled: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.mcpServer.url == url) {
            val updatedTools =
              state.mcpServer.toolsList.map { tool -> tool.toBuilder().setEnabled(enabled).build() }
            state.copy(
              mcpServer = state.mcpServer.toBuilder().clearTools().addAllTools(updatedTools).build()
            )
          } else {
            state
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
  }

  /** Sets always allow for a specific tool under a given MCP server URL. */
  fun setMcpToolAlwaysAllow(url: String, toolName: String, alwaysAllow: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.mcpServer.url == url) {
            val updatedTools =
              state.mcpServer.toolsList.map { tool ->
                if (tool.name == toolName) {
                  tool.toBuilder().setAlwaysAllow(alwaysAllow).build()
                } else {
                  tool
                }
              }
            state.copy(
              mcpServer = state.mcpServer.toBuilder().clearTools().addAllTools(updatedTools).build()
            )
          } else {
            state
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
  }

  /**
   * Initializes a streaming transport client for the given URL and fetches its supported tools,
   * restoring their enabled states if available.
   */
  private suspend fun initializeClientAndLoadTools(
    url: String,
    savedToolsMap: Map<String, Boolean>? = null,
    savedAlwaysAllowMap: Map<String, Boolean>? = null,
    mcpAuth: McpAuth? = null,
  ): Pair<Client, List<McpTool>> {
    Log.d(TAG, "Initializing MCP for $url...")
    val client =
      Client(
        clientInfo =
          Implementation(name = "google-ai-edge-gallery", version = BuildConfig.VERSION_NAME)
      )
    // Retrieve authentication details from parameter or DataStore and configure the HTTP transport.
    val resolvedAuth = mcpAuth ?: userDataDataStore.data.first().mcpAuthsMap[url]
    val transport =
      if (
        resolvedAuth != null && resolvedAuth.authMethodCase == McpAuth.AuthMethodCase.REQUEST_HEADER
      ) {
        val reqHeader = resolvedAuth.requestHeader
        StreamableHttpClientTransport(
          client = httpClient,
          url = url,
          requestBuilder = { headers.append(reqHeader.headerName, reqHeader.headerValue) },
        )
      } else {
        StreamableHttpClientTransport(client = httpClient, url = url)
      }
    client.connect(transport)
    val toolsResponse = client.listTools()
    val mcpTools =
      toolsResponse.tools.map { tool ->
        val isEnabled = savedToolsMap?.get(tool.name) ?: true
        val isAlwaysAllow = savedAlwaysAllowMap?.get(tool.name) ?: false
        McpTool.newBuilder()
          .setName(tool.name)
          .setDescription(tool.description ?: "")
          .setInputSchema(toolInputSchemaJson(tool.inputSchema))
          .setEnabled(isEnabled)
          .setAlwaysAllow(isAlwaysAllow)
          .build()
      }
    Log.d(TAG, "Loaded ${mcpTools.size} tools from $url: ${mcpTools.joinToString { it.name }}")
    return Pair(client, mcpTools)
  }

  /**
   * PhoneAgent fork: closes every MCP connection. Called when Agent Chat leaves the screen or the app
   * goes to the background; [reconnectEnabledServers] reopens them when it is back.
   */
  fun disconnectAll() {
    closeScope.launch {
      connectionLock.withLock {
        val clients = _uiState.value.mcpServers.mapNotNull { it.client }
        _uiState.update { state -> state.copy(mcpServers = state.mcpServers.map { it.copy(client = null) }) }
        closeClients(clients)
      }
    }
  }

  /** PhoneAgent fork: reopens connections to enabled servers after [disconnectAll]. */
  fun reconnectEnabledServers() {
    viewModelScope.launch {
      _uiState.value.mcpServers
        .filter { it.mcpServer.enabled && it.client == null && it.error == null }
        .forEach { connect(it.mcpServer.url) }
    }
  }

  /** PhoneAgent fork: connects a server when it is enabled and disconnects it when disabled. */
  private fun updateConnection(url: String, enabled: Boolean) {
    if (enabled) {
      viewModelScope.launch { connect(url) }
    } else {
      closeScope.launch {
        connectionLock.withLock {
          val clients = _uiState.value.mcpServers.filter { it.mcpServer.url == url }.mapNotNull { it.client }
          _uiState.update { state ->
            state.copy(
              mcpServers =
                state.mcpServers.map { if (it.mcpServer.url == url) it.copy(client = null) else it }
            )
          }
          closeClients(clients)
        }
      }
    }
  }

  private suspend fun connect(url: String) =
    connectionLock.withLock {
      withContext(Dispatchers.IO) {
        val state = _uiState.value.mcpServers.find { it.mcpServer.url == url }
        if (state == null || !state.mcpServer.enabled || state.client != null) return@withContext
        try {
          val tools = state.mcpServer.toolsList
          val (client, mcpTools) =
            initializeClientAndLoadTools(
              url,
              tools.associate { it.name to it.enabled },
              tools.associate { it.name to it.alwaysAllow },
            )
          _uiState.update { current ->
            current.copy(
              mcpServers =
                current.mcpServers.map {
                  if (it.mcpServer.url != url) it
                  else
                    it.copy(
                      mcpServer = it.mcpServer.toBuilder().clearTools().addAllTools(mcpTools).build(),
                      client = client,
                      error = null,
                    )
                }
            )
          }
        } catch (e: Exception) {
          if (e is CancellationException) throw e
          Log.e(TAG, "Error connecting to MCP server", e)
        }
      }
    }

  private suspend fun closeClients(clients: List<Client>) {
    withContext(NonCancellable) {
      for (client in clients) {
        runCatching { client.close() }.onFailure { Log.w(TAG, "Error closing MCP client", it) }
      }
    }
  }

  override fun onCleared() {
    // PhoneAgent fork: leaving Agent Chat closes every stream and the HTTP client.
    val clients = _uiState.value.mcpServers.mapNotNull { it.client }
    closeScope.launch {
      closeClients(clients)
      httpClient.close()
    }
    super.onCleared()
  }

  private fun persistServers(updatedServers: List<McpServerState>) {
    viewModelScope.launch(Dispatchers.IO) {
      mcpServersDataStore.updateData { currentServers ->
        val updatedProtos = updatedServers.map { state ->
          if (state.error != null) {
            currentServers.mcpServerList.find { it.url == state.mcpServer.url } ?: state.mcpServer
          } else {
            state.mcpServer
          }
        }
        McpServers.newBuilder().addAllMcpServer(updatedProtos).build()
      }
    }
  }
}
