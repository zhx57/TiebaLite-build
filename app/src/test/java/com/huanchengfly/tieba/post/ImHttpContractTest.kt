package com.huanchengfly.tieba.post

import com.huanchengfly.tieba.post.api.chat.http.ChatRoomListApi
import com.huanchengfly.tieba.post.api.chat.http.ImBduid
import com.huanchengfly.tieba.post.api.chat.http.ImHttpClient
import com.huanchengfly.tieba.post.api.chat.http.ImHttpConstants
import com.huanchengfly.tieba.post.api.chat.http.ImMessageApi
import com.huanchengfly.tieba.post.api.chat.http.ImSessionApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrueimport org.junit.Test

/**
 * 官方 IM HTTP 协议契约测试。
 *
 * 这些断言都来自官方 22.14.1.0 客户端的反编译结果，
 * 目的是防止后续改动把请求格式改回旧的猜测实现。
 */
class ImHttpContractTest {

    @Test
    fun officialCommonParamsUseSecondLevelTimestampAndKnownConstants() {
        val params = ImHttpClient.commonParams()
        assertEquals(10773430L, params["appid"])
        assertEquals(11250036, params["sdk_version"])
        assertEquals(2, params["device_type"])
        assertEquals(0L, params["origin_id"])
        // 毫秒级时间戳会被服务端判为 req expire，必须是秒级。
        val timestamp = params["timestamp"] as Long
        val nowSeconds = System.currentTimeMillis() / 1000L
        assertTrue("timestamp 必须是秒级", timestamp in (nowSeconds - 5)..(nowSeconds + 5))
    }

    @Test
    fun officialSignSortsKeysAndUsesNoSalt() {
        val params = linkedMapOf(
            "b" to "2",
            "a" to "1",
            "c" to "3",
        )
        // 官方 generateSign：TreeSet 排序后拼 k=value 再 MD5，无盐、无分隔符。
        // 基准值即 md5("a=1b=2c=3")。
        val sign = ImHttpClient.sign(params)
        assertEquals("1bf6e575a4d07c7b7f0f473f7ee1d90f", sign)
        // 顺序不同但内容相同，签名必须一致。
        assertEquals(sign, ImHttpClient.sign(linkedMapOf("c" to "3", "a" to "1", "b" to "2")))
    }

    @Test
    fun signDiffersWhenAnyValueChanges() {
        val base = ImHttpClient.sign(mapOf("a" to "1", "b" to "2"))
        assertNotEquals(base, ImHttpClient.sign(mapOf("a" to "1", "b" to "3")))
        assertNotEquals(base, ImHttpClient.sign(mapOf("a" to "1")))
    }

    @Test
    fun bduidEncryptionMatchesOfficialAesCbcAlgorithm() {
        // 官方 Utility.transBDUID：固定 KEY + 固定 IV 的 AES-128-CBC/PKCS5Padding，再 Base64 NO_WRAP。
        val encrypted = ImBduid.transBDUID("1234567890")
        assertEquals("2CTu1NPWwZ1CG0pnhcSPQw==", encrypted)
        assertEquals("1234567890", ImBduid.transBDUK(encrypted))
        // 不同 uid 必须得到不同密文，且都能解回。
        val other = ImBduid.transBDUID("999999999")
        assertNotEquals(encrypted, other)
        assertEquals("999999999", ImBduid.transBDUK(other))
        // 非法输入不能抛异常。
        assertEquals("", ImBduid.transBDUK("不是base64!!!"))
        assertEquals("", ImBduid.transBDUID(""))
    }

    @Test
    fun sessionsAreParsedFromOfficialFetchSessionShape() {
        val response = JSONObject()
            .put("error_code", 0)
            .put("has_more", 0)
            .put("total_unread_num", 3)
            .put(
                "sessions",
                org.json.JSONArray()
                    .put(
                        JSONObject()
                            .put("chat_type", ImHttpConstants.CHAT_TYPE_PRIVATE)
                            .put("contacter_pass_uk", ImBduid.transBDUID("987654321"))
                            .put("contacter_pa_uid", 0)
                            .put("content_type", 0)
                            .put("content", "{\"text\":\"你好\"}")
                            .put("unread_num", 2)
                            .put("last_time", 1795599000000L)
                            .put("contacter_im_uk", 987654321012345678L)
                            .put("is_top", 1)
                            .put("last_msg_bd_uid", 987654321L)
                            .put("contacter_type", 0)
                            .put("is_block", 0)
                            .put("block_timestamp", 0)
                            .put("do_not_disturb", 0)
                    )
                    .put(
                        JSONObject()
                            .put("chat_type", ImHttpConstants.CHAT_TYPE_GROUP)
                            .put("contacter_pass_uk", ImBduid.transBDUID("555"))
                            .put("content_type", 1)
                            .put("content", "{\"url\":\"https://imgsa.baidu.com/a.jpg\",\"w\":100,\"h\":80}")
                            .put("last_time", 1795599000001L)
                    ),
            )

        val sessions = ImSessionApi.parseSessions(response)
        assertEquals(2, sessions.size)

        val private = sessions.first { it.chatType == ImHttpConstants.CHAT_TYPE_PRIVATE }
        assertTrue(private.isPrivateChat)
        assertEquals(987654321L, private.peerUid)
        assertEquals("你好", private.summary)
        assertEquals(2, private.unreadNum)
        assertTrue(private.isTop)
        assertFalse(private.isBlock)

        val group = sessions.first { it.chatType == ImHttpConstants.CHAT_TYPE_GROUP }
        assertFalse(group.isPrivateChat)
        assertEquals("图片消息", group.summary)
    }

