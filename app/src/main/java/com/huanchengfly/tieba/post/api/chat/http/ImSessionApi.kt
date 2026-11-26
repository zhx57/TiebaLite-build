package com.huanchengfly.tieba.post.api.chat.http

import org.json.JSONObject

/**
 * 私信会话与消息的 HTTP 接口封装。
 *
 * 严格对齐官方 22.14 的 imsdk：
 * - fetch_session 拉会话列表，拉全部时 **不传** contacter / contacter_type
 * - fetch_msg 拉具体会话的消息
 * - send_msg 发消息，content 是裸的 {"text":...} 或 {"url",...}
 */
object ImSessionApi {

    /** 官方拉取会话列表的最大条数。 */
    private const val MAX_FETCH_COUNT = 20

    /**
     * 一个私信会话。
     *
     * 字段来自官方 fetch_session 的 sessions[] 元素。
     */
    data class Session(
        /** 对方贴吧 uid，来自 contacter_pass_uk 解密。 */
        val peerUid: Long,
        /** 对方 IM 唯一键。 */
        val peerImUk: Long,
        /** 会话类型，58 为用户私聊，57 为群聊。 */
        val chatType: Int,
        /** 消息类型，0 文本 / 1 图片 / 2 语音。 */
        val contentType: Int,
        /** 最后一条消息的原始内容 JSON。 */
        val content: String,
        /** 可读摘要。 */
        val summary: String,
        /** 未读数。 */
        val unreadNum: Int,
        /** 最后消息时间，毫秒。 */
        val lastTime: Long,
        /** 是否置顶。 */
        val isTop: Boolean,
        /** 是否被对方拉黑。 */
        val isBlock: Boolean,
        /** 是否免打扰。 */
        val doNotDisturb: Boolean,
        /** 公众号 uid，非 0 表示这是公众号会话。 */
        val paUid: Long,
    ) {
        /** 是否是用户之间的私聊会话。 */
        val isPrivateChat: Boolean get() = chatType == ImHttpConstants.CHAT_TYPE_PRIVATE
    }

    /**
     * 拉取会话列表。
     *
     * 官方拉全部会话时 mContacter 传 0、mContactorType 传 -1，
     * 这样请求体里不含 contacter / contacter_type，服务端返回全部会话。
     *
     * @param peerUid 指定对方 uid 时只拉该会话，传 0 表示拉全部。
     */
    suspend fun fetchSessions(peerUid: Long = 0L): List<Session> {
        val params = linkedMapOf<String, Any>(
            "need_top" to 1,
            "count" to MAX_FETCH_COUNT,
        )
        if (peerUid > 0L) {
            params["contacter"] = ImBduid.transBDUID(peerUid.toString())
            params["contacter_type"] = 0
        }
        val response = ImHttpClient.post("fetch_session", params)
        if (ImHttpClient.errorCode(response) != 0) {
            val code = ImHttpClient.errorCode(response)
            val message = ImHttpClient.errorMessage(response)
            // 只有官方确凿的登录错误码才提示重新登录；
            // 其余错误原样透出，避免把参数或网络问题误报成登录失效。
            if (ImHttpClient.isLoginError(response)) {
                throw ImHttpException(code, "登录态已失效，请重新登录贴吧（$code $message）")
            }
            throw ImHttpException(code, "拉取私信失败（$code $message）")
        }
        return parseSessions(response)
    }

    /**
     * 解析 fetch_session 响应。
     *
     * 官方把会话按 chat_type 分成不同 Map，这里统一拍平成列表。
     */
    internal fun parseSessions(response: JSONObject): List<Session> {
        val sessions = response.optJSONArray("sessions") ?: return emptyList()
        val result = mutableListOf<Session>()
        for (i in 0 until sessions.length()) {
            val item = sessions.optJSONObject(i) ?: continue
            val session = parseSession(item) ?: continue
            result += session
        }
        return result
    }

    private fun parseSession(item: JSONObject): Session? {
        val passerUk = item.optString("contacter_pass_uk")
        val peerUid = ImBduid.transBDUK(passerUk).toLongOrNull() ?: 0L
        val content = item.optString("content")
        val contentType = item.optInt("content_type", 0)
        return Session(
            peerUid = peerUid,
            peerImUk = item.optLong("contacter_im_uk"),
            chatType = item.optInt("chat_type"),
            contentType = contentType,
            content = content,
            summary = summarize(content, contentType),
            unreadNum = item.optInt("unread_num"),
            lastTime = item.optLong("last_time"),
            isTop = item.optInt("is_top") == 1,
            isBlock = item.optInt("is_block") == 1,
            doNotDisturb = item.optInt("do_not_disturb") == 1,
            paUid = item.optLong("contacter_pa_uid"),
        )
    }

    /**
     * 从消息内容 JSON 提取可读摘要。
     *
     * 官方 content 是 {"text":...} 或 {"url",...} 这种裸结构。
     */
    internal fun summarize(content: String, contentType: Int): String {
        if (content.isBlank()) {
            return when (contentType) {
                ImHttpConstants.TYPE_IMAGE -> "图片消息"
                ImHttpConstants.TYPE_VOICE -> "语音消息"
                else -> "聊天消息"
            }
        }
        val node = runCatching { org.json.JSONTokener(content).nextValue() }.getOrNull()
        if (node is JSONObject) {
            node.optString("text").takeIf { it.isNotBlank() }?.let { return it }
            val url = node.optString("url")
            if (url.isNotBlank()) {
                val width = node.optInt("w")
                val height = node.optInt("h")
                return if (width > 0 && height > 0) "图片消息" else "图片"
            }
        }
        return content.take(120)
    }
}
