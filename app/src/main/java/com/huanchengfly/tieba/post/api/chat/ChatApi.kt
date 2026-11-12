package com.huanchengfly.tieba.post.api.chat

import com.huanchengfly.tieba.post.api.chat.blcp.BlcpCrypto
import com.huanchengfly.tieba.post.api.models.chat.ChatRoom
import com.huanchengfly.tieba.post.api.models.chat.ChatRoomMessage
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.CuidUtils
import com.huanchengfly.tieba.post.utils.EmoticonManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

/**
 * 吧群（群聊）接口实现。
 *
 * 群聊涉及两个域名：
 * - tiebac.baidu.com：群列表、群详情，表单提交，需要业务签名
 * - pim.baidu.com：群消息，JSON 提交，使用聊天专用签名（salt 为空）
 *
 * 由于签名规则和普通业务接口不同，这里直接用 OkHttp 调用，不复用 Retrofit 实例。
 */
object ChatApi {

    private const val CHAT_APP_ID = "10773430"
    private const val CHAT_SDK_VERSION = "11250036"

    /** 普通业务表单接口的签名盐。 */
    private const val APP_SALT = "tiebaclient!!!"

    /**
     * 聊天接口上报的客户端版本。
     *
     * 服务端会校验该版本号，过旧时 pim 接口直接返回 110001 未知错误，
     * 因此需要与最新官方客户端保持一致。
     */
    private const val CHAT_VERSION = "22.13.1.1"
    private const val LONG_MAX = "9223372036854775807"

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 拉取指定贴吧的吧群列表。
     *
     * 接口返回 data.list[].room_list[]，这里把每个房间拍平成 ChatRoom。
     */
    suspend fun getRoomListByFid(fid: Long): List<ChatRoom> = withContext(Dispatchers.IO) {
        val params = linkedMapOf(
            "BDUSS" to (AccountUtil.getBduss() ?: ""),
            "_client_version" to CHAT_VERSION,
            "call_from" to "frs",
            "fid" to fid.toString(),
        )
        // 普通业务接口使用 tiebaclient!!! 作为盐
        val form = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
            add("sign", ChatSignUtil.sign(params, APP_SALT))
        }.build()
        val request = Request.Builder()
            .url("https://tiebac.baidu.com/c/f/chat/getRoomListByFid")
            .post(form)
            .header("User-Agent", "bdtb for Android $CHAT_VERSION")
            .header("Cookie", "BDUSS=${AccountUtil.getBduss() ?: ""}")
            .build()

