# AIAgent 项目文档

## 1. 项目概述

**AIAgent** 是一款 Android 端侧大语言模型（LLM）聊天应用，支持导入 GGUF 格式模型，在设备本地完成推理，无需联网。同时支持**多模态对话**（图片 + 文本），可加载多模态模型和 mmproj 文件实现图文理解。

- **应用 ID**：`com.example.aiagent`
- **最低 SDK**：33（Android 13）
- **编译 SDK**：36
- **Kotlin**：2.3.0
- **AGP**：8.9.1
- **Java**：17

---

## 2. 模块结构

```
AIAgent/
├── app/          ← 应用层（UI + 业务逻辑）
│   └── src/main/java/com/example/aiagent/
├── lib/          ← 推理引擎层（C++ native + JNI 桥接）
│   └── src/main/java/com/arm/aichat/
├── build.gradle.kts        ← 根构建脚本
├── settings.gradle.kts     ← 模块注册
└── gradle/libs.versions.toml ← 版本目录
```

### 2.1 app 模块

应用层，负责 UI 渲染、会话管理、模型管理。

| 依赖 | 版本 | 用途 |
|------|------|------|
| `androidx.core:core-ktx` | 1.12.0 | Android KTX 扩展 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.7.0 | 生命周期感知 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.7.0 | ViewModel + Compose |
| `androidx.activity:activity-compose` | 1.8.2 | Compose Activity |
| `compose-bom` | 2024.06.00 | Compose BOM |
| `material3` | BOM | Material Design 3 |
| `material-icons-extended` | BOM | 扩展图标集 |
| `io.coil-kt:coil-compose` | 2.6.0 | 异步图片加载（多模态附件预览） |
| `project(":lib")` | — | 推理引擎模块 |

### 2.2 lib 模块

推理引擎层，基于 llama.cpp 的 C++ native 实现，通过 JNI 桥接。

- **命名空间**：`com.arm.aichat`
- **NDK 版本**：29.0.13113456
- **ABI**：`arm64-v8a`、`x86_64`
- **CMake**：3.31.6
- **额外依赖**：CameraX 1.3.0（用于 Agent 工具中的相机能力）

---

## 3. 架构设计

### 3.1 整体架构

```
┌─────────────────────────────────────────────────┐
│                    UI 层 (Compose)               │
│  MainActivity → MainScreen → 各独立 UI 组件       │
├─────────────────────────────────────────────────┤
│                 ViewModel 层                      │
│  ChatViewModel (StateFlow → UI, Mutex → 线程安全) │
├─────────────────────────────────────────────────┤
│                  业务层                           │
│  SessionManager (多会话持久化)                     │
│  ModelManager   (模型文件管理)                     │
├─────────────────────────────────────────────────┤
│                  引擎层 (lib)                     │
│  AiChat → InferenceEngine → InferenceEngineImpl  │
│  (JNI → llama.cpp → GGUF 模型推理)                │
└─────────────────────────────────────────────────┘
```

### 3.2 数据流

**纯文本对话**：
```
用户输入 → ChatViewModel.sendMessage()
         → InferenceEngine.sendUserPrompt() → Flow<String> (逐 token)
         → _streamingAiContent StateFlow 实时更新（不拷贝消息列表）
         → derivedStateOf 合并到消息列表用于 UI 显示
         → snapshotFlow 检测变化 → LazyListState.scrollToItem() 自动滚动
         → 流式完成后 .copy(content=finalContent) 写回 _messages（保留消息 ID）
```

**多模态对话**：
```
用户输入 + 附件 → ChatViewModel.sendMultimodalMessage()
               → MultimodalEngine.sendMultimodalPrompt() → Flow<String>
               → 同样走流式分离路径
```

### 3.3 状态管理

所有业务状态通过 `StateFlow` 暴露给 Compose UI：

