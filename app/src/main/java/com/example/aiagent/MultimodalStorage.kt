package com.example.aiagent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 多模态附件的独立存储层。
 *
 * 与 SessionManager 完全隔离，不影响现有的纯文本消息持久化。
 *
 * 文件结构：
 * ```
 * filesDir/multimodal/
 *   ├── {messageId}_attachments.json  ← 附件元数据
 *   └── cache/                        ← 图片缓存副本
 *       └── {hash}.jpg
 * ```
 */
class MultimodalStorage(context: Context) {

    private val appContext = context.applicationContext
    private val baseDir = File(appContext.filesDir, "multimodal").also { it.mkdirs() }
    private val cacheDir = File(baseDir, "cache").also { it.mkdirs() }
    private val mutex = Mutex()

    /**
     * 保存消息的附件元数据。
     *
     * @param messageId 关联的消息 ID
     * @param attachments 附件列表
     */
    suspend fun saveAttachments(messageId: String, attachments: List<Attachment>) =
        withContext(Dispatchers.IO) {
            if (attachments.isEmpty()) return@withContext
            mutex.withLock {
                try {
                    val file = File(baseDir, "${messageId}_attachments.json")
                    val array = JSONArray()
                    attachments.forEach { att ->
                        array.put(JSONObject().apply {
                            put(KEY_URI, att.uri)
                            put(KEY_TYPE, att.type.name)
                            put(KEY_LOCAL_PATH, att.localPath ?: "")
                        })
                    }
                    file.writeText(array.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "保存附件失败: $messageId", e)
                }
            }
        }

    /**
     * 加载消息的附件元数据。
     */
    suspend fun loadAttachments(messageId: String): List<Attachment> =
        withContext(Dispatchers.IO) {
            val file = File(baseDir, "${messageId}_attachments.json")
            if (!file.exists()) return@withContext emptyList()
            try {
                val json = file.readText()
                val array = JSONArray(json)
                List(array.length()) { i ->
                    val obj = array.getJSONObject(i)
                    Attachment(
                        uri = obj.getString(KEY_URI),
                        type = Attachment.Type.valueOf(obj.getString(KEY_TYPE)),
                        localPath = if (obj.has(KEY_LOCAL_PATH)) obj.getString(KEY_LOCAL_PATH).takeIf { it.isNotEmpty() } else null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载附件失败: $messageId", e)
                emptyList()
            }
        }

    /**
     * 删除消息的附件（含缓存文件）。
     */
    suspend fun deleteAttachments(messageId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = File(baseDir, "${messageId}_attachments.json")
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "删除附件元数据失败: $messageId", e)
            }
        }
    }

    /**
     * 将 content:// URI 复制到本地缓存目录，返回缓存路径。
     *
     * @param uriString content:// URI
     * @return 本地缓存文件路径，失败返回 null
     */
    suspend fun cacheFromUri(uriString: String): String? = withContext(Dispatchers.IO) {
        try {
            val uri = android.net.Uri.parse(uriString)
            val ext = when {
                uriString.endsWith(".png", true)  -> ".png"
                uriString.endsWith(".webp", true) -> ".webp"
                uriString.endsWith(".gif", true)  -> ".gif"
                else -> ".jpg"
            }
            val cacheFile = File(cacheDir, "${System.nanoTime()}$ext")

            val inputStream = appContext.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e(TAG, "openInputStream 返回 null，无法读取 URI: $uriString")
                return@withContext null
            }
            inputStream.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            // 确认文件存在且不为空
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                Log.e(TAG, "缓存文件为空或不存在: ${cacheFile.absolutePath}")
                if (cacheFile.exists()) cacheFile.delete()
                return@withContext null
            }
            Log.i(TAG, "图片已缓存: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
            cacheFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "缓存文件失败: $uriString", e)
            null
        }
    }

    /**
     * 清理过期缓存文件（超过 24 小时的缓存图片）。
     * 在应用启动时调用，避免缓存无限增长。
     */
    suspend fun cleanExpiredCache(maxAgeMs: Long = 24 * 60 * 60 * 1000L) = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && (now - file.lastModified()) > maxAgeMs) {
                    file.delete()
                    Log.d(TAG, "已清理过期缓存: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理缓存失败", e)
        }
    }

    /**
     * 获取缓存目录当前大小（字节）。
     */
    suspend fun getCacheSize(): Long = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    companion object {
        private const val TAG = "MultimodalStorage"
        private const val KEY_URI = "uri"
        private const val KEY_TYPE = "type"
        private const val KEY_LOCAL_PATH = "localPath"
    }
}
