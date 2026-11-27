package com.huanchengfly.tieba.post.ui.page.chat

import com.huanchengfly.tieba.post.api.chat.http.ImMessageApi
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.api.models.chat.SendState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.*
import org.junit.Test

class PrivateChatPersistenceTest {
    private fun pending(id: Long = -1L, key: String = "key", localId: String = "local") = ChatWsMessage(
        msgId = id, peerUid = 42L, conversationUid = 42L, senderUid = 7L,
        peerName = "", peerPortrait = "", content = "相同内容", msgTime = 100L,
        isSelf = true, isLocal = true, deliveryConfirmed = false, sendState = SendState.UNKNOWN,
        localId = localId, msgKey = key, sendError = "结果未确认", transmissionStarted = true,
    )

    @Test
    fun serverEchoMatchesPersistedKeyAndPreservesLogicalIdentity() {
        val local = pending()
        val echo = local.copy(msgId = 100L, localId = "", isLocal = false, deliveryConfirmed = true,
            sendState = SendState.SENT, msgTime = 200L)
        val result = mergePrivateMessages(listOf(local), listOf(echo)).single()
        assertEquals("local", result.localId)
        assertEquals("key", result.msgKey)
        assertEquals(100L, result.msgId)
        assertEquals(SendState.SENT, result.sendState)
        assertNull(result.sendError)
        assertEquals(200L, listOf(result).privateHttpCursorTimeMillis())
    }

    @Test
    fun lateAckCannotEraseServerConfirmedCursor() {
        val local = pending()
        val echo = local.copy(msgId = 100L, isLocal = false, deliveryConfirmed = true,
            sendState = SendState.SENT, msgTime = 200L)
        val ack = local.copy(msgId = 100L, isLocal = false, sendState = SendState.SENT)
        val result = mergePrivateMessages(listOf(echo), listOf(ack)).single()
        assertTrue(result.deliveryConfirmed)
        assertEquals(200L, result.msgTime)
        assertEquals(200L, listOf(result).privateHttpCursorTimeMillis())
    }

    @Test
    fun equalContentWithDifferentKeysNeverConfirmsOtherPending() {
        val first = pending()
        val second = pending(-2L, "other-key", "other-local")
        val echo = first.copy(msgId = 100L, localId = "", isLocal = false,
            sendState = SendState.SENT, deliveryConfirmed = true)
        val result = mergePrivateMessages(listOf(first, second), listOf(echo))
        assertEquals(2, result.size)
        assertEquals(SendState.UNKNOWN, result.first { it.localId == second.localId }.sendState)
    }

    @Test
    fun lateFailureNeverRevertsConfirmedMessage() {
        val local = pending()
        val echo = local.copy(msgId = 100L, isLocal = false, sendState = SendState.SENT, deliveryConfirmed = true)
        val result = mergePrivateMessages(listOf(echo), listOf(local.copy(sendState = SendState.FAILED))).single()
        assertEquals(SendState.SENT, result.sendState)
        assertEquals(100L, result.msgId)
        assertNull(result.sendError)
    }

    @Test
    fun databaseSnapshotReplacesObsoleteNegativePlaceholderAndRejectsOtherSession() {
        val initial = PrivateChatUiState(accountUid = 7L, peerUid = 42L, messages = persistentListOf(pending()))
        val sent = pending().copy(msgId = 100L, isLocal = false, sendState = SendState.SENT)
        val snapshot = PrivateChatPartialChange.Snapshot(persistentListOf(sent), 7L, 42L, 120)
        assertEquals(listOf(100L), snapshot.reduce(initial).messages.map { it.msgId })
        assertEquals(initial, snapshot.copy(accountUid = 8L).reduce(initial))
        assertEquals(initial, snapshot.copy(peerUid = 43L).reduce(initial))
        assertEquals(initial, snapshot.copy(active = false).reduce(initial))
    }

    @Test
    fun sharedMappingRejectsUnownedMessages() {
        val message = ImMessageApi.Message(100L, 1L, 99L, 0, 0, "", "", "", 200L, "{}", "key")
        assertNull(ImMessageApi.toChatMessage(message, 7L, 42L))
        assertNull(ImMessageApi.toChatMessage(message.copy(fromUid = 0L), 7L, 42L))
        assertNull(ImMessageApi.toChatMessage(message.copy(fromUid = 42L, msgId = 0L), 7L, 42L))
        assertNull(ImMessageApi.toChatMessage(message.copy(fromUid = 7L), 7L, 7L))
    }

    @Test
    fun sharedMappingKeepsMessageKeySenderAndVoiceSemantics() {
        val incoming = ImMessageApi.Message(100L, 1L, 7L, 0, 2, "", "", "", 200L, "{}", "key")
        val result = requireNotNull(ImMessageApi.toChatMessage(incoming, 7L, 42L))
        assertEquals("key", result.msgKey)
        assertEquals(7L, result.senderUid)
        assertEquals(42L, result.conversationUid)
        assertTrue(result.isSelf)
        assertEquals(com.huanchengfly.tieba.post.api.models.chat.ChatMessageKind.VOICE, result.semantics.kind)
        assertEquals("{}", result.semantics.rawPayload)
    }
}