| 状态 | 类型 | 来源 |
|------|------|------|
| `messages` | `StateFlow<List<Message>>` | ChatViewModel 内部维护 |
| `inputText` | `StateFlow<String>` | ChatViewModel 内部维护 |
| `sessions` | `StateFlow<List<Session>>` | ChatViewModel 内部维护 |
| `currentSessionId` | `StateFlow<String?>` | ChatViewModel 内部维护 |
| `engineState` | `StateFlow<State>` | InferenceEngine 提供 |
| `isGenerating` | `StateFlow<Boolean>` | engineState.map 派生 |
| `streamingAiContent` | `StateFlow<String>` | 流式 token 实时累积（与 messages 分离） |
| `streamingAiIndex` | `StateFlow<Int>` | 当前流式消息在列表中的索引 |
| `inferenceParams` | `StateFlow<InferenceParams>` | 推理参数（温度/上下文/线程数） |

---

## 4. 源文件清单

### 4.1 数据模型

| 文件 | 行数 | 职责 |
|------|------|------|
| `Message.kt` | 14 | 消息数据类（content / isUser / id） |
| `Session.kt` | 30 | 会话数据类（id / title / createdAt / updatedAt / formattedUpdatedAt） |
| `Attachment.kt` | 17 | 消息附件数据类（uri / type / localPath） |

### 4.2 业务逻辑

| 文件 | 行数 | 职责 |
|------|------|------|
| `ChatViewModel.kt` | 683 | 核心 ViewModel：消息收发、流式状态分离、会话管理、模型加载、推理参数管理 |
| `SessionManager.kt` | 226 | 多会话持久化存储（JSON 文件 + SharedPreferences + 内存缓存） |
| `ModelManager.kt` | 96 | 模型文件管理（导入去重/删除/列表/选择） |
| `MultimodalStorage.kt` | 172 | 多模态附件持久化（元数据 JSON + 图片缓存 + 过期清理） |

### 4.3 入口

| 文件 | 行数 | 职责 |
|------|------|------|
| `MainActivity.kt` | 636 | 唯一 Activity：Compose UI 组装、导航抽屉、BottomSheet（模型管理 + 多模态 + 推理参数）、自动滚动 |

### 4.4 UI 组件

| 文件 | 行数 | 职责 |
|------|------|------|
| `ui/components/StatusBanner.kt` | 44 | 引擎状态栏（含加载动画） |
| `ui/components/EmptyHint.kt` | 29 | 空状态提示 |
| `ui/components/MessageBubble.kt` | 130 | 消息气泡（长按复制/删除 + AI 消息 Markdown 渲染） |
| `ui/components/MultimodalMessageBubble.kt` | 169 | 多模态消息气泡（图片附件 + 长按复制/删除 + Markdown） |
| `ui/components/AiMarkdownText.kt` | 150 | AI 消息 Markdown 渲染（代码块/行内代码/粗体/斜体/标题） |
| `ui/components/AttachmentPreviewBar.kt` | 97 | 输入区附件预览条 |
| `ui/components/ModelItem.kt` | 107 | 模型列表项（选中/加载/删除） |
| `ui/components/ModelListContent.kt` | 83 | 模型管理面板内容 |
| `ui/drawer/SessionDrawerContent.kt` | 208 | 侧滑抽屉会话面板（含重命名/删除） |

### 4.5 主题

| 文件 | 行数 | 职责 |
|------|------|------|
| `ui/theme/Color.kt` | 12 | Material 3 调色板 |
| `ui/theme/Theme.kt` | 55 | 主题 Composable（支持动态取色 + 暗色模式） |
| `ui/theme/Type.kt` | 18 | 排版定义 |

### 4.6 推理引擎（lib 模块）

| 文件 | 职责 |
|------|------|
| `InferenceEngine.kt` | 推理引擎接口 + 状态机定义 + `setParams()` |
| `AiChat.kt` | 引擎单例入口 |
| `internal/InferenceEngineImpl.kt` | 引擎实现（JNI 桥接 + `setInferenceParams`） |
| `multimodal/MultimodalEngine.kt` | 多模态引擎接口 + `setParams()` |
| `multimodal/MultimodalEngineImpl.kt` | 多模态引擎实现（JNI 桥接 + `setMultimodalParams`） |
| `agent/AgentEngine.kt` | Agent 引擎 |
| `agent/Tool.kt` | Agent 工具接口 |
| `agent/tools/*.kt` | 内置工具（日历、相机等） |
| `gguf/*.kt` | GGUF 格式解析 |