        val body = execute(request)
        val root = JSONObject(body)
        // 部分吧没有群时 error_code 为 0 但 data 为空；异常码则抛出便于排查
        val errCode = root.optInt("error_code", 0)
        if (errCode != 0) {
            throw ChatApiException(errCode, root.optString("error_msg"))
        }
        val data = root.optJSONObject("data") ?: return@withContext emptyList()
        val list = data.optJSONArray("list") ?: return@withContext emptyList()
        val rooms = mutableListOf<ChatRoom>()
        for (i in 0 until list.length()) {
            val group = list.optJSONObject(i) ?: continue
            val roomList = group.optJSONArray("room_list") ?: continue
            for (j in 0 until roomList.length()) {
                val room = roomList.optJSONObject(j) ?: continue
                // 真实结构：room_id 在顶层且是字符串，群信息嵌套在 room_info 里
                val roomId = room.optString("room_id").toLongOrNull() ?: 0L
                if (roomId <= 0L) continue
                val info = room.optJSONObject("room_info") ?: JSONObject()
                rooms.add(
                    ChatRoom(
                        roomId = roomId,
                        forumId = info.optString("forum_id").toLongOrNull() ?: fid,
                        forumName = info.optString("forum_name"),
                        name = info.optString("name"),
                        avatar = info.optString("avatar"),
                        discussNum = info.optString("discuss_num").toIntOrNull() ?: 0,
                        announce = room.optString("announce")
                            .ifBlank { info.optString("announce") },
                    )
                )
            }
        }
        rooms
    }

    /**
     * 拉取群最近消息。接口一次返回最多 60 条（count 为负表示取最新）。
     *
     * @param lastMsgId 增量游标，0 表示从最新开始拉
     */
    suspend fun getChatRoomMessages(
        roomId: Long,
        lastMsgId: Long = 0L,
    ): List<ChatRoomMessage> = withContext(Dispatchers.IO) {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val deviceId = CuidUtils.getNewCuid()
        // 签名使用的值与 JSON body 中的数值类型保持一致，均按字符串拼接
        val signParams = linkedMapOf(
            "appid" to CHAT_APP_ID,
            "mcast_id" to roomId.toString(),
            "msgid_end" to if (lastMsgId == 0L) LONG_MAX else lastMsgId.toString(),
            "count" to "-60",
            "category" to "4",
            "app_version" to CHAT_VERSION,
            "sdk_version" to CHAT_SDK_VERSION,
            "device_id" to deviceId,
            "timestamp" to timestamp,
        )
        val bodyJson = JSONObject().apply {
            put("appid", CHAT_APP_ID.toInt())
            put("mcast_id", roomId)
            put("msgid_end", if (lastMsgId == 0L) Long.MAX_VALUE else lastMsgId)
            put("count", -60)
            put("category", 4)
            put("app_version", CHAT_VERSION)
            put("sdk_version", CHAT_SDK_VERSION.toInt())
            put("device_id", deviceId)
            put("timestamp", timestamp.toLong())
            put("sign", ChatSignUtil.sign(signParams))
        }

        val request = Request.Builder()
            .url("https://pim.baidu.com/rest/3.0/im/fetch_mcast_msg_client")
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "okhttp/3.11.0")
            .header("Cookie", "BDUSS=${AccountUtil.getBduss() ?: ""}")
            .build()

        val body = execute(request)
        val root = JSONObject(body)
        val errCode = root.optInt("err_code", 0)
        if (errCode != 0) {
            throw ChatApiException(errCode, root.optString("err_msg"))
        }
        val messages = root.optJSONArray("messages") ?: return@withContext emptyList()
        val myUid = AccountUtil.getUid()?.toLongOrNull()
        (0 until messages.length()).mapNotNull { index ->
            parseMessage(messages.optJSONObject(index) ?: return@mapNotNull null, myUid)
        }
    }

    /**
     * 解析单条群消息。
     *
     * 线上字段为 msgid / type / from_user / create_time，content 为 JSON 字符串。
     * 正文可能以多种嵌套形态出现（text 字符串、对象、数组、content_body 再包一层），
     * 这里递归提取，避免正常文本被误判成不支持的消息。
     * from_user 为 0 的是系统消息（入群、退群等），直接丢弃。
     */
    private fun parseMessage(json: JSONObject, myUid: Long?): ChatRoomMessage? {
        val senderUid = json.optLong("from_user")
        if (senderUid == 0L) return null

        val msgType = json.optInt("type", 0)
        val content = runCatching {
            when (val raw = json.opt("content")) {
                is String -> if (raw.isBlank()) null else JSONTokener(raw).nextValue()
                else -> raw
            }
        }.getOrNull()

        var senderName = ""
        var portrait = ""
        var senderBduk = ""
        if (content is JSONObject) {
            senderName = content.optString("name")
            portrait = content.optString("portrait")
            senderBduk = content.optString("baidu_uk")
            // 用户信息常位于 content["text"] 这层，且该字段往往是 JSON 字符串
            val textNode = content.opt("text")
            val textObj = when (textNode) {
                is JSONObject -> textNode
                is String -> runCatching {
                    JSONTokener(textNode).nextValue() as? JSONObject
                }.getOrNull()
                else -> null
            }
            if (textObj != null) {
                if (senderName.isBlank()) senderName = textObj.optString("name")
                if (portrait.isBlank()) portrait = textObj.optString("portrait")
                if (senderBduk.isBlank()) senderBduk = textObj.optString("baidu_uk")
            }
            // 昵称兜底：部分消息放在 ext 里
            if (senderName.isBlank()) {
                val ext = content.opt("ext")
                val extObj = when (ext) {
                    is JSONObject -> ext
                    is String -> runCatching {
                        JSONTokener(ext).nextValue() as? JSONObject
                    }.getOrNull()
                    else -> null
                }
                if (extObj != null) {
                    senderName = extObj.optString("user_name").ifBlank {
                        extObj.optString("name")
                    }
                    if (portrait.isBlank()) portrait = extObj.optString("portrait")
                }
            }
        }

        val rawContent = (json.opt("content") as? String)?.trim()
        val rawText = extractText(content).trim().ifBlank {
            // content 本身不是 JSON 时按纯文本处理，但仍要排除 JSON 原文
            rawContent?.takeUnless { it.startsWith("{") || it.startsWith("[") }.orEmpty()
        }

        // 图片消息：图片地址在 content_body 的 src 上，多图时可能是数组或逗号拼接。
        val images = extractImages(content)

        // 表情消息：content_body 的 text 是 image_emoticon25 这类表情 id，
        // 需要转成渲染层认识的 (#表情名)，否则正文为空会被显示成不支持的消息。
        val text = replaceEmoticonIds(rawText)

        // 是否为自己：优先用 baidu_uk 解密出的 uid 判定，其次比对 from_user
        val bdukUid = if (senderBduk.isNotBlank()) {
            runCatching { BlcpCrypto.bduk2UserId(senderBduk) }.getOrNull()
        } else {
            null
        }
        val isSelf = when {
            bdukUid != null && myUid != null -> bdukUid == myUid
            myUid != null -> senderUid == myUid
            else -> false
        }

        return ChatRoomMessage(
            msgId = json.optLong("msgid"),
            // from_user 是 IM 体系内的账号 id，和贴吧 uid 不是同一套；
            // 跳转资料卡必须用 bduk 解出来的贴吧 uid，否则会进到别人主页。
            senderUid = bdukUid ?: senderUid,
            senderName = senderName,
            senderPortrait = portrait,
            contentType = msgType,
            text = text,
            images = images,
            msgTime = json.optLong("create_time") * 1000L,
            isSelf = isSelf,
        )
    }

    /**
     * 提取图片消息的图片地址。
     *
     * 群聊图片消息的 content_body 里带 src（可能是完整链接或相对路径），
     * 多图时可能是数组或逗号拼接。这里逐层向内找 src/img，非图片消息返回空列表。
     */
    private fun extractImages(node: Any?): List<String> = when (node) {
        null -> emptyList()

        is JSONObject -> {
            val direct = mutableListOf<String>()
            collectImageUrls(node.opt("src"), direct)
            collectImageUrls(node.opt("img"), direct)
            collectImageUrls(node.opt("image"), direct)
            if (direct.isNotEmpty()) {
                direct
            } else {
                val body = node.opt("content_body")
                extractImages(body).ifEmpty { extractImages(node.opt("text")) }
            }
        }

        is JSONArray -> buildList {
            for (i in 0 until node.length()) {
                addAll(extractImages(node.opt(i)))
            }
        }

        is String -> {
            val trimmed = node.trim()
            when {
                trimmed.startsWith("{") || trimmed.startsWith("[") ->
                    runCatching { extractImages(JSONTokener(trimmed).nextValue()) }
                        .getOrDefault(emptyList())

                else -> normalizeImageUrl(trimmed)?.let(::listOf).orEmpty()
            }
        }

        else -> emptyList()
    }

    /**
     * 规范化图片地址。
     *
     * 部分群聊图片只返回 /c/p/xxx 这样的相对路径，需要补上贴吧图床域名；
     * 完全不相关的字符串直接丢弃，避免把表情 id 误当图片。
     */
    private fun normalizeImageUrl(raw: String): String? {
        val url = raw.trim()
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://tiebac.baidu.com$url"
            else -> null
        }
    }

    private fun collectImageUrls(value: Any?, out: MutableList<String>) {
        when (value) {
            is String -> value.split(",")
                .mapNotNull { normalizeImageUrl(it) }
                .forEach(out::add)

            is JSONArray -> for (i in 0 until value.length()) {
                collectImageUrls(value.opt(i), out)
            }

            is JSONObject -> {
                collectImageUrls(value.opt("url"), out)
                collectImageUrls(value.opt("src"), out)
            }

            else -> Unit
        }
    }

    /**
     * 把群聊里的表情 id 转成渲染层认识的表情标记。
     *
     * 群聊表情消息的正文形如 image_emoticon25，而 PbContentText 只识别
     * #(滑稽) 这类标记；不转换会显示成不支持的消息。单条消息可能同时含
     * 文本与多个表情，这里逐个替换。
     */
    private fun replaceEmoticonIds(raw: String): String {
        if (raw.isBlank() || !raw.contains("image_emoticon")) return raw
        return EMOTICON_ID_REGEX.replace(raw) { match ->
            val id = match.value
            val name = EmoticonManager.getEmoticonNameById(id)
            if (name != null) "#($name)" else id
        }
    }

    private val EMOTICON_ID_REGEX = Regex("image_emoticon\\d+")

    /**
     * 解析正文，与 aiotieba 的 ChatroomMsg.from_json 保持一致。
     *
     * content（JSON）-> content["text"]（仍是 JSON 字符串）
     * -> 若其中有 content_body 再解一层 -> 最终取 text。
     * 任何一层解析失败都返回空串，绝不把 JSON 原文当作消息内容显示。
     */
    private fun extractText(node: Any?): String = when (node) {
        null -> ""
        is JSONObject -> {
            val body = node.opt("content_body")
            if (body != null) {
                extractText(body).ifBlank { extractText(node.opt("text")) }
            } else {
                extractText(node.opt("text")).ifBlank { node.optString("msg").trim() }
                    .takeUnless { it.startsWith("{") || it.startsWith("[") }
                    .orEmpty()
            }
        }

        is JSONArray -> buildString {
            for (i in 0 until node.length()) {
                append(extractText(node.opt(i)))
            }
        }

        is String -> {
            val trimmed = node.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                // 字符串本身还是 JSON，继续解一层；解析失败则丢弃
                runCatching { extractText(JSONTokener(trimmed).nextValue()) }.getOrDefault("")
            } else {
                trimmed
            }
        }

        else -> ""
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ChatApiException(response.code, response.message)
            }
            return response.body?.string().orEmpty()
        }
    }
}

class ChatApiException(val code: Int, message: String?) :
    RuntimeException("chat api error: $code $message")