    @Test
    fun summaryFallsBackToTypeNameWhenContentIsBlank() {
        assertEquals(
            "图片消息",
            ImSessionApi.summarize("", ImHttpConstants.TYPE_IMAGE),
        )
        assertEquals(
            "语音消息",
            ImSessionApi.summarize("", ImHttpConstants.TYPE_VOICE),
        )
        assertEquals(
            "聊天消息",
            ImSessionApi.summarize("", ImHttpConstants.TYPE_TEXT),
        )
    }

    @Test
    fun roomListParsesOfficialNestedStructureAndSkipsHiddenRooms() {
        val root = JSONObject()
            .put("error_code", 0)
            .put(
                "data",
                JSONObject()
                    .put("mid_group_id", "8360438050")
                    .put(
                        "list",
                        org.json.JSONArray()
                            .put(
                                JSONObject()
                                    .put("group_name", "")
                                    .put("group_id", "0")
                                    .put(
                                        "room_list",
                                        org.json.JSONArray()
                                            .put(
                                                JSONObject()
                                                    .put("room_id", "8360438138")
                                                    .put("is_show", 1)
                                                    .put(
                                                        "room_info",
                                                        JSONObject()
                                                            .put("forum_id", "1")
                                                            .put("forum_name", "某吧")
                                                            .put("name", "某群")
                                                            .put("avatar", "https://tiebapic.baidu.com/a.jpg")
                                                            .put("unread_num", "3")
                                                    )
                                            )
                                            .put(
                                                JSONObject()
                                                    .put("room_id", "8360439999")
                                                    .put("is_show", 0)
                                                    .put(
                                                        "room_info",
                                                        JSONObject()
                                                            .put("forum_id", "1")
                                                            .put("name", "隐藏群")
                                                    )
                                            ),
                                    )
                            ),
                    ),
            )

        val rooms = ChatRoomListApi.parseRooms(root)
        // 官方只展示 is_show == 1 的群。
        assertEquals(1, rooms.size)
        val room = rooms.single()
        assertEquals(8360438138L, room.roomId)
        assertEquals(1L, room.forumId)
        assertEquals("某群", room.name)
        assertEquals("某吧", room.forumName)
        assertEquals(3, room.discussNum)
    }

    @Test
    fun chatRoomNewMsgCarriesRoomStateLikeOfficial() {
        val json = ChatRoomListApi.buildChatRoomNewMsg(listOf(Triple(8360438138L, 100L, 1795599000000L)))
        val array = org.json.JSONArray(json)
        assertEquals(1, array.length())
        val item = array.optJSONObject(0)!!
        assertEquals("8360438138", item.optString("room_id"))
        assertEquals("100", item.optString("msg_id"))
        assertEquals(1795599000000L, item.optLong("time"))
    }

    @Test
    fun messagesAreParsedFromOfficialFetchMsgShape() {
        val response = JSONObject()
            .put("error_code", 0)
            .put("has_more", 0)
            .put(
                "msgs",
                org.json.JSONArray()
                    .put(
                        JSONObject()
                            .put("msgid", 1001L)
                            .put("from_user", 987654321L)
                            .put("from_im_uk", 987654321012345678L)
                            .put("from_pass_uk", ImBduid.transBDUID("987654321"))
                            .put("to_pass_uk", ImBduid.transBDUID("111111111"))
                            .put("create_time", 1795599000000L)
                            .put("category", 0)
                            .put("type", 0)
                            .put("content", "{\"text\":\"你好\"}")
                            .put("msg_key", "0a1b2c3d4e")
                    )
                    .put(
                        JSONObject()
                            .put("msgid", 1002L)
                            .put("from_im_uk", 555L)
                            .put("create_time", 1795599000001L)
                            .put("category", 1)
                            .put("type", 1)
                            .put(
                                "content",
                                "{\"url\":\"https://imgsa.baidu.com/a.jpg\",\"thumbnail\":\"https://imgsa.baidu.com/s.jpg\",\"w\":100,\"h\":80}"
                            )
                    ),
            )

        val messages = ImMessageApi.parseMessages(response)
        assertEquals(2, messages.size)

        val text = messages.first { it.msgId == 1001L }
        assertEquals("你好", text.text)
        assertEquals(987654321L, text.fromUid)
        assertEquals("", text.imageUrl)
        assertEquals(10, text.msgKey.length)

        val image = messages.first { it.msgId == 1002L }
        assertEquals("https://imgsa.baidu.com/a.jpg", image.imageUrl)
        assertEquals("https://imgsa.baidu.com/s.jpg", image.thumbnailUrl)
        assertEquals("", image.text)
    }

    @Test
    fun msgKeyIsTenHexCharsLikeOfficial() {
        val key = ImMessageApi.createMsgKey()
        assertEquals(10, key.length)
        assertTrue(key.all { "0123456789abcdef".contains(it) })
        // 两次生成不应完全相同，因为含随机位。
        assertNotEquals(key, ImMessageApi.createMsgKey())
    }
}
