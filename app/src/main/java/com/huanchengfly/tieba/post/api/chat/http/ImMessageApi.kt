package com.huanchengfly.tieba.post.api.chat.http

import org.json.JSONObject
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsConstants
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageKind
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageSemantics

/**
 * 私信消息的拉取与发送。
 *
 * 严格对齐官方 22.14 的 imsdk：
 * - fetch_msg 拉指定会话的消息，contacter 用加密后的对方 uid
 * - send_msg 发消息，content 是**裸 JSON**（{"text":...} 或 {"url","thumbnail","w","h"}），
 *   不带 buid/tobuid/ext 包装，那是 WebSocket 路径的格式
 */
object ImMessageApi {

    /** 前后台统一过滤无法归属当前账号及会话的消息。 */
    fun toChatMessage(message: Message, selfUid: Long, peerUid: Long): ChatWsMessage? {
        if (selfUid <= 0L || peerUid <= 0L || selfUid == peerUid || message.msgId <= 0L ||
            (message.fromUid != selfUid && message.fromUid != peerUid)
        ) return null
        val image = message.imageUrl.isNotBlank()
        val kind = when {
            image -> ChatMessageKind.IMAGE
            message.type == ImHttpConstants.TYPE_VOICE -> ChatMessageKind.VOICE
            else -> ChatMessageKind.TEXT
        }
        return ChatWsMessage(
            msgId = message.msgId,
            msgKey = message.msgKey,
            peerUid = peerUid,
            conversationUid = peerUid,
            senderUid = message.fromUid,
            peerName = "",
            peerPortrait = "",
            content = message.text,
            images = if (image) listOf(message.imageUrl) else emptyList(),
            msgTime = message.msgTime,
            isSelf = message.fromUid == selfUid,
            msgType = if (image) ChatWsConstants.MSG_TYPE_PRIVATE_IMAGE else ChatWsConstants.MSG_TYPE_PRIVATE,
            semantics = ChatMessageSemantics(
                kind = kind,
                protocolSource = "private_http",
                transportType = if (image) ChatWsConstants.MSG_TYPE_PRIVATE_IMAGE else ChatWsConstants.MSG_TYPE_PRIVATE,
                businessType = when (kind) { ChatMessageKind.IMAGE -> 2; ChatMessageKind.VOICE -> 3; else -> 1 },
                summary = message.text.ifBlank { when (kind) { ChatMessageKind.IMAGE -> "图片消息"; ChatMessageKind.VOICE -> "语音消息"; else -> "聊天消息" } },
                rawPayload = ChatMessageSemantics.limitedRawPayload(message.rawContent),
            ),
        )
    }

    /** 一次拉取的消息条数上限。 */
    private const val FETCH_COUNT = 20

    /**
     * 一条私信消息。
     */
    data class Message(
        /** 消息 id。 */
        val msgId: Long,
        /** 发送者 IM 唯一键。 */
        val fromImUk: Long,
        /** 发送者贴吧 uid，来自 from_pass_uk 解密。 */
        val fromUid: Long,
        /** 消息大类，0 文本 / 1 图片。 */
        val category: Int,
        /** 消息子类型，0 文本 / 1 图片 / 2 语音。 */
        val type: Int,
        /** 可读正文。 */
        val text: String,
        /** 图片地址，非图片消息为空。 */
        val imageUrl: String,
        /** 缩略图地址。 */
        val thumbnailUrl: String,
        /** 消息时间，毫秒。 */
        val msgTime: Long,
        /** 原始内容 JSON。 */
        val rawContent: String,
        /** 客户端消息唯一键。 */
        val msgKey: String,
    )

    /**
     * 拉取与指定对方的消息。
     *
     * @param peerUid 对方贴吧 uid。
     * @param beginTime 起始时间，毫秒，0 表示不限。
     * @param endTime 结束时间，毫秒，0 表示不限。
     */
    suspend fun fetchMessages(
        peerUid: Long,
        beginTime: Long = 0L,
        endTime: Long = 0L,
        accountBduss: String = AccountUtil.getBduss().orEmpty(),
    ): List<Message> {
        val params = linkedMapOf<String, Any>(
            "count" to FETCH_COUNT,
            "contacter" to ImBduid.transBDUID(peerUid.toString()),
            "contacter_type" to 0,
        )
        if (beginTime > 0L) params["begin_time"] = beginTime
        if (endTime > 0L) params["end_time"] = endTime
        val response = ImHttpClient.post("fetch_msg", params, accountBduss)
        if (ImHttpClient.errorCode(response) != 0) {
            throw ImHttpException(
                ImHttpClient.errorCode(response),
                ImHttpClient.errorMessage(response),
            )
        }
        return parseMessages(response)
    }

    /**
     * 解析 fetch_msg 响应。
     */
    internal fun parseMessages(response: JSONObject): List<Message> {
        val msgs = response.optJSONArray("msgs") ?: return emptyList()
        val result = mutableListOf<Message>()
        for (i in 0 until msgs.length()) {
            val item = msgs.optJSONObject(i) ?: continue
            result += parseMessage(item)
        }
        return result
    }

