package com.huanchengfly.tieba.post.ui.page.chat

import com.huanchengfly.tieba.post.api.chat.ChatApi
import com.huanchengfly.tieba.post.api.chat.blcp.BlcpClient
import com.huanchengfly.tieba.post.api.chat.blcp.BlcpCrypto
import kotlinx.coroutines.runBlocking
import com.huanchengfly.tieba.post.api.chat.blcp.ChatSendApi
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import org.json.JSONObject
import com.huanchengfly.tieba.post.api.models.chat.ChatMention
import com.huanchengfly.tieba.post.api.models.chat.ChatRoomMessage
import com.huanchengfly.tieba.post.api.models.chat.SendState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRoomStateTest {
    private fun message(
        id: Long,
        time: Long = 100_000L,
        text: String = "重复内容",
        local: Boolean = false,
        state: SendState = SendState.SENT,
    ) = ChatRoomMessage(
        msgId = id, senderUid = 42L, senderName = "测试用户", senderPortrait = "portrait",
        contentType = 0, text = text, msgTime = time, isSelf = true,
        isLocal = local, sendState = state,
    )

    @Test
    fun sendingStartsOnlyOneNewScrollRequestAndPreservesPayload() {
        val mention = ChatMention(99L, "target-bduk", "机器人", "robot-portrait")
        val started = ChatRoomPartialChange.Send.Start(-1L, "测试", 42L, "测试用户", "portrait",
            mentions = listOf(mention), robotSkill = 7).reduce(ChatRoomUiState())
        assertEquals(1L, started.sendRequestId)
        assertEquals(-1L, started.messages.single().msgId)
        assertEquals(listOf(mention), started.messages.single().mentions)
        assertEquals(1L, ChatRoomPartialChange.Send.Success(-1L).reduce(started).sendRequestId)
        assertEquals(1L, ChatRoomPartialChange.Poll.Success(persistentListOf()).reduce(started).sendRequestId)
    }

    @Test
    fun closedClientCannotReconnectAndCloseIsIdempotent() = runBlocking {
        val client = BlcpClient()
        assertFalse(client.isConnected)
        client.close()
        client.close()
        val error = runCatching { client.connect() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(client.isConnected)
    }

    @Test
    fun transportAckDoesNotPretendDeliverySucceeded() {
        val local = message(-1, local = true, state = SendState.SENDING)
        val accepted = ChatRoomPartialChange.Send.Success(-1L)
            .reduce(ChatRoomUiState(messages = persistentListOf(local)))
        assertEquals(SendState.SENDING, accepted.messages.single().sendState)
        assertTrue(accepted.messages.single().transportAccepted)
        val timedOut = ChatRoomPartialChange.Send.Unconfirmed(-1L).reduce(accepted)
        assertEquals(SendState.FAILED, timedOut.messages.single().sendState)
        assertEquals(UNCONFIRMED_SEND_MESSAGE, timedOut.messages.single().sendError)
    }

    @Test
    fun lateHistoryEchoResolvesUnconfirmedSend() {
        val local = message(-1, local = true, state = SendState.SENDING)
        val accepted = ChatRoomPartialChange.Send.Success(-1L)
            .reduce(ChatRoomUiState(messages = persistentListOf(local)))
        val unconfirmed = ChatRoomPartialChange.Send.Unconfirmed(-1L).reduce(accepted)
        val delivered = ChatRoomPartialChange.Poll.Success(persistentListOf(message(10))).reduce(unconfirmed)
        assertEquals(1, delivered.messages.size)
        assertFalse(delivered.messages.single().isLocal)
        assertEquals(SendState.SENT, delivered.messages.single().sendState)
        assertEquals(null, delivered.messages.single().sendError)
        assertEquals(delivered, ChatRoomPartialChange.Send.Unconfirmed(-1L).reduce(delivered))
    }

    @Test
    fun failureRetainsStageAndServerCodeForRetryDialog() {
        val local = message(-1, local = true, state = SendState.SENDING)
        val error = IllegalStateException("群聊IM 登录 失败：RPC 110001")
        val failed = ChatRoomPartialChange.Send.Failure(-1L, error)
            .reduce(ChatRoomUiState(messages = persistentListOf(local)))
        assertEquals(error.message, failed.messages.single().sendError)
        assertEquals(SendState.FAILED, failed.messages.single().sendState)
    }

    @Test
    fun unchangedPollKeepsListIdentityAndHistory() {
        val history = message(1, time = 1L)
        val latest = message(2)
        val current = persistentListOf(history, latest)
        assertSame(current, mergeServerMessages(current, listOf(latest)))
    }

    @Test
    fun singleEchoOnlyReconcilesOneOfTwoIdenticalSends() {
        val first = message(-1, local = true)
        val second = message(-2, time = 101_000L, local = true)
        val merged = mergeServerMessages(persistentListOf(first, second), listOf(message(10)))
        assertEquals(2, merged.size)
        assertEquals(1, merged.count { it.isLocal })
        assertEquals(1, merged.count { it.msgId == 10L })
        assertSame(merged, mergeServerMessages(merged, listOf(message(10))))
    }

    @Test
    fun oldEchoDoesNotConsumeNewLocalMessage() {
        val pending = message(-1, time = 600_000L, local = true)
        val oldEcho = message(10, time = 1L)
        val merged = mergeServerMessages(persistentListOf(pending), listOf(oldEcho))
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.msgId == pending.msgId })
    }

    @Test
    fun failedSendIsNotReconciledByAnUnrelatedEcho() {
        val failed = message(-1, local = true, state = SendState.FAILED)
        val merged = mergeServerMessages(persistentListOf(failed), listOf(message(10)))
        assertTrue(merged.any { it.msgId == -1L && it.sendState == SendState.FAILED })
    }

    @Test
    fun lateConfirmationReconcilesEchoAndRetainsMentionPayload() {
        val mention = ChatMention(99L, "target-bduk", "机器人", "robot-portrait")
        val local = message(-1, local = true, state = SendState.SENDING)
            .copy(mentions = listOf(mention), robotSkill = 7)
        val state = ChatRoomUiState(messages = persistentListOf(local))
        val earlyEcho = message(10).copy(mentions = listOf(mention), robotSkill = 7)
        val polled = ChatRoomPartialChange.Poll.Success(persistentListOf(earlyEcho)).reduce(state)
        val confirmed = ChatRoomPartialChange.Send.Success(-1L).reduce(polled)
        assertEquals(1, confirmed.messages.size)
        assertFalse(confirmed.messages.single().isLocal)
        assertEquals(listOf(mention), confirmed.messages.single().mentions)
        assertEquals(7, confirmed.messages.single().robotSkill)
    }

    @Test
    fun anEchoCannotReserveTwoConcurrentSends() {
        val first = message(-1, local = true, state = SendState.SENDING)
        val second = message(-2, local = true, state = SendState.SENDING)
        val state = ChatRoomUiState(messages = persistentListOf(first, second))
        val polled = ChatRoomPartialChange.Poll.Success(persistentListOf(message(10))).reduce(state)
        val confirmed = ChatRoomPartialChange.Send.Success(-1L).reduce(polled)
        assertEquals(2, confirmed.messages.size)
        assertTrue(confirmed.messages.any { it.msgId == -2L && it.isLocal })
        assertSame(confirmed.messages, mergeServerMessages(confirmed.messages, listOf(message(10))))
    }

    @Test
    fun observedServerDeliveryWinsOverTransportTimeout() {
        val local = message(-1, local = true, state = SendState.SENDING)
        val polled = ChatRoomPartialChange.Poll.Success(persistentListOf(message(10)))
            .reduce(ChatRoomUiState(messages = persistentListOf(local)))
        val failed = ChatRoomPartialChange.Send.Failure(-1L, IllegalStateException("timeout")).reduce(polled)
        assertEquals(1, failed.messages.size)
        assertFalse(failed.messages.single().isLocal)
    }

    private fun payload(mentions: List<ChatMention> = emptyList(), skill: Int? = null, text: String = "测试消息") =
        ChatSendApi.buildMessagePayload(
            chatroomId = 1L, text = text, forumId = 2L, forumLevel = 0, uk = 123L,
            bduk = BlcpCrypto.userId2Bduk(42L), triggerId = 1L, selfName = "测试用户",
            selfPortrait = "self-portrait", bduss = "test-only", mentions = mentions, robotSkill = skill,
        )

    private fun received(payload: JSONObject) = ChatApi.parseMessage(JSONObject().apply {
        put("msgid", 10L)
        put("from_user", 123L)
        put("type", 81)
        put("create_time", 100L)
        put("content", payload.getString("content"))
    }, 42L)!!

    @Test
    fun payloadRoundTripPreservesMentionsRobotAndNestedBody() {
        val targets = listOf(
            ChatMention(99L, BlcpCrypto.userId2Bduk(99L), "机器人", "robot-portrait"),
            ChatMention(100L, BlcpCrypto.userId2Bduk(100L), "成员", "member-portrait"),
        )
        val payload = payload(targets, 7, "@机器人 @成员 测试消息")
        val content = JSONObject(JSONObject(payload.getString("content")).getString("text"))
        val at = content.getJSONArray("at_data")
        assertEquals("user", at.getJSONObject(0).getString("at_type"))
        assertEquals("0", at.getJSONObject(0).getString("position"))
        assertEquals("1", at.getJSONObject(1).getString("position"))
        val robot = JSONObject(content.getString("ext")).getJSONObject("content").getJSONObject("robot_params")
        assertEquals("tieba_group_chat", robot.getString("scene"))
        val parsed = received(payload)
        assertEquals(42L, parsed.senderUid)
        assertTrue(parsed.isSelf)
        assertEquals(0, parsed.contentType)
        assertEquals("@机器人 @成员 测试消息", parsed.text)
        assertEquals(targets, parsed.mentions)
        assertEquals(7, parsed.robotSkill)
    }

    @Test
    fun urlTextIsNotAnImageAndNormalSendHasNoRobotCommand() {
        val parsed = received(payload(text = "https://example.com/message"))
        assertEquals("https://example.com/message", parsed.text)
        assertTrue(parsed.images.isEmpty())
        assertTrue(parsed.mentions.isEmpty())
        assertEquals(null, parsed.robotSkill)
    }

    @Test
    fun unknownImSenderNeverBecomesTiebaProfileId() {
        val payload = payload()
        val content = JSONObject(JSONObject(payload.getString("content")).getString("text"))
        content.remove("baidu_uk")
        payload.put("content", JSONObject().put("text", content.toString()).toString())
        val parsed = received(payload)
        assertEquals(0L, parsed.senderUid)
        assertFalse(parsed.isSelf)
    }

    @Test
    fun nestedImagesAreExtractedWithoutProfileMetadata() {
        val payload = payload()
        val content = JSONObject(JSONObject(payload.getString("content")).getString("text"))
        content.put("content_type", "1")
        content.put("content_body", JSONObject().put("src", "https://example.com/one.png,https://example.com/two.png").toString())
        payload.put("content", JSONObject().put("text", content.toString()).toString())
        val parsed = received(payload)
        assertEquals(1, parsed.contentType)
        assertEquals(listOf("https://example.com/one.png", "https://example.com/two.png"), parsed.images)
        assertTrue(parsed.text.isEmpty())
    }

    private fun imagePayload(type: Int, body: Any, source: Any = ""): JSONObject {
        val payload = payload()
        val content = JSONObject(JSONObject(payload.getString("content")).getString("text"))
        content.put("content_type", type.toString())
        content.put("content_body", body)
        content.put("src", source)
        return payload.put("content", JSONObject().put("text", content.toString()).toString())
    }

    @Test
    fun imageUrlInTextRendersAsImageWithoutDuplicatingCdnAddress() {
        val url = "https://example.com/photo.jpg"
        val parsed = received(imagePayload(1, JSONObject().put("text", url).toString()))
        assertEquals(listOf(url), parsed.images)
        assertEquals("", parsed.text)
    }

    @Test
    fun outerImageSourceAndEncodedImageArraysAreRead() {
        val urls = org.json.JSONArray().put("https://example.com/one.jpg").put("https://example.com/two.jpg")
        val parsed = received(imagePayload(1, JSONObject().put("text", "图片说明").toString(), urls.toString()))
        assertEquals(listOf("https://example.com/one.jpg", "https://example.com/two.jpg"), parsed.images)
        assertEquals("图片说明", parsed.text)
    }

    @Test
    fun imageCaptionKeepsLineBreaksAndSpacing() {
        val url = "https://example.com/photo.jpg"
        val parsed = received(imagePayload(1,
            JSONObject().put("text", "第一行  说明\n第二行\n$url").toString()))
        assertEquals(listOf(url), parsed.images)
        assertEquals("第一行  说明\n第二行", parsed.text)
    }

    @Test
    fun knownTiebaCdnTextBecomesImageEvenWhenContentTypeIsText() {
        val url = "https://tiebapic.baidu.com/forum/pic/item/example.jpg"
        val parsed = received(payload(text = url))
        assertEquals(listOf(url), parsed.images)
        assertEquals("", parsed.text)
    }

    @Test
    fun signInSkillIsNotDiscardedBecauseItsOfficialEntryIsHidden() {
        val bot = JSONObject().put("user", JSONObject()
            .put("uk", BlcpCrypto.userId2Bduk(99L)).put("name_show", "签到机器人"))
            .put("skill", org.json.JSONArray().put(JSONObject()
                .put("type", 10005).put("name", "签到").put("alias", "签到").put("is_show", 0)))
        val robots = ChatApi.parseRobots(JSONObject().put("bots", JSONObject()
            .put("bot_list", org.json.JSONArray().put(bot))))
        val robot = robots.single()
        val skill = robot.skills.single()
        assertEquals(10005, skill.type)
        val parsed = received(payload(listOf(robot.user), skill.type, "@签到机器人 签到"))
        assertEquals(10005, parsed.robotSkill)
        assertEquals(listOf(robot.user), parsed.mentions)
    }

    private fun privateMessage(
        id: Long,
        peerUid: Long,
        time: Long,
        content: String,
    ) = ChatWsMessage(
        msgId = id,
        peerUid = peerUid,
        peerName = "用户$peerUid",
        peerPortrait = "portrait-$peerUid",
        content = content,
        msgTime = time,
        isSelf = false,
    )

    @Test
    fun privateListEmptyIncrementDoesNotClearExistingSession() {
        val current = PrivateSession(
            peerUid = 1L,
            peerName = "用户1",
            peerPortrait = "portrait-1",
            lastMessage = "已有消息",
            lastTime = 200L,
        )
        val state = PrivateChatListUiState(
            sessions = persistentListOf(current),
        )
        val reduced = PrivateChatListPartialChange.Load.Success(
            sessions = persistentListOf(),
            replace = false,
        ).reduce(state)
        assertEquals(listOf(current), reduced.sessions)
    }

    @Test
    fun privateListOlderIncrementDoesNotReplaceNewerPreview() {
        val current = PrivateSession(
            peerUid = 1L,
            peerName = "用户1",
            peerPortrait = "portrait-1",
            lastMessage = "新消息",
            lastTime = 200L,
        )
        val older = current.copy(lastMessage = "旧消息", lastTime = 100L)
        val reduced = PrivateChatListPartialChange.Load.Success(
            sessions = persistentListOf(older),
            replace = false,
        ).reduce(PrivateChatListUiState(sessions = persistentListOf(current)))
        assertEquals("新消息", reduced.sessions.single().lastMessage)
        assertEquals(200L, reduced.sessions.single().lastTime)
    }

    @Test
    fun privateListNewContactIsSortedByLatestMessage() {
        val current = PrivateSession(
            peerUid = 1L,
            peerName = "用户1",
            peerPortrait = "portrait-1",
            lastMessage = "较早消息",
            lastTime = 100L,
        )
        val incoming = PrivateSession(
            peerUid = 2L,
            peerName = "用户2",
            peerPortrait = "portrait-2",
            lastMessage = "最新消息",
            lastTime = 200L,
        )
        val reduced = PrivateChatListPartialChange.Load.Success(
            sessions = persistentListOf(incoming),
            replace = false,
        ).reduce(PrivateChatListUiState(sessions = persistentListOf(current)))
        assertEquals(listOf(2L, 1L), reduced.sessions.map { it.peerUid })
    }

    @Test
    fun privateListHistoryDoesNotBecomeUnreadCount() {
        val sessions = listOf(
            privateMessage(1L, 7L, 100L, "第一条"),
            privateMessage(2L, 7L, 200L, "第二条"),
        ).toSessions()
        assertEquals(1, sessions.size)
        assertEquals(0, sessions.single().unreadCount)
        assertEquals("第二条", sessions.single().lastMessage)
    }

    @Test
    fun privateIncomingDoesNotMixOtherPeersOrUnaddressedSelfMessages() {
        val incoming = privateMessage(1L, 7L, 100L, "来信")
        val other = privateMessage(2L, 8L, 200L, "其他会话")
        val self = privateMessage(3L, 9L, 300L, "自己发给未知联系人").copy(isSelf = true)
        assertEquals(listOf(incoming), listOf(incoming, other, self).forPrivatePeer(7L))
    }

    @Test
    fun privateEmptyPollPreservesHistoryAndPendingMessages() {
        val incoming = privateMessage(1L, 7L, 100L, "历史")
        val initial = PrivateChatUiState(messages = persistentListOf(incoming))
        val sending = PrivateChatPartialChange.Send.Start(-1L, "发送中", 7L).reduce(initial)
        val reduced = PrivateChatPartialChange.Poll.Success(persistentListOf()).reduce(sending)
        assertEquals(sending.messages, reduced.messages)
        assertEquals(SendState.SENDING, reduced.messages.last().sendState)
    }

    @Test
    fun privateSendConfirmationReplacesOnlyMatchingPendingId() {
        val initial = PrivateChatPartialChange.Send.Start(-1L, "相同文案", 7L)
            .reduce(PrivateChatUiState())
        val sending = PrivateChatPartialChange.Send.Start(-2L, "相同文案", 7L).reduce(initial)
        val server = privateMessage(10L, 7L, Long.MAX_VALUE, "相同文案").copy(isSelf = true)
        val confirmed = PrivateChatPartialChange.Send.Success(-1L, server).reduce(sending)
        assertEquals(setOf(-2L, 10L), confirmed.messages.map { it.msgId }.toSet())
        assertEquals(SendState.SENDING, confirmed.messages.first { it.msgId == -2L }.sendState)
        assertTrue(!confirmed.messages.first { it.msgId == 10L }.isLocal)
        val duplicate = PrivateChatPartialChange.Poll.Success(persistentListOf(server)).reduce(confirmed)
        assertEquals(confirmed.messages, duplicate.messages)
    }

    @Test
    fun privateLateFailureDoesNotOverwriteAcknowledgedMessage() {
        val server = privateMessage(10L, 7L, 100L, "发送成功").copy(isSelf = true)
        val state = PrivateChatUiState(messages = persistentListOf(server))
        val reduced = PrivateChatPartialChange.Send.Failure(-1L, IllegalStateException("timeout"))
            .reduce(state)
        assertEquals(state.messages, reduced.messages)
    }

    @Test
    fun privatePollIsSortedAndDeduplicatedByMessageId() {
        val earlier = privateMessage(1L, 7L, 100L, "较早")
        val later = privateMessage(2L, 7L, 200L, "较新")
        val reduced = PrivateChatPartialChange.Poll.Success(persistentListOf(later, earlier, later))
            .reduce(PrivateChatUiState(messages = persistentListOf(earlier)))
        assertEquals(listOf(earlier, later), reduced.messages)
    }

    @Test
    fun confirmationAndFailureDoNotClearSendingParameters() {
        val mention = ChatMention(99L, "target-bduk", "机器人", "robot-portrait")
        val local = message(-1, local = true, state = SendState.SENDING)
            .copy(mentions = listOf(mention), robotSkill = 7)
        val state = ChatRoomUiState(messages = persistentListOf(local))
        val sent = ChatRoomPartialChange.Send.Success(-1L).reduce(state).messages.single()
        val failed = ChatRoomPartialChange.Send.Failure(-1L, IllegalStateException("timeout"))
            .reduce(state).messages.single()
        assertEquals(listOf(mention), sent.mentions)
        assertEquals(7, sent.robotSkill)
        assertEquals(listOf(mention), failed.mentions)
        assertEquals(7, failed.robotSkill)
        assertEquals(SendState.FAILED, failed.sendState)
    }
}
