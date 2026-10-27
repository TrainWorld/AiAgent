package com.example.aiagent.ui.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.example.aiagent.Attachment

/**
 * 多模态消息气泡，支持显示图片附件 + 文本。
 *
 * @param messageContent 文本内容
 * @param isUser         是否为用户消息
 * @param attachments    关联的附件列表
 * @param messageId      消息 ID（用于删除回调）
 * @param onDelete       删除回调
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultimodalMessageBubble(
    messageContent: String,
    isUser: Boolean,
    attachments: List<Attachment> = emptyList(),
    messageId: String = "",
    onDelete: ((String) -> Unit)? = null,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = if (isUser)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = { showMenu = true },
            ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 图片附件
                if (attachments.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        attachments.take(3).forEach { att ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(120.dp)
                            ) {
                                Image(
                                    painter = rememberAsyncImagePainter(
                                        model = att.localPath ?: att.uri,
                                        contentScale = ContentScale.Crop,
                                    ),
                                    contentDescription = "图片附件",
                                    modifier = Modifier
                                        .size(120.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                }

                // 文本内容
                if (messageContent.isNotBlank()) {
                    if (isUser) {
                        Text(
                            text = messageContent,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        AiMarkdownText(
                            text = messageContent,
                        )
                    }
                }
            }
        }
    }

    // ── 长按菜单 ──
    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text("消息操作") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(messageContent))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        showMenu = false
                    }) { Text("复制") }

                    if (onDelete != null) {
                        TextButton(onClick = {
                            showMenu = false
                            showDeleteConfirm = true
                        }) { Text("删除") }
                    }
                }
            },
            confirmButton = {},
        )
    }

    // ── 删除确认 ──
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除这条消息吗？") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete?.invoke(messageId)
                    showDeleteConfirm = false
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}
