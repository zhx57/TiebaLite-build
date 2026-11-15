package com.huanchengfly.tieba.post.api.chat.ws

import android.content.Context
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.models.chat.ChatCloudMeme
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageKind
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageSemantics
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
    private const val CACHE_VERSION = 3
    private val accountMessages = java.util.concurrent.ConcurrentHashMap<Long, MutableStateFlow<List<ChatWsMessage>>>()

    @Synchronized
    fun observe(selfUid: Long) = accountMessages.getOrPut(selfUid) {
        MutableStateFlow(loadAll(selfUid))
    }.asStateFlow()

    @Synchronized
    fun saveIncoming(selfUid: Long, messages: List<ChatWsMessage>, consumedCursor: Long? = null) {
        if (selfUid <= 0L) return
        val known = loadAll(selfUid).associateBy { it.msgId }
        messages.mapNotNull { message ->
            val peerUid = message.privateConversationUid(selfUid).takeIf { it > 0L }
                ?: known[message.msgId]?.privateConversationUid(selfUid)?.takeIf { it > 0L }
                ?: return@mapNotNull null
            message.copy(conversationUid = peerUid)
        }.groupBy { it.conversationUid }.forEach { (peerUid, batch) ->
            save(selfUid, peerUid, batch)
        }
        consumedCursor?.takeIf { it > syncCursor(selfUid) }?.let {
            prefs.edit().putLong("cursor_$selfUid", it).apply()
        }
    }

    fun syncCursor(selfUid: Long): Long =
        if (selfUid > 0L) prefs.getLong("cursor_$selfUid", 0L) else 0L

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
        if (selfUid <= 0L || peerUid <= 0L || selfUid == peerUid) return emptyList()
        val raw = prefs.getString(key(selfUid, peerUid), null) ?: return emptyList()
        val messages = runCatching {
            val root = JSONObject(raw)
            root.optJSONArray("messages")
        }.getOrNull()?.let(::readMessages) ?: runCatching {
            readMessages(JSONArray(raw))
        }.getOrDefault(emptyList())
        return messages.filter { it.conversationUid == 0L || it.conversationUid == peerUid }
            .map { message ->
                message.copy(
                    conversationUid = peerUid,
                    senderUid = message.senderUid.takeIf { it > 0L }
                        ?: if (message.isSelf) selfUid else message.peerUid,
                )
            }.sortedBy { it.msgTime }
    }

    private fun readMessages(array: JSONArray): List<ChatWsMessage> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            runCatching {
                val msgType = item.optInt("msgType")
                val semanticsObject = item.optJSONObject("semantics")
                val semantics = semanticsObject?.let {
                    ChatMessageSemantics(
                        kind = runCatching { ChatMessageKind.valueOf(it.optString("kind")) }
                            .getOrDefault(ChatMessageKind.UNKNOWN),
                        protocolSource = it.optString("protocolSource", "private_chat"),
                        transportType = it.optInt("transportType", msgType),
                        businessType = if (it.has("businessType") && !it.isNull("businessType"))
                            it.optInt("businessType") else null,
                        targetId = if (it.has("targetId") && !it.isNull("targetId"))
                            it.optLong("targetId") else null,
                        summary = it.optString("summary").ifBlank { "未知消息" },
                        rawPayload = it.optString("rawPayload").takeIf { value -> value.isNotBlank() },
                    )
                } ?: ChatMessageSemantics(
                    kind = ChatMessageSemantics.classify(msgType, null),
                    protocolSource = "private_chat_legacy_cache",
                    transportType = msgType,
                    summary = item.optString("content").ifBlank { "旧缓存消息" },
                )
                add(ChatWsMessage(
                    msgId = item.optLong("msgId"),
                    peerUid = item.optLong("peerUid"),
                    peerName = item.optString("peerName"),
                    peerPortrait = item.optString("peerPortrait"),
                    content = item.optString("content"),
                    msgTime = item.optLong("msgTime"),
                    isSelf = item.optBoolean("isSelf"),
                    deliveryConfirmed = item.optBoolean("deliveryConfirmed", semantics.protocolSource != "local_outgoing"),
                    conversationUid = item.optLong("conversationUid"),
                    senderUid = item.optLong("senderUid"),
                    msgType = msgType,
                    images = item.optJSONArray("images")?.let { images ->
                        (0 until images.length()).mapNotNull {
                            (images.opt(it) as? String)?.takeIf(String::isNotBlank)
                        }
                    }.orEmpty(),
                    cloudMeme = item.optJSONObject("cloudMeme")?.let { meme ->
                        runCatching {
                            ChatCloudMeme(
                                id = meme.optString("id"),
                                imageUrl = meme.optString("imageUrl"),
                                uploadedImageUrl = meme.optString("uploadedImageUrl").takeIf { it.isNotBlank() },
                                uploadedThumbnailUrl = meme.optString("uploadedThumbnailUrl").takeIf { it.isNotBlank() },
                                officialPicId = meme.optString("officialPicId").takeIf { it.isNotBlank() },
                            )
                        }.getOrNull()
                    },
                    semantics = semantics,
                ))
            }
        }
    }

    /**
     * 合并写入某个会话，避免通知和详情并发保存时覆盖新消息。
     */
    @Synchronized
    fun save(selfUid: Long, peerUid: Long, messages: List<ChatWsMessage>) {
        if (selfUid <= 0L || peerUid <= 0L || selfUid == peerUid) return
        val current = load(selfUid, peerUid)
        val known = current.associateByTo(linkedMapOf()) { it.msgId }
        messages.forEach { message ->
            val existing = known[message.msgId]
            val owner = message.privateConversationUid(selfUid).takeIf { it > 0L }
                ?: existing?.conversationUid
            if (owner != peerUid) return@forEach
            known[message.msgId] = message.copy(
                conversationUid = peerUid,
                senderUid = message.senderUid.takeIf { it > 0L }
                    ?: if (message.isSelf) selfUid else message.peerUid,
                peerName = message.peerName.ifBlank { existing?.peerName.orEmpty() },
                peerPortrait = message.peerPortrait.ifBlank { existing?.peerPortrait.orEmpty() },
                cloudMeme = message.cloudMeme ?: existing?.cloudMeme,
                deliveryConfirmed = message.deliveryConfirmed || existing?.deliveryConfirmed == true,
                semantics = if (message.isSelf && existing?.semantics?.protocolSource == "local_outgoing")
                    existing.semantics else message.semantics,
            )
        }
        val sorted = known.values.sortedBy { it.msgTime }
        val profileMessages = listOfNotNull(
            sorted.lastOrNull { !it.isSelf && it.peerName.isNotBlank() },
            sorted.lastOrNull { !it.isSelf && it.peerPortrait.isNotBlank() },
        ).distinctBy { it.msgId }
        val trimmed = (sorted.takeLast(MAX_MESSAGES - profileMessages.size) + profileMessages)
            .distinctBy { it.msgId }
            .sortedBy { it.msgTime }
        val array = JSONArray()
        trimmed.forEach { message ->
            array.put(JSONObject().apply {
                put("msgId", message.msgId)
                put("peerUid", message.peerUid)
                put("conversationUid", message.conversationUid)
                put("senderUid", message.senderUid)
                put("peerName", message.peerName)
                put("peerPortrait", message.peerPortrait)
                put("content", message.content)
                put("msgTime", message.msgTime)
                put("isSelf", message.isSelf)
                put("deliveryConfirmed", message.deliveryConfirmed)
                put("msgType", message.msgType)
                put("images", JSONArray(message.images))
                put("semantics", JSONObject().apply {
                    put("kind", message.semantics.kind.name)
                    put("protocolSource", message.semantics.protocolSource)
                    put("transportType", message.semantics.transportType)
                    message.semantics.businessType?.let { put("businessType", it) }
                    message.semantics.targetId?.let { put("targetId", it) }
                    put("summary", message.semantics.summary)
                    message.semantics.rawPayload?.let { put("rawPayload", it) }
                })
                message.cloudMeme?.let { meme ->
                    put("cloudMeme", JSONObject().apply {
                        put("id", meme.id)
                        put("imageUrl", meme.imageUrl)
                        meme.uploadedImageUrl?.let { put("uploadedImageUrl", it) }
                        meme.uploadedThumbnailUrl?.let { put("uploadedThumbnailUrl", it) }
                        meme.officialPicId?.let { put("officialPicId", it) }
                    })
                }
            })
        }
        val root = JSONObject().apply {
            put("version", CACHE_VERSION)
            put("messages", array)
        }
        prefs.edit().putString(key(selfUid, peerUid), root.toString()).apply()
        accountMessages[selfUid]?.value = loadAll(selfUid)
        revision.update { it + 1 }
    }
}
