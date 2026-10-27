package com.example.aiagent.ui.components

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.example.aiagent.Attachment

/**
 * 附件预览横条，显示待发送的图片缩略图。
 *
 * @param attachments 待发送的附件列表
 * @param onRemove    移除某个附件的回调
 */
@Composable
fun AttachmentPreviewBar(
    attachments: List<Attachment>,
    onRemove: (String) -> Unit
) {
    if (attachments.isEmpty()) return

    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { att ->
            AttachmentThumbnail(att, onRemove = { onRemove(att.uri) })
        }
    }
}

@Composable
private fun AttachmentThumbnail(
    attachment: Attachment,
    onRemove: () -> Unit
) {
    Box(
        modifier = Modifier.size(64.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.size(64.dp)
        ) {
            Image(
                painter = rememberAsyncImagePainter(
                    model = Uri.parse(attachment.uri),
                    contentScale = ContentScale.Crop,
                ),
                contentDescription = "附件预览",
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        // 移除按钮
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .background(
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(10.dp)
                )
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "移除",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}
