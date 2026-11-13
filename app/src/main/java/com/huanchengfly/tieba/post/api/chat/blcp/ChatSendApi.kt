package com.huanchengfly.tieba.post.api.chat.blcp

import com.huanchengfly.tieba.post.api.models.chat.ChatMention
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 吧群发送能力。
 *
 * 群聊发送需要先完成 IM 登录拿到聊天身份（uk / bduk / trigger_id），
 * 再做 BLCP 的 sendMcastMsg。
 */
object ChatSendApi {

    private val client = okhttp3.OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 拉取 LCM token，是 BLCP 登录的第一步。
     */
    suspend fun fetchLcmToken(cuidGalaxy2: String): String = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val signature = md5("${BlcpConstants.CHAT_APP_ID}${cuidGalaxy2}android$timestamp")
        val body = JSONObject().apply {
            put("app_id", BlcpConstants.CHAT_APP_ID.toString())
            put("app_version", BlcpConstants.CHAT_VERSION)
            put("cuid", cuidGalaxy2)
            put("device_type", "android")
            put("manufacture", "")
            put("model_type", "")
            put("request_id", timestamp.toString())
            put("sdk_version", BlcpConstants.LCP_SDK_VERSION)
            put("sign", signature)
            put("ts", timestamp)
            put("user_key", "")
        }
        val request = okhttp3.Request.Builder()
            .url("https://pim.baidu.com/rest/5.0/generate_lcm_token")
            .post(body.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "okhttp/3.11.0")
            .build()
        client.newCall(request).execute().use { response ->
            // 不手动设置 Accept-Encoding，交由 OkHttp 自动处理 gzip 解压
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException(
                    "lcm token request failed: HTTP ${response.code} ${response.message}"
                )
            }
            json.optString("token").ifBlank {
                throw IllegalStateException(
                    "lcm token missing: ${json.optString("err_msg").ifBlank { text.take(200) }}"
                )
            }
        }
    }

    /** 一个页面独占一份发送会话，账号或群变化时重新登录。 */
    class Session(private val scope: CoroutineScope) {
        private val mutex = Mutex()
        private val lifecycleLock = Any()
        private var blcp: BlcpClient? = null
        private var identity: BlcpImIdentity? = null
        private var account: String? = null
        private var device: String? = null
        private var room: Long? = null
        private var expiryClose: Job? = null
        private var released = false

        fun release() = synchronized(lifecycleLock) {
            released = true
            close()
        }

        fun close() = synchronized(lifecycleLock) {
            expiryClose?.cancel()
            expiryClose = null
            blcp?.close()
            blcp = null
            identity = null
            account = null
            device = null
            room = null
        }

        suspend fun sendChatRoomMessage(
            chatroomId: Long,
            text: String,
            forumId: Long,
            forumLevel: Int,
            accountBduss: String,
            selfName: String,
            selfPortrait: String,
            selfUid: Long,
            cuidGalaxy2: String,
            mentions: List<ChatMention> = emptyList(),
            robotSkill: Int? = null,
        ) = withContext(Dispatchers.IO) {
            mutex.withLock {
                var stage = "连接"
                try {
                    val connection = synchronized(lifecycleLock) {
                        check(!released) { "群聊页面已离开，连接已释放" }
                        if (blcp?.isConnected != true || account != accountBduss ||
                            device != cuidGalaxy2 || room != chatroomId
                        ) {
                            close()
                            blcp = BlcpClient()
                        }
                        blcp!!
                    }
                    var login = synchronized(lifecycleLock) { identity }
                    if (login == null) {
                        connection.connect()
                        stage = "获取登录令牌"
                        val token = fetchLcmToken(cuidGalaxy2)
                        stage = "LCM 登录"
                        val interval = connection.loginLcm(token, cuidGalaxy2)
                        stage = "IM 登录"
                        val newLogin = connection.loginIm(accountBduss, cuidGalaxy2)
                        login = newLogin
                        stage = "加入群聊"
                        connection.joinChatroom(chatroomId, newLogin.uk)
                        synchronized(lifecycleLock) {
                            check(blcp === connection) { "群聊页面已离开，连接已关闭" }
                            identity = login
                            account = accountBduss
                            device = cuidGalaxy2
                            room = chatroomId
                            // 尚无可靠的心跳协议，在首个心跳期限前主动回收登录态。
                            val lifetimeMs = if (interval > 0) (interval / 2).coerceAtLeast(1L) else 30_000L
                            expiryClose = scope.launch {
                                delay(lifetimeMs.coerceAtMost(30_000L))
                                mutex.withLock { close() }
                            }
                        }
                    }
                    stage = "发送消息"
                    val authenticated = checkNotNull(login)
                    val payload = buildMessagePayload(
                        chatroomId, text, forumId, forumLevel, authenticated.uk,
                        BlcpCrypto.userId2Bduk(selfUid), authenticated.triggerId,
                        selfName, selfPortrait, accountBduss, mentions, robotSkill,
                    )
                    connection.sendMcastMsg(chatroomId, payload)
                } catch (e: Exception) {
                    close()
                    if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                    val detail = e.message.orEmpty().replace(accountBduss, "[已隐藏]")
                        .replace(cuidGalaxy2, "[已隐藏]").take(300)
                    throw IllegalStateException("群聊$stage 失败：${detail.ifBlank { e.javaClass.simpleName }}")
                }
            }
        }
    }

    /**
     * 构造群消息业务体。
     *
     * content.text 与 ext 都需要二次 JSON 编码，字段需与官方客户端一致。
     */
    internal fun buildMessagePayload(
        chatroomId: Long,
        text: String,
        forumId: Long,
        forumLevel: Int,
        uk: Long,
        bduk: String,
        triggerId: Long,
        selfName: String,
        selfPortrait: String,
        bduss: String,
        mentions: List<ChatMention>,
        robotSkill: Int?,
    ): JSONObject {
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val portraitWithTime = "$selfPortrait?t=$nowSec"
        val levelText = forumLevel.toString().padStart(2, '0')

        val mainData = JSONArray().apply {
            // 昵称
            put(JSONObject().apply {
                put("type", 1)
                put("text", JSONObject().apply {
                    put("short_enable", 1)
                    put("short_length", 5)
                    put("short_priority", 1)
                    put("priority", 1)
                    put("str", selfName)
                    put("suffix", "...")
                    put("type", "1")
                })
            })
            // 成长等级
            put(JSONObject().apply {
                put("type", 2)
                put("icon", JSONObject().apply {
                    put("height", 15)
                    put("priority", 5)
                    put(
                        "schema",
                        "https://tieba.baidu.com/mo/q/hybrid-main-user/taskCenter" +
                            "?customfullscreen=1&nonavigationbar=1",
                    )
                    put("type", "3")
                    put("url", "local://icon/icon_mask_level_usergrouth_$forumLevel?type=webp")
                    put("width", 24)
                })
            })
            // 吧等级
            put(JSONObject().apply {
                put("type", 2)
                put("icon", JSONObject().apply {
                    put("height", 12)
                    put("priority", 2)
                    put(
                        "schema",
                        "https://tieba.baidu.com/mo/q/wise-bawu-core/forum-level" +
                            "?customfullscreen=1&forum_id=$forumId&nonavigationbar=1" +
                            "&obj_locate=5&portrait=$portraitWithTime",
                    )
                    put("type", "4")
                    put("url", "local://icon/icon_level_$levelText?type=webp")
                    put("width", 16)
                })
            })
        }

        val extData = JSONObject().apply {
            put("main_data", mainData)
            put("is_sys_msg", 0)
            put("version", "")
            put("portrait", portraitWithTime)
            put("robot_role", 0)
            put("role", 0)
            put("send_status", 0)
            put("from", "android")
            put("session_id", chatroomId)
            put("type", 1)
            put("user_name", selfName)
            put("content", JSONObject().apply {
                if (robotSkill != null) {
                    put("robot_params", JSONObject().apply {
                        put("scene", "tieba_group_chat")
                        put("type", robotSkill)
                    })
                }
            })
            put("level", forumLevel)
            put("forum_id", forumId)
        }

        val textData = JSONObject().apply {
            put("room_id", chatroomId.toString())
            put("type", "0")
            put("to_uid", "0")
            put("vip", "0")
            put("name", selfName)
            put("portrait", portraitWithTime)
            put("content_type", "0")
            put("content_body", JSONObject().put("text", text).toString())
            put("src", "")
            put("baidu_uk", bduk)
            put("at_data", JSONArray().apply {
                mentions.forEachIndexed { index, user ->
                    put(JSONObject().apply {
                        put("at_type", "user")
                        put("at_baidu_uk", user.baiduUk)
                        put("at_name", user.name)
                        put("at_portrait", user.portrait)
                        // position 是接收者序号，不是正文字符偏移。
                        put("position", index.toString())
                    })
                }
            })
            put("ext", extData.toString())
        }

        return JSONObject().apply {
            put("method", BlcpConstants.METHOD_SEND_MCAST_MSG)
            put("mcast_id", chatroomId)
            put("role", 3)
            put("token", bduss)
            put("appid", BlcpConstants.CHAT_APP_ID)
            put("uk", uk)
            put("origin_id", triggerId)
            put("type", 81)
            put("app_safe_ext", JSONObject().put("haotianjing", JSONObject().put("zid", "")).toString())
            put("content", JSONObject().put("text", textData.toString()).toString())
            put("msg_key", BlcpCrypto.computeMsgKey(bduk))
            put("account_type", 1)
            put("sdk_version", BlcpConstants.CHAT_SDK_VERSION)
            put("event_list", JSONArray().apply {
                put(JSONObject().put("event", "CClickSendBegin").put("timestamp_ms", 0))
                put(JSONObject().put("event", "CSendBegin").put("timestamp_ms", 0))
                put(JSONObject().put("event", "CIMSendBegin").put("timestamp_ms", nowMs))
            })
        }
    }

    private fun md5(text: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
