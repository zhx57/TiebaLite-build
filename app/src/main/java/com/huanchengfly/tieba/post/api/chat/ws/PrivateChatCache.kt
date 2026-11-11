package com.huanchengfly.tieba.post.api.chat.ws

import android.content.Context
import com.huanchengfly.tieba.post.App
import org.json.JSONArray
import org.json.JSONObject

/**
 * 私信消息的本地缓存。
 *
 * 贴吧私信接口只提供增量拉取，退出页面后连接断开、游标重置，
 * 因此把会话消息落到本地，重新进入时先展示缓存再拉增量。
 *
 * 存储为 SharedPreferences 中的 JSON 字符串，key 为「当前账号 uid + 对方 uid」。
 * 每个会话最多保留 [MAX_MESSAGES] 条，避免无限膨胀。
 */
object PrivateChatCache {

    private const val PREF_NAME = "private_chat_cache"
    private const val MAX_MESSAGES = 200

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
     * 覆盖写入某个会话的消息列表，只保留最近 [MAX_MESSAGES] 条。
     */
    fun save(selfUid: Long, peerUid: Long, messages: List<ChatWsMessage>) {
        if (selfUid == 0L) return
        val trimmed = if (messages.size > MAX_MESSAGES) {
            messages.takeLast(MAX_MESSAGES)
        } else {
            messages
        }
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
    }
}