### 4.7 C++ Native 层

| 文件 | 行数 | 职责 |
|------|------|------|
| `cpp/ai_chat.cpp` | 600 | 文本引擎 JNI（可配置推理参数 + 全局状态变量） |
| `cpp/multimodal.cpp` | 428 | 多模态引擎 JNI（可配置参数 + 图片编码处理） |

---

## 5. 功能清单

### 5.1 AI 对话

| # | 功能 | 实现位置 |
|---|------|----------|
| 1 | 发送消息 | `ChatViewModel.sendMessage()` |
| 2 | 流式接收 AI 回复（逐 token 显示） | `_streamingAiContent` 分离状态 + `derivedStateOf` 合并显示 |
| 3 | 停止生成 | `ChatViewModel.stopGeneration()` → `engine.stopGeneration()` |
| 4 | 长按复制消息 | `MessageBubble.kt` → `combinedClickable(onLongClick)` |
| 5 | 长按删除消息 | `MessageBubble.kt` → `ChatViewModel.deleteMessage()` → `SessionManager` 持久化 |
| 6 | 自动滚动到底部 | `MainActivity.kt` → `snapshotFlow` + `scrollToItem` |
| 7 | 空状态提示 | `EmptyHint.kt` |
| 8 | 引擎状态栏 | `StatusBanner.kt` + `MainActivity.statusText()` |
| 9 | 清空当前对话 | `ChatViewModel.clearConversation()` |
| 10 | AI 消息 Markdown 渲染 | `AiMarkdownText.kt`（代码块/行内代码/粗体/斜体/标题） |

### 5.2 多模态对话

| # | 功能 | 实现位置 |
|---|------|----------|
| 11 | 发送图片 + 文本消息 | `ChatViewModel.sendMultimodalMessage()` |
| 12 | 图片附件预览显示 | `MultimodalMessageBubble.kt` + Coil 异步加载 |
| 13 | 附件本地缓存 | `MultimodalStorage.cacheFromUri()` |
| 14 | 附件元数据持久化 | `MultimodalStorage.saveAttachments()` / `loadAttachments()` |
| 15 | 附件缓存自动清理 | `MultimodalStorage.cleanExpiredCache()`（24h 过期） |
| 16 | 会话切换时重置多模态历史 | `MultimodalEngine.resetConversation()` |

### 5.3 模型管理

| # | 功能 | 实现位置 |
|---|------|----------|
| 17 | 导入 GGUF 模型文件 | `ChatViewModel.onModelPicked()` → `ModelManager.importModel()` |
| 18 | 模型导入去重（同名同大小跳过拷贝） | `ModelManager.importModel()` 文件大小校验 |
| 19 | 模型列表展示 | `ModelListContent.kt` → `ModelItem.kt` |
| 20 | 切换模型 | `ChatViewModel.selectModel()` → `loadModelFile()` |
| 21 | 删除模型（不可删当前使用中的） | `ChatViewModel.deleteModel()` → `ModelManager.deleteModel()` |
| 22 | 自动加载上次使用的模型 | `ChatViewModel.autoLoadSavedModel()` |
| 23 | 模型管理入口（抽屉底部） | `SessionDrawerContent.kt` → `ModalBottomSheet` |
| 24 | 多模态模型 + mmproj 加载 | BottomSheet 多模态区域 + `MultimodalEngine` |

### 5.4 多会话管理

| # | 功能 | 实现位置 |
|---|------|----------|
| 25 | 创建新会话 | `ChatViewModel.createSession()` |
| 26 | 切换会话（含 native 上下文重建） | `ChatViewModel.switchSession()` → `switchSessionInternal()` |
| 27 | 删除会话（删除当前自动切换） | `ChatViewModel.deleteSession()` |
| 28 | 自动生成会话标题（首条消息前 20 字） | `ChatViewModel.sendMessage()` |
| 29 | 会话重命名 | `ChatViewModel.renameSession()` → `SessionManager.updateTitle()` |
| 30 | 会话列表（侧滑抽屉） | `SessionDrawerContent.kt` |
| 31 | 会话持久化 + 启动恢复 | `SessionManager.kt` + `ChatViewModel.init` |
| 32 | 消息 ID 持久化（跨重启稳定） | `SessionManager.loadMessages()` / `saveMessages()` 含 ID 字段 |

