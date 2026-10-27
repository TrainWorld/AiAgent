package com.example.aiagent.ui.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.aiagent.Message

/**
 * 消息气泡组件，用于显示用户或 AI 的消息。
 *
 * - 用户消息：纯文本
 * - AI 消息：支持基础 Markdown 渲染
 * - 长按弹出菜单：复制 / 删除
 *
 * @param message 消息对象
 * @param onDelete 删除回调
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    onDelete: ((String) -> Unit)? = null,
) {
    val isUser = message.isUser
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
            if (isUser) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                AiMarkdownText(
                    text = message.content,
                    modifier = Modifier.padding(12.dp),
                )
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
                        clipboardManager.setText(AnnotatedString(message.content))
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
                    onDelete?.invoke(message.id)
                    showDeleteConfirm = false
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}
