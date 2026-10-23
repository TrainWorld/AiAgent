package com.example.aiagent.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arm.aichat.InferenceEngine
import com.example.aiagent.ModelManager

/**
 * 模型管理列表内容组件，显示所有可用模型及导入选项。
 *
 * @param engineState 引擎状态
 * @param onImport 导入模型回调
 * @param onSelect 选择模型回调
 * @param onDelete 删除模型回调
 */
@Composable
fun ModelListContent(
    engineState: InferenceEngine.State,
    onImport: () -> Unit,
    onSelect: (ModelManager.ModelInfo) -> Unit,
    onDelete: (ModelManager.ModelInfo) -> Unit,
    currentModelName: String?,
    models: List<ModelManager.ModelInfo>,
) {
    val isLoading = engineState is InferenceEngine.State.LoadingModel ||
            engineState is InferenceEngine.State.UnloadingModel

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp)
            .navigationBarsPadding()
    ) {
        Text(
            "模型管理",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        // 导入按钮
        TextButton(
            onClick = onImport,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.height(4.dp))
            Text("导入新模型 (.gguf)")
        }

        Spacer(Modifier.height(4.dp))

        if (models.isEmpty()) {
            Text(
                "暂无模型，请导入 .gguf 文件",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            models.forEach { model ->
                val isSelected = model.displayName == currentModelName
                ModelItem(
                    model = model,
                    isSelected = isSelected,
                    isLoading = isSelected && isLoading,
                    onSelect = { onSelect(model) },
                    onDelete = { onDelete(model) },
                )
            }
        }
    }
}