### 5.5 推理参数配置

| # | 功能 | 实现位置 |
|---|------|----------|
| 33 | 温度参数调节 | `ChatViewModel.InferenceParams` + BottomSheet Slider |
| 34 | 上下文大小选择 | FilterChip 组件 (2K/4K/8K/16K) |
| 35 | 线程数选择 | FilterChip 组件 (自动/1/2/4) |
| 36 | 参数持久化 | SharedPreferences `ai_agent_inference_params` |
| 37 | C++ 层参数动态生效 | JNI `setInferenceParams` / `setMultimodalParams` |

### 5.6 导航与 UI 结构

| # | 功能 | 实现位置 |
|---|------|----------|
| 38 | 侧滑导航抽屉 | `MainActivity.kt` → `ModalNavigationDrawer` |
| 39 | 顶部栏（汉堡菜单 + 标题 + 清空按钮） | `MainActivity.kt` → `TopAppBar` |
| 40 | 底部输入栏（输入框 + 发送/停止按钮） | `MainActivity.kt` → `Surface` bottomBar |
| 41 | 模型管理 BottomSheet（含多模态 + 推理参数） | `MainActivity.kt` → `ModalBottomSheet` |
| 42 | Material 3 动态取色 + 暗色模式 | `Theme.kt` → `AIAgentTheme` |

---

## 6. 持久化策略

### 6.1 文件存储结构

```
filesDir/
├── models/                          ← 模型文件目录
│   ├── model_a.gguf
│   └── model_b.gguf
├── sessions/                        ← 会话数据目录
│   ├── sessions_index.json          ← 会话索引（列表 + 元数据）
│   ├── {uuid-1}.json                ← 会话1的消息记录（含消息 ID）
│   ├── {uuid-2}.json                ← 会话2的消息记录
│   └── ...
└── multimodal/                      ← 多模态附件目录
    ├── {messageId}_attachments.json ← 附件元数据
    └── cache/                       ← 图片缓存副本
        └── {nanotime}.jpg
```

### 6.2 SharedPreferences

| 文件 | Key | 内容 |
|------|-----|------|
| `ai_agent_model_prefs` | `current_model_name` | 当前选中的模型名 |
| `ai_agent_session_prefs` | `current_session_id` | 当前活跃会话 ID |
| `ai_agent_inference_params` | `temperature` / `context_size` / `n_threads` | 推理参数持久化 |

### 6.3 JSON 格式

**sessions_index.json**：
```json
[
  {
    "id": "uuid-string",
    "title": "会话标题",
    "createdAt": 1729670400000,
    "updatedAt": 1729670500000
  }
]
```

**{session-id}.json**：
```json
[
  { "content": "用户消息", "isUser": true, "id": "uuid-string" },
  { "content": "AI 回复内容", "isUser": false, "id": "uuid-string" }
]
```

**{messageId}_attachments.json**：
```json
[
  { "uri": "content://...", "type": "IMAGE", "localPath": "/data/.../cache/12345.jpg" }
]
```

---

## 7. 并发与线程安全

### 7.1 线程模型

| 调度器 | 用途 |
|--------|------|
| `Dispatchers.Default` | ViewModel 业务逻辑（会话读写、消息处理） |
| `Dispatchers.IO` | 文件 IO、模型加载（`withContext(Dispatchers.IO)`） |
| `limitedParallelism(1)` | 引擎操作串行（cleanUp → loadModel 严格顺序） |
| 主线程 | Compose UI 渲染、StateFlow 收集 |

### 7.2 Mutex 保护

| Mutex | 位置 | 保护对象 |
|-------|------|----------|
| `messagesMutex` | `ChatViewModel` | `_messages` 列表的读-改-写操作 |
| `mutex` | `SessionManager` | 文件索引写入、消息文件保存 |

