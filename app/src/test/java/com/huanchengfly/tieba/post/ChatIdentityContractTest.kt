package com.huanchengfly.tieba.post

import com.huanchengfly.tieba.post.api.chat.ChatApi
import com.huanchengfly.tieba.post.api.chat.blcp.BlcpBusinessException
import com.huanchengfly.tieba.post.api.chat.blcp.BlcpClient
import com.huanchengfly.tieba.post.api.chat.blcp.ChatSendApi
import com.huanchengfly.tieba.post.api.chat.http.ImHttpException
import com.huanchengfly.tieba.post.api.chat.http.ImHttpTransportException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatIdentityContractTest {
    @Test
    fun retryRetainsCallerMessageKeyWithoutChangingProtocol() {
        val payload = ChatSendApi.buildMessagePayload(
            chatroomId = 77L,
            text = "相同正文并不表示同一条消息",
            forumId = 12L,
            forumLevel = 1,
            uk = 23L,
            bduk = "test-user",
            triggerId = 45L,
            selfName = "测试",
            selfPortrait = "test-portrait",
            bduss = "test-token",
            mentions = emptyList(),
            robotSkill = null,
            zid = "",
            msgKey = "persistent-message-key",
        )
        assertEquals("persistent-message-key", payload.getString("msg_key"))
        assertEquals(77L, payload.getLong("mcast_id"))
        assertEquals(81, payload.getInt("type"))
    }

    @Test
    fun historyPreservesServerMessageKeyForExactReconciliation() {
        val message = ChatApi.parseMessage(
            JSONObject("""{"msgid":12,"msg_key":"persistent-message-key","from_user":0,"type":0,"content":{"text":{"content_body":{"text":"测试"}}},"create_time":1700000000}"""),
            5L,
        )
        assertEquals("persistent-message-key", message?.msgKey)
        val nested = ChatApi.parseMessage(
            JSONObject("""{"msgid":13,"from_user":0,"type":0,"content":{"msg_key":"nested-key","text":{"content_body":{"text":"测试"}}},"create_time":1700000000}"""),
            5L,
        )
        assertEquals("nested-key", nested?.msgKey)
    }

    @Test
    fun explicitBusinessRejectionDiffersFromUncertainTransportFailure() {
        val rejection = runCatching {
            BlcpClient().parseBusinessResponse("""{"err_code":100,"msg":"拒绝发送"}""".toByteArray(), "send")
        }.exceptionOrNull()
        assertTrue(rejection is BlcpBusinessException)
        assertEquals(100, (rejection as BlcpBusinessException).code)
        val malformed = runCatching {
            BlcpClient().parseBusinessResponse("""{"err_code":"invalid"}""".toByteArray(), "send")
        }.exceptionOrNull()
        assertFalse(malformed is BlcpBusinessException)
        val transport: Throwable = ImHttpTransportException("响应中断")
        val business: Throwable = ImHttpException(1, "明确拒绝")
        assertTrue(transport is java.io.IOException)
        assertFalse(business is java.io.IOException)
    }
}
