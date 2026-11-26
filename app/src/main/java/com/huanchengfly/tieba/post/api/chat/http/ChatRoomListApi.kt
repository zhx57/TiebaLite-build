package com.huanchengfly.tieba.post.api.chat.http

import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.chat.ChatApi
import com.huanchengfly.tieba.post.api.chat.ChatApiException
import com.huanchengfly.tieba.post.api.chat.ChatSignUtil
import com.huanchengfly.tieba.post.api.models.chat.ChatRoom
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.ClientUtils
import com.huanchengfly.tieba.post.utils.CuidUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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

    /** 兜底路径最多拉取的关注贴吧数量。 */
    private const val MAX_FALLBACK_FORUMS = 20

    /** 兜底路径的并发上限，避免首屏同时打太多请求。 */
    private const val MAX_FALLBACK_CONCURRENCY = 4

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 拉取用户已订阅的全部群。
     *
     * 官方首次请求时本地没有缓存群清单，nvb.U 返回空串，
     * nvb.Q 仍会把它作为 chatroom_new_msg 参数发出去（FastRequest.v 无条件写入 body），
     * 所以这里必须传空串而不是省略，也不是传 "[]"。
     *
     * 服务端对空清单可能只返回本地已有群的状态，第三方没有本地库时拿不到任何群，
     * 因此订阅接口为空或失败时回退到关注贴吧逐个拉群，避免聊天 Tab 长期空白。
     */
    suspend fun fetchAllRooms(): List<ChatRoom> = withContext(Dispatchers.IO) {
        val subscribed = runCatching {
            postForm(
                SUBSCRIPTION_PATH,
                linkedMapOf(
                    "call_from" to "message_tab",
                    "chatroom_new_msg" to "",
                )
            ).let(::parseSubscriptionRooms)
        }.getOrNull()
        if (!subscribed.isNullOrEmpty()) return@withContext subscribed
        fetchRoomsFromFollowedForums()
    }

    /**
     * 用关注贴吧逐个拉群作为兜底。
     *
     * getRoomListByFid 未登录也能返回公共群，是最稳定的取群路径。
     * 关注吧可能很多，这里限制并发数并只取前若干个，避免首屏过慢或被限流。
     */
    private suspend fun fetchRoomsFromFollowedForums(): List<ChatRoom> {
        val forumIds = runCatching {
            TiebaApi.getInstance().getForumListFlow().first()
                .forumInfo
                .mapNotNull { it.forumId.toLongOrNull() }
                .take(MAX_FALLBACK_FORUMS)
        }.getOrDefault(emptyList())
        if (forumIds.isEmpty()) return emptyList()
        val semaphore = Semaphore(MAX_FALLBACK_CONCURRENCY)
        return coroutineScope {
            forumIds.map { fid ->
                async {
                    semaphore.withPermit {
                        runCatching { fetchRoomsByFid(fid) }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten().distinctBy { it.roomId }
        }
    }

    /**
     * 拉取指定贴吧的群列表。
     *
     * @param fid 贴吧 forum_id，不是 room_id。
     */
    suspend fun fetchRoomsByFid(fid: Long): List<ChatRoom> = withContext(Dispatchers.IO) {
        val params = linkedMapOf(
            "call_from" to "frs",
            "fid" to fid.toString(),
        )
        val body = postForm(ROOM_LIST_PATH, params)
        parseRoomListResponse(body)
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
    private fun postForm(
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
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ChatApiException(response.code, "HTTP ${response.code}")
            }
            val root = runCatching { JSONObject(text) }.getOrElse {
                throw ChatApiException(response.code, "响应不是合法 JSON")
            }
            val errCode = root.optInt("error_code", 0)
            if (errCode != 0) {
                throw ChatApiException(errCode, root.optString("error_msg"))
            }
            return root
        }
    }

    /**
     * 解析 getChatSubscriptionList 的响应。
     *
     * 官方 nvb$d.b 把整个 data 字符串交给 ChatGroupInfo.parse，
     * 而 ChatGroupInfo.parse 只读 group_name / group_id / room_list，
     * 说明 data 顶层就是群对象，而不是 getRoomListByFid 的 data.list[] 两层结构。
     */
    internal fun parseSubscriptionRooms(root: JSONObject): List<ChatRoom> {
        val data = root.optJSONObject("data") ?: return emptyList()
        return parseRoomList(data.optJSONArray("room_list"))
    }

    /**
     * 解析 getRoomListByFid 的响应。
     *
     * 官方结构是 data.list[]（群）-> room_list[]（房间）两层。
     */
    internal fun parseRoomListResponse(root: JSONObject): List<ChatRoom> {
        val data = root.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("list") ?: return emptyList()
        val rooms = mutableListOf<ChatRoom>()
        for (i in 0 until list.length()) {
            val group = list.optJSONObject(i) ?: continue
            rooms += parseRoomList(group.optJSONArray("room_list"))
        }
        return rooms
    }

    /**
     * 解析 room_list 数组。
     *
     * 群名与头像在 room_info 里，不在 room_list 元素顶层；
     * 官方只保留 is_show == 1 的群。
     */
    private fun parseRoomList(roomList: JSONArray?): List<ChatRoom> {
        if (roomList == null) return emptyList()
        val rooms = mutableListOf<ChatRoom>()
        for (j in 0 until roomList.length()) {
            val room = roomList.optJSONObject(j) ?: continue
            // 官方只展示 is_show == 1 的群。
            if (room.optInt("is_show", 1) != 1) continue
            val roomId = room.optString("room_id").toLongOrNull()
                ?: room.optLong("room_id")
            if (roomId <= 0L) continue
            val info = room.optJSONObject("room_info") ?: JSONObject()
            rooms += ChatRoom(
                roomId = roomId,
                forumId = info.optString("forum_id").toLongOrNull() ?: 0L,
                forumName = info.optString("forum_name"),
                name = info.optString("name"),
                avatar = info.optString("avatar"),
                discussNum = info.optString("unread_num").toIntOrNull() ?: 0,
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