### 7.3 关键并发场景

- **发送消息**：主线程添加消息 → Mutex 保护下更新 StateFlow → 协程 collect token → Mutex 保护下追加内容
- **会话切换**：停止生成 → Mutex 内保存旧会话 → 加载新会话 → 重建 native 上下文
- **AI 生成中切换会话**：`stopGeneration()` 立即终止 → 保存 → 切换 → `cleanUp()` + `loadModel()` 清空 KV cache

---

## 8. 引擎状态机

```
Uninitialized
    ↓ initialize()
Initialized
    ↓ loadModel()
ModelReady ←──── stopGeneration() ──── Generating
    ↓                                    ↑
LoadingModel ──────────────────────→ ProcessingUserPrompt
                                         ↓
                                     Generating
                                         ↓
                                     ModelReady

任意状态 → Error(exception)
Error → cleanUp() → Initialized
```

**状态说明**：

| 状态 | 含义 | UI 表现 |
|------|------|---------|
| `Uninitialized` | 引擎未初始化 | "未初始化" |
| `Initializing` | 引擎初始化中 | "引擎初始化中…" + 转圈 |
| `Initialized` | 引擎就绪，未加载模型 | "引擎就绪，未加载模型" |
| `LoadingModel` | 模型加载中 | "模型加载中…" + 转圈 |
| `UnloadingModel` | 模型卸载中 | "卸载模型中…" + 转圈 |
| `ModelReady` | 模型就绪，可接收输入 | "模型就绪：xxx" |
| `Generating` | AI 正在生成 | "生成中…" + 停止按钮 |
| `ProcessingSystemPrompt` | 处理系统提示词 | 转圈 |
| `ProcessingUserPrompt` | 处理用户提示词 | "生成中…" + 停止按钮 |
| `Error` | 发生错误 | "错误：xxx" |

---

## 9. 自动滚动机制

### 9.1 实现原理

```kotlin
// 在 LazyColumn 末尾添加 0 高度占位符
item(key = "bottom_spacer") { Spacer(Modifier.height(0.dp)) }

// 监听消息变化，滚动到占位符
LaunchedEffect(Unit) {
    snapshotFlow { messages.size to messages.lastOrNull()?.content }
        .collect { (size, _) ->
            if (size > 0) {
                listState.scrollToItem(size) // 占位符索引 = messages.size
            }
        }
}
```

### 9.2 为什么用占位符

- `scrollToItem(lastIndex)` 将该项**顶部**对齐到视口**顶部**
- 当最后一项高度超过屏幕时，底部新内容不可见
- 添加 0 高度占位符后，`scrollToItem(size)` 将占位符（即内容底部）对齐到视口顶部 → 内容底部始终可见

### 9.3 为什么用 snapshotFlow

- `LaunchedEffect(Unit)` 只启动一次协程，不会被频繁取消/重启
- `snapshotFlow` 持续监听 Compose snapshot 状态变化
- 高频 token 更新时滚动稳定可靠

---

## 10. 关键设计决策

### 10.1 会话切换时的上下文重建

切换会话时必须 `cleanUp()` + `loadModel()` 清空 native 侧 KV cache，否则 AI 会混淆不同会话的历史内容。

### 10.2 会话标题自动生成

首条用户消息截取前 20 字符（`SESSION_TITLE_MAX_LENGTH`）作为标题，后续不再自动更新。用户可通过长按菜单或编辑图标手动重命名。

### 10.3 消息 id 字段与 ID 持久化

`Message` 包含 `id: UUID`，用于 LazyColumn 的 `key` 参数，优化列表更新时的重组性能。`copy()` 操作保留原 id。`SessionManager` 序列化时保存 ID 字段，反序列化时恢复，确保跨重启 ID 稳定。

### 10.4 流式状态分离（P0 优化）

流式生成期间每 token 更新不再拷贝整个消息列表，而是写入独立的 `_streamingAiContent` StateFlow。UI 层通过 `derivedStateOf` 实时合并显示，流式完成后用 `.copy(content = finalContent)` 写回 `_messages`，保留原消息 ID。

