package com.huanchengfly.tieba.post.api.chat.http

import android.util.Log
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

    private const val TAG = "ChatRoomListApi"

    /** 关注贴吧列表最多翻页数，防止异常 has_more 导致死循环。 */
    private const val MAX_FORUM_PAGES = 50

    /** 拉群的并发上限，避免同时打太多请求。 */
    private const val MAX_FALLBACK_CONCURRENCY = 4

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 合并用户订阅群与全部关注贴吧的公开群，重复 roomId 优先保留订阅结果。
     *
     * 官方消息中心的主路径是 c/u/chat/getChatSubscriptionList：
     * 新装客户端本地没有群缓存时 chatroom_new_msg 传空串，服务端返回全量订阅群。
     * 两路每次都加载，成功空结果与失败分开处理。
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
        }.onFailure { if (it is CancellationException) throw it }
        val followed = runCatching { fetchRoomsFromFollowedForums() }
            .onFailure { if (it is CancellationException) throw it }
        if (subscribed.isFailure && followed.isFailure) {
            throw subscribed.exceptionOrNull()!!
        }
        if (subscribed.isFailure) {
            Log.w(TAG, "订阅群加载失败，保留关注吧群结果")
        }
        if (followed.isFailure) {
            Log.w(TAG, "关注吧群加载失败，保留订阅群结果")
        }
        (subscribed.getOrDefault(emptyList()) + followed.getOrDefault(emptyList()))
            .distinctBy { it.roomId }
    }

    /** 拉取全部关注贴吧的群，仅限制并发；单吧失败在批次内汇总。 */
    private suspend fun fetchRoomsFromFollowedForums(): List<ChatRoom> {
        val forumIds = fetchAllFollowedForumIds()
        if (forumIds.isEmpty()) return emptyList()
        val semaphore = Semaphore(MAX_FALLBACK_CONCURRENCY)
        val results = coroutineScope {
            forumIds.map { fid ->
                async {
                    semaphore.withPermit {
                        runCatching { fetchRoomsByFidWithRetry(fid) }
                            .onFailure { if (it is CancellationException) throw it }
                    }
                }
            }.awaitAll()
        }
        val failedCount = results.count { it.isFailure }
        if (failedCount == results.size) {
            throw results.first().exceptionOrNull()!!
        }
        if (failedCount > 0) {
            Log.w(TAG, "关注吧群部分加载失败：$failedCount/${results.size}，保留成功结果")
        }
        return results.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.roomId }
    }

    /** 拉单个吧的群，失败最多重试一次；两次均失败时抛出首次异常。 */
    private suspend fun fetchRoomsByFidWithRetry(fid: Long): List<ChatRoom> {
        var firstError: Throwable? = null
        repeat(2) {
            val result = runCatching { fetchRoomsByFid(fid) }
                .onFailure { if (it is CancellationException) throw it }
            if (result.isSuccess) return result.getOrThrow()
            if (firstError == null) firstError = result.exceptionOrNull()
        }
        throw firstError!!
    }

    /**
     * 分页拉取当前账号关注的全部贴吧 id。
     *
     * 官方的关注吧列表分页返回，has_more == "1" 时继续翻下一页；
     * 单次只取第一页会漏掉大量吧，导致群列表只显示少部分群。
     */
    private suspend fun fetchAllFollowedForumIds(): List<Long> {
        val uid = AccountUtil.getUid()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val result = linkedSetOf<Long>()
        var page = 1
        while (page <= MAX_FORUM_PAGES) {
            val bean = runCatching {
                TiebaApi.getInstance().userLikeForumFlow(uid, page).first()
            }.getOrElse {
                if (it is CancellationException) throw it
                if (page == 1) throw it
                Log.w(TAG, "关注吧第 $page 页加载失败，保留此前已获取的贴吧")
                return result.toList()
            }
            val forums = bean.forumList.forumList + bean.forumList.gconForumList
            forums.mapNotNullTo(result) { it.id.toLongOrNull()?.takeIf { fid -> fid > 0L } }
            if (bean.hasMore != "1") break
            if (forums.isEmpty() || page == MAX_FORUM_PAGES) {
                Log.w(TAG, "关注吧分页在第 $page 页提前停止，服务端仍标记有更多数据")
                break
            }
            page++
        }
        return result.toList()
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
                if (it is CancellationException) throw it
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
     * 官方 nvb$d 的取值路径是 optString("data") 后再 new JSONObject(json)，
     * 说明 data 在这里常常是**JSON 字符串**而不是对象，直接用 optJSONObject 会拿到 null。
     * 同时 ChatGroupInfo.parse 只读 group_name / group_id / room_list，
     * 说明 data 顶层就是群对象，没有 getRoomListByFid 的 list[] 那一层。
     * 这里两种形态都兼容。
     */
    internal fun parseSubscriptionRooms(root: JSONObject): List<ChatRoom> {
        val data = root.readDataObject() ?: return emptyList()
        return parseRoomList(data.optJSONArray("room_list"))
    }

    /** 读取 data 字段，兼容 JSONObject 与 JSON 字符串两种形态。 */
    internal fun JSONObject.readDataObject(): JSONObject? {
        optJSONObject("data")?.let { return it }
        val raw = optString("data").trim()
        if (raw.isEmpty() || raw == "null") return null
        return runCatching { JSONObject(raw) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
    }

    /**
     * 解析 getRoomListByFid 的响应。
     *
     * 官方结构是 data.list[]（群）-> room_list[]（房间）两层。
     */
    internal fun parseRoomListResponse(root: JSONObject): List<ChatRoom> {
        val data = root.readDataObject() ?: return emptyList()
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
