package com.huanchengfly.tieba.post.api.chat.http

import com.huanchengfly.tieba.post.api.chat.ChatApi
import com.huanchengfly.tieba.post.api.chat.ChatSignUtil
import com.huanchengfly.tieba.post.api.models.chat.ChatRoom
import com.huanchengfly.tieba.post.utils.AccountUtil
import kotlinx.coroutines.Dispatchers
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
 * - c/u/chat/getChatSubscriptionList：一次拿回用户加入的全部群，**不需要 fid**，
 *   这是官方消息中心聊天 Tab 真正使用的主路径
 * - c/f/chat/getRoomListByFid：按单个贴吧拉群，参数是 fid（forum_id）
 *
 * 两条都是 POST 表单接口，参数进 body，签名用 tiebaclient!!! 作为盐。
 */
object ChatRoomListApi {

    private const val SUBSCRIPTION_PATH = "c/u/chat/getChatSubscriptionList"
    private const val ROOM_LIST_PATH = "c/f/chat/getRoomListByFid"

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 拉取用户加入的全部群。
     *
     * 官方请求体里的 chatroom_new_msg 是本地已缓存群的清单，
     * 服务端据此返回这些群的状态。这里没有本地库，传空数组即可拿到全量群。
     */
    suspend fun fetchAllRooms(): List<ChatRoom> = withContext(Dispatchers.IO) {
        val params = linkedMapOf(
            "_client_version" to ImHttpConstants.CHAT_VERSION,
            "call_from" to "frs",
            "chatroom_new_msg" to "[]",
        )
        val body = postForm(SUBSCRIPTION_PATH, params)
        parseRooms(body)
    }

    /**
     * 拉取指定贴吧的群列表。
     *
     * @param fid 贴吧 forum_id，不是 room_id。
     */
    suspend fun fetchRoomsByFid(fid: Long): List<ChatRoom> = withContext(Dispatchers.IO) {
        val params = linkedMapOf(
            "_client_version" to ImHttpConstants.CHAT_VERSION,
            "call_from" to "frs",
            "fid" to fid.toString(),
            "chatroom_new_msg" to "[]",
        )
        val body = postForm(ROOM_LIST_PATH, params)
        parseRooms(body)
    }

    /**
     * 提交表单请求并返回响应 JSON。
     */
    private fun postForm(path: String, params: Map<String, String>): JSONObject {
        val form = FormBody.Builder().apply {
            params.forEach { (key, value) -> add(key, value) }
            add("sign", ChatSignUtil.sign(params, ChatApi.APP_SALT))
        }.build()
        val request = Request.Builder()
            .url("https://tiebac.baidu.com/$path".toHttpUrl())
            .post(form)
            .header("User-Agent", "bdtb for Android ${ImHttpConstants.CHAT_VERSION}")
            .header("Cookie", "BDUSS=${AccountUtil.getBduss() ?: ""}")
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
                val message = root.optString("error_msg")
                // 官方在 BDUSS 失效时返回 error_code=1 与"用户未登录或登录失败"。
                if (errCode == 1) {
                    throw ChatApiException(errCode, "登录态已失效，请重新登录贴吧")
                }
                throw ChatApiException(errCode, message)
            }
            return root
        }
    }

    /**
     * 解析群列表响应。
     *
     * 官方结构是 data.list[]（群）-> room_list[]（房间）两层，
     * 群名与头像在 room_info 里，不在 room_list 元素顶层。
     * 官方只保留 is_show == 1 的群。
     */
    internal fun parseRooms(root: JSONObject): List<ChatRoom> {
        val data = root.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("list") ?: return emptyList()
        val rooms = mutableListOf<ChatRoom>()
        for (i in 0 until list.length()) {
            val group = list.optJSONObject(i) ?: continue
            val roomList = group.optJSONArray("room_list") ?: continue
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