    private fun parseMessage(item: JSONObject): Message {
        val rawContent = item.optString("content")
        val category = item.optInt("category", 0)
        val type = item.optInt("type", 0)
        val image = parseImageContent(rawContent)
        val text = parseTextContent(rawContent, category, type, image != null)
        return Message(
            msgId = item.optLong("msgid"),
            fromImUk = item.optLong("from_im_uk"),
            fromUid = ImBduid.transBDUK(item.optString("from_pass_uk")).toLongOrNull() ?: 0L,
            category = category,
            type = type,
            text = text,
            imageUrl = image?.first.orEmpty(),
            thumbnailUrl = image?.second.orEmpty(),
            msgTime = item.optLong("create_time"),
            rawContent = rawContent,
            msgKey = item.optString("msg_key"),
        )
    }

    /**
     * 从内容 JSON 里取图片地址与缩略图。
     */
    private fun parseImageContent(rawContent: String): Pair<String, String>? {
        if (rawContent.isBlank()) return null
        val node = runCatching { org.json.JSONTokener(rawContent).nextValue() }.getOrNull()
        if (node !is JSONObject) return null
        val url = node.optString("url")
        if (url.isBlank()) return null
        val thumbnail = node.optString("thumbnail").ifBlank { url }
        return url to thumbnail
    }

    /**
     * 从内容 JSON 里取可读正文。
     */
    private fun parseTextContent(
        rawContent: String,
        category: Int,
        type: Int,
        isImage: Boolean,
    ): String {
        if (isImage) return ""
        if (rawContent.isBlank()) return ""
        val node = runCatching { org.json.JSONTokener(rawContent).nextValue() }.getOrNull()
        if (node is JSONObject) {
            node.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        }
        if (category == ImHttpConstants.CATEGORY_TEXT && type == ImHttpConstants.TYPE_TEXT) {
            // 纯文本消息的内容有时不是 JSON，直接当正文。
            return rawContent.take(4096)
        }
        return ""
    }

    /**
     * 发送私信。
     *
     * @param peerUid 对方贴吧 uid。
     * @param text 文本内容，图片消息时为空。
     * @param imageUrl 图片原图地址，文本消息时为空。
     * @param thumbnailUrl 缩略图地址。
     * @param width 图片宽。
     * @param height 图片高。
     * @return 服务端返回的消息 id。
     */
    suspend fun sendMessage(
        peerUid: Long,
        text: String,
        imageUrl: String? = null,
        thumbnailUrl: String? = null,
        width: Int = 0,
        height: Int = 0,
        msgKey: String = createMsgKey(),
        clientIdentifier: String = buildClientIdentifier(),
        accountBduss: String = AccountUtil.getBduss().orEmpty(),
    ): Long {
        val isImage = !imageUrl.isNullOrBlank()
        val content = if (isImage) {
            JSONObject().apply {
                put("url", imageUrl)
                put("thumbnail", thumbnailUrl?.ifBlank { imageUrl } ?: imageUrl)
                // 官方只有宽高都大于 0 时才写这两个字段。
                if (width > 0 && height > 0) {
                    put("w", width)
                    put("h", height)
                }
            }.toString()
        } else {
            JSONObject().put("text", text).toString()
        }
        val params = linkedMapOf<String, Any>(
            "contacter" to ImBduid.transBDUID(peerUid.toString()),
            "contacter_type" to 0,
            "type" to if (isImage) ImHttpConstants.TYPE_IMAGE else ImHttpConstants.TYPE_TEXT,
            "content" to content,
            "msg_key" to msgKey,
            // 官方 send_msg 在 category != 4 且 != 9 时必须带 client_identifier，
            // 内容是 {"zid":"...","version_code":"..."} 的 JSON 字符串。
            // 缺了它服务端会拒绝发送，这是私信一直发不出去的根因之一。
            "client_identifier" to clientIdentifier,
        )
        val response = ImHttpClient.post("send_msg", params, accountBduss)
        if (ImHttpClient.errorCode(response) != 0) {
            throw ImHttpException(
                ImHttpClient.errorCode(response),
                ImHttpClient.errorMessage(response),
            )
        }
        val msgId = response.optLong("msgid", 0L)
        if (msgId <= 0L) {
            throw ImHttpTransportException("发送私信未返回消息 id，结果未确认")
        }
        return msgId
    }

    /**
     * 构造官方 send_msg 的 client_identifier。
     *
     * 官方 AccountManagerImpl.getExtraSafeParams 返回
     * {"zid":"<zid>","version_code":"<version_code>"}，两值都是字符串且可为空。
     * 服务端用它做设备安全校验，缺失会被判为非法请求。
     */
    internal fun buildClientIdentifier(): String {
        val zid = com.huanchengfly.tieba.post.utils.AccountUtil
            .getAccountInfo { zid }.orEmpty()
        return JSONObject().apply {
            put("zid", zid)
            put("version_code", "")
        }.toString()
    }

    /**
     * 生成客户端消息唯一键。
     *
     * 官方 ChatMsg.createMsgKey 把 20 位毫秒时间戳、10 位 trigger id 与 10 位随机数
     * 拼成 40 位，再按小端写成 5 字节的十六进制字符串。
     */
    internal fun createMsgKey(triggerId: Long = 0L): String {
        // 40 位拼接必须用 Long，Int 会溢出丢掉高位。
        val millis = System.currentTimeMillis() and 0xFFFFFL
        val trigger = (triggerId and 0x3FFL) shl 10
        val random = (java.util.Random().nextInt(1024) and 0x3FF).toLong()
        val value = (millis shl 20) or trigger or random
        val bytes = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 32) and 0xFF).toByte(),
        )
        return bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    }
}
