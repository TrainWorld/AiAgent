package com.example.aiagent.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 空状态提示组件，当没有消息时显示。
 *
 * @param isModelReady 模型是否已就绪
 * @param modifier Modifier
 */
@Composable
fun EmptyHint(isModelReady: Boolean, modifier: Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isModelReady) "发送一条消息开始对话" else "请从左侧抽屉导入模型",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
