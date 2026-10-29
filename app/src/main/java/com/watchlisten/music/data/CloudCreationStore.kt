package com.watchlisten.music.data

import android.content.Context
import com.watchlisten.music.model.*
import org.json.JSONArray
import org.json.JSONObject

/** 只保存操作名称、原列表标识和身份指纹，不保存 token、会话或歌曲。 */
interface CloudCreationStore {
    fun load(userId: String): PendingCloudCreate?
    fun save(pending: PendingCloudCreate)
    fun clear(userId: String, operationId: String)
}

class MemoryCloudCreationStore : CloudCreationStore {
    private val records = mutableMapOf<String, PendingCloudCreate>()
    override fun load(userId: String) = records[userId]
    override fun save(pending: PendingCloudCreate) { records[pending.userId] = pending }
    override fun clear(userId: String, operationId: String) {
        if (records[userId]?.operationId == operationId) records.remove(userId)
    }
}

class CloudCreationPreferences(context: Context) : CloudCreationStore {
    private val preferences = context.getSharedPreferences("cloud_create_pending", Context.MODE_PRIVATE)
    @Synchronized override fun load(userId: String): PendingCloudCreate? = runCatching {
        val record = preferences.getString("user_$userId", null) ?: return null
        val value = JSONObject(record)
        val before = value.getJSONArray("before")
        PendingCloudCreate(value.getString("operation"), value.getString("user"), value.getString("fingerprint"),
            value.getString("name"), (0 until before.length()).mapTo(linkedSetOf()) { before.getString(it) },
            value.getLong("created")).takeIf { it.userId == userId && it.operationId.isNotBlank() && it.name.isNotBlank() }
    }.getOrNull()

    @Synchronized override fun save(pending: PendingCloudCreate) {
        val value = JSONObject().put("operation", pending.operationId).put("user", pending.userId)
            .put("fingerprint", pending.identityFingerprint).put("name", pending.name)
            .put("before", JSONArray(pending.beforeIds.toList())).put("created", pending.createdAtMs)
        if (!preferences.edit().putString("user_${pending.userId}", value.toString()).commit())
            throw MusicFailure(MusicError.Unknown("无法保存待确认记录，创建请求未发送"))
    }

    @Synchronized override fun clear(userId: String, operationId: String) {
        if (load(userId)?.operationId != operationId) return
        if (!preferences.edit().remove("user_$userId").commit())
            throw MusicFailure(MusicError.Unknown("云端结果已确认，待确认记录清理失败，请刷新"))
    }
}
