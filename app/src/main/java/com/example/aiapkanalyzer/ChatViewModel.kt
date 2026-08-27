package com.example.aiapkanalyzer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.getSystemService
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiapkanalyzer.mcp.McpStreamableHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

data class ChatMessage(
    val role: String,
    val content: String,
    val isToolCall: Boolean = false,
    val toolName: String = "",
    val imageUris: List<Uri> = emptyList()
)

data class PendingAttachment(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val type: AttachmentType
)

enum class AttachmentType {
    FILE, IMAGE, VIDEO
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isBusy: Boolean = false,
    val status: String = "连接中...",
    val connectionState: ConnectionState = ConnectionState.CONNECTING,
    val streamingText: String = "",
    val inputCollapsed: Boolean = false,
    val availableTools: List<String> = emptyList(),
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val snackbarMessage: String = ""
)

data class SettingsUiState(
    val visible: Boolean = false,
    val currentProvider: String = "",
    val providers: List<String> = emptyList(),
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val apiMode: String = "chat",
    val mcpUrl: String = "",
    val mcpEnabled: Boolean = true,
    val workDirUri: String = "",
    val maxIterations: Int = 25,
    val temperature: Float = 0.2f,
    val saveResult: String = "",
    val floatingEnabled: Boolean = false,
    val floatingBallSizeDp: Int = 60,
    val floatingBallNormalAlpha: Float = 1.0f,
    val floatingBallHiddenAlpha: Float = 0.3f,
    val floatingBallHiddenRatio: Float = 0.67f,
    val floatingBallIconIndex: Int = 0,
    val floatingBallCustomIconPath: String = ""
)

data class ToolsUiState(
    val balanceResult: String = "",
    val modelsResult: String = "",
    val isQuerying: Boolean = false
)

data class ArtifactUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val error: String = "",
    val files: List<WorkFile> = emptyList(),
    val snackbarMessage: String = "",
    val selectMode: Boolean = false,
    val selectedNames: Set<String> = emptySet()
)

data class WorkFile(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val contentUri: android.net.Uri? = null,
    val isApk: Boolean = false,
    val isText: Boolean = false
)

