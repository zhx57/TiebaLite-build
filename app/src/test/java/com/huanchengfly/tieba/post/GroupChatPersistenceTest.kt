package com.huanchengfly.tieba.post.ui.page.chat

import com.huanchengfly.tieba.post.api.chat.blcp.BlcpBusinessException
import com.huanchengfly.tieba.post.api.models.chat.ChatRoomMessage
import com.huanchengfly.tieba.post.api.models.chat.SendState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 持久化页面边界的纯逻辑回归；数据库事务由仓库测试覆盖。 */
class GroupChatPersistenceTest {
    private fun message(id: Long, local: Boolean, key: String = "") = ChatRoomMessage(
        msgId = id, senderUid = 42L, senderName = "测试", senderPortrait = "",
        contentType = 0, text = "相同正文", msgTime = 1000L, isSelf = true,
        isLocal = local, sendState = if (local) SendState.UNKNOWN else SendState.SENT,
        localId = if (local) "local-$id" else "server-$id", msgKey = key,
    )

    @Test
    fun snapshotsReplaceWindowWithoutMergingAnOldAccount() {
        val previous = ChatRoomPartialChange.OpenSession("old", 42L, 7L).reduce(ChatRoomUiState())
        val current = ChatRoomPartialChange.OpenSession("new", 99L, 7L).reduce(previous)
        val stale = ChatRoomPartialChange.ForSession("old",
            ChatRoomPartialChange.LocalSnapshot(persistentListOf(message(1, false))))
        assertEquals(current, stale.reduce(current))
        val snapshot = ChatRoomPartialChange.ForSession("new",
            ChatRoomPartialChange.LocalSnapshot(persistentListOf(message(2, false))))
        assertEquals(listOf(2L), snapshot.reduce(current).messages.map { it.msgId })
    }

    @Test
    fun equalContentWithoutExactKeyNeverConfirmsUnknown() {
        val local = message(-1, true)
        val merged = mergeServerMessages(listOf(local), listOf(message(10, false)))
        assertEquals(2, merged.size)
        assertEquals(SendState.UNKNOWN, merged.single { it.isLocal }.sendState)
    }

    @Test
    fun exactKeyConfirmsOnlyItsOwnMessageAndKeepsStableIdentity() {
        val first = message(-1, true, "key-a")
        val second = message(-2, true, "key-b")
        val merged = mergeServerMessages(listOf(first, second), listOf(message(10, false, "key-a")))
        assertEquals(2, merged.size)
        val confirmed = merged.single { !it.isLocal }
        assertEquals(first.localId, confirmed.localId)
        assertEquals(-1L, confirmed.echoForPendingId)
        assertEquals(SendState.SENT, confirmed.sendState)
        assertEquals(second, merged.single { it.isLocal })
        assertEquals(merged, mergeServerMessages(merged, listOf(message(10, false, "key-a"))))
    }

    @Test
    fun transmissionDistinguishesBusinessRejectionFromUncertainFailure() {
        val network = java.io.IOException("断开")
        assertEquals(SendState.FAILED, groupSendFailureState(false, network))
        assertEquals(SendState.UNKNOWN, groupSendFailureState(true, network))
        val rejected = IllegalStateException("发送失败", BlcpBusinessException(123, "拒绝"))
        assertEquals(SendState.FAILED, groupSendFailureState(true, rejected))
    }

    @Test
    fun localSnapshotAndNetworkFailureDoNotEnableAnUninitializedComposer() {
        val opened = ChatRoomPartialChange.OpenSession("session", 42L, 7L).reduce(ChatRoomUiState())
        val cached = ChatRoomPartialChange.LocalSnapshot(persistentListOf()).reduce(opened)
        assertFalse(cached.isLoading)
        assertFalse(cached.ready)
        val ready = ChatRoomPartialChange.Ready(true).reduce(cached)
        val offline = ChatRoomPartialChange.Load.Failure(java.io.IOException("离线")).reduce(ready)
        assertTrue(offline.ready)
        assertFalse(ChatRoomPartialChange.Ready(false).reduce(offline).ready)
        assertFalse(ChatRoomPartialChange.OpenSession("next", 99L, 7L).reduce(offline).ready)
    }

    @Test
    fun onlyPersistenceReceiptAcknowledgesItsDraftAndStaleReceiptIsIgnored() {
        val opened = ChatRoomPartialChange.OpenSession("session", 42L, 7L).reduce(ChatRoomUiState())
        val failed = ChatRoomPartialChange.DraftResult("draft-a", false).reduce(opened)
        assertEquals("draft-a", failed.completedDraftId)
        assertFalse(failed.draftPersisted)
        val accepted = ChatRoomPartialChange.DraftResult("draft-a", true).reduce(failed)
        assertTrue(accepted.draftPersisted)
        val switched = ChatRoomPartialChange.OpenSession("new", 99L, 7L).reduce(accepted)
        val stale = ChatRoomPartialChange.ForSession("session", ChatRoomPartialChange.DraftResult("draft-a", true))
        assertEquals(switched, stale.reduce(switched))
        assertEquals("", switched.completedDraftId)
    }

    @Test
    fun pendingIdsDoNotRestartAtMinusOneAcrossProducerRecreation() {
        val ids = (1..1000).map { newGroupPendingId() }
        assertTrue(ids.all { it < 0 })
        assertFalse(ids.contains(0L))
        assertEquals(ids.size, ids.toSet().size)
    }
}
