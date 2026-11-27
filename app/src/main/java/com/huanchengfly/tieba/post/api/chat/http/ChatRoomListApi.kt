package com.huanchengfly.tieba.post.api.chat.http

import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.chat.ChatApi
import com.huanchengfly.tieba.post.api.chat.ChatApiException
import com.huanchengfly.tieba.post.api.chat.ChatSignUtil
import com.huanchengfly.tieba.post.api.models.chat.ChatRoom
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.ClientUtils
import com.huanchengfly.tieba.post.utils.CuidUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 吧群列表接口。
 *
 * 官方 22.14 有两条路径：
 * - c/u/chat/getChatSubscriptionList：消息中心聊天 Tab 的主路径，**不需要 fid**。
 *   官方通过 FastRequest 创建任务（w7f.e），参数为 isNeedLogin=false、isNeedTbs=true、
 *   isNeedAddCommenParam=true、isUseCurrentBDUSS=true，因此会带上 BDUSS、stoken、tbs
 *   以及 cuid 等一整套框架公共参数。
 * - c/f/chat/getRoomListByFid：按单个贴吧拉群，参数是 fid（forum_id），
 *   走 TbHttpMessageTask 默认开关，isNeedTbs=false。
 *
 * 两条都是 POST 表单接口，参数进 body，签名用 tiebaclient!!! 作为盐。
 */
object ChatRoomListApi {

    private const val SUBSCRIPTION_PATH = "c/u/chat/getChatSubscriptionList"
    private const val ROOM_LIST_PATH = "c/f/chat/getRoomListByFid"

    data class RoomListUpdate(
        val rooms: List<ChatRoom>,
        val isComplete: Boolean = false,
        val error: Throwable? = null,
        val failedCount: Int = 0,
    )

    internal fun mergeRooms(preferred: List<ChatRoom>, discovered: List<ChatRoom>): List<ChatRoom> =
        (preferred + discovered).distinctBy { it.roomId }

    /** 关注贴吧列表最多翻页数，防止异常 has_more 导致死循环。 */
    private const val MAX_FORUM_PAGES = 50

