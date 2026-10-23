package com.example.aiagent

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arm.aichat.InferenceEngine
import com.example.aiagent.ui.components.*
import com.example.aiagent.ui.drawer.SessionDrawerContent
import com.example.aiagent.ui.theme.AIAgentTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val pickModelLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.onModelPicked(it) } }

    private lateinit var viewModel: ChatViewModel

    // ─────────────────────── Lifecycle ───────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AIAgentTheme {
                viewModel = viewModel()
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

        val isModelReady = engineState is InferenceEngine.State.ModelReady ||
                engineState is InferenceEngine.State.Generating
        val isBusy = engineState is InferenceEngine.State.LoadingModel ||
                engineState is InferenceEngine.State.UnloadingModel ||
                engineState is InferenceEngine.State.Initializing ||
                engineState is InferenceEngine.State.ProcessingSystemPrompt ||
                engineState is InferenceEngine.State.ProcessingUserPrompt
        val statusLabel = statusText(engineState)

        val listState = rememberLazyListState()
        var showModelSheet by remember { mutableStateOf(false) }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val currentSessionTitle = sessions.find { it.id == currentSessionId }?.title ?: "AI Chat"

        // 消息列表变化时自动滚动到底部
        // 滚动到末尾占位符（高度为0），其位置即消息内容底部
        LaunchedEffect(Unit) {
            snapshotFlow { messages.size to messages.lastOrNull()?.content }.collect { (size, _) ->
                if (size > 0) {
                    listState.scrollToItem(size) // size = 占位符索引（messages 之后）
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
                        onManageModels = {
                            scope.launch { drawerState.close() }
                            showModelSheet = true
                        },
                    )
                }
            },
        ) {
            Scaffold(
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { viewModel.updateInputText(it) },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("输入消息…") },
                                enabled = isModelReady,
                                maxLines = 4,
                            )
                            Spacer(Modifier.width(8.dp))
                            if (isGenerating) {
                                Button(
                                    onClick = { viewModel.stopGeneration() },
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
                                    onClick = { viewModel.sendMessage() },
                                    enabled = inputText.isNotBlank() && isModelReady,
                                ) {
                                    Text("发送")
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
                    if (messages.isEmpty()) {
                        EmptyHint(isModelReady, Modifier
                            .weight(1f)
                            .fillMaxWidth())
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                items = messages,
                                key = { it.id }
                            ) { MessageBubble(it) }
                            // 末尾占位符：高度为0，scrollToItem 到此处即对齐到内容底部
                            item(key = "bottom_spacer") {
                                Spacer(Modifier.height(0.dp))
                            }
                        }
                    }
                }
            }
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
            }
        }
    }

    // ─────────────────────── Helpers ───────────────────────

    private fun statusText(state: InferenceEngine.State): String = when (state) {
        is InferenceEngine.State.Uninitialized -> "未初始化"
        is InferenceEngine.State.Initializing -> "引擎初始化中…"
        is InferenceEngine.State.Initialized -> "引擎就绪，未加载模型"
        is InferenceEngine.State.LoadingModel -> "模型加载中…"
        is InferenceEngine.State.ModelReady -> {
            val name = viewModel.getCurrentModelName() ?: ""
            "模型就绪：$name"
        }

        is InferenceEngine.State.Generating -> "生成中…"
        is InferenceEngine.State.UnloadingModel -> "卸载模型中…"
        is InferenceEngine.State.Error -> "错误：${state.exception.javaClass.simpleName}"
        else -> state.toString()
    }
}
