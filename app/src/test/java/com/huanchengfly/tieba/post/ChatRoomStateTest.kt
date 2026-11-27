package com.huanchengfly.tieba.post.ui.page.chat

import com.huanchengfly.tieba.post.api.chat.ChatApi
import com.huanchengfly.tieba.post.api.chat.blcp.BlcpClient
import com.huanchengfly.tieba.post.api.chat.blcp.BlcpCrypto
import com.huanchengfly.tieba.post.api.chat.blcp.BlcpImIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import com.huanchengfly.tieba.post.api.chat.blcp.ChatSendApi
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.api.chat.ws.awaitPrivateChatResponse
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsConstants
import com.huanchengfly.tieba.post.api.chat.ws.PrivateChatImageContent
import com.huanchengfly.tieba.post.api.chat.ws.buildPrivateMessageRequest
import org.json.JSONObject
import com.huanchengfly.tieba.post.api.models.chat.ChatCloudMeme
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageKind
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageSemantics
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
    @Test
    fun groupHistoryRequestUsesLatestPageAndSignsExactlyItsJsonFields() {
        for (cursor in listOf(0L, 1795694596873979L)) {
            val request = ChatApi.buildChatRoomHistoryRequest(
                roomId = 123L, lastMsgId = cursor, deviceId = "test-cuid", timestamp = 1795694596L,
            )
            val expected = mapOf(
                "appid" to "10773430",
                "mcast_id" to "123",
                "msgid_end" to (if (cursor == 0L) Long.MAX_VALUE else cursor).toString(),
                "count" to "-60",
                "category" to "4",
                "app_version" to ChatApi.CHAT_VERSION,
                "sdk_version" to "11250036",
                "device_id" to "test-cuid",
                "timestamp" to "1795694596",
            )
            assertEquals(expected.keys + "sign", request.keys().asSequence().toSet())
            assertEquals(expected, expected.keys.associateWith { request.get(it).toString() })
            assertEquals(-60, request.getInt("count"))
            assertEquals(if (cursor == 0L) Long.MAX_VALUE else cursor, request.getLong("msgid_end"))
            assertFalse(request.has("msgid_begin"))
            assertFalse(request.has("device_type"))
            // 独立计算 MD5，锁定无额外盐且签名字段与 JSON 完全一致。
            val raw = expected.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" }
            val signature = java.security.MessageDigest.getInstance("MD5")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(signature, request.getString("sign"))
        }
        val latest = ChatApi.buildChatRoomHistoryRequest(
            roomId = 123L, deviceId = "test-cuid", timestamp = 1795694596L,
        )
        assertEquals(Long.MAX_VALUE, latest.getLong("msgid_end"))
    }

    @Test
    fun onlineGroupTextAndImageShapesUseZeroAndOneInsteadOfSdkTypes() {
        for (encoded in listOf(false, true)) {
            for (type in listOf(0, 1)) {
                val body = if (type == 0) JSONObject().put("text", "你好") else JSONObject()
                    .put("url", "https://example.com/original.jpg")
                    .put("thumbnail", "https://example.com/preview.jpg").put("w", 640).put("h", 480)
                val inner = JSONObject().put("content_type", if (encoded) type.toString() else type)
                    .put("content_body", if (encoded) body.toString() else body)
                    .put("baidu_uk", BlcpCrypto.userId2Bduk(42L))
                val envelope = JSONObject().put("text", if (encoded) inner.toString() else inner)
                val parsed = ChatApi.parseMessage(JSONObject()
                    .put("msgid", 1795694596873979L).put("from_user", 123L)
                    .put("type", 81).put("category", 4).put("create_time", 1795694596L)
                    .put("content", if (encoded) envelope.toString() else envelope), 42L)!!
                assertEquals(type, parsed.contentType)
                assertEquals(type, parsed.semantics.businessType)
                assertEquals(81, parsed.semantics.transportType)
                assertEquals(if (type == 0) ChatMessageKind.TEXT else ChatMessageKind.IMAGE, parsed.semantics.kind)
                assertEquals(if (type == 0) "你好" else "", parsed.text)
                assertEquals(if (type == 0) emptyList<String>() else listOf("https://example.com/original.jpg"), parsed.images)
                assertEquals(42L, parsed.senderUid)
                assertTrue(parsed.isSelf)
            }
        }
    }

    @Test
    fun parsedTextAndImageEchoesReplaceTheirLocalPlaceholdersWithoutChangingTypes() {
        for (meme in listOf(null, ChatCloudMeme("image", "https://example.com/image.jpg"))) {
            val type = if (meme == null) 0 else 1
            val text = if (meme == null) "你好" else ""
            val started = ChatRoomPartialChange.Send.Start(-1L, text, 42L, "测试用户", "portrait",
                cloudMeme = meme).reduce(ChatRoomUiState())
            val transmitting = ChatRoomPartialChange.Send.Transmitting(-1L).reduce(started).let {
                it.copy(messages = persistentListOf(it.messages.single().copy(msgKey = "exact-key")))
            }
            val local = transmitting.messages.single()
            assertEquals(type, local.contentType)
            assertEquals(type, local.semantics.businessType)
            val request = ChatSendApi.buildMessagePayload(
                1L, text, 2L, 0, 123L, BlcpCrypto.userId2Bduk(42L), 1L,
                "测试用户", "portrait", "test-only", emptyList(), null, "", cloudMeme = meme,
            )
            val echo = received(request).copy(msgTime = local.msgTime, msgKey = local.msgKey)
            val delivered = ChatRoomPartialChange.Poll.Success(persistentListOf(echo)).reduce(transmitting)
            val confirmed = delivered.messages.single()
            assertFalse(confirmed.isLocal)
            assertEquals(-1L, confirmed.echoForPendingId)
            assertEquals(type, confirmed.contentType)
            assertEquals(type, confirmed.semantics.businessType)
            assertEquals(local.semantics.kind, confirmed.semantics.kind)
            assertEquals(local.images, confirmed.images)
            assertEquals(local.text, confirmed.text)
            assertEquals(delivered, ChatRoomPartialChange.Send.Unconfirmed(-1L).reduce(delivered))
        }
    }

    @Test
    fun unknownGroupBusinessTypeDoesNotFallThroughToSdkImageOrVoice() {
        for (type in listOf(2, 3, 9999)) {
            val parsed = received(imagePayload(type, JSONObject().put("future", "value").toString()))
            assertEquals(ChatMessageKind.UNKNOWN, parsed.semantics.kind)
            assertEquals(type, parsed.semantics.businessType)
            assertEquals(type, parsed.contentType)
            assertTrue(parsed.text.contains(type.toString()))
            assertTrue(parsed.images.isEmpty())
            assertEquals(null, parsed.voiceUrl)
            assertTrue(parsed.semantics.rawPayload!!.contains("future"))
        }
    }

    @Test
    fun protocolNamespacesRemainSeparateAndKnownBusinessTypesAreClassified() {
        assertEquals(ChatMessageKind.TEXT, ChatMessageSemantics.classify(81, 0))
        assertEquals(ChatMessageKind.IMAGE, ChatMessageSemantics.classify(81, 1))
        assertEquals(ChatMessageKind.UNKNOWN, ChatMessageSemantics.classify(81, 2))
        assertEquals(ChatMessageKind.UNKNOWN, ChatMessageSemantics.classify(81, 3))
        assertEquals(ChatMessageKind.TEXT, ChatMessageSemantics.classify(99, 1))
        assertEquals(ChatMessageKind.IMAGE, ChatMessageSemantics.classify(1, 2))
        assertEquals(ChatMessageKind.VOICE, ChatMessageSemantics.classify(1, 3))
        assertEquals(ChatMessageKind.LARGE_EMOTICON, ChatMessageSemantics.classify(1, 4))
        assertEquals(ChatMessageKind.IMAGE_TO_TEXT, ChatMessageSemantics.classify(1, 101))
        assertEquals(ChatMessageKind.SINGLE_TEXT_IMAGE, ChatMessageSemantics.classify(1, 102))
        assertEquals(ChatMessageKind.TEXT_TO_SPEECH, ChatMessageSemantics.classify(1, 103))
        assertEquals(ChatMessageKind.AI_INTRODUCTION, ChatMessageSemantics.classify(1, 104))
        assertEquals(ChatMessageKind.WEB_CHAT, ChatMessageSemantics.classify(1, 1001))
        assertEquals(ChatMessageKind.POST_SHARE, ChatMessageSemantics.classify(1, 7010, system = true))
        assertEquals(ChatMessageKind.DELETE_EVENT, ChatMessageSemantics.classify(1, 7025, system = true))
        assertEquals(ChatMessageKind.EMOTION_REACTION, ChatMessageSemantics.classify(1, 20000, system = true))
        assertEquals(ChatMessageKind.SYSTEM_EVENT, ChatMessageSemantics.classify(1, 999999, system = true))
        assertEquals(ChatMessageKind.UNKNOWN, ChatMessageSemantics.classify(7010, null))
    }

    @Test
    fun transportType81WithNoBusinessTypeIsReadableText() {
        val message = ChatApi.parseMessage(
            JSONObject().apply {
                put("msgid", 9)
                put("type", 81)
                put("from_user", 7)
                put("content", "{\"text\":{\"content_type\":0,\"content_body\":{\"text\":\"分享正文\"}}}")
            },
            null,
        )!!
        assertEquals(ChatMessageKind.TEXT, message.semantics.kind)
        assertEquals("分享正文", message.text)
        assertFalse(message.text.contains("暂未支持"))
    }

    @Test
    fun groupParserKeepsTransportTypeAndBusinessTypeSeparate() {
        val message = ChatApi.parseMessage(
            JSONObject().apply {
                put("msgid", 10)
                put("type", 77)
                put("from_user", 9)
                put("create_time", 100)
                put("content", JSONObject().apply {
                    put("text", JSONObject().apply {
                        put("content_type", 2)
                        put("content_body", JSONObject().apply {
                            put("src", "https://imgsrc.baidu.com/a.jpg")
                        })
                    }.toString())
                }.toString())
            },
            null,
        )!!
        assertEquals(2, message.contentType)
        assertEquals(77, message.semantics.transportType)
        assertEquals(2, message.semantics.businessType)
        assertEquals(ChatMessageKind.IMAGE, message.semantics.kind)
        assertEquals(listOf("https://imgsrc.baidu.com/a.jpg"), message.images)
    }

    @Test
    fun missingOrInvalidSenderDoesNotPretendToBeASystemAccount() {
        for (sender in listOf<Any?>(null, JSONObject.NULL, "invalid")) {
            val json = JSONObject().put("msgid", 123).put("type", 81)
                .put("content", "{\"text\":{\"content_type\":0,\"content_body\":{\"text\":\"正常正文\"}}}")
            if (sender != null) json.put("from_user", sender)
            val message = ChatApi.parseMessage(json, null)!!
            assertEquals(ChatMessageKind.TEXT, message.semantics.kind)
            assertEquals("正常正文", message.text)
            assertFalse(message.senderName == "系统")
        }
    }

    @Test
    fun emptySystemEventKeepsTypeAndPayloadInsteadOfAnOpaqueLabel() {
        val message = ChatApi.parseMessage(JSONObject()
            .put("msgid", 124).put("type", 9999).put("from_user", 0)
            .put("content", "{\"text\":{\"future_field\":7}}"), null)!!
        assertEquals(ChatMessageKind.SYSTEM_EVENT, message.semantics.kind)
        assertTrue(message.text.contains("9999"))
        assertFalse(message.text == "系统消息")
        assertTrue(message.semantics.rawPayload!!.contains("future_field"))
    }

    @Test
    fun readableSystemMessageStillPreservesItsActualBody() {
        val message = ChatApi.parseMessage(JSONObject()
            .put("msgid", 125).put("type", 9999).put("from_user", "0")
            .put("content", "{\"text\":{\"content_body\":{\"text\":\"有人加入了群聊\"}}}"), null)!!
        assertEquals("有人加入了群聊", message.text)
        assertEquals(ChatMessageKind.SYSTEM_EVENT, message.semantics.kind)
    }

    @Test
    fun groupParserRetainsSystemAndUnknownPayload() {
        val system = ChatApi.parseMessage(
            JSONObject().apply {
                put("msgid", 11)
                put("type", 7025)
                put("from_user", 0)
                put("create_time", 100)
                put("content", "{\"text\":{\"target_msgid\":\"10\"}}")
            },
            null,
        )!!
        assertEquals(ChatMessageKind.DELETE_EVENT, system.semantics.kind)
        assertEquals(10L, system.semantics.targetId)
        assertEquals("系统", system.senderName)
        assertTrue(system.semantics.rawPayload!!.isNotEmpty())

        val unknown = ChatApi.parseMessage(
            JSONObject().apply {
                put("msgid", 12)
                put("type", 999)
                put("from_user", 9)
                put("create_time", 100)
                put("content", "{\"text\":{\"content_type\":9999,\"future\":\"value\"}}")
            },
            null,
        )!!
        assertEquals(ChatMessageKind.UNKNOWN, unknown.semantics.kind)
        assertTrue(unknown.text.contains("9999"))
    }

    @Test
    fun privateResponseTimeoutIsAFailureInsteadOfCoroutineCancellation() = runBlocking {
        val response = kotlinx.coroutines.CompletableDeferred<ByteArray>()
        val failure = runCatching { awaitPrivateChatResponse(response, 1L) }.exceptionOrNull()!!
        assertTrue(failure is java.net.SocketTimeoutException)
        assertFalse(failure is kotlinx.coroutines.CancellationException)
        val pending = PrivateChatPartialChange.Send.Start(-1L, "测试", 42L).reduce(PrivateChatUiState())
        val failed = PrivateChatPartialChange.Send.Failure(-1L, failure).reduce(pending)
        assertEquals(SendState.FAILED, failed.messages.single().sendState)
        assertTrue(failure.message!!.contains("避免重复发送"))
    }

    @Test
    fun privateResponseSuccessAndExternalCancellationRemainDistinct() = runBlocking {
        val payload = byteArrayOf(1, 2, 3)
        val ready = kotlinx.coroutines.CompletableDeferred(payload)
        assertTrue(payload.contentEquals(awaitPrivateChatResponse(ready)))
        val cancelled = kotlinx.coroutines.CompletableDeferred<ByteArray>()
        cancelled.cancel(kotlinx.coroutines.CancellationException("page closed"))
        val failure = runCatching { awaitPrivateChatResponse(cancelled) }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun confirmationTimersDoNotBlockFollowingAcknowledgements() = runBlocking {
        val changes = (1L..32L).map { ChatRoomPartialChange.Send.Success(-it) }
        val emitted = kotlinx.coroutines.withTimeout(5_000) {
            kotlinx.coroutines.flow.flow<ChatRoomPartialChange> {
                changes.forEach { emit(it) }
            }.withSendConfirmationTimeout(timeoutMillis = 60_000)
                .take(changes.size).toList()
        }
        assertEquals(changes, emitted)
    }

    @Test
    fun independentConfirmationTimerRetainsPendingIdentity() = runBlocking {
        val accepted = ChatRoomPartialChange.Send.Success(-42L)
        val emitted = kotlinx.coroutines.withTimeout(5_000) {
            kotlinx.coroutines.flow.flowOf<ChatRoomPartialChange>(accepted)
                .withSendConfirmationTimeout(timeoutMillis = 1).toList()
        }
        assertEquals(listOf(accepted, ChatRoomPartialChange.Send.Unconfirmed(-42L)), emitted)
    }

    @Test
    fun privateSendRequestsScrollOnlyWhenStartingAndNotOnAckOrPoll() {
        val initial = PrivateChatUiState()
        val started = PrivateChatPartialChange.Send.Start(-1L, "测试", 42L).reduce(initial)
        assertEquals(1L, started.sendRequestId)
        val confirmed = ChatWsMessage(
            msgId = 101L, peerUid = 42L, peerName = "", peerPortrait = "",
            content = "测试", msgTime = 1L, isSelf = true,
        )
        val acknowledged = PrivateChatPartialChange.Send.Success(-1L, confirmed).reduce(started)
        assertEquals(1L, acknowledged.sendRequestId)
        val polled = PrivateChatPartialChange.Poll.Success(persistentListOf(confirmed)).reduce(acknowledged)
        assertEquals(1L, polled.sendRequestId)
        val resent = PrivateChatPartialChange.Send.Start(-2L, "测试", 42L, -1L).reduce(polled)
        assertEquals(2L, resent.sendRequestId)
        assertEquals(2L, PrivateChatPartialChange.Send.Failure(-2L, Exception("失败")).reduce(resent).sendRequestId)
    }

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
        assertEquals(SendState.UNKNOWN, timedOut.messages.single().sendState)
        assertEquals(UNCONFIRMED_SEND_MESSAGE, timedOut.messages.single().sendError)
    }

    @Test
    fun lateHistoryEchoResolvesUnconfirmedSend() {
        val local = message(-1, local = true, state = SendState.SENDING).copy(msgKey = "late-key")
        val accepted = ChatRoomPartialChange.Send.Success(-1L)
            .reduce(ChatRoomUiState(messages = persistentListOf(local)))
        val unconfirmed = ChatRoomPartialChange.Send.Unconfirmed(-1L).reduce(accepted)
        val delivered = ChatRoomPartialChange.Poll.Success(persistentListOf(message(10).copy(msgKey = "late-key"))).reduce(unconfirmed)
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
    fun chatRoomMessageExposesDerivedLocalAndServerIds() {
        val local = message(-7, local = true)
        val server = message(7)
        assertEquals(-7L, local.localPendingId)
        assertEquals(null, local.serverMsgId)
        assertFalse(local.deliveryConfirmed)
        assertEquals(null, server.localPendingId)
        assertEquals(7L, server.serverMsgId)
        assertTrue(server.deliveryConfirmed)
    }

    @Test
    fun localPhotoUriIsRetainedByPendingImageReducer() {
        val state = ChatRoomPartialChange.Send.Start(
            pendingId = -8L,
            text = "",
            selfUid = 42L,
            selfName = "测试用户",
            selfPortrait = "portrait",
            localPhotoUri = "content://photos/8",
        ).reduce(ChatRoomUiState())
        val pending = state.messages.single()
        assertEquals("content://photos/8", pending.localPhotoUri)
        assertEquals(listOf("content://photos/8"), pending.images)
        assertEquals(1, pending.contentType)
        assertEquals(1, pending.semantics.businessType)
        assertEquals(ChatMessageKind.IMAGE, pending.semantics.kind)
    }

    @Test
    fun unchangedPollKeepsListIdentityAndHistory() {
        val history = message(1, time = 1L)
        val latest = message(2)
        val current = persistentListOf(history, latest)
        assertSame(current, mergeServerMessages(current, listOf(latest)))
    }

    @Test
    fun singleEchoWithoutKeyDoesNotReconcileEitherIdenticalSend() {
        val first = message(-1, local = true)
        val second = message(-2, time = 101_000L, local = true)
        val merged = mergeServerMessages(persistentListOf(first, second), listOf(message(10)))
        assertEquals(3, merged.size)
        assertEquals(2, merged.count { it.isLocal })
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
            .copy(mentions = listOf(mention), robotSkill = 7, msgKey = "mention-key")
        val state = ChatRoomUiState(messages = persistentListOf(local))
        val earlyEcho = message(10).copy(mentions = listOf(mention), robotSkill = 7, msgKey = "mention-key")
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
        assertEquals(3, confirmed.messages.size)
        assertEquals(2, confirmed.messages.count { it.isLocal })
        assertTrue(confirmed.messages.any { it.msgId == -2L && it.isLocal })
        assertSame(confirmed.messages, mergeServerMessages(confirmed.messages, listOf(message(10))))
    }

    @Test
    fun observedServerDeliveryWinsOverTransportTimeout() {
        val local = message(-1, local = true, state = SendState.SENDING).copy(msgKey = "observed-key")
        val polled = ChatRoomPartialChange.Poll.Success(persistentListOf(message(10).copy(msgKey = "observed-key")))
            .reduce(ChatRoomUiState(messages = persistentListOf(local)))
        val failed = ChatRoomPartialChange.Send.Failure(-1L, IllegalStateException("timeout")).reduce(polled)
        assertEquals(1, failed.messages.size)
        assertFalse(failed.messages.single().isLocal)
    }

    @Test
    fun cloudMemeRequiresOriginalHttpUrl() {
        listOf("", "pic-id", "/relative.gif", "file:///local.gif").forEach { url ->
            assertTrue(runCatching { ChatCloudMeme("id", url) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { ChatCloudMeme("", "https://example.com/a.gif") }.isFailure)
    }

    @Test
    fun uploadedCloudMemePayloadUsesChatUrlsAndKeepsOriginalForRetry() {
        val meme = ChatCloudMeme(
            id = "cloud-id",
            imageUrl = "https://cloud.example/original.gif",
            uploadedImageUrl = "https://chat.example/big.gif?token=1",
            uploadedThumbnailUrl = "https://chat.example/small.gif?token=1",
        )
        val request = ChatSendApi.buildMessagePayload(
            1L, "", 2L, 0, 123L, BlcpCrypto.userId2Bduk(42L), 1L,
            "测试用户", "portrait", "test-only", emptyList(), null, "test-zid", cloudMeme = meme,
        )
        val data = JSONObject(JSONObject(request.getString("content")).getString("text"))
        val body = JSONObject(data.getString("content_body"))
        assertEquals(meme.sendImageUrl, body.getString("url"))
        assertEquals(meme.sendThumbnailUrl, body.getString("thumbnail"))
        assertEquals("1", data.getString("content_type"))
        assertEquals("https://cloud.example/original.gif", meme.imageUrl)
    }

    @Test
    fun cloudMemePayloadIsImageNotTextLink() {
        val meme = ChatCloudMeme("cloud-id", "https://example.com/original.gif")
        val request = ChatSendApi.buildMessagePayload(
            1L, "", 2L, 0, 123L, BlcpCrypto.userId2Bduk(42L), 1L,
            "测试用户", "portrait", "test-only", emptyList(), null, "test-zid", cloudMeme = meme,
        )
        val data = JSONObject(JSONObject(request.getString("content")).getString("text"))
        assertEquals("1", data.getString("content_type"))
        val body = JSONObject(data.getString("content_body"))
        assertEquals(meme.imageUrl, body.getString("url"))
        assertEquals(meme.imageUrl, body.getString("thumbnail"))
        assertFalse(body.has("text"))
        val parsed = received(request)
        assertEquals(1, parsed.contentType)
        assertEquals(listOf(meme.imageUrl), parsed.images)
        assertEquals("", parsed.text)
    }

    @Test
    fun groupImagePrefersOriginalUrlOverThumbnailWithoutDuplicating() {
        val original = "https://example.com/original.gif"
        val request = payload()
        val envelope = JSONObject(request.getString("content"))
        val body = JSONObject(envelope.getString("text"))
            .put("content_type", "1")
            .put("content_body", JSONObject().put("url", original)
                .put("thumbnail", "https://example.com/preview.jpg").toString())
        envelope.put("text", body.toString())
        request.put("content", envelope.toString())
        assertEquals(listOf(original), received(request).images)
        body.put("content_body", JSONObject().put("thumbnail", original).toString())
        envelope.put("text", body.toString())
        request.put("content", envelope.toString())
        assertEquals(listOf(original), received(request).images)
    }

    @Test
    fun cloudMemeCannotSilentlyDiscardTextOrMentions() {
        val meme = ChatCloudMeme("cloud-id", "https://example.com/original.gif")
        listOf(
            Triple("正文", emptyList(), null),
            Triple("", listOf(ChatMention(1L, "uk", "用户", "portrait")), null),
            Triple("", emptyList(), 7),
        ).forEach { (text, mentions, skill) ->
            assertTrue(runCatching {
                ChatSendApi.buildMessagePayload(
                    1L, text, 2L, 0, 123L, BlcpCrypto.userId2Bduk(42L), 1L,
                    "测试用户", "portrait", "test-only", mentions, skill, "test-zid", cloudMeme = meme,
                )
            }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun cloudMemeFailureAndRetryPreserveOriginalPayload() {
        val meme = ChatCloudMeme("cloud-id", "https://example.com/original.gif")
        val started = ChatRoomPartialChange.Send.Start(
            -1L, "", 42L, "测试用户", "portrait", cloudMeme = meme,
        ).reduce(ChatRoomUiState())
        assertEquals(1, started.messages.single().contentType)
        assertEquals(listOf(meme.imageUrl), started.messages.single().images)
        val failed = ChatRoomPartialChange.Send.Failure(-1L, IllegalStateException("图片发送失败")).reduce(started)
        assertEquals(SendState.FAILED, failed.messages.single().sendState)
        assertEquals(meme, failed.messages.single().cloudMeme)
        val retried = ChatRoomPartialChange.Send.Start(
            -2L, "", 42L, "测试用户", "portrait", removeMsgId = -1L,
            cloudMeme = failed.messages.single().cloudMeme,
        ).reduce(failed)
        assertEquals(-2L, retried.messages.single().msgId)
        assertEquals(meme, retried.messages.single().cloudMeme)
        assertEquals(listOf(meme.imageUrl), retried.messages.single().images)
        assertEquals(2L, retried.sendRequestId)
        val accepted = ChatRoomPartialChange.Send.Success(-2L).reduce(retried).let {
            it.copy(messages = persistentListOf(it.messages.single().copy(msgKey = "image-retry-key")))
        }
        assertEquals(SendState.SENDING, accepted.messages.single().sendState)
        assertTrue(accepted.messages.single().transportAccepted)
        val confirmed = accepted.messages.single().copy(msgId = 101L, isLocal = false, sendState = SendState.SENT)
        val delivered = ChatRoomPartialChange.Poll.Success(persistentListOf(confirmed)).reduce(accepted)
        assertEquals(meme, delivered.messages.single().cloudMeme)
        assertEquals(-2L, delivered.messages.single().echoForPendingId)
        assertEquals(SendState.SENT, delivered.messages.single().sendState)
    }

    @Test
    fun preparedImageDoesNotClaimAnAlreadySeenEchoWithoutKey() {
        val original = "https://example.com/original.gif"
        val uploaded = "https://chat.example/uploaded.gif?token=1"
        val local = message(-1, time = 200_000L, text = "", local = true).copy(
            contentType = 1, images = listOf(original),
            cloudMeme = ChatCloudMeme("meme", original),
        )
        val seenEcho = local.copy(msgId = 10, isLocal = false, images = listOf(uploaded), msgTime = 200_001L)
        val beforePrepared = mergeServerMessages(listOf(local), listOf(seenEcho))
        assertEquals(2, beforePrepared.size)
        val prepared = ChatCloudMeme("meme", original, uploadedImageUrl = uploaded)
        val preparedState = ChatRoomPartialChange.Send.Prepared(-1L, prepared).reduce(
            ChatRoomUiState(messages = beforePrepared)
        )
        val afterPrepared = preparedState.messages
        assertEquals(setOf(-1L, 10L), afterPrepared.map { it.msgId }.toSet())
        assertTrue(afterPrepared.single { it.isLocal }.echoForPendingId == null)
        assertEquals(prepared, afterPrepared.single { it.isLocal }.cloudMeme)
        assertEquals(200_001L, afterPrepared.single { !it.isLocal }.msgTime)
    }

    @Test
    fun imageSeenBeforeTransmissionCannotBeClaimedByPreparedOrAccepted() {
        val uploaded = ChatCloudMeme("photo", "https://example.com/photo.jpg",
            uploadedImageUrl = "https://example.com/photo.jpg")
        val started = ChatRoomPartialChange.Send.Start(-1L, "", 42L, "self", "portrait",
            localPhotoUri = "content://photos/new"
        ).reduce(ChatRoomUiState())
        val old = started.messages.single().copy(msgId = 10L, isLocal = false,
            images = listOf(uploaded.sendImageUrl))
        val polled = ChatRoomPartialChange.Poll.Success(persistentListOf(old)).reduce(started)
        val prepared = ChatRoomPartialChange.Send.Prepared(-1L, uploaded).reduce(polled)
        assertEquals(2, prepared.messages.size)
        val transmitting = ChatRoomPartialChange.Send.Transmitting(-1L).reduce(prepared)
        val accepted = ChatRoomPartialChange.Send.Success(-1L, uploaded).reduce(transmitting)
        assertEquals(2, accepted.messages.size)
        val echo = old.copy(msgId = 11L, msgTime = System.currentTimeMillis())
        val confirmed = ChatRoomPartialChange.Poll.Success(persistentListOf(echo)).reduce(accepted)
        assertEquals(listOf(-1L, 10L, 11L), confirmed.messages.map { it.msgId }.sorted())
    }

    @Test
    fun preparingImageNeverConsumesAnOlderAlreadySeenMessage() {
        val local = message(-1, time = 200_000L, text = "", local = true).copy(
            contentType = 1, images = listOf("content://photos/new"),
        )
        val uploaded = "https://example.com/uploaded.gif"
        val old = local.copy(msgId = 10L, isLocal = false, msgTime = 100L,
            images = listOf(uploaded), displayTime = 100L)
        val state = ChatRoomPartialChange.Send.Prepared(-1L,
            ChatCloudMeme("prepared", uploaded, uploadedImageUrl = uploaded)
        ).reduce(ChatRoomUiState(messages = persistentListOf(old, local)))
        assertEquals(listOf(10L, -1L), state.messages.map { it.msgId })
        assertTrue(state.messages.last().isLocal)
    }

    @Test
    fun privatePhotoPreparationPreservesUploadedResourceForRetry() {
        val initial = PrivateChatPartialChange.Send.Start(-1L, "", 42L,
            localPhotoUri = "content://photos/new"
        ).reduce(PrivateChatUiState())
        assertEquals(ChatMessageKind.IMAGE, initial.messages.single().semantics.kind)
        val uploaded = ChatCloudMeme("photo", "https://example.com/photo.jpg",
            uploadedImageUrl = "https://example.com/photo.jpg")
        val prepared = PrivateChatPartialChange.Send.Prepared(-1L, uploaded).reduce(initial)
        val failed = PrivateChatPartialChange.Send.Failure(-1L, Exception("network")).reduce(prepared)
        assertEquals(uploaded, failed.messages.single().cloudMeme)
        assertEquals("content://photos/new", failed.messages.single().localPhotoUri)
        assertEquals(SendState.FAILED, failed.messages.single().sendState)
    }

    @Test
    fun stalePrivateFetchResultsCannotEnterAnotherAccountOrConversation() {
        val current = PrivateChatUiState(accountUid = 99L, peerUid = 8L)
        val stale = persistentListOf(privateMessage(1L, 7L, 100L, "旧消息"))
        assertEquals(current, PrivateChatPartialChange.Load.Cached(stale, 42L, 7L).reduce(current))
        assertEquals(current, PrivateChatPartialChange.Load.Success(stale, 42L, 7L).reduce(current))
        assertEquals(current, PrivateChatPartialChange.Poll.Success(stale, 42L, 7L).reduce(current))
    }

    @Test
    fun switchingPrivateAccountOrPeerClearsPreviousTimeline() {
        val old = PrivateChatUiState(messages = persistentListOf(privateMessage(1L, 7L, 100L, "旧消息")),
            accountUid = 42L, peerUid = 7L)
        val switched = PrivateChatPartialChange.Load.Start(99L, 8L).reduce(old)
        assertTrue(switched.messages.isEmpty())
        assertEquals(99L, switched.accountUid)
        assertEquals(8L, switched.peerUid)
    }

    @Test
    fun preparedImageKeepsASeenEchoFromBeingOverwrittenByLateFailure() {
        val original = "https://example.com/original.gif"
        val uploaded = "https://chat.example/uploaded.gif?token=1"
        val local = message(-1, text = "", local = true).copy(
            contentType = 1, images = listOf(original), cloudMeme = ChatCloudMeme("meme", original),
            msgKey = "prepared-exact-key",
        )
        val echo = local.copy(msgId = 10, isLocal = false, images = listOf(uploaded))
        val state = ChatRoomPartialChange.Send.Prepared(-1L,
            ChatCloudMeme("meme", original, uploadedImageUrl = uploaded)
        ).reduce(ChatRoomUiState(messages = persistentListOf(local)))
        val delivered = ChatRoomPartialChange.Poll.Success(persistentListOf(echo)).reduce(state)
        val failed = ChatRoomPartialChange.Send.Failure(-1L, Exception("late")).reduce(delivered)
        assertEquals(delivered, failed)
    }

    @Test
    fun imageEchoCannotConsumeDifferentImageOrEmptyTextMessage() {
        val first = message(-1, text = "", local = true).copy(
            contentType = 1, images = listOf("https://example.com/a.gif"),
        )
        val second = first.copy(msgId = -2, images = listOf("https://example.com/b.gif"))
        val text = message(-3, text = "", local = true)
        val echo = second.copy(msgId = 10, isLocal = false)
        val merged = mergeServerMessages(listOf(first, second, text), listOf(echo))
        assertEquals(4, merged.size)
        assertTrue(merged.any { it.msgId == -1L })
        assertTrue(merged.any { it.msgId == -2L })
        assertTrue(merged.any { it.msgId == -3L })
        assertEquals(null, merged.single { it.msgId == 10L }.echoForPendingId)
    }

    @Test
    fun sameImageEchoesAreMatchedOneToOneAndLateTimeoutCannotUndoDelivery() {
        val local = message(-1, text = "", local = true, state = SendState.SENDING).copy(
            contentType = 1, images = listOf("https://example.com/a.gif"), msgKey = "first-image-key",
        )
        val second = local.copy(msgId = -2, msgKey = "second-image-key")
        val echo = local.copy(msgId = 10, isLocal = false, sendState = SendState.SENT)
        val firstPoll = mergeServerMessages(listOf(local, second), listOf(echo))
        assertEquals(1, firstPoll.count { it.isLocal })
        val finalPoll = mergeServerMessages(firstPoll, listOf(echo, echo.copy(msgId = 11, msgKey = "second-image-key")))
        assertEquals(2, finalPoll.size)
        assertTrue(finalPoll.none { it.isLocal })
        assertEquals(setOf(-1L, -2L), finalPoll.map { it.echoForPendingId }.toSet())
        val state = ChatRoomUiState(messages = finalPoll)
        assertEquals(state, ChatRoomPartialChange.Send.Unconfirmed(-1L).reduce(state))
        assertEquals(state, ChatRoomPartialChange.Send.Failure(-2L, Exception("timeout")).reduce(state))
    }

    @Test
    fun alreadyBoundEchoCannotBeConsumedByAnotherPendingMessageOnPoll() {
        val first = message(-1, text = "", local = true).copy(
            contentType = 1, images = listOf("https://example.com/a.gif"),
        )
        val second = first.copy(msgId = -2)
        val boundEcho = first.copy(
            msgId = 10,
            isLocal = false,
            echoForPendingId = -1L,
        )
        val merged = mergeServerMessages(listOf(boundEcho, second), listOf(boundEcho))
        assertEquals(2, merged.size)
        assertEquals(-1L, merged.single { it.msgId == 10L }.echoForPendingId)
        assertEquals(-2L, merged.single { it.isLocal }.msgId)
    }

    @Test
    fun imageMatchingPreservesHostAndSignedQuery() {
        val local = message(-1, text = "", local = true).copy(
            contentType = 1, images = listOf("https://example.com/a.gif?token=one"),
        )
        val differentHost = local.copy(msgId = 10, isLocal = false,
            images = listOf("https://other.example.com/a.gif?token=one"))
        val differentQuery = local.copy(msgId = 11, isLocal = false,
            images = listOf("https://example.com/a.gif?token=two"))
        val differentPort = local.copy(msgId = 13, isLocal = false,
            images = listOf("https://example.com:8443/a.gif?token=one"))
        assertTrue(mergeServerMessages(listOf(local), listOf(differentHost, differentQuery, differentPort)).any { it.isLocal })
        val httpEcho = local.copy(msgId = 12, isLocal = false,
            images = listOf("http://example.com/a.gif?token=one"))
        val samePath = mergeServerMessages(listOf(local), listOf(httpEcho))
        assertEquals(2, samePath.size)
        assertTrue(samePath.any { it.isLocal })
    }

    @Test
    fun privateImageRequestUsesOfficialArrayAndMessageType() {
        val meme = ChatCloudMeme("cloud-id", "https://example.com/original.gif")
        val request = buildPrivateMessageRequest(42L, "", 100L, meme)
        val data = requireNotNull(request.data_)
        assertEquals(ChatWsConstants.MSG_TYPE_PRIVATE_IMAGE, data.msgType)
        assertEquals(42L, data.toUid)
        assertEquals(100L, data.recordId)
        val image = org.json.JSONArray(data.content).getJSONObject(0)
        assertEquals(meme.imageUrl, image.getString("url"))
        assertEquals(meme.imageUrl, image.getString("thumbnail"))
        assertEquals(listOf(meme.imageUrl), PrivateChatImageContent.parse(data.content.orEmpty(), 2))
        val text = buildPrivateMessageRequest(42L, "普通文字", 101L).data_!!
        assertEquals(ChatWsConstants.MSG_TYPE_PRIVATE, text.msgType)
        assertEquals("普通文字", text.content)
        assertTrue(PrivateChatImageContent.parse("https://example.com/a.gif", 1).isEmpty())
        assertTrue(runCatching { buildPrivateMessageRequest(42L, "不能丢掉的草稿", 102L, meme) }.isFailure)
    }

    @Test
    fun privateImageParserDoesNotDuplicateOriginalAndPreview() {
        val official = """[{"url":"https://example.com/original.gif","thumbnail":"https://example.com/small.gif","w":100,"h":80}]"""
        assertEquals(listOf("https://example.com/original.gif"), PrivateChatImageContent.parse(official, 2))
        val legacy = """[{"type":"3","src":"https://example.com/small.gif","big_src":"https://example.com/original.gif"}]"""
        assertEquals(listOf("https://example.com/original.gif"), PrivateChatImageContent.parse(legacy, 2))
        assertTrue(PrivateChatImageContent.parse(legacy, 1).isEmpty())
        assertTrue(PrivateChatImageContent.parse("not-json", 2).isEmpty())
    }

    @Test
    fun privateCloudMemeFailureRetryAndAckKeepImageIdentity() {
        val meme = ChatCloudMeme("cloud-id", "https://example.com/original.gif")
        val started = PrivateChatPartialChange.Send.Start(-1L, "", 42L, cloudMeme = meme).reduce(PrivateChatUiState())
        assertEquals(listOf(meme.imageUrl), started.messages.single().images)
        assertEquals(2, started.messages.single().msgType)
        val failed = PrivateChatPartialChange.Send.Failure(-1L, Exception("failed")).reduce(started)
        assertEquals(meme, failed.messages.single().cloudMeme)
        assertEquals(SendState.FAILED, failed.messages.single().sendState)
        val retried = PrivateChatPartialChange.Send.Start(-2L, "", 42L, removeMsgId = -1L,
            cloudMeme = failed.messages.single().cloudMeme).reduce(failed)
        assertEquals(1, retried.messages.size)
        val another = PrivateChatPartialChange.Send.Start(-3L, "", 42L, cloudMeme = meme).reduce(retried)
        val ack = another.messages.first { it.msgId == -2L }.copy(msgId = 101L, isLocal = false, sendState = SendState.SENT)
        val confirmed = PrivateChatPartialChange.Send.Success(-2L, ack).reduce(another)
        assertEquals(2, confirmed.messages.size)
        assertEquals(1, confirmed.messages.count { it.isLocal })
        assertEquals(meme, confirmed.messages.first { it.msgId == 101L }.cloudMeme)
        assertEquals(confirmed, PrivateChatPartialChange.Send.Failure(-2L, Exception("late")).reduce(confirmed))
        val polled = PrivateChatPartialChange.Poll.Success(persistentListOf(ack.copy(cloudMeme = null))).reduce(confirmed)
        assertEquals(meme, polled.messages.first { it.msgId == 101L }.cloudMeme)
        assertEquals(2, polled.messages.size)
    }

    private fun payload(mentions: List<ChatMention> = emptyList(), skill: Int? = null, text: String = "测试消息") =
        ChatSendApi.buildMessagePayload(
            chatroomId = 1L, text = text, forumId = 2L, forumLevel = 0, uk = 123L,
            bduk = BlcpCrypto.userId2Bduk(42L), triggerId = 1L, selfName = "测试用户",
            selfPortrait = "self-portrait", bduss = "test-only", mentions = mentions, robotSkill = skill,
            zid = "test-device-security-token",
        )

    private fun received(payload: JSONObject) = ChatApi.parseMessage(JSONObject().apply {
        put("msgid", 10L)
        put("from_user", 123L)
        put("type", 81)
        put("create_time", 100L)
        put("content", payload.getString("content"))
    }, 42L)!!

    @Test
    fun sendPayloadCarriesDeviceSecurityToken() {
        val safeExt = JSONObject(payload().getString("app_safe_ext"))
        assertEquals("test-device-security-token", safeExt.getJSONObject("haotianjing").getString("zid"))
    }

    @Test
    fun textPayloadUsesOfficialTextContentType() {
        val data = JSONObject(JSONObject(payload().getString("content")).getString("text"))
        // 群 type=81 内层与 SDK msgType 不同：文本为 0，图片为 1。
        assertEquals("0", data.getString("content_type"))
        assertFalse(JSONObject(data.getString("content_body")).has("url"))
    }

    @Test
    fun blankSecurityTokenCanConstructTextAndImagePayloads() {
        for (meme in listOf(null, ChatCloudMeme("image", "https://example.com/image.jpg"))) {
            val request = ChatSendApi.buildMessagePayload(
                1L, if (meme == null) "测试" else "", 2L, 0, 123L, BlcpCrypto.userId2Bduk(42L), 1L,
                "测试用户", "portrait", "test-only", emptyList(), null, "", cloudMeme = meme,
            )
            val safeExt = JSONObject(request.getString("app_safe_ext"))
            assertEquals("", safeExt.getJSONObject("haotianjing").getString("zid"))
            assertEquals(if (meme == null) ChatMessageKind.TEXT else ChatMessageKind.IMAGE,
                received(request).semantics.kind)
        }
    }

    @Test
    fun loginIdentityUsesRawBaiduUserIdNotImUk() {
        val identity = BlcpImIdentity.fromJson(JSONObject()
            .put("uk", 123L).put("bd_uid", "42").put("trigger_id", org.json.JSONArray().put(456L)))
        assertEquals(123L, identity.uk)
        assertEquals(42L, identity.userId)
        assertEquals(456L, identity.triggerId)
        assertEquals(42L, BlcpCrypto.bduk2UserId(BlcpCrypto.userId2Bduk(identity.userId)))
        for (field in listOf("uk", "bd_uid", "trigger_id")) {
            val invalid = JSONObject().put("uk", 123L).put("bd_uid", 42L)
                .put("trigger_id", org.json.JSONArray().put(456L))
            invalid.remove(field)
            assertTrue(runCatching { BlcpImIdentity.fromJson(invalid) }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test
    fun businessResponseRequiresExplicitValidSuccessCode() {
        val client = BlcpClient()
        for (body in listOf("", "{}", "{\"err_code\":null}", "{\"err_code\":\"invalid\"}", "{\"err_code\":123,\"msg\":\"denied\"}")) {
            assertTrue(runCatching {
                client.parseBusinessResponse(body.toByteArray(), "send")
            }.exceptionOrNull() is IllegalStateException)
        }
        assertEquals(0, client.parseBusinessResponse("{\"err_code\":0}".toByteArray(), "send").getInt("err_code"))
        assertEquals("0", client.parseBusinessResponse("{\"err_code\":\"0\"}".toByteArray(), "send").getString("err_code"))
    }

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
    fun sharedPostFallsBackToTitleAndUrlWhenBodyIsEmpty() {
        val url = "https://tieba.baidu.com/p/123456789?pid=987654321"
        listOf<Any>("", JSONObject.NULL, JSONObject(), org.json.JSONArray()).forEach { empty ->
            val body = JSONObject().put("content_body", empty).put("text", empty)
                .put("title", "分享的帖子").put("url", url)
            val message = ChatApi.parseMessage(JSONObject().put("from_user", 123L)
                .put("type", 81).put("content", JSONObject().put("text", body)), null)!!
            assertEquals("分享的帖子\n$url", message.text)
            assertTrue(message.images.isEmpty())
            assertEquals(81, message.contentType)
        }
    }

    @Test
    fun nestedShareContentCanBeJsonEncodedWithoutShowingMetadata() {
        val url = "https://tieba.baidu.com/p/123456789"
        val share = JSONObject().put("title", "分享标题").put("link", url)
        val body = JSONObject().put("content_body", "")
            .put("share_info", share.toString())
            .put("ext", JSONObject().put("url", "https://example.com/profile"))
        val message = ChatApi.parseMessage(JSONObject().put("from_user", 123L)
            .put("type", 81).put("content", JSONObject().put("text", body.toString()).toString()), null)!!
        assertEquals("分享标题\n$url", message.text)
        assertFalse(message.text.contains("profile"))
    }

    @Test
    fun sharedPostUrlIsNotLostWhenTextIsPresent() {
        val url = "https://tieba.baidu.com/p/123456789"
        val message = ChatApi.parseMessage(JSONObject().put("from_user", 123L).put("type", 81)
            .put("content", JSONObject().put("text", JSONObject().put("text", "帖子标题")
                .put("url", url))), null)!!
        assertEquals("帖子标题\n$url", message.text)
    }

    @Test
    fun emptyBodyFallsThroughToNonEmptyText() {
        val message = ChatApi.parseMessage(JSONObject().put("from_user", 123L).put("type", 81)
            .put("content", JSONObject().put("text", JSONObject()
                .put("content_body", "").put("text", "普通文字").put("content_type", 0))), null)!!
        assertEquals("普通文字", message.text)
        assertEquals(0, message.contentType)
    }

    @Test
    fun chatPostLinksHaveClickableAnnotationsAndRetainQuery() {
        val url = "https://tieba.baidu.com/p/123456789?pid=987654321&see_lz=1"
        val text = "分享的帖子\n$url。"
        val annotated = annotateChatLinks(text)
        assertEquals(text, annotated.text)
        val link = annotated.getStringAnnotations("url", 0, text.length).single()
        assertEquals(url, link.item)
        assertEquals(url, annotated.text.substring(link.start, link.end))
        val plain = annotateChatLinks("普通文字 (#滑稽)")
        assertTrue(plain.getStringAnnotations("url", 0, plain.length).isEmpty())
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
        // 线上群图片消息：content_type=1，content_body 使用 url/thumbnail。
        val parsed = received(imagePayload(1, JSONObject().put("url", url).toString()))
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
        // 官方图片消息：图片地址在 content_body.url，说明文字与图片分开保存。
        val parsed = received(imagePayload(1,
            JSONObject().put("url", url).put("text", "第一行  说明\n第二行").toString()))
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
    fun privateKnownSessionRetainsSelfHistoryButUnknownSelfHistoryIsDropped() {
        val self = privateMessage(3L, 42L, 300L, "自己发送").copy(isSelf = true)
        val cached = self.copy(peerUid = 7L)
        val incoming = listOf(self, privateMessage(4L, 7L, 400L, "来信"))
        assertEquals(listOf(incoming[0], incoming[1]), incoming.forPrivatePeer(7L, listOf(cached)))
        assertEquals(listOf(incoming[1]), incoming.forPrivatePeer(7L))
    }

    @Test
    fun privateSessionsUseLatestKnownMessageAndIgnoreUnknownNetworkSelfHistory() {
        val incoming = privateMessage(1L, 7L, 100L, "来信")
        val knownSelf = ChatWsMessage(
            msgId = 2L, peerUid = 7L, peerName = "用户7", peerPortrait = "portrait-7",
            content = "自己发送", msgTime = 200L, isSelf = true,
            semantics = ChatMessageSemantics(protocolSource = "local_outgoing"),
        )
        val unknownSelf = privateMessage(3L, 42L, 300L, "未知会话").copy(isSelf = true)
        val sessions = listOf(incoming, knownSelf, unknownSelf).toSessions(selfUid = 42L)
        assertEquals(1, sessions.size)
        assertEquals(7L, sessions.single().peerUid)
        assertEquals("自己发送", sessions.single().lastMessage)
        assertEquals(200L, sessions.single().lastTime)
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
            .copy(mentions = listOf(mention), robotSkill = 7, msgKey = "mention-key")
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

    @Test
    fun groupVoiceMessageNeverBecomesImage() {
        val voiceUrl = "https://tiebac.baidu.com/c/p/voice?voice_md5=abc123&play_from=pb_voice_play"
        val voiceBody = JSONObject()
            .put("voice_md5", "abc123")
            .put("during_time", 8000)
            .put("src", voiceUrl)
        // 未验证的群类型不套用 SDK 枚举；语音由 voice_md5/音频地址识别，原始类型仍保留。
        val voiceWithUrl = received(imagePayload(2, voiceBody.toString(), voiceUrl))
        assertEquals(2, voiceWithUrl.contentType)
        assertEquals(2, voiceWithUrl.semantics.businessType)
        assertEquals(ChatMessageKind.VOICE, voiceWithUrl.semantics.kind)
        assertTrue(voiceWithUrl.images.isEmpty())
        assertEquals(voiceUrl, voiceWithUrl.voiceUrl)
        assertEquals(8000, voiceWithUrl.voiceDurationMs)
        assertEquals("", voiceWithUrl.text)

        // 只给 voice_md5 时，按回帖语音气泡相同的地址格式拼接。
        val md5Only = received(
            imagePayload(2, JSONObject().put("voice_md5", "abc123").put("during_time", 8).toString())
        )
        assertEquals(ChatMessageKind.VOICE, md5Only.semantics.kind)
        assertEquals(
            "https://tiebac.baidu.com/c/p/voice?voice_md5=abc123&play_from=pb_voice_play",
            md5Only.voiceUrl,
        )
        assertEquals(8000, md5Only.voiceDurationMs)

        // 即使类型值与群图片重叠，明确语音证据仍优先，不能渲染为图片。
        val voiceWithImageType = received(imagePayload(1, voiceBody.toString(), voiceUrl))
        assertEquals(ChatMessageKind.VOICE, voiceWithImageType.semantics.kind)
        assertEquals(1, voiceWithImageType.semantics.businessType)
        assertEquals(voiceUrl, voiceWithImageType.voiceUrl)
        assertTrue(voiceWithImageType.images.isEmpty())

        val audioUrlOnly = received(imagePayload(2, JSONObject().put("src", "https://example.com/clip.amr").toString()))
        assertEquals(ChatMessageKind.VOICE, audioUrlOnly.semantics.kind)
        assertTrue(audioUrlOnly.images.isEmpty())
        assertEquals("https://example.com/clip.amr", audioUrlOnly.voiceUrl)
        assertEquals("", audioUrlOnly.text)
    }

    @Test
    fun groupSendImagePayloadUsesActualDimensionsWhenProvided() {
        val memeWithSize = ChatCloudMeme(
            id = "m1",
            imageUrl = "https://example.com/big.jpg",
            width = 640,
            height = 480,
        )
        val payload = ChatSendApi.buildMessagePayload(
            chatroomId = 1L, text = "", forumId = 2L, forumLevel = 0, uk = 123L,
            bduk = BlcpCrypto.userId2Bduk(42L), triggerId = 1L, selfName = "测试用户",
            selfPortrait = "self-portrait", bduss = "test-only", mentions = emptyList(), robotSkill = null,
            zid = "test-device-security-token", cloudMeme = memeWithSize,
        )
        val textJson = JSONObject(JSONObject(payload.getString("content")).getString("text"))
        val contentBody = JSONObject(textJson.getString("content_body"))
        assertEquals(640, contentBody.getInt("w"))
        assertEquals(480, contentBody.getInt("h"))
        assertEquals(memeWithSize.sendImageUrl, contentBody.getString("url"))
        assertEquals("1", textJson.getString("content_type"))
    }
}
