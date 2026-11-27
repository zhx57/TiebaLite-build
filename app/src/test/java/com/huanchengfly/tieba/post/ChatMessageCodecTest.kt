package com.huanchengfly.tieba.post.repository

import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageKind
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageSemantics
import com.huanchengfly.tieba.post.api.models.chat.ChatRoomMessage
import com.huanchengfly.tieba.post.api.models.chat.SendState
import org.junit.Assert.*
import org.junit.Test

class ChatMessageCodecTest {
    @Test
    fun privateRoundTripPreservesAttachmentsSemanticsAndSendMetadata() {
        val message = ChatWsMessage(
            7, 2, "对端", "头像", "内容", 100, true,
            sendState = SendState.UNKNOWN, isLocal = true, images = listOf("https://example.com/a"),
            localPhotoUri = "file:///data/local/image", conversationUid = 2, senderUid = 1,
            semantics = ChatMessageSemantics(kind = ChatMessageKind.IMAGE, rawPayload = "原始语义"),
            deliveryConfirmed = false, localId = "stable", msgKey = "key", sendError = "结果未确认",
            transmissionStarted = true,
        )
        assertEquals(message, ChatMessageCodec.decodePrivate(ChatMessageCodec.encodePrivate(message)))
    }

    @Test
    fun retryResetsTransmissionMetadataButLateFailureCannotRegressSent() {
        val failed = ChatWsMessage(-1, 2, "", "", "消息", 100, true,
            sendState = SendState.UNKNOWN, isLocal = true, conversationUid = 2,
            localId = "stable-private", msgKey = "key-private", transmissionStarted = true)
        val retry = ChatMessageCodec.mergePrivate(failed,
            failed.copy(sendState = SendState.SENDING, transmissionStarted = false))
        assertFalse(retry.transmissionStarted)
        assertEquals("stable-private", retry.localId)
        assertEquals("key-private", retry.msgKey)
        val sent = ChatMessageCodec.mergePrivate(retry,
            retry.copy(msgId = 9, sendState = SendState.SENT, isLocal = false, deliveryConfirmed = true))
        assertEquals(sent, ChatMessageCodec.mergePrivate(sent, failed))
        val group = ChatRoomMessage(-2, 1, "", "", 0, "消息", 100, true,
            sendState = SendState.UNKNOWN, isLocal = true, localId = "stable-group",
            transmissionStarted = true, transportAccepted = true)
        val groupRetry = ChatMessageCodec.mergeGroup(group,
            group.copy(sendState = SendState.SENDING, transmissionStarted = false, transportAccepted = false))
        assertFalse(groupRetry.transmissionStarted)
        assertFalse(groupRetry.transportAccepted)
    }

    @Test
    fun groupRoundTripAndMergeAllowRetryButNeverRegressSent() {
        val failed = ChatRoomMessage(-1, 1, "用户", "", 0, "相同内容", 100, true,
            sendState = SendState.FAILED, isLocal = true, localId = "stable", msgKey = "key",
            localPhotoUri = "file:///data/local/image")
        assertEquals(failed, ChatMessageCodec.decodeGroup(ChatMessageCodec.encodeGroup(failed)))
        val retry = ChatMessageCodec.mergeGroup(failed, failed.copy(sendState = SendState.SENDING))
        assertEquals(SendState.SENDING, retry.sendState)
        val sent = ChatMessageCodec.mergeGroup(retry,
            failed.copy(msgId = 55, isLocal = false, sendState = SendState.SENT, localId = "echo", localPhotoUri = null))
        assertEquals("stable", sent.localId)
        assertEquals(failed.localPhotoUri, sent.localPhotoUri)
        assertEquals(sent, ChatMessageCodec.mergeGroup(sent, failed.copy(sendState = SendState.UNKNOWN)))
        assertEquals(100L, sent.msgTime)
    }
}
