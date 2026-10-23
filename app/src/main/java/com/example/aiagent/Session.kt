package com.example.aiagent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 表示一个对话会话。
 *
 * @param id        唯一标识（UUID）
 * @param title     会话标题（首条用户消息自动生成，可修改）
 * @param createdAt 创建时间戳（毫秒）
 * @param updatedAt 最后更新时间戳（毫秒）
 */
data class Session(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String = "新对话",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 格式化的更新时间（MM/dd HH:mm） */
    val formattedUpdatedAt: String
        get() = SimpleDateFormat(DATE_FORMAT_PATTERN, Locale.getDefault())
            .format(Date(updatedAt))

    companion object {
        private const val DATE_FORMAT_PATTERN = "MM/dd HH:mm"
    }
}