    /** 拉群的并发上限，避免同时打太多请求。 */
    private const val MAX_FALLBACK_CONCURRENCY = 4

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 合并用户订阅群与全部关注贴吧的公开群，重复 roomId 优先保留订阅结果。
     *
     * 官方消息中心的主路径是 c/u/chat/getChatSubscriptionList：
     * 新装客户端本地没有群缓存时 chatroom_new_msg 传空串，服务端返回全量订阅群。
     * 两路每次都加载，成功空结果与失败分开处理。
     */
    fun fetchAllRooms(): Flow<RoomListUpdate> = flow {
        var rooms = emptyList<ChatRoom>()
        var error: Throwable? = null
        var failedCount = 0
        suspend fun recordFailure(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedCount++
                if (error == null) error = e
            }
        }
        recordFailure { rooms = fetchSubscriptionRooms() }
        emit(RoomListUpdate(rooms, error = error, failedCount = failedCount))
        val seenForums = mutableSetOf<Long>()
        val uid = AccountUtil.getUid()?.takeIf { it.isNotBlank() }
        if (uid != null) {
                var page = 1
                while (page <= MAX_FORUM_PAGES) {
                    val pageResult = runCatching {
                        withTimeoutOrNull(30_000L) {
                            TiebaApi.getInstance().userLikeForumFlow(uid, page).first()
                        } ?: throw ChatApiException(-1, "关注吧请求超时")
                    }.onFailure { if (it is CancellationException) throw it }
                    if (pageResult.isFailure) {
                        failedCount++
                        if (error == null) error = pageResult.exceptionOrNull()
                        break
                    }
                    val bean = pageResult.getOrThrow()
                    val forums = bean.forumList.forumList + bean.forumList.gconForumList
                    val ids = forums.mapNotNull { it.id.toLongOrNull()?.takeIf { fid -> fid > 0 } }
                        .filter { seenForums.add(it) }
                    for (batch in ids.chunked(MAX_FALLBACK_CONCURRENCY)) {
                        val results = coroutineScope {
                            batch.map { fid ->
                                async {
                                    try {
                                        Result.success(fetchPublicRoomsByFid(fid))
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Result.failure<List<ChatRoom>>(e)
                                    }
                                }
                            }.awaitAll()
                        }
                        results.forEach { result ->
                            result.fold(
                                onSuccess = { rooms = mergeRooms(rooms, it) },
                                onFailure = { failedCount++; if (error == null) error = it },
                            )
                        }
                        emit(RoomListUpdate(rooms, error = error, failedCount = failedCount))
                    }
                    if (bean.hasMore != "1") break
                    if (forums.isEmpty() || page == MAX_FORUM_PAGES) {
                        failedCount++
                        if (error == null) error = ChatApiException(-1, "关注吧分页未完整返回，请重试")
                        break
                    }
                    page++
                }
        }
        emit(RoomListUpdate(rooms, isComplete = true, error = error, failedCount = failedCount))
    }.flowOn(Dispatchers.IO)

    private suspend fun fetchSubscriptionRooms(): List<ChatRoom> = postForm(
        SUBSCRIPTION_PATH,
        linkedMapOf("call_from" to "message_tab", "chatroom_new_msg" to ""),
    ).let(::parseSubscriptionRooms)

    /**
     * 拉取指定贴吧的群列表。
     *
     * @param fid 贴吧 forum_id，不是 room_id。
     */
    suspend fun fetchRoomsByFid(fid: Long): List<ChatRoom> = withContext(Dispatchers.IO) {
        val publicRooms = fetchPublicRoomsByFid(fid)
        if (publicRooms.isNotEmpty()) publicRooms
        else fetchSubscriptionRooms().filter { it.forumId == fid }.distinctBy { it.roomId }
    }

    /** 全关注吧扫描只读公开接口，空吧不会重复触发订阅请求。 */
    private suspend fun fetchPublicRoomsByFid(fid: Long): List<ChatRoom> {
        val body = postForm(ROOM_LIST_PATH, linkedMapOf("call_from" to "frs", "fid" to fid.toString()))
        return parseRoomListResponse(body, fid).distinctBy { it.roomId }
    }

    /**
     * 提交表单请求并返回响应 JSON。
     *
     * 官方 mc7 参数装配器（FastRequest 同样复用）会加入：
     * BDUSS / stoken（mc7.b）、_client_type / _client_version / tbs / cuid 系列 / timestamp
     * （mc7.c），并且这些参数全部参与 sign 计算。只放 Cookie 头服务端不认。
     *
     * @param needTbs 对应 FastRequest 创建任务时的 isNeedTbs 开关，
     *   getChatSubscriptionList 为 true，getRoomListByFid 为 false。
     */
    private suspend fun postForm(
        path: String,
        params: Map<String, String>,
        needTbs: Boolean = path == SUBSCRIPTION_PATH,
    ): JSONObject {
        val bduss = AccountUtil.getBduss().orEmpty()
        val all = linkedMapOf<String, String>().apply {
            putAll(params)
            put("_client_type", "2")
            put("_client_version", ImHttpConstants.CHAT_VERSION)
            put("timestamp", System.currentTimeMillis().toString())
            put("cuid", CuidUtils.getNewCuid())
            put("cuid_galaxy2", CuidUtils.getNewCuid())
            ClientUtils.baiduId?.takeIf { it.isNotBlank() }?.let { put("baiduid", it) }
            if (needTbs) {
                AccountUtil.getAccountInfo { tbs }?.takeIf { it.isNotBlank() }?.let { put("tbs", it) }
            }
            if (bduss.isNotEmpty()) {
                put("BDUSS", bduss)
                AccountUtil.getSToken()?.takeIf { it.isNotBlank() }?.let { put("stoken", it) }
            }
        }
        val form = FormBody.Builder().apply {
            all.forEach { (key, value) -> add(key, value) }
            add("sign", ChatSignUtil.sign(all, ChatApi.APP_SALT))
        }.build()
        val request = Request.Builder()
            .url("https://tiebac.baidu.com/$path".toHttpUrl())
            .post(form)
            .header("User-Agent", "bdtb for Android ${ImHttpConstants.CHAT_VERSION}")
            .header("Cookie", "BDUSS=$bduss")
            .build()
        val text = suspendCancellableCoroutine<String> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.use {
                            if (!it.isSuccessful) throw ChatApiException(it.code, "HTTP ${it.code}")
                            it.body?.string().orEmpty()
                        }
                        continuation.resume(body)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
        val root = try {
            JSONObject(text)
        } catch (e: org.json.JSONException) {
            throw ChatApiException(-1, "响应不是合法 JSON")
        }
        val errCode = root.optInt("error_code", 0)
        if (errCode != 0) throw ChatApiException(errCode, root.optString("error_msg"))
        return root
    }

    /**
     * 解析 getChatSubscriptionList 的响应。
     *
     * 官方 nvb$d 的取值路径是 optString("data") 后再 new JSONObject(json)，
     * 说明 data 在这里常常是**JSON 字符串**而不是对象，直接用 optJSONObject 会拿到 null。
     * 同时 ChatGroupInfo.parse 只读 group_name / group_id / room_list，
     * 说明 data 顶层就是群对象，没有 getRoomListByFid 的 list[] 那一层。
     * 这里两种形态都兼容。
     */
    internal fun parseSubscriptionRooms(root: JSONObject): List<ChatRoom> {
        val data = root.readDataObject() ?: return emptyList()
        return parseRoomList(data.readOptionalArray("room_list"))
    }

    /** 读取 data 字段，兼容 JSONObject 与 JSON 字符串两种形态。 */
    internal fun JSONObject.readDataObject(): JSONObject? {
        val raw = opt("data")
        if (raw == null || raw == JSONObject.NULL) return null
        if (raw is JSONObject) return raw
        if (raw is String) {
            if (raw.isBlank() || raw.trim() == "null") return null
            try {
                return JSONObject(raw)
            } catch (e: org.json.JSONException) {
                throw ChatApiException(-1, "群列表协议错误：data 不是 JSON 对象")
            }
        }
        throw ChatApiException(-1, "群列表协议错误：data 类型错误")
    }

    /**
     * 解析 getRoomListByFid 的响应。
     *
     * 官方结构是 data.list[]（群）-> room_list[]（房间）两层。
     */
    internal fun parseRoomListResponse(root: JSONObject, fid: Long = 0L): List<ChatRoom> {
        val data = root.readDataObject() ?: return emptyList()
        val list = data.readOptionalArray("list") ?: return emptyList()
        val rooms = mutableListOf<ChatRoom>()
        for (i in 0 until list.length()) {
            val group = list.optJSONObject(i)
                ?: throw ChatApiException(-1, "群列表协议错误：list 元素不是对象")
            rooms += parseRoomList(group.readOptionalArray("room_list"), fid)
        }
        return rooms
    }

    // 官方使用 optJSONArray，缺失或 null 按无群处理；非空错误类型不能伪装成空列表。
    private fun JSONObject.readOptionalArray(key: String): JSONArray? {
        val value = opt(key)
        if (value == null || value == JSONObject.NULL) return null
        return value as? JSONArray
            ?: throw ChatApiException(-1, "群列表协议错误：$key 不是数组")
    }

    /**
     * 解析 room_list 数组。
     *
     * 群名与头像在 room_info 里，不在 room_list 元素顶层；
     * 官方只保留 is_show == 1 的群。
     */
    private fun parseRoomList(roomList: JSONArray?, fid: Long = 0L): List<ChatRoom> {
        if (roomList == null) return emptyList()
        val rooms = mutableListOf<ChatRoom>()
        for (j in 0 until roomList.length()) {
            val room = roomList.optJSONObject(j)
                ?: throw ChatApiException(-1, "群列表协议错误：room_list 元素不是对象")
            // 官方只展示 is_show == 1 的群。
            if (room.optInt("is_show", 1) != 1) continue
            val roomId = room.optString("room_id").toLongOrNull()
                ?: room.optLong("room_id")
            if (roomId <= 0L) throw ChatApiException(-1, "群列表协议错误：room_id 无效")
            val info = room.optJSONObject("room_info")
                ?: throw ChatApiException(-1, "群列表协议错误：room_info 不是对象")
            rooms += ChatRoom(
                roomId = roomId,
                forumId = info.optString("forum_id").toLongOrNull()?.takeIf { it > 0 } ?: fid,
                forumName = info.optString("forum_name"),
                name = info.optString("name"),
                avatar = info.optString("avatar"),
                discussNum = info.optString("discuss_num").toIntOrNull() ?: 0,
                unreadNum = info.optString("unread_num").toIntOrNull() ?: 0,
                announce = room.optJSONObject("msg_info")
                    ?.optString("content").orEmpty(),
            )
        }
        return rooms
    }

    /**
     * 构造 chatroom_new_msg 参数。
     *
     * 官方在这里携带本地缓存的群状态，元素形如 {"room_id","msg_id","time"}。
     */
    internal fun buildChatRoomNewMsg(rooms: List<Triple<Long, Long, Long>>): String {
        val array = JSONArray()
        rooms.forEach { (roomId, msgId, time) ->
            array.put(JSONObject().apply {
                put("room_id", roomId.toString())
                put("msg_id", msgId.toString())
                put("time", time)
            })
        }
        return array.toString()
    }
}