class ChatViewModel(private val context: Context) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _settingsState = MutableStateFlow(SettingsUiState())
    val settingsState: StateFlow<SettingsUiState> = _settingsState.asStateFlow()

    private val _toolsState = MutableStateFlow(ToolsUiState())
    val toolsState: StateFlow<ToolsUiState> = _toolsState.asStateFlow()

    private val _artifactsState = MutableStateFlow(ArtifactUiState())
    val artifactsState: StateFlow<ArtifactUiState> = _artifactsState.asStateFlow()

    var config: AppConfig = AppConfig.load(context)
        private set
    private var mcp: McpStreamableHttpClient? = null
    private var agent: Agent? = null
    private var llm: LlmClient? = null
    private var currentJob: Job? = null
    private var healthCheckJob: Job? = null

    init {
        connect()
        startHealthCheck()
    }

    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(10_000)
                val state = _uiState.value
                if (state.connectionState == ConnectionState.CONNECTING) continue
                if (state.isBusy) continue

                val mcpEnabled = config.mcp.enabled && config.mcp.url.isNotBlank()
                if (!mcpEnabled) continue

                val currentLlm = llm
                if (currentLlm == null) continue

                val llmOk = runCatching { currentLlm.testConnection() }.getOrDefault(false)
                if (!llmOk) {
                    _uiState.update {
                        it.copy(connectionState = ConnectionState.DISCONNECTED, status = "连接已断开")
                    }
                    continue
                }

                val currentMcp = mcp
                var mcpOk = false
                if (currentMcp != null) {
                    mcpOk = runCatching { currentMcp.testConnection() }.getOrDefault(false)
                    if (!mcpOk) {
                        mcp = null
                        agent = null
                        _uiState.update {
                            it.copy(
                                availableTools = emptyList(),
                                status = "已连接（仅 AI 模式）"
                            )
                        }
                    }
                } else {
                    mcpOk = runCatching {
                        val m = McpStreamableHttpClient(config.mcp.url)
                        m.initialize()
                        val a = Agent(currentLlm, m, config.agent.max_iterations, config.agent.temperature)
                        val tools = a.refreshTools()
                        mcp = m
                        agent = a
                        _uiState.update { it.copy(availableTools = tools) }
                        KeepAliveService.start(context)
                        true
                    }.getOrDefault(false)
                }

                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.CONNECTED,
                        status = if (mcpOk) "已连接" else "已连接（仅 AI 模式）"
                    )
                }
            }
        }
    }

    fun connect() {
        _uiState.update { it.copy(status = "连接中...", connectionState = ConnectionState.CONNECTING) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = LlmClient(config.llm) { delta ->
                    _uiState.update { it.copy(streamingText = it.streamingText + delta) }
                }
                llm = client

                val llmOk = client.testConnection()
                if (!llmOk) {
                    _uiState.update {
                        it.copy(
                            status = "连接失败: 无法连接到 AI 服务",
                            connectionState = ConnectionState.DISCONNECTED
                        )
                    }
                    return@launch
                }

                var toolNames: List<String> = emptyList()
                var mcpOk = false

                if (config.mcp.enabled && config.mcp.url.isNotBlank()) {
                    try {
                        if (config.mcp.transport == "http") {
                            val m = McpStreamableHttpClient(config.mcp.url)
                            m.initialize()
                            mcp = m
                            val a = Agent(client, m, config.agent.max_iterations, config.agent.temperature)
                            agent = a
                            toolNames = a.refreshTools()
                            mcpOk = true
                            KeepAliveService.start(context)
                        }
                    } catch (_: Exception) { }
                }

                _uiState.update {
                    it.copy(
                        status = if (mcpOk) "已连接" else "已连接（仅 AI 模式）",
                        connectionState = ConnectionState.CONNECTED,
                        availableTools = toolNames
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        status = "连接失败: ${e.message}",
                        connectionState = ConnectionState.DISCONNECTED
                    )
                }
                KeepAliveService.stop(context)
            }
        }
    }

    fun onInputChange(text: String) { _uiState.update { it.copy(input = text) } }

    fun addAttachment(attachment: PendingAttachment) {
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + attachment) }
    }

    fun removeAttachment(index: Int) {
        _uiState.update {
            it.copy(pendingAttachments = it.pendingAttachments.filterIndexed { i, _ -> i != index })
        }
    }

    fun clearAttachments() {
        _uiState.update { it.copy(pendingAttachments = emptyList()) }
    }

    fun send() {
        val text = _uiState.value.input.trim()
        val attachments = _uiState.value.pendingAttachments
        if ((text.isEmpty() && attachments.isEmpty()) || _uiState.value.isBusy) return
        val llmClient = llm
        if (llmClient == null) {
            _uiState.update { it.copy(messages = it.messages + ChatMessage("assistant", "未连接 AI 模型，请先在设置中配置")) }
            return
        }

        val hasImageAttachments = attachments.any { it.type == AttachmentType.IMAGE }
        val hasAgent = agent != null

        val userMessageContent = buildString {
            append(text)
            if (attachments.isNotEmpty()) {
                append(if (text.isBlank()) "" else "\n\n")
                append("[附件: ${attachments.joinToString(", ") { att -> att.name }}]")
            }
        }

        val imageUris = attachments.filter { it.type == AttachmentType.IMAGE }.map { it.uri }

        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage("user", userMessageContent, imageUris = imageUris),
                input = "",
                isBusy = true,
                streamingText = "",
                inputCollapsed = true,
                pendingAttachments = emptyList()
            )
        }

        currentJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val history = buildMessageHistory()

                if (hasImageAttachments && !hasAgent) {
                    val imagesBase64 = mutableListOf<Pair<String, String>>()
                    val textPrompt = buildString {
                        append(text.ifBlank { "请分析这些图片" })
                        attachments.forEach { att ->
                            when (att.type) {
                                AttachmentType.IMAGE -> {
                                    try {
                                        val bytes = context.contentResolver.openInputStream(att.uri)?.use { it.readBytes() }
                                        if (bytes != null && bytes.size < 4 * 1024 * 1024) {
                                            val mimeType = att.mimeType
                                            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                            imagesBase64.add(mimeType to base64)
                                        } else if (bytes != null) {
                                            append("\n\n[图片 ${att.name} 太大，已跳过]")
                                        }
                                    } catch (_: Exception) {
                                        append("\n\n[图片 ${att.name} 读取失败]")
                                    }
                                }
                                AttachmentType.FILE -> {
                                    val content = try {
                                        context.contentResolver.openInputStream(att.uri)?.bufferedReader()?.use {
                                            it.readText().take(8000)
                                        } ?: "(无法读取)"
                                    } catch (_: Exception) { "(读取失败)" }
                                    append("\n\n[文件: ${att.name}]\n$content")
                                }
                                AttachmentType.VIDEO -> {
                                    append("\n\n[视频: ${att.name} (${att.mimeType})]")
                                }
                            }
                        }
                    }
                    if (imagesBase64.isNotEmpty()) {
                        llmClient.chatStreamMultimodal(history, textPrompt, imagesBase64) { delta ->
                            _uiState.update { st -> st.copy(streamingText = st.streamingText + delta) }
                        }
                    } else {
                        llmClient.chatStream(history) { delta ->
                            _uiState.update { st -> st.copy(streamingText = st.streamingText + delta) }
                        }
                    }
                } else {
                    val a = agent
                    if (a != null) {
                        a.run(userMessageContent)
                    } else {
                        llmClient.chatStream(history) { delta ->
                            _uiState.update { st -> st.copy(streamingText = st.streamingText + delta) }
                        }
                    }
                }
                val finalText = _uiState.value.streamingText
                if (finalText.isNotEmpty()) {
                    _uiState.update { st ->
                        st.copy(
                            messages = st.messages + ChatMessage("assistant", finalText),
                            streamingText = ""
                        )
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "未知错误"
                _uiState.update {
                    it.copy(
                        messages = it.messages + ChatMessage("assistant", "[错误] $msg"),
                        snackbarMessage = "发送失败: $msg"
                    )
                }
            } finally {
                _uiState.update { it.copy(isBusy = false, inputCollapsed = false) }
                currentJob = null
            }
        }
    }

    private fun buildMessageHistory(): List<Message> {
        val messages = _uiState.value.messages
        val result = mutableListOf<Message>()
        result.add(Message(role = "system", content = "你是AI助力助手，回答要准确、简洁、实用。"))
        val recentMessages = messages.takeLast(10)
        for (msg in recentMessages) {
            when (msg.role) {
                "user" -> result.add(Message(role = "user", content = msg.content))
                "assistant" -> result.add(Message(role = "assistant", content = msg.content))
            }
        }
        val last = messages.lastOrNull()
        if (last != null && last.role == "user") {
            // already added
        }
        return result
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = "") }
    }

    fun stopGeneration() {
        currentJob?.cancel()
        currentJob = null
        val streamed = _uiState.value.streamingText
        _uiState.update {
            val msgs = if (streamed.isNotEmpty()) {
                it.messages + ChatMessage("assistant", streamed + "\n\n[已停止]")
            } else {
                it.messages
            }
            it.copy(isBusy = false, streamingText = "", inputCollapsed = false, messages = msgs)
        }
    }

    fun toggleInputCollapsed() {
        _uiState.update { it.copy(inputCollapsed = !it.inputCollapsed) }
    }

    fun clear() {
        agent?.clear()
        _uiState.update { it.copy(messages = emptyList(), streamingText = "") }
    }

    // ---------- 设置 ----------

    fun openSettings() {
        loadSettingsData()
        _settingsState.update { it.copy(visible = true) }
    }

    fun loadSettingsData() {
        val llmCfg = config.llm
        val providerCfg = llmCfg.providers[llmCfg.provider]
        _settingsState.update {
            it.copy(
                currentProvider = llmCfg.provider,
                providers = llmCfg.providers.keys.toList(),
                apiKey = providerCfg?.apiKey ?: "",
                baseUrl = providerCfg?.baseUrl ?: "",
                model = providerCfg?.model ?: "",
                apiMode = providerCfg?.apiMode ?: "chat",
                mcpUrl = config.mcp.url,
                mcpEnabled = config.mcp.enabled,
                workDirUri = config.workDirUri,
                maxIterations = config.agent.max_iterations,
                temperature = config.agent.temperature,
                saveResult = "",
                floatingEnabled = config.floatingEnabled,
                floatingBallSizeDp = config.floatingBall.sizeDp,
                floatingBallNormalAlpha = config.floatingBall.normalAlpha,
                floatingBallHiddenAlpha = config.floatingBall.hiddenAlpha,
                floatingBallHiddenRatio = config.floatingBall.hiddenRatio,
                floatingBallIconIndex = config.floatingBall.iconIndex,
                floatingBallCustomIconPath = config.floatingBall.customIconPath
            )
        }
    }

    fun closeSettings() {
        _settingsState.update { it.copy(visible = false, saveResult = "") }
    }

    fun onProviderChange(provider: String) {
        val providerCfg = config.llm.providers[provider]
        _settingsState.update {
            it.copy(
                currentProvider = provider,
                apiKey = providerCfg?.apiKey ?: "",
                baseUrl = providerCfg?.baseUrl ?: "",
                model = providerCfg?.model ?: "",
                apiMode = providerCfg?.apiMode ?: "chat"
            )
        }
    }

    fun onApiKeyChange(v: String) { _settingsState.update { it.copy(apiKey = v) } }
    fun onBaseUrlChange(v: String) { _settingsState.update { it.copy(baseUrl = v) } }
    fun onModelChange(v: String) { _settingsState.update { it.copy(model = v) } }
    fun onApiModeChange(v: String) { _settingsState.update { it.copy(apiMode = v) } }
    fun onMcpUrlChange(v: String) { _settingsState.update { it.copy(mcpUrl = v) } }
    fun onMcpEnabledChange(v: Boolean) {
        _settingsState.update { it.copy(mcpEnabled = v) }
        config = AppConfig.update(context) { copy(mcp = mcp.copy(enabled = v)) }
    }
    fun onWorkDirUriChange(v: String) {
        _settingsState.update { it.copy(workDirUri = v) }
    }

    fun setWorkDirUri(uri: String) {
        _settingsState.update { it.copy(workDirUri = uri) }
        config = AppConfig.update(context) { copy(workDirUri = uri) }
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(Uri.parse(uri), flags)
        } catch (_: Exception) {}
    }

    fun isOverlayPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun getOverlaySettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun setFloatingEnabled(enabled: Boolean) {
        _settingsState.update { it.copy(floatingEnabled = enabled) }
        config = AppConfig.update(context) { copy(floatingEnabled = enabled) }
        if (enabled) {
            if (isOverlayPermissionGranted()) {
                FloatingWidgetService.start(context)
            } else {
                _uiState.update { it.copy(snackbarMessage = "请先授予悬浮窗权限") }
            }
        } else {
            FloatingWidgetService.stop(context)
        }
    }

    fun onMaxIterChange(v: Int) { _settingsState.update { it.copy(maxIterations = v) } }
    fun onTemperatureChange(v: Float) { _settingsState.update { it.copy(temperature = v) } }

    fun setFloatingBallSize(dp: Int) {
        _settingsState.update { it.copy(floatingBallSizeDp = dp) }
        saveFloatingBallConfig()
    }
    fun setFloatingBallNormalAlpha(alpha: Float) {
        _settingsState.update { it.copy(floatingBallNormalAlpha = alpha) }
        saveFloatingBallConfig()
    }
    fun setFloatingBallHiddenAlpha(alpha: Float) {
        _settingsState.update { it.copy(floatingBallHiddenAlpha = alpha) }
        saveFloatingBallConfig()
    }
    fun setFloatingBallHiddenRatio(ratio: Float) {
        _settingsState.update { it.copy(floatingBallHiddenRatio = ratio) }
        saveFloatingBallConfig()
    }
    fun setFloatingBallIcon(index: Int) {
        _settingsState.update { it.copy(floatingBallIconIndex = index) }
        saveFloatingBallConfig()
    }

    fun saveCroppedIcon(bitmap: android.graphics.Bitmap) {
        val dir = java.io.File(context.filesDir, "icons")
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, "floating_icon.png")
        val out = java.io.FileOutputStream(file)
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        out.close()
        _settingsState.update { it.copy(floatingBallCustomIconPath = file.absolutePath, floatingBallIconIndex = 2) }
        saveFloatingBallConfig()
    }

    private fun saveFloatingBallConfig() {
        val s = _settingsState.value
        val newCfg = FloatingBallConfig(
            sizeDp = s.floatingBallSizeDp,
            normalAlpha = s.floatingBallNormalAlpha,
            hiddenAlpha = s.floatingBallHiddenAlpha,
            hiddenRatio = s.floatingBallHiddenRatio,
            iconIndex = s.floatingBallIconIndex,
            customIconPath = s.floatingBallCustomIconPath
        )
        config = config.copy(floatingBall = newCfg)
        AppConfig.save(context, config)
        FloatingWidgetService.applyConfig(context, newCfg)
    }

    fun saveSettings() {
        val s = _settingsState.value
        val curConfig = config
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newProviderCfg = ProviderConfig(
                    apiKey = s.apiKey,
                    baseUrl = s.baseUrl,
                    model = s.model,
                    apiMode = s.apiMode
                )
                val newProviders = curConfig.llm.providers.toMutableMap().apply {
                    put(s.currentProvider, newProviderCfg)
                }
                val updated = curConfig.copy(
                    llm = curConfig.llm.copy(
                        provider = s.currentProvider,
                        providers = newProviders
                    ),
                    mcp = curConfig.mcp.copy(url = s.mcpUrl, enabled = s.mcpEnabled),
                    agent = curConfig.agent.copy(
                        max_iterations = s.maxIterations,
                        temperature = s.temperature
                    ),
                    workDirUri = s.workDirUri,
                    floatingEnabled = s.floatingEnabled,
                    floatingBall = FloatingBallConfig(
                        sizeDp = s.floatingBallSizeDp,
                        normalAlpha = s.floatingBallNormalAlpha,
                        hiddenAlpha = s.floatingBallHiddenAlpha,
                        hiddenRatio = s.floatingBallHiddenRatio,
                        iconIndex = s.floatingBallIconIndex,
                        customIconPath = s.floatingBallCustomIconPath
                    )
                )
                config = updated
                AppConfig.save(context, updated)

                if (_uiState.value.isBusy) {
                    _settingsState.update { it.copy(saveResult = "保存成功，设置将在当前任务完成后生效") }
                } else {
                    _settingsState.update { it.copy(saveResult = "保存成功") }
                    reconnect()
                }
            } catch (e: Exception) {
                _settingsState.update { it.copy(saveResult = "[错误] ${e.message}") }
            }
        }
    }

    // ---------- 余额/模型/文件查询 ----------

    fun queryBalance() {
        if (llm == null) {
            _toolsState.update { it.copy(balanceResult = "未连接 AI 模型，请先在设置中配置并连接") }
            return
        }
        val client = llm!!
        viewModelScope.launch(Dispatchers.IO) {
            _toolsState.update { it.copy(isQuerying = true) }
            try {
                val result = client.balance()
                _toolsState.update { it.copy(balanceResult = result) }
            } catch (e: Exception) {
                _toolsState.update { it.copy(balanceResult = "[错误] ${e.message}") }
            } finally {
                _toolsState.update { it.copy(isQuerying = false) }
            }
        }
    }

    fun queryModels() {
        if (llm == null) {
            _toolsState.update { it.copy(modelsResult = "未连接 AI 模型，请先在设置中配置并连接") }
            return
        }
        val client = llm!!
        viewModelScope.launch(Dispatchers.IO) {
            _toolsState.update { it.copy(isQuerying = true) }
            try {
                val models = client.listModels()
                _toolsState.update { it.copy(modelsResult = models.joinToString("\n")) }
            } catch (e: Exception) {
                _toolsState.update { it.copy(modelsResult = "[错误] ${e.message}") }
            } finally {
                _toolsState.update { it.copy(isQuerying = false) }
            }
        }
    }

    // ---------- APK 产物栏 ----------

    fun openArtifacts() {
        _artifactsState.update { it.copy(visible = true) }
        refreshWorkDir()
    }

    fun closeArtifacts() {
        _artifactsState.update { it.copy(visible = false) }
    }

    fun sendPrompt(prompt: String) {
        _uiState.update { it.copy(input = prompt) }
        closeArtifacts()
        send()
    }

    // ---------- 文件操作 ----------

    fun refreshWorkDir() {
        viewModelScope.launch(Dispatchers.IO) {
            _artifactsState.update { it.copy(loading = true, error = "") }
            try {
                val files = scanWorkingDir()
                _artifactsState.update {
                    it.copy(files = files, loading = false)
                }
            } catch (e: Exception) {
                _artifactsState.update {
                    it.copy(error = "扫描失败: ${e.message}", loading = false)
                }
            }
        }
    }

    private fun scanWorkingDir(): List<WorkFile> {
        val results = mutableListOf<WorkFile>()
        val uriStr = config.workDirUri
        if (uriStr.isBlank()) return results
        try {
            val rootUri = Uri.parse(uriStr)
            val root = DocumentFile.fromTreeUri(context, rootUri) ?: return results
            root.listFiles().forEach { file ->
                val name = file.name ?: return@forEach
                val isApk = name.endsWith(".apk", ignoreCase = true)
                val textExts = listOf(".txt", ".md", ".json", ".xml", ".html", ".css", ".js", ".kt", ".java", ".py", ".log", ".yaml", ".yml", ".gradle")
                val isText = textExts.any { name.lowercase().endsWith(it) }
                results.add(WorkFile(
                    name = name,
                    path = file.uri.toString(),
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    contentUri = file.uri,
                    isApk = isApk,
                    isText = isText
                ))
            }
        } catch (_: Exception) {}
        return results.sortedByDescending { it.lastModified }
    }

    fun installWorkFile(file: WorkFile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = file.contentUri
                if (uri != null) {
                    val destDir = File(context.cacheDir, "apks")
                    destDir.mkdirs()
                    val dest = File(destDir, file.name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (dest.exists() && dest.length() > 0) {
                        val fileUri = androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", dest
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(fileUri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        _artifactsState.update { it.copy(error = "已调起系统安装器: ${file.name}") }
                        return@launch
                    }
                }
                _artifactsState.update { it.copy(error = "无法安装: 文件不可访问") }
            } catch (e: Exception) {
                _artifactsState.update { it.copy(error = "[错误] ${e.message}") }
            }
        }
    }

    fun analyzeWorkFile(file: WorkFile) {
        val prompt = if (file.isText) {
            try {
                val content = readFileContent(file)
                "请帮我分析文件 ${file.name}:\n\n$content"
            } catch (_: Exception) {
                "请帮我分析文件 ${file.name}"
            }
        } else {
            "请帮我分析 ${file.name} (大小: ${file.sizeBytes} bytes)"
        }
        _uiState.update { it.copy(input = prompt) }
        closeArtifacts()
        send()
    }

    fun openWorkFile(file: WorkFile) {
        val uri = file.contentUri
        if (uri == null) {
            _artifactsState.update { it.copy(snackbarMessage = "未知文件，无法打开") }
            return
        }

        val mimeType = context.contentResolver.getType(uri)
        val fileName = file.name.lowercase()

        when {
            file.isApk -> installWorkFile(file)

            mimeType != null -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val resolveInfo = context.packageManager.resolveActivity(intent, 0)
                    if (resolveInfo != null) {
                        context.startActivity(intent)
                    } else {
                        _artifactsState.update { it.copy(snackbarMessage = "未知文件，无法打开") }
                    }
                } catch (e: Exception) {
                    _artifactsState.update { it.copy(snackbarMessage = "未知文件，无法打开") }
                }
            }

            else -> {
                val knownExts = listOf(
                    ".txt", ".md", ".json", ".xml", ".html", ".htm", ".css", ".js", ".ts",
                    ".kt", ".java", ".py", ".c", ".cpp", ".h", ".cs", ".go", ".rs", ".rb",
                    ".php", ".swift", ".log", ".yaml", ".yml", ".gradle", ".properties",
                    ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".svg",
                    ".mp4", ".avi", ".mov", ".mkv", ".webm",
                    ".mp3", ".wav", ".ogg", ".flac", ".aac",
                    ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
                    ".zip", ".rar", ".7z", ".tar", ".gz",
                    ".apk", ".so", ".dex", ".jar"
                )
                val isKnown = knownExts.any { fileName.endsWith(it) }
                if (isKnown) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "*/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        _artifactsState.update { it.copy(snackbarMessage = "未知文件，无法打开") }
                    }
                } else {
                    _artifactsState.update { it.copy(snackbarMessage = "未知文件，无法打开") }
                }
            }
        }
    }

    fun clearArtifactsSnackbar() {
        _artifactsState.update { it.copy(snackbarMessage = "") }
    }

    fun enterSelectMode() {
        _artifactsState.update { it.copy(selectMode = true, selectedNames = emptySet()) }
    }

    fun exitSelectMode() {
        _artifactsState.update { it.copy(selectMode = false, selectedNames = emptySet()) }
    }

    fun toggleFileSelection(name: String) {
        _artifactsState.update { state ->
            val newSet = state.selectedNames.toMutableSet()
            if (name in newSet) newSet.remove(name) else newSet.add(name)
            state.copy(selectedNames = newSet)
        }
    }

    fun selectAllFiles() {
        _artifactsState.update { state ->
            state.copy(selectedNames = state.files.map { it.name }.toSet())
        }
    }

    fun invertFileSelection() {
        _artifactsState.update { state ->
            val allNames = state.files.map { it.name }.toSet()
            val inverted = allNames - state.selectedNames
            state.copy(selectedNames = inverted)
        }
    }

    fun deleteSelectedFiles() {
        val state = _artifactsState.value
        val selected = state.selectedNames
        if (selected.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            var deletedCount = 0
            var failedCount = 0
            val currentFiles = state.files

            for (file in currentFiles) {
                if (file.name in selected) {
                    try {
                        val rootUri = Uri.parse(config.workDirUri)
                        val root = DocumentFile.fromTreeUri(context, rootUri)
                        if (root != null) {
                            val docFile = root.findFile(file.name)
                            if (docFile != null && docFile.delete()) {
                                deletedCount++
                            } else {
                                failedCount++
                            }
                        } else {
                            failedCount++
                        }
                    } catch (_: Exception) {
                        failedCount++
                    }
                }
            }

            val newFiles = _artifactsState.value.files.filter { it.name !in selected }
            _artifactsState.update {
                it.copy(
                    files = newFiles,
                    selectedNames = emptySet(),
                    selectMode = false,
                    snackbarMessage = "删除完成: 成功 $deletedCount 个${if (failedCount > 0) "，失败 $failedCount 个" else ""}"
                )
            }
        }
    }

    fun renameFile(oldName: String, newName: String) {
        if (newName.isBlank()) return
        val state = _artifactsState.value

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val rootUri = Uri.parse(config.workDirUri)
                val root = DocumentFile.fromTreeUri(context, rootUri)
                if (root == null) {
                    _artifactsState.update { it.copy(snackbarMessage = "无法访问工作目录") }
                    return@launch
                }

                val docFile = root.findFile(oldName)
                if (docFile == null) {
                    _artifactsState.update { it.copy(snackbarMessage = "文件不存在") }
                    return@launch
                }

                val success = docFile.renameTo(newName)
                if (success) {
                    refreshWorkDir()
                    _artifactsState.update { it.copy(snackbarMessage = "已重命名为 $newName") }
                } else {
                    _artifactsState.update { it.copy(snackbarMessage = "重命名失败") }
                }
            } catch (e: Exception) {
                _artifactsState.update { it.copy(snackbarMessage = "重命名失败: ${e.message}") }
            }
        }
    }

    private fun readFileContent(file: WorkFile): String {
        val uri = file.contentUri ?: return "(无法读取文件)"
        return try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                it.readText().take(8000)
            } ?: "(无法读取文件)"
        } catch (_: Exception) {
            "(读取失败)"
        }
    }

    fun reconnect() {
        if (_uiState.value.isBusy) {
            _uiState.update { it.copy(snackbarMessage = "正在生成中，请等待完成后再重连") }
            return
        }
        _uiState.update {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                status = "重新连接中..."
            )
        }
        llm = null
        mcp = null
        agent = null
        connect()
    }
}
