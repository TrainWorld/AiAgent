package com.example.aiagent

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    }

    // ──────────────────── 依赖 ────────────────────

    private val engine: InferenceEngine = AiChat.getInferenceEngine(application)
    private val modelManager = ModelManager(application)
    private val sessionManager = SessionManager(application)
    private val messagesMutex = Mutex() // 保护消息列表的并发访问

    // ──────────────────── 状态 ────────────────────

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

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
                    .onCompletion {
                        // AI 回复完成（或被中止）后持久化
                        viewModelScope.launch(Dispatchers.Default) {
                            messagesMutex.withLock {
                                sessionManager.saveMessages(sessionId, _messages.value)
                            }
                        }
                    }
                    .collect { token ->
                        messagesMutex.withLock {
                            val cur = _messages.value[aiIndex]
                            _messages.value = _messages.value.toMutableList().also {
                                it[aiIndex] = cur.copy(content = cur.content + token)
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "生成失败", e)
                messagesMutex.withLock {
                    _messages.value = _messages.value.toMutableList().also {
                        it[aiIndex] = Message("[生成错误]", false)
                    }
                }
            }
        }
    }

    fun stopGeneration() {
        engine.stopGeneration()
    }

    fun clearConversation() {
        val sessionId = _currentSessionId.value ?: return
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
    }
}
