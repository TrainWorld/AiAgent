package com.example.aiagent

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * 管理本地模型文件列表，并通过 SharedPreferences 持久化当前使用的模型。
 *
 * 所有模型文件统一存放在应用私有目录 `filesDir/models/` 下。
 */
class ModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val modelsDir: File = File(appContext.filesDir, "models").also { it.mkdirs() }
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ────────────────────────── 数据模型 ──────────────────────────

    data class ModelInfo(
        val file: File,
        val displayName: String,
        val sizeMb: Double,
    ) {
        val path: String get() = file.absolutePath
    }

    // ────────────────────────── 公开 API ──────────────────────────

    /** 扫描 models 目录，返回全部模型（按文件名排序） */
    fun listModels(): List<ModelInfo> {
        return modelsDir.listFiles { f -> f.isFile && f.extension == "gguf" }
            ?.sortedBy { it.name }
            ?.map { f ->
                ModelInfo(
                    file = f,
                    displayName = f.nameWithoutExtension,
                    sizeMb = f.length() / 1024.0 / 1024.0,
                )
            }
            ?: emptyList()
    }

    /** 获取当前选中的模型文件名（持久化值） */
    fun getCurrentModelName(): String? = prefs.getString(KEY_CURRENT_MODEL, null)

    /** 保存当前选中的模型文件名 */
    fun setCurrentModelName(name: String) {
        prefs.edit().putString(KEY_CURRENT_MODEL, name).apply()
    }

    /** 清除当前选中记录 */
    fun clearCurrentModel() {
        prefs.edit().remove(KEY_CURRENT_MODEL).apply()
    }

    /**
     * 将外部 Uri 对应的文件复制到 models 目录，返回目标文件。
     * 如果同名文件已存在则先删除再覆盖。
     */
    fun importModel(context: Context, uri: android.net.Uri, displayName: String): File {
        val target = File(modelsDir, "$displayName.gguf")
        if (target.exists()) target.delete()
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw java.io.IOException("无法读取模型文件")
        return target
    }

    /** 删除指定模型文件；若删除的是当前选中模型，同时清除记录 */
    fun deleteModel(model: ModelInfo) {
        if (model.file.exists()) model.file.delete()
        if (getCurrentModelName() == model.displayName) clearCurrentModel()
    }

    /** models 目录是否存在至少一个模型 */
    fun hasModels(): Boolean = listModels().isNotEmpty()

    companion object {
        private const val PREFS_NAME = "ai_agent_model_prefs"
        private const val KEY_CURRENT_MODEL = "current_model_name"
    }
}
