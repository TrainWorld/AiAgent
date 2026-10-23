package com.example.aiagent

/**
 * 表示一条聊天消息。
 *
 * @param content 消息内容
 * @param isUser  是否为用户发送（false 表示 AI 回复）
 * @param id      消息唯一标识（UUID）
 */
data class Message(
    val content: String,
    val isUser: Boolean,
    val id: String = java.util.UUID.randomUUID().toString()
)
