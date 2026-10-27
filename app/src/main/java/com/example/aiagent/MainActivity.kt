package com.example.aiagent

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arm.aichat.InferenceEngine
import com.arm.aichat.multimodal.MultimodalEngine
import com.example.aiagent.ui.components.*
import com.example.aiagent.ui.drawer.SessionDrawerContent
import com.example.aiagent.ui.theme.AIAgentTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val pickModelLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.onModelPicked(it) } }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.addAttachment(it) } }

    private val pickMmModelLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.onMmModelPicked(it) } }

    private val pickMmprojLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.onMmprojPicked(it) } }

    private val viewModel: ChatViewModel by viewModels()

    // ─────────────────────── Lifecycle ───────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AIAgentTheme {
                MainScreen()
            }
        }
    }

    // ─────────────────────── Compose UI ───────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainScreen() {
        val messages by viewModel.messages.collectAsState()
        val inputText by viewModel.inputText.collectAsState()
        val engineState by viewModel.engineState.collectAsState()
        val isGenerating by viewModel.isGenerating.collectAsState()
        val sessions by viewModel.sessions.collectAsState()
        val currentSessionId by viewModel.currentSessionId.collectAsState()
        val pendingAttachments by viewModel.pendingAttachments.collectAsState()
        val mmEngineState by viewModel.multimodalEngineState.collectAsState()
        val isMmGenerating by viewModel.isMultimodalGenerating.collectAsState()
        val mmModelUri by viewModel.mmModelUri.collectAsState()
        val mmMmprojUri by viewModel.mmMmprojUri.collectAsState()
        val isMmPreparing by viewModel.isMmPreparing.collectAsState()
        val mmLoadError by viewModel.mmLoadError.collectAsState()
        val streamingAiContent by viewModel.streamingAiContent.collectAsState()
        val streamingAiIndex by viewModel.streamingAiIndex.collectAsState()
        val inferenceParams by viewModel.inferenceParams.collectAsState()

        // 合并流式内容到消息列表（仅用于显示，不修改原始 messages）
        val displayMessages by remember {
            derivedStateOf {
                val idx = streamingAiIndex
                val content = streamingAiContent
                if (idx >= 0 && idx < messages.size && content.isNotEmpty()) {
                    messages.toMutableList().also {
                        it[idx] = it[idx].copy(content = content)
                    }
                } else {
                    messages
                }
            }
        }

        val isModelReady = engineState is InferenceEngine.State.ModelReady ||
                engineState is InferenceEngine.State.Generating
        val isMmReady = mmEngineState is MultimodalEngine.State.Ready ||
                mmEngineState is MultimodalEngine.State.Generating
        val isBusy = engineState is InferenceEngine.State.LoadingModel ||
                engineState is InferenceEngine.State.UnloadingModel ||
                engineState is InferenceEngine.State.Initializing ||
                engineState is InferenceEngine.State.ProcessingSystemPrompt ||
                engineState is InferenceEngine.State.ProcessingUserPrompt ||
                isMmPreparing ||
                mmEngineState is MultimodalEngine.State.LoadingModel
        val statusLabel = buildStatusLabel(engineState, mmEngineState, isMmPreparing)

        // 多模态模式：有待发送的附件 或 多模态引擎正在生成
        val isMultimodalMode = pendingAttachments.isNotEmpty() || isMmGenerating
        val effectiveGenerating = isGenerating || isMmGenerating

        val listState = rememberLazyListState()
        var showModelSheet by remember { mutableStateOf(false) }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val currentSessionTitle = sessions.find { it.id == currentSessionId }?.title ?: "AI Chat"

        // 会话重命名对话框状态
        var renameSessionId by remember { mutableStateOf<String?>(null) }
        var renameText by remember { mutableStateOf("") }

        // 显示多模态加载错误 Snackbar
        LaunchedEffect(mmLoadError) {
            mmLoadError?.let { error ->
                snackbarHostState.showSnackbar(error)
                viewModel.clearMmLoadError()
            }
        }

        // 消息列表变化时自动滚动到底部
        LaunchedEffect(Unit) {
            snapshotFlow { displayMessages.size to displayMessages.lastOrNull()?.content }.collect { (size, _) ->
                if (size > 0) {
                    listState.scrollToItem(size)
                }
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = {
                ModalDrawerSheet {
                    SessionDrawerContent(
                        sessions = sessions,
                        currentSessionId = currentSessionId,
                        onNewSession = {
                            scope.launch { drawerState.close() }
                            viewModel.createSession()
                        },
                        onSelectSession = { id ->
                            scope.launch { drawerState.close() }
                            viewModel.switchSession(id)
                        },
                        onDeleteSession = { id ->
                            viewModel.deleteSession(id)
                        },
                        onRenameSession = { id, currentTitle ->
                            renameSessionId = id
                            renameText = currentTitle
                        },
                        onManageModels = {
                            scope.launch { drawerState.close() }
                            showModelSheet = true
                        },
                    )
                }
            },
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "会话列表")
                            }
                        },
                        title = {
                            Text(
                                currentSessionTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        actions = {
                            // 清空对话
                            if (messages.isNotEmpty()) {
                                IconButton(onClick = { viewModel.clearConversation() }) {
                                    Icon(Icons.Default.Delete, contentDescription = "清空对话")
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    )
                },
                bottomBar = {
                    // 输入区域
                    Surface(tonalElevation = 3.dp) {
                        Column {
                            // 附件预览条
                            AttachmentPreviewBar(
                                attachments = pendingAttachments,
                                onRemove = { viewModel.removeAttachment(it) }
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 图片附件按钮
                                IconButton(
                                    onClick = { pickImageLauncher.launch("image/*") }
                                ) {
                                    Icon(
                                        Icons.Default.Image,
                                        contentDescription = "添加图片",
                                        tint = if (isMmReady)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    )
                                }
                                Spacer(Modifier.width(4.dp))

                                OutlinedTextField(
                                    value = inputText,
                                    onValueChange = { viewModel.updateInputText(it) },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("输入消息…") },
                                    enabled = isModelReady || isMmReady,
                                    maxLines = 4,
                                )
                                Spacer(Modifier.width(8.dp))
                                if (effectiveGenerating) {
                                    Button(
                                        onClick = {
                                            if (isMmGenerating) viewModel.stopMultimodalGeneration()
                                            else viewModel.stopGeneration()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                        ),
                                    ) {
                                        Icon(
                                            Icons.Default.Stop,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("停止")
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            when {
                                                pendingAttachments.isNotEmpty() -> {
                                                    // 有附件 → 始终走多模态路径
                                                    viewModel.sendMultimodalMessage()
                                                }
                                                isModelReady -> {
                                                    // 文本引擎就绪 → 走文本路径
                                                    viewModel.sendMessage()
                                                }
                                                isMmReady -> {
                                                    // 文本引擎未就绪但多模态引擎就绪 → 走多模态路径
                                                    viewModel.sendMultimodalMessage()
                                                }
                                            }
                                        },
                                        enabled = (inputText.isNotBlank() || pendingAttachments.isNotEmpty()) &&
                                                (isModelReady || isMmReady),
                                    ) {
                                        if (isMultimodalMode) {
                                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                        } else {
                                            Text("发送")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    // 状态条
                    StatusBanner(statusLabel, isBusy)

                    // 消息列表
                    if (displayMessages.isEmpty()) {
                        EmptyHint(isModelReady, Modifier
                            .weight(1f)
                            .fillMaxWidth())
                    } else {
                        // 缓存每条消息的附件
                        val attachmentsMap = remember { mutableStateMapOf<String, List<Attachment>>() }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                items = displayMessages,
                                key = { it.id }
                            ) { message ->
                                // 为用户消息异步加载附件（LaunchedEffect 不能在条件块内）
                                LaunchedEffect(message.id, message.isUser) {
                                    if (message.isUser && !attachmentsMap.containsKey(message.id)) {
                                        val atts = withContext(Dispatchers.IO) {
                                            viewModel.getAttachmentsForMessage(message.id)
                                        }
                                        attachmentsMap[message.id] = atts
                                    }
                                }

                                val attachments = attachmentsMap[message.id] ?: emptyList()
                                if (attachments.isNotEmpty()) {
                                    MultimodalMessageBubble(
                                        messageContent = message.content,
                                        isUser = message.isUser,
                                        attachments = attachments,
                                        messageId = message.id,
                                        onDelete = { viewModel.deleteMessage(it) },
                                    )
                                } else {
                                    MessageBubble(
                                        message = message,
                                        onDelete = { viewModel.deleteMessage(it) },
                                    )
                                }
                            }
                            // 末尾占位符：高度为0，scrollToItem 到此处即对齐到内容底部
                            item(key = "bottom_spacer") {
                                Spacer(Modifier.height(0.dp))
                            }
                        }
                    }
                }
            }
        }

        // ─── 会话重命名对话框 ───
        if (renameSessionId != null) {
            AlertDialog(
                onDismissRequest = { renameSessionId = null },
                title = { Text("重命名会话") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("会话标题") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val id = renameSessionId
                        if (id != null && renameText.isNotBlank()) {
                            viewModel.renameSession(id, renameText)
                        }
                        renameSessionId = null
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { renameSessionId = null }) { Text("取消") }
                },
            )
        }

        // ─── 模型管理 BottomSheet ───
        if (showModelSheet) {
            ModalBottomSheet(onDismissRequest = { showModelSheet = false }) {
                ModelListContent(
                    engineState = engineState,
                    onImport = { pickModelLauncher.launch("*/*") },
                    onSelect = { model -> viewModel.selectModel(model) },
                    onDelete = { model -> viewModel.deleteModel(model) },
                    currentModelName = viewModel.getCurrentModelName(),
                    models = viewModel.listModels(),
                )

                // 多模态模型加载区域
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))

                Text(
                    "多模态模型",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )

                // 多模态引擎状态
                val mmStatusLabel = when {
                    isMmPreparing && mmEngineState !is MultimodalEngine.State.LoadingModel -> "准备文件中…"
                    else -> when (mmEngineState) {
                        is MultimodalEngine.State.Uninitialized -> "未初始化"
                        is MultimodalEngine.State.Initialized -> "引擎已初始化，等待加载模型"
                        is MultimodalEngine.State.LoadingModel -> "加载中…"
                        is MultimodalEngine.State.Ready -> "模型已就绪 ✓"
                        is MultimodalEngine.State.ProcessingPrompt -> "处理中…"
                        is MultimodalEngine.State.Generating -> "生成中…"
                        is MultimodalEngine.State.Error -> "错误: ${(mmEngineState as MultimodalEngine.State.Error).message}"
                    }
                }
                Text(
                    "状态: $mmStatusLabel",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    color = if (mmEngineState is MultimodalEngine.State.Ready)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))

                // 选择模型文件
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "模型: ${mmModelUri?.lastPathSegment?.substringAfterLast('/') ?: "未选择"}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    TextButton(onClick = { pickMmModelLauncher.launch("*/*") }) {
                        Text("选择")
                    }
                }

                // 选择 mmproj 文件
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "mmproj: ${mmMmprojUri?.lastPathSegment?.substringAfterLast('/') ?: "未选择"}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    TextButton(onClick = { pickMmprojLauncher.launch("*/*") }) {
                        Text("选择")
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 加载按钮
                Button(
                    onClick = { viewModel.loadSelectedMmModel() },
                    enabled = mmModelUri != null && mmMmprojUri != null && !isMmPreparing,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    if (isMmPreparing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("加载多模态模型")
                }

                Spacer(Modifier.height(16.dp))

                // ─── 推理参数设置 ───
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))

                Text(
                    "推理参数",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )

                // 温度滑块
                var tempValue by remember { mutableFloatStateOf(inferenceParams.temperature) }
                Text(
                    "温度: ${"%.2f".format(tempValue)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Slider(
                    value = tempValue,
                    onValueChange = { tempValue = it },
                    valueRange = 0.0f..2.0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                )

                // 上下文大小
                var ctxValue by remember { mutableIntStateOf(inferenceParams.contextSize) }
                Text(
                    "上下文大小: $ctxValue tokens",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(2048, 4096, 8192, 16384).forEach { size ->
                        FilterChip(
                            selected = ctxValue == size,
                            onClick = { ctxValue = size },
                            label = { Text("${size / 1024}K", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                // 线程数
                var threadValue by remember { mutableIntStateOf(inferenceParams.nThreads) }
                Text(
                    "线程数: ${if (threadValue == 0) "自动" else threadValue.toString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(0, 1, 2, 4).forEach { n ->
                        FilterChip(
                            selected = threadValue == n,
                            onClick = { threadValue = n },
                            label = { Text(if (n == 0) "自动" else "$n", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 应用按钮
                Button(
                    onClick = {
                        viewModel.updateInferenceParams(
                            ChatViewModel.InferenceParams(
                                temperature = tempValue,
                                contextSize = ctxValue,
                                nThreads = threadValue,
                            )
                        )
                    },
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Text("应用参数")
                }

                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    // ─────────────────────── Helpers ───────────────────────
    
    /**
     * 综合文本引擎和多模态引擎状态，生成统一的状态标签。
     */
    private fun buildStatusLabel(
        engineState: InferenceEngine.State,
        mmEngineState: MultimodalEngine.State,
        isMmPreparing: Boolean,
    ): String {
        val textLabel = statusText(engineState)
    
        // 多模态引擎状态
        val mmLabel = when {
            isMmPreparing && mmEngineState !is MultimodalEngine.State.LoadingModel -> "多模态: 准备文件中\u2026"
            else -> when (mmEngineState) {
                is MultimodalEngine.State.Uninitialized -> null
                is MultimodalEngine.State.Initialized -> null // 不显示“等待加载模型”，避免干扰
                is MultimodalEngine.State.LoadingModel -> "多模态: 加载中\u2026"
                is MultimodalEngine.State.Ready -> "多模态模型已就绪 \u2713"
                is MultimodalEngine.State.ProcessingPrompt -> "多模态: 处理中\u2026"
                is MultimodalEngine.State.Generating -> "多模态: 生成中\u2026"
                is MultimodalEngine.State.Error -> "多模态错误: ${mmEngineState.message}"
            }
        }
    
        return when {
            mmLabel != null && engineState is InferenceEngine.State.ModelReady -> "$textLabel | $mmLabel"
            mmLabel != null -> mmLabel
            else -> textLabel
        }
    }
    
    private fun statusText(state: InferenceEngine.State): String = when (state) {
        is InferenceEngine.State.Uninitialized -> "未初始化"
        is InferenceEngine.State.Initializing -> "引擎初始化中\u2026"
        is InferenceEngine.State.Initialized -> "引擎就绪，未加载模型"
        is InferenceEngine.State.LoadingModel -> "模型加载中\u2026"
        is InferenceEngine.State.ModelReady -> {
            val name = viewModel.getCurrentModelName() ?: ""
            "文本模型就绪：$name"
        }
    
        is InferenceEngine.State.Generating -> "生成中\u2026"
        is InferenceEngine.State.UnloadingModel -> "卸载模型中\u2026"
        is InferenceEngine.State.Error -> "错误：${state.exception.javaClass.simpleName}"
        else -> state.toString()
    }
}
