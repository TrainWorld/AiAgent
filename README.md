# AI Agent - Android 端侧 LLM 聊天应用

<div align="center">

**基于 llama.cpp 的 Android 本地 AI 助手，支持多会话管理、多模态对话与 GGUF 模型推理**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-blue.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-13%2B-green.svg)](https://developer.android.com)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-purple.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

</div>

---

## 📖 项目简介

AIAgent 是一款完全运行在 Android 设备上的本地化 AI 聊天应用。通过集成 llama.cpp 推理引擎，支持导入 GGUF 格式的大语言模型和多模态模型，在无需网络连接的情况下实现智能对话和图文理解。

### ✨ 核心特性

- 🔒 **完全本地运行** - 所有推理在设备端完成，保护隐私
- 💬 **多会话管理** - 创建、切换、删除、重命名多个独立对话
- 🖼️ **多模态对话** - 支持图片 + 文本输入，图文理解
- ⚡ **流式输出优化** - 独立状态流实时合并，每 token 零拷贝
- 📝 **Markdown 渲染** - AI 回复支持代码块/粗体/斜体/标题等格式
- 🎛️ **推理参数可调** - 温度/上下文大小/线程数动态配置
- 🎨 **Material 3 设计** - 现代化 UI，支持动态取色与暗色模式
- 💾 **持久化存储** - 消息 ID 跨重启稳定，附件缓存自动清理
- 🔄 **上下文隔离** - 切换会话时自动重建 native KV cache
- 📱 **响应式架构** - MVVM + StateFlow + Compose

---

## 🚀 快速开始

### 前提条件

- Android Studio Hedgehog (2023.1.1) 或更新版本
- Android SDK 36
- NDK 29.0.13113456
- CMake 3.31.6
- Java 17
- Android 设备 (API 33+, Android 13+)

### 1. 克隆项目

```bash
git clone <repository-url>
cd AIAgent
```

### 2. 打开项目

使用 Android Studio 打开：`File → Open → 选择 AIAgent 目录`

### 3. 同步 Gradle

Android Studio 会自动下载依赖和配置 NDK。首次同步可能需要 10-20 分钟。

### 4. 准备模型文件

下载 GGUF 格式的模型文件（推荐小型模型）：

| 模型 | 大小 | 特点 |
|------|------|------|
| **TinyLlama-1.1B-Chat** | ~670MB | 适合测试，速度快 |
| **Phi-2 (2.7B)** | ~1.5GB | 性能更好，英文优秀 |
| **Qwen-1.8B-Chat** | ~1GB | 中文支持好 |

下载地址：[Hugging Face Models](https://huggingface.co/models) (搜索 "gguf")

### 5. 运行应用

1. 连接 Android 设备 (API 33+)
2. 点击 Android Studio 的 **Run** 按钮
3. 打开左侧**抽屉菜单**（点击左上角汉堡图标）
4. 点击底部**"模型管理"**
5. 点击**"导入新模型"**，选择下载好的 GGUF 文件
6. 等待模型加载完成（状态栏显示"模型就绪"）
7. 输入消息开始对话！

---

## 📱 功能清单

### AI 对话（10 项）

| # | 功能 | 说明 |
|---|------|------|
| 1 | 发送消息 | 输入文本并发送给 AI |
| 2 | 流式接收 | 独立状态流实时合并，每 token 零拷贝 |
| 3 | 停止生成 | 随时中断 AI 生成 |
| 4 | 长按复制 | 复制任意消息内容 |
| 5 | 长按删除 | 删除单条消息并持久化 |
| 6 | 自动滚动 | 跟随内容生成自动滚动到底部 |
| 7 | 状态栏 | 实时显示引擎状态 |
| 8 | 清空对话 | 一键清空当前会话内容 |
| 9 | 空状态提示 | 未导入模型时显示引导 |
| 10 | Markdown 渲染 | AI 回复支持代码块/粗体/斜体/标题 |

### 多模态对话（6 项）

| # | 功能 | 说明 |
|---|------|------|
| 11 | 发送图片消息 | 图片 + 文本混合输入 |
| 12 | 图片预览 | Coil 异步加载显示附件 |
| 13 | 附件缓存 | 本地缓存 content:// URI |
| 14 | 附件持久化 | JSON 元数据 + 缓存文件 |
| 15 | 缓存自动清理 | 24h 过期自动清理 |
| 16 | 会话切换重置 | 多模态对话历史随会话切换清除 |

### 模型管理（8 项）

| # | 功能 | 说明 |
|---|------|------|
| 17 | 导入模型 | 从文件管理器选择 GGUF 文件 |
| 18 | 导入去重 | 同名同大小文件跳过拷贝 |
| 19 | 模型列表 | 展示所有已导入模型 |
| 20 | 切换模型 | 选择不同的模型进行对话 |
| 21 | 删除模型 | 移除不需要的模型文件 |
| 22 | 自动加载 | 启动时自动加载上次使用的模型 |
| 23 | 模型信息 | 显示模型名称和大小 |
| 24 | 多模态模型 | 支持加载多模态模型 + mmproj 文件 |

### 多会话管理（8 项）

| # | 功能 | 说明 |
|---|------|------|
| 25 | 创建会话 | 新建独立对话 |
| 26 | 切换会话 | 在不同对话间切换 |
| 27 | 删除会话 | 移除不需要的会话 |
| 28 | 自动标题 | 首条消息生成会话标题（前 20 字） |
| 29 | 会话重命名 | 长按或点击编辑图标修改标题 |
| 30 | 会话列表 | 侧滑抽屉展示所有会话 |
| 31 | 持久化 | 自动保存会话，支持恢复 |
| 32 | 消息 ID 稳定 | 跨重启保持消息 ID 不变 |

### 推理参数配置（5 项）

| # | 功能 | 说明 |
|---|------|------|
| 33 | 温度调节 | Slider 组件，范围 0.00–2.00 |
| 34 | 上下文大小 | FilterChip 选择 2K/4K/8K/16K |
| 35 | 线程数 | FilterChip 选择 自动/1/2/4 |
| 36 | 参数持久化 | SharedPreferences 存储 |
| 37 | 动态生效 | C++ 层立即应用，无需重启 |

### UI 与导航（5 项）

| # | 功能 | 说明 |
|---|------|------|
| 38 | 侧滑抽屉 | Material 3 导航抽屉 |
| 39 | 顶部栏 | 显示当前会话标题 |
| 40 | 底部输入栏 | 输入框 + 发送/停止按钮 |
| 41 | BottomSheet | 模型管理 + 多模态 + 推理参数 |
| 42 | 动态主题 | 支持 Material You 动态取色 |

---

## 🏗️ 架构设计

### 分层架构

```
┌─────────────────────────────────────────┐
│         UI Layer (Compose)              │
│  MainActivity + 独立 UI 组件             │
├─────────────────────────────────────────┤
│         ViewModel Layer                 │
│  ChatViewModel (StateFlow + Mutex)      │
├─────────────────────────────────────────┤
│         Business Layer                  │
│  SessionManager | ModelManager          │
├─────────────────────────────────────────┤
│         Engine Layer (lib)              │
│  InferenceEngine → llama.cpp (JNI)      │
└─────────────────────────────────────────┘
```

### 核心组件

| 组件 | 文件 | 职责 |
|------|------|------|
| **MainActivity** | `MainActivity.kt` | Compose UI 组装、导航、BottomSheet、自动滚动 |
| **ChatViewModel** | `ChatViewModel.kt` | 业务逻辑、流式状态分离、推理参数管理、线程安全 |
| **SessionManager** | `SessionManager.kt` | 多会话持久化（JSON + Mutex + 内存缓存） |
| **ModelManager** | `ModelManager.kt` | 模型文件管理（导入去重/删除/列表） |
| **MultimodalStorage** | `MultimodalStorage.kt` | 多模态附件持久化（缓存 + 过期清理） |
| **InferenceEngine** | `lib/` | 文本推理引擎接口 + 可配置参数 |
| **MultimodalEngine** | `lib/` | 多模态推理引擎接口 + 可配置参数 |

### 数据流

```
用户输入 → ViewModel.sendMessage()
         → InferenceEngine.sendUserPrompt()
         → Flow<String> (逐 token)
         → _streamingAiContent 独立更新（不拷贝消息列表）
         → derivedStateOf 合并显示
         → snapshotFlow 检测
         → scrollToItem() 自动滚动
         → 流式完成后 .copy() 写回 _messages
```

---

## 📂 项目结构

```
AIAgent/
├── app/                              # 主应用模块
│   ├── src/main/
│   │   ├── java/com/example/aiagent/
│   │   │   ├── MainActivity.kt       # 入口 Activity (636 行)
│   │   │   ├── ChatViewModel.kt      # 核心 ViewModel (683 行)
│   │   │   ├── Session.kt            # 会话数据类 (30 行)
│   │   │   ├── Message.kt            # 消息数据类 (14 行)
│   │   │   ├── Attachment.kt         # 附件数据类 (17 行)
│   │   │   ├── SessionManager.kt     # 会话存储 (226 行)
│   │   │   ├── ModelManager.kt       # 模型管理 (96 行)
│   │   │   ├── MultimodalStorage.kt  # 多模态附件存储 (172 行)
│   │   │   └── ui/
│   │   │       ├── components/       # 独立 UI 组件
│   │   │       │   ├── StatusBanner.kt
│   │   │       │   ├── EmptyHint.kt
│   │   │       │   ├── MessageBubble.kt
│   │   │       │   ├── MultimodalMessageBubble.kt
│   │   │       │   ├── AiMarkdownText.kt
│   │   │       │   ├── AttachmentPreviewBar.kt
│   │   │       │   ├── ModelItem.kt
│   │   │       │   └── ModelListContent.kt
│   │   │       ├── drawer/
│   │   │       │   └── SessionDrawerContent.kt
│   │   │       └── theme/
│   │   │           ├── Color.kt
│   │   │           ├── Theme.kt
│   │   │           └── Type.kt
│   │   └── res/                      # 资源文件
│   └── build.gradle.kts
├── lib/                              # 推理引擎模块
│   ├── src/main/
│   │   ├── java/com/arm/aichat/
│   │   │   ├── AiChat.kt            # 引擎入口
│   │   │   ├── InferenceEngine.kt   # 引擎接口
│   │   │   ├── internal/
│   │   │   │   └── InferenceEngineImpl.kt
│   │   │   ├── multimodal/
│   │   │   │   ├── MultimodalEngine.kt
│   │   │   │   └── MultimodalEngineImpl.kt
│   │   │   ├── agent/               # Agent 能力
│   │   │   └── gguf/                # GGUF 解析
│   │   └── cpp/                     # C++ 原生代码
│   │       ├── ai_chat.cpp          # 文本引擎 JNI (600 行)
│   │       └── multimodal.cpp       # 多模态引擎 JNI (428 行)
│   └── build.gradle.kts
├── build.gradle.kts                  # 根构建脚本
├── settings.gradle.kts               # 模块注册
└── gradle/
    └── libs.versions.toml           # 版本目录
```

---

## 🔧 技术栈

### 开发语言与框架

| 技术 | 版本 | 用途 |
|------|------|------|
| **Kotlin** | 2.3.0 | 主要开发语言 |
| **Jetpack Compose** | BOM 2024.06 | 现代 UI 工具包 |
| **Material 3** | BOM | 设计系统 |
| **Coroutines** | 1.x | 异步编程 |
| **Flow** | 2.7.0 | 响应式数据流 |
| **Coil** | 2.6.0 | 异步图片加载（多模态附件） |

### Android 架构组件

| 组件 | 版本 | 用途 |
|------|------|------|
| **ViewModel** | 2.7.0 | 生命周期感知 |
| **StateFlow** | 2.7.0 | 状态管理 |
| **LiveData** | 2.7.0 | 数据观察 |
| **Room** | - | 未使用（采用 JSON 文件） |

### Native 层

| 技术 | 版本 | 用途 |
|------|------|------|
| **llama.cpp** | - | LLM 推理引擎 |
| **CMake** | 3.31.6 | 原生构建系统 |
| **NDK** | 29.0.13113456 | C++ 编译 |
| **JNI** | - | Java-Kotlin 桥接 |
| **Multimodal Engine** | - | 多模态推理引擎（图片 + 文本） |

### 支持的 ABI

- ✅ `arm64-v8a` (ARM 64 位)
- ✅ `x86_64` (模拟器)
- ❌ `armeabi-v7a` (不支持)
- ❌ `x86` (不支持)

---

## 📚 API 使用示例

### 基础用法

```kotlin
// 1. 获取推理引擎
val engine = AiChat.getInferenceEngine(context)

// 2. 加载模型
engine.loadModel("/path/to/model.gguf")

// 3. 发送消息并接收流式响应
engine.sendUserPrompt("你好").collect { token ->
    println(token)  // 处理每个生成的 token
}

// 4. 停止生成
engine.stopGeneration()

// 5. 清理资源
engine.cleanUp()
engine.destroy()
```

### 状态监听

```kotlin
// 监听引擎状态
engine.state.collect { state ->
    when (state) {
        is InferenceEngine.State.ModelReady -> {
            // 模型就绪，可以发送消息
        }
        is InferenceEngine.State.Generating -> {
            // 正在生成
        }
        is InferenceEngine.State.Error -> {
            // 发生错误
            println(state.exception)
        }
        else -> {}
    }
}
```

### 多会话管理

```kotlin
// 创建会话
val session = sessionManager.createSession("新对话")

// 保存消息
sessionManager.saveMessages(session.id, messages)

// 加载消息
val history = sessionManager.loadMessages(session.id)

// 切换会话
viewModel.switchSession(session.id)
```

---

## 🔒 并发与线程安全

### 线程模型

| 调度器 | 用途 |
|--------|------|
| `Dispatchers.Main` | UI 渲染、StateFlow 收集 |
| `Dispatchers.Default` | ViewModel 业务逻辑 |
| `Dispatchers.IO` | 文件读写、模型加载 |
| `limitedParallelism(1)` | 引擎操作串行 |

### Mutex 保护

```kotlin
// ChatViewModel 中的消息保护
private val messagesMutex = Mutex()

messagesMutex.withLock {
    // 安全地更新消息列表
    _messages.value = newList
}

// SessionManager 中的文件保护
private val mutex = Mutex()

mutex.withLock {
    // 安全地写入文件
    indexFile.writeText(json)
}
```

---

## 💾 持久化策略

### 文件存储

```
filesDir/
├── models/                          # 模型文件
│   └── *.gguf
├── sessions/                        # 会话数据
│   ├── sessions_index.json          # 会话索引
│   └── {uuid}.json                  # 会话消息（含消息 ID）
└── multimodal/                      # 多模态附件
    ├── {messageId}_attachments.json # 附件元数据
    └── cache/                       # 图片缓存（24h 过期）
```

### SharedPreferences

| 文件 | Key | 内容 |
|------|-----|------|
| `ai_agent_model_prefs` | `current_model_name` | 当前模型名 |
| `ai_agent_session_prefs` | `current_session_id` | 当前会话 ID |
| `ai_agent_inference_params` | `temperature` / `context_size` / `n_threads` | 推理参数 |

---

## 📊 性能优化

### 已完成的优化

| # | 优化项 | 效果 |
|---|--------|------|
| 1 | 流式状态分离 (P0) | 每 token 零拷贝消息列表，UI 流畅度大幅提升 |
| 2 | 多模态对话历史重置 (P0) | 会话切换时清除多模态引擎上下文 |
| 3 | 附件缓存自动清理 (P1) | 24h 过期清理，避免存储无限增长 |
| 4 | 推理参数可配置 (P1) | 温度/上下文/线程数动态调节，C++ 层立即生效 |
| 5 | SessionManager 内存缓存 (P2) | 避免每次操作读磁盘，写入同步更新 |
| 6 | 模型文件导入去重 (P2) | 同名同大小跳过拷贝，节省 IO |
| 7 | 消息 ID 持久化 | 跨重启 ID 稳定，删除操作可靠 |
| 8 | 消息长按操作 | 复制 + 删除，交互体验完善 |
| 9 | 会话重命名 | 支持自定义会话标题 |
| 10 | AI 消息 Markdown 渲染 | 代码块/粗体/斜体/标题，可读性提升 |
| 11 | 线程安全保护 | Mutex 保护并发访问 |
| 12 | LazyColumn key | 列表重组性能优化 |
| 13 | UI 组件拆分 | 模块化架构，可维护性提升 |
| 14 | 自动滚动优化 | snapshotFlow 监听，长内容稳定跟随 |

### 架构改进

- **功能总数**：42 项（从 25 项扩展）
- **组件化**：拆分 9 个独立 UI 组件
- **可维护性**：单一职责原则
- **性能**：流式零拷贝 + Mutex 保护并发访问

---

## ⚠️ 常见问题

### 构建失败: NDK 版本不匹配

在 `lib/build.gradle.kts` 中修改 `ndkVersion` 为你安装的版本。

### 运行时崩溃: UnsatisfiedLinkError

确保设备的 CPU 架构受支持（arm64-v8a 或 x86_64）。

### 模型加载失败

- 检查文件路径是否正确
- 确保模型是 GGUF 格式
- 检查设备存储空间
- 同名同大小文件会自动跳过拷贝，不会重复导入

### 生成速度很慢

- 使用更小的模型（如 TinyLlama）
- 关闭其他占用 CPU 的应用
- 确保设备散热良好
- 在推理参数中减少线程数或上下文大小

### 会话切换后 AI 没有上下文

这是**正常行为**。切换会话时会清空 native KV cache，新消息不会包含旧会话的上下文。UI 上的历史消息仅用于查看。

### 多模态对话图片不显示

- 确保已加载多模态模型和 mmproj 文件
- 检查图片格式是否支持（JPG/PNG/WebP/GIF）
- 附件缓存 24h 后自动清理，过期图片需重新加载

### Markdown 渲染不正确

- AI 消息自动渲染 Markdown（代码块/粗体/斜体/标题）
- 用户消息保持纯文本显示
- 复杂格式可能渲染不完整，属于已知限制

### 自动滚动不跟随

- 确保使用最新版本代码
- 检查是否有自定义修改
- 查看 `MainActivity.kt` 中的 `snapshotFlow` 实现

---

## 📖 参考文档

### 项目文档

- [PROJECT.md](PROJECT.md) - 详细项目文档
- [UML.md](UML.md) - UML 架构图（10 张图）

### 外部资源

- [llama.cpp 官方文档](https://github.com/ggerganov/llama.cpp)
- [Android 集成指南](https://github.com/ggerganov/llama.cpp/blob/master/docs/android.md)
- [Jetpack Compose 官方文档](https://developer.android.com/jetpack/compose)
- [Kotlin Coroutines 指南](https://kotlinlang.org/docs/coroutines-guide.html)

---

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

### 开发规范

- 遵循 Kotlin 官方编码规范
- Compose 组件保持单一职责
- 所有公开 API 添加 KDoc 注释
- 新功能必须包含完整的边界情况处理

---

## 📄 许可证

本项目基于 llama.cpp 的 **MIT 许可证**。

```
MIT License

Copyright (c) 2024 AIAgent

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction...
```

---

## 🙏 致谢

- [llama.cpp](https://github.com/ggerganov/llama.cpp) - 强大的本地 LLM 推理引擎
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - 现代化的 Android UI 工具包
- [Hugging Face](https://huggingface.co/) - 丰富的开源模型资源

---

<div align="center">

**如果这个项目对你有帮助，请给一个 ⭐️ Star 支持！**

[问题反馈](https://github.com/yourusername/AIAgent/issues) · [功能建议](https://github.com/yourusername/AIAgent/discussions) · [开发计划](PROJECT.md)

</div>