### 10.5 推理参数可配置化（P1 优化）

C++ 层全局变量 + JNI setter 函数，支持温度/上下文大小/线程数动态配置。Kotlin 层通过 `InferenceParams` 数据类 + `SharedPreferences` 持久化，UI 层提供 Slider + FilterChip 组件。参数变更立即生效（温度通过重建 sampler）。

### 10.6 SessionManager 内存缓存（P2 优化）

`@Volatile cachedSessions` 缓存会话列表，避免每次操作都读磁盘。写入时同步更新缓存和磁盘，读取时优先返回缓存。

### 10.7 模型文件导入去重（P2 优化）

导入前通过 `openFileDescriptor().statSize` 比较源文件和目标文件大小，同名同大小文件跳过拷贝，避免重复拷贝大文件。

### 10.8 日期格式化封装

`Session.formattedUpdatedAt` 将日期格式化逻辑封装在数据类内部，UI 层直接使用，避免在 Composable 中重复创建 `SimpleDateFormat`。

### 10.9 JSON 序列化/反序列化复用

`SessionManager` 提取 `parseSessionFromJson()` 和 `sessionToJson()` 公共方法，消除 4 处重复的 JSON 操作代码。

### 10.10 isGenerating 响应式派生

```kotlin
val isGenerating: StateFlow<Boolean> = engineState.map { state ->
    state is State.Generating || state is State.ProcessingUserPrompt
}.stateIn(viewModelScope, SharingStarted.Eagerly, false)
```

使用 `map` + `stateIn` 替代手动 `MutableStateFlow` + `collect`，代码更简洁。

---

## 11. 资源文件

### 11.1 Drawable

| 文件 | 用途 |
|------|------|
| `bg_user_message.xml` | 用户消息气泡背景 |
| `bg_assistant_message.xml` | AI 消息气泡背景 |
| `bg_avatar.xml` | 头像背景 |
| `bg_input_field.xml` | 输入框背景 |
| `bg_loading_indicator.xml` | 加载指示器背景 |
| `outline_send_24.xml` | 发送图标 |
| `outline_folder_open_24.xml` | 文件夹图标 |
| `ic_launcher_foreground.xml` | 应用图标前景 |
| `ic_launcher_background.xml` | 应用图标背景 |

### 11.2 颜色方案

- **主色**：`#6750A4`（紫色系）
- **用户气泡**：`#6750A4` 背景 + 白色文字
- **AI 气泡**：`#F3EDF7` 背景 + 深色文字
- **支持动态取色**（Android 12+）和暗色模式

---

## 12. 边缘情况处理

| 场景 | 处理方式 |
|------|----------|
| 用户发消息后立即关闭 app | 第一次 save 已保存用户消息，AI 空占位在 load 时被过滤 |
| AI 生成中途 app 被杀 | onCompletion 可能未执行，恢复到上次保存点 |
| 切换模型时 UI 历史保留 | native KV cache 清空，新消息无旧上下文 |
| 删除当前会话 | 自动切换到时间最近的另一个会话 |
| 无会话时启动 | 自动创建空白“新对话”会话 |
| 切换会话时引擎正在生成 | `stopGeneration()` 立即终止，安全调用 |
| onDestroy 时协程取消 | lifecycleScope 取消 → flow collect 取消 → cleanUp 正常执行 |
| 加载历史时空 AI 消息 | `filter { it.isUser \|\| it.content.isNotBlank() }` 过滤 |
| 流式生成中 UI 性能 | 流式状态分离，每 token 不拷贝消息列表 |
| 多模态引擎未加载模型 | 显示明确错误提示，不崩溃 |
| 附件缓存过期 | 启动时自动清理 24h 以上的缓存文件 |
| 同名同大小模型重复导入 | 文件大小校验跳过拷贝，直接返回现有文件 |
| 消息 ID 跨重启稳定性 | SessionManager 序列化时保存/恢复 ID 字段 |
| BottomSheet 底部被导航栏遮挡 | 最底部统一 `Spacer(navigationBarsPadding())` |
