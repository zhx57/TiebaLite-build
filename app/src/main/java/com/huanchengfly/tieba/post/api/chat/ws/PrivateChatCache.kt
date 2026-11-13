package com.huanchengfly.tieba.post.api.chat.ws

import android.content.Context
import com.huanchengfly.tieba.post.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * 私信消息的本地缓存。
 *
 * 贴吧私信接口只提供增量拉取，退出页面后连接断开、游标重置，
 * 因此把会话消息落到本地，重新进入时先展示缓存再拉增量。
 *
 * 存储为 SharedPreferences 中的 JSON 字符串，键由账号和对方 uid 组成。
 * 缓存键由当前账号 uid 和对方 uid 组成。
 * 每个会话最多保留 [MAX_MESSAGES] 条，避免无限膨胀。
 */
object PrivateChatCache {

    private const val PREF_NAME = "private_chat_cache"
    private const val MAX_MESSAGES = 200
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    fun loadAll(selfUid: Long): List<ChatWsMessage> {
        if (selfUid == 0L) return emptyList()
        val prefix = "${selfUid}_"
        return prefs.all.keys.filter { it.startsWith(prefix) }.flatMap { entry ->
            entry.removePrefix(prefix).toLongOrNull()?.let { load(selfUid, it) }.orEmpty()
        }
    }

    private val prefs by lazy {
        App.INSTANCE.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun key(selfUid: Long, peerUid: Long) = "${selfUid}_$peerUid"

    /**
     * 读取某个会话的缓存消息，按时间正序。
     */
    fun load(selfUid: Long, peerUid: Long): List<ChatWsMessage> {
        val raw = prefs.getString(key(selfUid, peerUid), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                ChatWsMessage(
                    msgId = item.optLong("msgId"),
                    peerUid = item.optLong("peerUid"),
                    peerName = item.optString("peerName"),
                    peerPortrait = item.optString("peerPortrait"),
                    content = item.optString("content"),
                    msgTime = item.optLong("msgTime"),
                    isSelf = item.optBoolean("isSelf"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 合并写入某个会话，避免通知和详情并发保存时覆盖新消息。
     */
    @Synchronized
    fun save(selfUid: Long, peerUid: Long, messages: List<ChatWsMessage>) {
        if (selfUid == 0L) return
        val trimmed = (load(selfUid, peerUid) + messages)
            .associateBy { it.msgId }
            .values
            .sortedBy { it.msgTime }
            .takeLast(MAX_MESSAGES)
        val array = JSONArray()
        trimmed.forEach { message ->
            array.put(JSONObject().apply {
                put("msgId", message.msgId)
                put("peerUid", message.peerUid)
                put("peerName", message.peerName)
                put("peerPortrait", message.peerPortrait)
                put("content", message.content)
                put("msgTime", message.msgTime)
                put("isSelf", message.isSelf)
            })
        }
        prefs.edit().putString(key(selfUid, peerUid), array.toString()).apply()
        revision.update { it + 1 }
    }
}
