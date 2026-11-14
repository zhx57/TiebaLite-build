package com.huanchengfly.tieba.post.api.chat

import com.huanchengfly.tieba.post.api.chat.blcp.BlcpCrypto
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageKind
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageSemantics
import com.huanchengfly.tieba.post.api.models.chat.ChatMention
import com.huanchengfly.tieba.post.api.models.chat.ChatRobot
import com.huanchengfly.tieba.post.api.models.chat.ChatRobotSkill
import com.huanchengfly.tieba.post.api.models.chat.ChatRoom
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    suspend fun getChatRoomRobots(roomId: Long): List<ChatRobot> = withContext(Dispatchers.IO) {
        val params = linkedMapOf(
            "_client_version" to CHAT_VERSION,
            "chatroom_id" to roomId.toString(),
        )
        val url = "https://tiebac.baidu.com/c/f/chat/getChatRoomDetail".toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
            addQueryParameter("sign", ChatSignUtil.sign(params, APP_SALT))
        }.build()
        val root = JSONObject(execute(Request.Builder().url(url)
            .header("Cookie", "BDUSS=${AccountUtil.getBduss() ?: ""}")
            .header("User-Agent", "bdtb for Android $CHAT_VERSION").build()))
        val code = root.optInt("error_code")
        if (code != 0) throw ChatApiException(code, root.optString("error_msg"))
        parseRobots(root.optJSONObject("data") ?: JSONObject())
    }

    internal fun parseRobots(data: JSONObject): List<ChatRobot> {
        val bots = data.optJSONObject("bots")?.optJSONArray("bot_list") ?: return emptyList()
        return buildList {
            for (i in 0 until bots.length()) {
                val bot = bots.optJSONObject(i) ?: continue
                val user = bot.optJSONObject("user") ?: continue
                val bduk = user.optString("uk")
                val name = user.optString("name_show").ifBlank { user.optString("name") }
                if (bduk.isBlank() || name.isBlank()) continue
                val skills = bot.optJSONArray("skill") ?: JSONArray()
                add(ChatRobot(
                    ChatMention(decodeUid(bduk), bduk, name, user.optString("portrait")),
                    buildList {
                        for (j in 0 until skills.length()) {
                            val skill = skills.optJSONObject(j) ?: continue
                            // is_show 控制官方入口展示，不代表技能不可调用。
                            add(ChatRobotSkill(skill.optInt("type"), skill.optString("name"),
                                skill.optString("alias"), skill.optString("desc")))
                        }
                    },
                ))
            }
        }
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
     * from_user 为 0 的是系统消息，保留为系统事件；已读等控制回执不在本接口消息列表中。
     */
    internal fun parseMessage(json: JSONObject, myUid: Long?): ChatRoomMessage? {
        val senderUid = json.optLong("from_user")
        val isSystem = senderUid == 0L
        val transportType = json.optInt("type", 0)
        val content = runCatching {
            when (val raw = json.opt("content")) {
                is String -> if (raw.isBlank()) null else JSONTokener(raw).nextValue()
                else -> raw
            }
        }.getOrNull()

        val envelope = content as? JSONObject
        val textObj = decodeJson(envelope?.opt("text")) as? JSONObject ?: envelope
        val ext = decodeJson(textObj?.opt("ext")) as? JSONObject
        val senderName = textObj?.optString("name").orEmpty().ifBlank {
            ext?.optString("user_name").orEmpty().ifBlank { ext?.optString("name").orEmpty() }
        }.ifBlank { if (isSystem) "系统" else "" }
        val portrait = textObj?.optString("portrait").orEmpty().ifBlank {
            ext?.optString("portrait").orEmpty()
        }
        val senderBduk = textObj?.optString("baidu_uk").orEmpty()
        val businessType = textObj?.takeIf { it.has("content_type") }?.optInt("content_type")
            ?: transportType.takeIf { isSystem && it in SYSTEM_BUSINESS_TYPES }
        val messageKind = ChatMessageSemantics.classify(transportType, businessType, isSystem)
        val targetId = sequenceOf("msgid", "msg_id", "target_id", "target_msgid", "pid", "tid")
            .mapNotNull { key ->
                val value = textObj?.opt(key) ?: envelope?.opt(key)
                value?.toString()?.toLongOrNull()
            }
            .firstOrNull()
        val atData = decodeJson(textObj?.opt("at_data")) as? JSONArray
        val mentions = buildList {
            if (atData != null) for (i in 0 until atData.length()) {
                val user = atData.optJSONObject(i) ?: continue
                val bduk = user.optString("at_baidu_uk")
                if (bduk.isBlank()) continue
                add(ChatMention(decodeUid(bduk), bduk, user.optString("at_name"), user.optString("at_portrait")))
            }
        }
        val robotParams = (decodeJson(ext?.opt("content")) as? JSONObject)?.optJSONObject("robot_params")
        val robotSkill = robotParams?.takeIf { it.has("type") }?.optInt("type")

        val rawContent = (json.opt("content") as? String)?.trim()
        val rawText = extractText(content).trim().ifBlank {
            // content 本身不是 JSON 时按纯文本处理，但仍要排除 JSON 原文
            rawContent?.takeUnless { it.startsWith("{") || it.startsWith("[") }.orEmpty()
        }

        // 图片消息：图片地址在 content_body 的 src 上，多图时可能是数组或逗号拼接。
        val bodyImages = extractImages(textObj?.opt("content_body"))
        val sourceImages = extractImageSources(textObj?.opt("src"))
        val textImages = if (messageKind == ChatMessageKind.TEXT) extractImageSources(rawText) else
            extractImageSources(rawText).filter { url ->
                val host = runCatching { url.toHttpUrl().host }.getOrDefault("")
                host in setOf("imgsa.baidu.com", "imgsrc.baidu.com", "tiebapic.baidu.com")
            }
        val images = (bodyImages + sourceImages + textImages).distinct()

        // 表情消息：content_body 的 text 是 image_emoticon25 这类表情 id，
        // 需要转成渲染层认识的 (#表情名)，否则正文为空会被显示成不支持的消息。
        val visibleText = if (images.isEmpty()) rawText else
            Regex("[^\\s,]+").replace(rawText) { match ->
                if (normalizeImageUrl(match.value) in images) "" else match.value
            }.trim()
        val text = replaceEmoticonIds(visibleText)

        // from_user 属于 IM 身份体系，只用可解密的贴吧身份判断自己。
        val bdukUid = if (senderBduk.isNotBlank()) {
            runCatching { BlcpCrypto.bduk2UserId(senderBduk) }.getOrNull()
        } else {
            null
        }
        val isSelf = when {
            bdukUid != null && myUid != null -> bdukUid == myUid
            else -> false
        }

        return ChatRoomMessage(
            msgId = json.optLong("msgid"),
            // from_user 是 IM 体系内的账号 id，和贴吧 uid 不是同一套；
            // 跳转资料卡必须用 bduk 解出来的贴吧 uid，否则会进到别人主页。
            senderUid = bdukUid ?: 0L,
            senderName = senderName,
            senderPortrait = portrait,
            contentType = transportType,
            text = text.ifBlank {
                when {
                    images.isNotEmpty() -> ""
                    messageKind == ChatMessageKind.SYSTEM_EVENT -> "系统消息"
                    messageKind == ChatMessageKind.DELETE_EVENT -> "消息已删除"
                    messageKind == ChatMessageKind.EMOTION_REACTION -> "表情回应"
                    else -> "[暂未支持的消息类型 ${businessType ?: transportType}]"
                }
            },
            semantics = ChatMessageSemantics(
                kind = messageKind,
                protocolSource = "chatroom",
                transportType = transportType,
                businessType = businessType,
                targetId = targetId,
                summary = text.ifBlank { messageKind.name },
                rawPayload = ChatMessageSemantics.limitedRawPayload(rawContent),
            ),
            images = images,
            msgTime = json.optLong("create_time") * 1000L,
            isSelf = isSelf,
            mentions = mentions,
            robotSkill = robotSkill,
            senderBaiduUk = senderBduk,
        )
    }

    private fun decodeJson(node: Any?): Any? = if (node is String &&
        (node.trimStart().startsWith("{") || node.trimStart().startsWith("["))
    ) runCatching { JSONTokener(node).nextValue() }.getOrNull() else node

    private fun decodeUid(bduk: String): Long =
        runCatching { BlcpCrypto.bduk2UserId(bduk) }.getOrDefault(0L)

    private fun extractImageSources(node: Any?, depth: Int = 0): List<String> {
        if (depth > 8) return emptyList()
        return when (val value = decodeJson(node)) {
            is String -> value.take(4096).split(Regex("[\\s,]+"))
                .mapNotNull(::normalizeImageUrl)
            is JSONArray -> (0 until minOf(value.length(), 64))
                .flatMap { extractImageSources(value.opt(it), depth + 1) }
            is JSONObject -> listOf("src", "url", "original", "big", "small")
                .flatMap { extractImageSources(value.opt(it), depth + 1) }
            else -> emptyList()
        }
    }

    private fun extractImages(node: Any?, depth: Int = 0): List<String> {
        if (depth > 8) return emptyList()
        return when (node) {
        null -> emptyList()

        is JSONObject -> {
            val sdkImage = extractImageSources(node.opt("url"))
                .ifEmpty { extractImageSources(node.opt("thumbnail")) }
            val direct = sdkImage + listOf("src", "img", "image", "images", "img_url", "image_url", "pic_list", "pic_infos")
                .flatMap { extractImageSources(node.opt(it)) }
            if (direct.isNotEmpty()) {
                direct
            } else {
                val body = node.opt("content_body")
                extractImages(body, depth + 1).ifEmpty { extractImages(node.opt("text"), depth + 1) }
            }
        }

        is JSONArray -> buildList {
            for (i in 0 until minOf(node.length(), 64)) {
                addAll(extractImages(node.opt(i), depth + 1))
            }
        }

        is String -> {
            val trimmed = node.trim()
            when {
                trimmed.startsWith("{") || trimmed.startsWith("[") ->
                    runCatching { extractImages(JSONTokener(trimmed).nextValue(), depth + 1) }
                        .getOrDefault(emptyList())

                else -> emptyList()
            }
        }

        else -> emptyList()
        }
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
    private val SYSTEM_BUSINESS_TYPES = setOf(7010, 7025, 20000)

    /**
     * 解析正文，与 aiotieba 的 ChatroomMsg.from_json 保持一致。
     *
     * content（JSON）-> content["text"]（仍是 JSON 字符串）
     * -> 若其中有 content_body 再解一层 -> 最终取 text。
     * 任何一层解析失败都返回空串，绝不把 JSON 原文当作消息内容显示。
     */
    private fun extractText(node: Any?, depth: Int = 0): String {
        if (depth > 8) return ""
        return when (node) {
        null -> ""
        is JSONObject -> {
            // 空正文不能挡住同层分享内容；只沿内容字段解析，避免读出 ext 中的用户资料。
            val body = sequenceOf(
                "content_body", "text", "str", "msg", "content",
                "share", "share_info", "thread", "thread_info",
            )
                .map { extractText(node.opt(it), depth + 1) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            val links = listOf("url", "link", "jump_url")
                .mapNotNull { (node.opt(it) as? String)?.trim()?.takeIf(String::isNotBlank) }
            val description = if (body.isBlank()) {
                listOf("title", "description", "desc")
                    .mapNotNull { (node.opt(it) as? String)?.trim()?.takeIf(String::isNotBlank) }
            } else {
                listOf(body)
            }
            (description + links.filterNot { it in body })
                .distinct().joinToString("\n")
        }

        is JSONArray -> buildString {
            for (i in 0 until minOf(node.length(), 64)) {
                append(extractText(node.opt(i), depth + 1))
            }
        }

        is String -> {
            val trimmed = node.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                // 字符串本身还是 JSON，继续解一层；解析失败则丢弃
                runCatching { extractText(JSONTokener(trimmed).nextValue(), depth + 1) }.getOrDefault("")
            } else {
                trimmed.take(4096)
            }
        }

        else -> ""
        }
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
