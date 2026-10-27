package com.example.aiagent

/**
 * 表示一条消息的附件（图片等）。
 *
 * @param uri      附件的 URI 字符串（content:// 或 file://）
 * @param type     附件类型
 * @param localPath 本地缓存路径（用于传给 native 层）
 */
data class Attachment(
    val uri: String,
    val type: Type,
    val localPath: String? = null
) {
    enum class Type { IMAGE, AUDIO, VIDEO }
}
