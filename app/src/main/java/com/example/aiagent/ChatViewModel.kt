package com.example.aiagent

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.multimodal.MultimodalEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 聊天界面的 ViewModel，持有所有业务状态与逻辑。
 *
 * - 消息列表、输入文本、引擎状态均通过 [StateFlow] 暴露给 Compose UI
 * - 模型管理由 [ModelManager] 负责
 * - 多会话管理、消息持久化由 [SessionManager] 负责
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val SESSION_TITLE_MAX_LENGTH = 20
        private const val PREFS_NAME = "ai_agent_inference_params"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_CONTEXT_SIZE = "context_size"
        private const val KEY_N_THREADS = "n_threads"
    }

    /** 推理参数配置 */
    data class InferenceParams(
        val temperature: Float = 0.3f,
        val contextSize: Int = 8192,
        val nThreads: Int = 0, // 0 = 自动
    )

    // ──────────────────── 依赖 ────────────────────

    private val engine: InferenceEngine = AiChat.getInferenceEngine(application)
    private val modelManager = ModelManager(application)
    private val sessionManager = SessionManager(application)
    private val messagesMutex = Mutex() // 保护消息列表的并发访问

    // ──────────────────── 推理参数 ────────────────────

    private val prefs = getApplication<Application>()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _inferenceParams = MutableStateFlow(loadInferenceParams())
    val inferenceParams: StateFlow<InferenceParams> = _inferenceParams.asStateFlow()

    // ──────────────────── 多模态 ────────────────────

    private val multimodalEngine: MultimodalEngine = MultimodalEngine.create(application)
    private val multimodalStorage = MultimodalStorage(application)

    private val _pendingAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    val pendingAttachments: StateFlow<List<Attachment>> = _pendingAttachments.asStateFlow()

    val multimodalEngineState: StateFlow<MultimodalEngine.State> = multimodalEngine.state

    val isMultimodalGenerating: StateFlow<Boolean> = multimodalEngineState.map { state ->
        state is MultimodalEngine.State.Generating ||
                state is MultimodalEngine.State.ProcessingPrompt
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 多模态模型文件选择状态
    private val _mmModelUri = MutableStateFlow<Uri?>(null)
    val mmModelUri: StateFlow<Uri?> = _mmModelUri.asStateFlow()

    private val _mmMmprojUri = MutableStateFlow<Uri?>(null)
    val mmMmprojUri: StateFlow<Uri?> = _mmMmprojUri.asStateFlow()

    val isMmLoading: StateFlow<Boolean> = multimodalEngineState.map { state ->
        state is MultimodalEngine.State.LoadingModel
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 文件解析阶段状态（大文件拷贝到缓存）
    private val _isMmResolving = MutableStateFlow(false)
    val isMmPreparing: StateFlow<Boolean> = _isMmResolving
        .combine(isMmLoading) { resolving, loading -> resolving || loading }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 多模态加载错误消息（Snackbar 显示）
    private val _mmLoadError = MutableStateFlow<String?>(null)
    val mmLoadError: StateFlow<String?> = _mmLoadError.asStateFlow()

    fun clearMmLoadError() { _mmLoadError.value = null }

    // ──────────────────── 状态 ────────────────────

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    /** 流式生成中的 AI 回复内容（与 messages 分离，避免每 token 拷贝整个列表） */
    private val _streamingAiContent = MutableStateFlow("")
    val streamingAiContent: StateFlow<String> = _streamingAiContent.asStateFlow()

    /** 流式生成中的 AI 消息在 messages 列表中的索引，-1 表示无流式消息 */
    private val _streamingAiIndex = MutableStateFlow(-1)
    val streamingAiIndex: StateFlow<Int> = _streamingAiIndex.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    val engineState: StateFlow<InferenceEngine.State> = engine.state

    /** 引擎是否正在生成（含处理用户提示词阶段） */
    val isGenerating: StateFlow<Boolean> = engineState.map { state ->
        state is InferenceEngine.State.Generating ||
                state is InferenceEngine.State.ProcessingUserPrompt
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            // 加载会话列表
            val loadedSessions = sessionManager.loadSessions()
            _sessions.value = loadedSessions

            // 恢复上次活跃的会话
            val savedSessionId = sessionManager.loadCurrentSessionId()
            val targetSession = if (savedSessionId != null && loadedSessions.any { it.id == savedSessionId }) {
                savedSessionId
            } else if (loadedSessions.isNotEmpty()) {
                loadedSessions.first().id
            } else {
                // 没有任何会话，创建一个默认的
                val newSession = sessionManager.createSession()
                _sessions.value = listOf(newSession)
                newSession.id
            }
            _currentSessionId.value = targetSession

            // 加载该会话的消息
            val history = sessionManager.loadMessages(targetSession)
            if (history.isNotEmpty()) {
                _messages.value = history
                Log.i(TAG, "已恢复会话 $targetSession 的 ${history.size} 条消息")
            }

            // 自动加载上次使用的模型
            autoLoadSavedModel()

            // 清理过期的多模态附件缓存
            multimodalStorage.cleanExpiredCache()

            // 应用推理参数到引擎
            applyInferenceParams()
        }
    }

    // ──────────────────── 推理参数管理 ────────────────────

    fun updateInferenceParams(params: InferenceParams) {
        _inferenceParams.value = params
        saveInferenceParams(params)
        applyInferenceParams()
    }

    private fun loadInferenceParams(): InferenceParams {
        return InferenceParams(
            temperature = prefs.getFloat(KEY_TEMPERATURE, 0.3f),
            contextSize = prefs.getInt(KEY_CONTEXT_SIZE, 8192),
            nThreads = prefs.getInt(KEY_N_THREADS, 0),
        )
    }

    private fun saveInferenceParams(params: InferenceParams) {
        prefs.edit()
            .putFloat(KEY_TEMPERATURE, params.temperature)
            .putInt(KEY_CONTEXT_SIZE, params.contextSize)
            .putInt(KEY_N_THREADS, params.nThreads)
            .apply()
    }

    private fun applyInferenceParams() {
        val p = _inferenceParams.value
        try {
            engine.setParams(p.temperature, p.contextSize, p.nThreads)
            multimodalEngine.setParams(p.temperature, p.contextSize, p.nThreads)
            Log.i(TAG, "推理参数已应用: temp=${p.temperature}, ctx=${p.contextSize}, threads=${p.nThreads}")
        } catch (e: Exception) {
            Log.e(TAG, "应用推理参数失败", e)
        }
    }

    // ──────────────────── 模型加载 ────────────────────

    private suspend fun autoLoadSavedModel() {
        val savedName = modelManager.getCurrentModelName() ?: return
        val models = modelManager.listModels()
        val saved = models.find { it.displayName == savedName } ?: return
        try {
            loadModelFile(saved.file)
        } catch (e: Exception) {
            Log.e(TAG, "自动加载模型失败: $savedName", e)
        }
    }

    /** 由 Activity 的 ActivityResultCallback 调用 */
    fun onModelPicked(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val displayName = resolveFileName(uri) ?: "model_${System.currentTimeMillis()}"
                val nameNoExt = displayName.removeSuffix(".gguf")
                val target = modelManager.importModel(getApplication(), uri, nameNoExt)
                loadModelFile(target)
            } catch (e: Exception) {
                Log.e(TAG, "导入模型失败", e)
            }
        }
    }

    fun selectModel(model: ModelManager.ModelInfo) {
        modelManager.setCurrentModelName(model.displayName)
        viewModelScope.launch { loadModelFile(model.file) }
    }

    fun deleteModel(model: ModelManager.ModelInfo) {
        modelManager.deleteModel(model)
    }

    fun listModels(): List<ModelManager.ModelInfo> = modelManager.listModels()

    fun getCurrentModelName(): String? = modelManager.getCurrentModelName()

    // ──────────────────── 会话管理 ────────────────────

    /** 创建新会话并自动切换 */
    fun createSession() {
        viewModelScope.launch(Dispatchers.Default) {
            val newSession = sessionManager.createSession()
            _sessions.value = sessionManager.loadSessions()
            switchSessionInternal(newSession.id)
        }
    }

    /** 切换到指定会话 */
    fun switchSession(id: String) {
        if (id == _currentSessionId.value) return
        viewModelScope.launch(Dispatchers.Default) {
            switchSessionInternal(id)
        }
    }

    /** 内部方法：保存当前 → 切换 → 加载 → 重建上下文 */
    private suspend fun switchSessionInternal(id: String) {
        // 1. 停止当前生成
        engine.stopGeneration()
        multimodalEngine.stopGeneration()
        _streamingAiContent.value = ""
        _streamingAiIndex.value = -1

        // 2. 保存当前会话消息
        messagesMutex.withLock {
            _currentSessionId.value?.let { oldId ->
                sessionManager.saveMessages(oldId, _messages.value)
            }

            // 3. 切换到新会话
            _currentSessionId.value = id
            sessionManager.saveCurrentSessionId(id)
            _messages.value = sessionManager.loadMessages(id)
            _inputText.value = ""
        }
        Log.i(TAG, "已切换到会话 $id")

        // 4. 重建 native 上下文（cleanUp + reload 当前模型）
        rebuildNativeContext()

        // 5. 重置多模态引擎对话历史（会话隔离）
        multimodalEngine.resetConversation()
    }

    /** 删除会话；若删除的是当前会话，自动切到另一个 */
    fun deleteSession(id: String) {
        viewModelScope.launch(Dispatchers.Default) {
            sessionManager.deleteSession(id)
            val remaining = sessionManager.loadSessions()
            _sessions.value = remaining

            if (id == _currentSessionId.value) {
                if (remaining.isNotEmpty()) {
                    switchSessionInternal(remaining.first().id)
                } else {
                    val newSession = sessionManager.createSession()
                    _sessions.value = listOf(newSession)
                    switchSessionInternal(newSession.id)
                }
            }
        }
    }

    /** 重建 native 上下文：卸载当前模型再重新加载，清空 KV cache */
    private suspend fun rebuildNativeContext() {
        val state = engineState.value
        val isModelLoaded = state is InferenceEngine.State.ModelReady ||
                state is InferenceEngine.State.Error
        if (!isModelLoaded) return

        val modelName = modelManager.getCurrentModelName() ?: return
        val model = modelManager.listModels().find { it.displayName == modelName } ?: return
        try {
            engine.cleanUp()
            loadModelFile(model.file)
            Log.i(TAG, "Native 上下文已重建")
        } catch (e: Exception) {
            Log.e(TAG, "重建上下文失败", e)
        }
    }

    // ──────────────────── 消息 ────────────────────

    fun sendMessage() {
        val content = _inputText.value
        if (content.isBlank()) return
        val state = engineState.value
        if (state !is InferenceEngine.State.ModelReady) {
            Log.w(TAG, "引擎未就绪，忽略发送 (state=$state)")
            return
        }
        _inputText.value = ""

        val sessionId = _currentSessionId.value ?: return

        viewModelScope.launch(Dispatchers.Default) {
            messagesMutex.withLock {
                val current = _messages.value
                _messages.value = current + Message(content, true) + Message("", false)
            }
            val aiIndex = _messages.value.size - 1

            // 启动流式状态
            _streamingAiIndex.value = aiIndex
            _streamingAiContent.value = ""

            // 首条用户消息 → 自动生成会话标题
            val userMsgCount = _messages.value.count { it.isUser }
            if (userMsgCount == 1) {
                val title = content.take(SESSION_TITLE_MAX_LENGTH).ifBlank { "新对话" }
                sessionManager.updateTitle(sessionId, title)
                _sessions.value = sessionManager.loadSessions()
            }

            // 保存用户消息
            sessionManager.saveMessages(sessionId, _messages.value)

            try {
                engine.sendUserPrompt(content)
                    .collect { token ->
                        // 只更新流式状态，不拷贝消息列表
                        _streamingAiContent.value += token
                    }

                // 流式完成：合并到消息列表（保留原 ID，避免 LazyColumn key 变化）
                val finalContent = _streamingAiContent.value
                messagesMutex.withLock {
                    val cur = _messages.value.toMutableList()
                    if (aiIndex < cur.size) {
                        cur[aiIndex] = cur[aiIndex].copy(content = finalContent)
                        _messages.value = cur
                    }
                }
                sessionManager.saveMessages(sessionId, _messages.value)
            } catch (e: Exception) {
                Log.e(TAG, "生成失败", e)
                val errorContent = _streamingAiContent.value.ifEmpty { "[生成错误]" }
                messagesMutex.withLock {
                    val cur = _messages.value.toMutableList()
                    if (aiIndex < cur.size) {
                        cur[aiIndex] = cur[aiIndex].copy(content = errorContent)
                        _messages.value = cur
                    }
                }
            } finally {
                _streamingAiContent.value = ""
                _streamingAiIndex.value = -1
            }
        }
    }

    /** 重命名会话 */
    fun renameSession(id: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch(Dispatchers.Default) {
            sessionManager.updateTitle(id, newTitle)
            _sessions.value = sessionManager.loadSessions()
        }
    }

    fun stopGeneration() {
        engine.stopGeneration()
    }

    fun clearConversation() {
        val sessionId = _currentSessionId.value ?: return
        engine.stopGeneration()
        multimodalEngine.stopGeneration()
        _streamingAiContent.value = ""
        _streamingAiIndex.value = -1
        viewModelScope.launch(Dispatchers.Default) {
            messagesMutex.withLock {
                _messages.value = emptyList()
                sessionManager.saveMessages(sessionId, emptyList())
            }
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    /** 删除指定消息并持久化 */
    fun deleteMessage(messageId: String) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            messagesMutex.withLock {
                val cur = _messages.value.toMutableList()
                cur.removeAll { it.id == messageId }
                _messages.value = cur
                sessionManager.saveMessages(sessionId, cur)
            }
        }
    }

    // ──────────────────── 多模态消息 ────────────────────

    fun addAttachment(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = multimodalStorage.cacheFromUri(uri.toString())
            if (path == null) {
                Log.e(TAG, "图片缓存失败，无法添加附件: $uri")
                return@launch
            }
            Log.i(TAG, "附件已缓存: $path (来源: $uri)")
            val att = Attachment(uri.toString(), Attachment.Type.IMAGE, path)
            _pendingAttachments.value = _pendingAttachments.value + att
        }
    }

    fun removeAttachment(uri: String) {
        _pendingAttachments.value = _pendingAttachments.value.filter { it.uri != uri }
    }

    fun clearAttachments() {
        _pendingAttachments.value = emptyList()
    }

    fun sendMultimodalMessage() {
        val content = _inputText.value
        val attachments = _pendingAttachments.value
        if (content.isBlank() && attachments.isEmpty()) return

        val sessionId = _currentSessionId.value ?: return
        val state = multimodalEngineState.value
        val engineReady = state is MultimodalEngine.State.Ready

        if (!engineReady) {
            Log.w(TAG, "多模态引擎未就绪 (state=$state)，显示错误提示")
        }

        // 清空输入和附件
        _inputText.value = ""
        _pendingAttachments.value = emptyList()

        val imagePaths = attachments.mapNotNull { it.localPath }
        Log.i(TAG, "发送多模态消息: text='${content.take(30)}', 图片数=${imagePaths.size}, paths=$imagePaths")

        viewModelScope.launch(Dispatchers.Default) {
            // 始终添加用户消息和 AI 回复占位符
            messagesMutex.withLock {
                val current = _messages.value
                val userMsg = Message(content, true)
                val aiMsg = Message("", false)
                _messages.value = current + userMsg + aiMsg
            }
            val aiIndex = _messages.value.size - 1

            // 保存附件元数据
            val userMsgId = _messages.value[aiIndex - 1].id
            multimodalStorage.saveAttachments(userMsgId, attachments)

            // 首条消息 → 自动生成标题
            val titleText = content.ifBlank { "[图片]" }
            val userMsgCount = _messages.value.count { it.isUser }
            if (userMsgCount == 1) {
                val title = titleText.take(SESSION_TITLE_MAX_LENGTH)
                sessionManager.updateTitle(sessionId, title)
                _sessions.value = sessionManager.loadSessions()
            }

            // 持久化用户消息
            sessionManager.saveMessages(sessionId, _messages.value)

            // 如果引擎未就绪，显示错误并返回
            if (!engineReady) {
                messagesMutex.withLock {
                    _messages.value = _messages.value.toMutableList().also {
                        it[aiIndex] = it[aiIndex].copy(content = "[多模态引擎未加载模型，请先在模型管理中加载多模态模型和 mmproj 文件]")
                    }
                }
                sessionManager.saveMessages(sessionId, _messages.value)
                return@launch
            }

            try {
                // 允许纯图片消息（空文本 + 图片）
                val promptText = content.ifBlank { "Please describe this image." }

                // 启动流式状态
                _streamingAiIndex.value = aiIndex
                _streamingAiContent.value = ""

                multimodalEngine.sendMultimodalPrompt(promptText, imagePaths)
                    .collect { token ->
                        _streamingAiContent.value += token
                    }

                // 流式完成：合并到消息列表（保留原 ID）
                val finalContent = _streamingAiContent.value
                messagesMutex.withLock {
                    val cur = _messages.value.toMutableList()
                    if (aiIndex < cur.size) {
                        cur[aiIndex] = cur[aiIndex].copy(content = finalContent)
                        _messages.value = cur
                    }
                }
                sessionManager.saveMessages(sessionId, _messages.value)
            } catch (e: Exception) {
                Log.e(TAG, "多模态生成失败", e)
                val errorContent = _streamingAiContent.value.ifEmpty { "[生成错误: ${e.message}]" }
                messagesMutex.withLock {
                    val cur = _messages.value.toMutableList()
                    if (aiIndex < cur.size) {
                        cur[aiIndex] = cur[aiIndex].copy(content = errorContent)
                        _messages.value = cur
                    }
                }
            } finally {
                _streamingAiContent.value = ""
                _streamingAiIndex.value = -1
            }
        }
    }

    fun loadMultimodalModel(modelPath: String, mmprojPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                multimodalEngine.loadModel(modelPath, mmprojPath)
                Log.i(TAG, "多模态模型加载完成")
            } catch (e: Exception) {
                Log.e(TAG, "加载多模态模型失败", e)
            }
        }
    }

    /** 由 Activity 的 ActivityResultCallback 调用，选择多模态模型文件 */
    fun onMmModelPicked(uri: Uri) {
        _mmModelUri.value = uri
        Log.i(TAG, "已选择多模态模型: $uri")
    }

    /** 由 Activity 的 ActivityResultCallback 调用，选择 mmproj 文件 */
    fun onMmprojPicked(uri: Uri) {
        _mmMmprojUri.value = uri
        Log.i(TAG, "已选择 mmproj: $uri")
    }

    /** 加载已选择的多模态模型和 mmproj 文件 */
    fun loadSelectedMmModel() {
        val modelUri = _mmModelUri.value ?: return
        val mmprojUri = _mmMmprojUri.value ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _isMmResolving.value = true
            try {
                // 将 URI 转换为文件路径（大文件拷贝到缓存可能耗时较长）
                val modelPath = resolveFilePath(modelUri)
                if (modelPath == null) {
                    _mmLoadError.value = "无法解析模型文件路径，请尝试使用文件管理器选择"
                    return@launch
                }
                val mmprojPath = resolveFilePath(mmprojUri)
                if (mmprojPath == null) {
                    _mmLoadError.value = "无法解析 mmproj 文件路径，请尝试使用文件管理器选择"
                    return@launch
                }
                _isMmResolving.value = false

                loadMultimodalModel(modelPath, mmprojPath)
                Log.i(TAG, "多模态模型加载完成: $modelPath + $mmprojPath")
            } catch (e: Exception) {
                Log.e(TAG, "加载多模态模型失败", e)
                _mmLoadError.value = "加载失败: ${e.message ?: "未知错误"}"
            } finally {
                _isMmResolving.value = false
            }
        }
    }

    /** 解析 URI 为文件路径 */
    private suspend fun resolveFilePath(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            // 尝试从 content resolver 获取文件路径
            val cursor = getApplication<Application>().contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.MediaStore.Files.FileColumns.DATA)
                    if (idx >= 0) return@withContext it.getString(idx)
                }
            }
            // 如果无法获取路径，尝试缓存文件
            val path = multimodalStorage.cacheFromUri(uri.toString())
            path
        } catch (e: Exception) {
            Log.e(TAG, "解析文件路径失败: $uri", e)
            null
        }
    }

    fun stopMultimodalGeneration() {
        multimodalEngine.stopGeneration()
    }

    suspend fun getAttachmentsForMessage(messageId: String): List<Attachment> {
        return multimodalStorage.loadAttachments(messageId)
    }

    // ──────────────────── 内部方法 ────────────────────

    private suspend fun loadModelFile(file: java.io.File) {
        withContext(Dispatchers.IO) {
            try {
                engine.loadModel(file.absolutePath)
                modelManager.setCurrentModelName(file.nameWithoutExtension)
            } catch (e: Exception) {
                Log.e(TAG, "加载模型失败: ${file.name}", e)
            }
        }
    }

    private fun resolveFileName(uri: Uri): String? {
        return getApplication<Application>().contentResolver.query(
            uri, null, null, null, null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && cursor.moveToFirst()) cursor.getString(nameIdx) else null
        }
    }

    // ──────────────────── 生命周期 ────────────────────

    override fun onCleared() {
        super.onCleared()
        // 保存当前会话 ID
        _currentSessionId.value?.let { sessionManager.saveCurrentSessionId(it) }
        engine.cleanUp()
        engine.destroy()
        // 清理多模态引擎
        multimodalEngine.cleanUp()
        multimodalEngine.destroy()
    }
}
