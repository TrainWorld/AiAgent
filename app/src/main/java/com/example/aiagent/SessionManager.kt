package com.example.aiagent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 管理多会话的持久化存储。
 *
 * 文件结构：
 * ```
 * filesDir/sessions/
 *   ├── sessions_index.json   ← 会话列表索引
 *   ├── {uuid1}.json          ← 会话1的消息
 *   ├── {uuid2}.json          ← 会话2的消息
 *   └── ...
 * ```
 */
class SessionManager(context: Context) {

    private val appContext = context.applicationContext
    private val sessionsDir = File(appContext.filesDir, "sessions").also { it.mkdirs() }
    private val indexFile = File(sessionsDir, INDEX_FILE)
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    // ──────────────────── 会话索引 ────────────────────

    /** 加载全部会话（按更新时间倒序） */
    suspend fun loadSessions(): List<Session> = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) return@withContext emptyList()
        try {
            val json = indexFile.readText()
            val array = JSONArray(json)
            List(array.length()) { i ->
                parseSessionFromJson(array.getJSONObject(i))
            }.sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            Log.e(TAG, "加载会话列表失败", e)
            emptyList()
        }
    }

    /** 保存会话索引 */
    private suspend fun saveIndex(sessions: List<Session>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val array = JSONArray()
                sessions.forEach { s ->
                    array.put(sessionToJson(s))
                }
                indexFile.writeText(array.toString())
            } catch (e: Exception) {
                Log.e(TAG, "保存会话列表失败", e)
            }
        }
    }

    /** 创建新会话并返回它 */
    suspend fun createSession(title: String = "新对话"): Session = withContext(Dispatchers.IO) {
        val session = Session(title = title)
        val current = loadSessions().toMutableList()
        current.add(0, session)
        saveIndex(current)
        session
    }

    /** 删除会话（索引 + 消息文件） */
    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val current = loadSessions().toMutableList()
                current.removeAll { it.id == id }
                val array = JSONArray()
                current.forEach { s ->
                    array.put(sessionToJson(s))
                }
                indexFile.writeText(array.toString())
                // 删除消息文件
                val msgFile = File(sessionsDir, "$id.json")
                if (msgFile.exists()) msgFile.delete()
            } catch (e: Exception) {
                Log.e(TAG, "删除会话失败", e)
            }
        }
    }

    /** 更新会话标题 */
    suspend fun updateTitle(id: String, title: String) = withContext(Dispatchers.IO) {
        val current = loadSessions().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(title = title)
            saveIndex(current)
        }
    }

    /** 更新会话的 updatedAt 时间戳 */
    suspend fun touchSession(id: String) = withContext(Dispatchers.IO) {
        val current = loadSessions().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(updatedAt = System.currentTimeMillis())
            saveIndex(current)
        }
    }

    // ──────────────────── 消息读写 ────────────────────

    /** 加载指定会话的消息 */
    suspend fun loadMessages(sessionId: String): List<Message> = withContext(Dispatchers.IO) {
        val file = File(sessionsDir, "$sessionId.json")
        if (!file.exists()) return@withContext emptyList()
        try {
            val json = file.readText()
            val array = JSONArray(json)
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                Message(
                    content = obj.getString(MSG_CONTENT),
                    isUser = obj.getBoolean(MSG_IS_USER),
                )
            }.filter { it.isUser || it.content.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "加载会话消息失败: $sessionId", e)
            emptyList()
        }
    }

    /** 保存指定会话的消息 */
    suspend fun saveMessages(sessionId: String, messages: List<Message>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val file = File(sessionsDir, "$sessionId.json")
                    val array = JSONArray()
                    messages.forEach { msg ->
                        array.put(JSONObject().apply {
                            put(MSG_CONTENT, msg.content)
                            put(MSG_IS_USER, msg.isUser)
                        })
                    }
                    file.writeText(array.toString())
                    // 同步更新索引的 updatedAt
                    touchSessionInternal(sessionId)
                } catch (e: Exception) {
                    Log.e(TAG, "保存会话消息失败: $sessionId", e)
                }
            }
        }

    /** 内部方法：在已持有 mutex 时更新 updatedAt */
    private fun touchSessionInternal(id: String) {
        try {
            if (!indexFile.exists()) return
            val json = indexFile.readText()
            val array = JSONArray(json)
            val list = mutableListOf<Session>()
            for (i in 0 until array.length()) {
                list.add(parseSessionFromJson(array.getJSONObject(i)))
            }
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                list[idx] = list[idx].copy(updatedAt = System.currentTimeMillis())
            }
            val newArray = JSONArray()
            list.forEach { s ->
                newArray.put(sessionToJson(s))
            }
            indexFile.writeText(newArray.toString())
        } catch (e: Exception) {
            Log.e(TAG, "更新会话时间戳失败", e)
        }
    }

    /** 从 JSON 对象解析 Session */
    private fun parseSessionFromJson(obj: JSONObject): Session {
        return Session(
            id = obj.getString(KEY_ID),
            title = obj.optString(KEY_TITLE, "新对话"),
            createdAt = obj.optLong(KEY_CREATED, System.currentTimeMillis()),
            updatedAt = obj.optLong(KEY_UPDATED, System.currentTimeMillis()),
        )
    }

    /** 将 Session 序列化为 JSON 对象 */
    private fun sessionToJson(session: Session): JSONObject {
        return JSONObject().apply {
            put(KEY_ID, session.id)
            put(KEY_TITLE, session.title)
            put(KEY_CREATED, session.createdAt)
            put(KEY_UPDATED, session.updatedAt)
        }
    }

    // ──────────────────── 当前会话持久化 ────────────────────

    /** 保存当前活跃会话 ID */
    fun saveCurrentSessionId(id: String?) {
        if (id != null) prefs.edit().putString(KEY_CURRENT_SESSION, id).apply()
        else prefs.edit().remove(KEY_CURRENT_SESSION).apply()
    }

    /** 读取上次活跃的会话 ID */
    fun loadCurrentSessionId(): String? = prefs.getString(KEY_CURRENT_SESSION, null)

    companion object {
        private const val TAG = "SessionManager"
        private const val PREFS_NAME = "ai_agent_session_prefs"
        private const val INDEX_FILE = "sessions_index.json"
        private const val KEY_ID = "id"
        private const val KEY_TITLE = "title"
        private const val KEY_CREATED = "createdAt"
        private const val KEY_UPDATED = "updatedAt"
        private const val KEY_CURRENT_SESSION = "current_session_id"
        private const val MSG_CONTENT = "content"
        private const val MSG_IS_USER = "isUser"
    }
}
