package com.huanchengfly.tieba.post.api.chat.http

import com.huanchengfly.tieba.post.utils.AccountUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 官方 IM HTTP 接口客户端。
 *
 * 官方 22.14 的私信业务走 https://pim.baidu.com/rest/3.0/im/ 下的 JSON 接口，
 * 与群聊使用的 tiebac.baidu.com 表单接口是两套完全不同的协议：
 * - 公共参数固定为 appid / timestamp / app_version / sdk_version / device_type / cuid / origin_id
 * - timestamp 是**秒级**
 * - 签名是 TreeSet 排序后拼 k=v 再取 MD5，**没有盐**
 * - 请求头只有 Cookie: BDUSS=xxx
 *
 * 这里的实现严格对齐官方 com/baidu/android/imsdk/utils/BaseHttpRequest 的行为。
 */
object ImHttpClient {

    /** 官方 appid。 */
    const val APP_ID = 10773430L

    /** 官方 IM SDK 版本号。 */
    const val SDK_VERSION = 11250036

    /** 官方 device_type，Android 固定为 2。 */
    const val DEVICE_TYPE = 2

    private const val HOST = "https://pim.baidu.com/rest/3.0/im/"

    /**
     * 官方 Content-Type 取自 IMMediaGetChatSessionRequest.getContentType()，
     * 值就是 application/json，不带 charset。服务端会按原值做校验。
     */
    private val jsonMediaType = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 组装公共参数。
     *
     * 官方 putCommonParams 的顺序无关紧要，但取值必须一致，
     * 否则服务端会因为时间窗或签名校验失败拒绝请求。
     */
    fun commonParams(): MutableMap<String, Any> = linkedMapOf(
        "appid" to APP_ID,
        // 官方是 System.currentTimeMillis() / 1000，秒级；毫秒会被判为 req expire。
        "timestamp" to System.currentTimeMillis() / 1000L,
        "app_version" to ImHttpConstants.CHAT_VERSION,
        "sdk_version" to SDK_VERSION,
        "device_type" to DEVICE_TYPE,
        "cuid" to ImHttpConstants.cuid(),
        "origin_id" to 0L,
    )
    /**
     * 计算官方签名。
     *
     * 官方 generateSign 用 TreeSet 排序 key 后拼接 k=value 再取 MD5，
     * 没有分隔符，也不追加盐。字符串值不做转义，引号会原样进入待签串。
     */
    fun sign(params: Map<String, Any>): String {
        val raw = params.entries
            .sortedWith(compareBy({ it.key }, { it.value.toString() }))
            .joinToString(separator = "") { "${it.key}=${it.value}" }
        return MessageDigest.getInstance("MD5")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * 发起一次 IM HTTP 请求。
     *
     * @param path 接口路径，例如 fetch_session。
     * @param params 业务参数，会与公共参数合并后一起签名。
     */
    suspend fun post(path: String, params: Map<String, Any> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val bduss = AccountUtil.getBduss().orEmpty()
            val body = commonParams().apply { putAll(params) }
            body.put("sign", sign(body))
            val request = Request.Builder()
                .url((HOST + path).toHttpUrl())
                .post(body.toString().toRequestBody(jsonMediaType))
                .header("Cookie", "BDUSS=$bduss")
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ImHttpException(response.code, "HTTP ${response.code}")
                }
                runCatching { JSONObject(text) }
                    .getOrElse { throw ImHttpException(response.code, "响应不是合法 JSON") }
            }
        }

    /** 读取 IM 接口的错误码，0 表示成功。 */
    fun errorCode(response: JSONObject): Int = response.optInt("error_code", 0)

    /** 读取 IM 接口的错误信息。 */
    fun errorMessage(response: JSONObject): String = response.optString("error_msg").ifBlank {
        "未知错误"
    }

    /**
     * 检查登录态。
     *
     * 官方错误码表里 BDUSS 过期是 8，参数校验失败是 430（param bduss error）；
     * error_code=1 只是通用的参数错误，不能当作登录失效，否则会把网络或参数问题
     * 误报成登录态问题，让用户反复重新登录。
     */
    fun isLoginError(response: JSONObject): Boolean {
        val code = errorCode(response)
        if (code == 8 || code == 430) return true
        return errorMessage(response).contains("bduss", ignoreCase = true) ||
            errorMessage(response).contains("未登录")
    }
}

/** IM HTTP 接口异常。 */
class ImHttpException(val code: Int, message: String) : Exception(message)

/** IM 接口用到的常量。 */
object ImHttpConstants {

    /** 聊天接口上报的客户端版本，过旧会被服务端拒绝。 */
    const val CHAT_VERSION = "22.14.1.0"

    /** 官方 fetch_session 里 chat_type 为 58 表示用户私聊。 */
    const val CHAT_TYPE_PRIVATE = 58

    /** 官方 fetch_session 里 chat_type 为 57 表示群聊。 */
    const val CHAT_TYPE_GROUP = 57

    /** 消息 category：文本。 */
    const val CATEGORY_TEXT = 0

    /** 消息 category：图片。 */
    const val CATEGORY_IMAGE = 1

    /** 消息 type：文本。 */
    const val TYPE_TEXT = 0

    /** 消息 type：图片。 */
    const val TYPE_IMAGE = 1

    /** 消息 type：语音。 */
    const val TYPE_VOICE = 2

    /**
     * 设备标识。
     *
     * 官方 Utility.getDeviceId 读的是贴吧客户端同一份设备号（hfc.b -> getCuidGalaxy2），
     * 必须与贴吧业务接口共用，否则服务端会认为设备不一致而拒绝鉴权。
     * 这里优先复用项目里的 CuidUtils；纯 JVM 环境（单测）没有 Android 上下文时，
     * 回落到进程内固定串，保证不崩且同一进程内稳定。
     */
    fun cuid(): String = runCatching {
        com.huanchengfly.tieba.post.utils.CuidUtils.getNewCuid()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallbackCuid

    private val fallbackCuid: String by lazy {
        java.util.UUID.randomUUID().toString().replace("-", "")
    }
}
