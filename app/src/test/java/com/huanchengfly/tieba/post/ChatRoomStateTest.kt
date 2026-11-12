package com.huanchengfly.tieba.post.ui.page.chat

import com.huanchengfly.tieba.post.api.chat.ChatApi
import com.huanchengfly.tieba.post.api.chat.blcp.BlcpCrypto
import com.huanchengfly.tieba.post.api.chat.blcp.ChatSendApi
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
