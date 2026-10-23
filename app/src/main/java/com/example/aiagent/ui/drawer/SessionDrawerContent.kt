package com.example.aiagent.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aiagent.Session

/**
 * 会话抽屉内容组件，显示所有会话列表及操作按钮。
 *
 * @param sessions 会话列表
 * @param currentSessionId 当前会话 ID
 * @param onNewSession 新建会话回调
 * @param onSelectSession 选择会话回调
 * @param onDeleteSession 删除会话回调
 * @param onManageModels 管理模型回调
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionDrawerContent(
    sessions: List<Session>,
    currentSessionId: String?,
    onNewSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onManageModels: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ── 头部 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "会话",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onNewSession) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("新对话")
            }
        }

        HorizontalDivider()

        // ── 会话列表 ──
        LazyColumn(
            modifier = Modifier.weight(1f),
        ) {
            items(sessions, key = { it.id }) { session ->
                val isCurrent = session.id == currentSessionId
                ListItem(
                    headlineContent = {
                        Text(
                            session.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    supportingContent = {
                        Text(
                            session.formattedUpdatedAt,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    leadingContent = {
                        if (isCurrent) {
                            Icon(
                                Icons.AutoMirrored.Filled.Chat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    trailingContent = {
                        IconButton(
                            onClick = { onDeleteSession(session.id) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onSelectSession(session.id) },
                        )
                        .background(
                            if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else Color.Transparent,
                        ),
                )
            }
        }

        HorizontalDivider()

        // ── 底部：模型管理 ──
        ListItem(
            headlineContent = { Text("模型管理") },
            leadingContent = {
                Icon(Icons.Default.Settings, contentDescription = null)
            },
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onManageModels),
        )
    }
}
