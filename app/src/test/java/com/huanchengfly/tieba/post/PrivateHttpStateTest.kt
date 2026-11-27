package com.huanchengfly.tieba.post.ui.page.chat

import com.huanchengfly.tieba.post.api.chat.http.ImMessageApi
import com.huanchengfly.tieba.post.api.chat.ws.privateConversationUid
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.*
import org.junit.Test

class PrivateHttpStateTest {
    private val selfUid = 100L
    private val peerUid = 42L

    @Test
    fun outgoingHttpHistoryHasKnownConversationWithoutCachedMessages() {
        val message = requireNotNull(httpMessage(selfUid).toChatWsMessage(selfUid, peerUid))
        assertTrue(message.isSelf)
        assertEquals(selfUid, message.senderUid)
        assertEquals(peerUid, message.peerUid)
        assertEquals(peerUid, message.conversationUid)
        assertEquals(peerUid, message.privateConversationUid(selfUid))
        val state = PrivateChatPartialChange.Load.Success(
            persistentListOf(message), selfUid, peerUid,
        ).reduce(PrivateChatUiState(accountUid = selfUid, peerUid = peerUid))
        assertEquals(listOf(message), state.messages)
        assertFalse(state.isLoading)
    }

    @Test
    fun incomingHttpHistoryKeepsSenderSeparateFromConversation() {
        val message = requireNotNull(httpMessage(peerUid).toChatWsMessage(selfUid, peerUid))
        assertFalse(message.isSelf)
        assertEquals(peerUid, message.senderUid)
        assertEquals(peerUid, message.conversationUid)
    }

    @Test
    fun unknownAndUnrelatedHttpSendersAreRejected() {
        for (sender in listOf(0L, -1L, 999L)) {
            assertNull(httpMessage(sender).toChatWsMessage(selfUid, peerUid))
        }
        assertNull(httpMessage(selfUid).toChatWsMessage(0L, peerUid))
        assertNull(httpMessage(selfUid).toChatWsMessage(selfUid, 0L))
    }

    @Test
    fun listLoadStartClearsPreviousAccountAndError() {
        val old = PrivateChatListUiState(
            sessions = persistentListOf(session()), accountUid = selfUid, error = "old error",
        )
        val state = PrivateChatListPartialChange.Load.Start(200L).reduce(old)
        assertEquals(200L, state.accountUid)
        assertTrue(state.isLoading)
        assertTrue(state.sessions.isEmpty())
        assertNull(state.error)
    }

    @Test
    fun accountChangeClearsImmediatelyWithoutWaitingForLoad() {
        val old = PrivateChatListUiState(
            isLoading = true, sessions = persistentListOf(session()), accountUid = selfUid,
        )
        val state = PrivateChatListPartialChange.AccountChanged(200L).reduce(old)
        assertEquals(200L, state.accountUid)
        assertTrue(state.sessions.isEmpty())
        assertFalse(state.isLoading)
        assertEquals(state, PrivateChatListPartialChange.AccountChanged(200L).reduce(state))
        assertTrue(PrivateChatListPartialChange.AccountChanged(0L).reduce(old).sessions.isEmpty())
    }

    @Test
    fun lateSuccessAndFailureFromOldAccountAreIgnored() {
        val state = PrivateChatListUiState(accountUid = 200L, isLoading = true)
        assertEquals(state, PrivateChatListPartialChange.Load.Success(
            persistentListOf(session()), selfUid,
        ).reduce(state))
        assertEquals(state, PrivateChatListPartialChange.Load.Failure(
            IllegalStateException("old failure"), selfUid,
        ).reduce(state))
        // 默认账号仅适用于未绑定账号的旧测试构造，不是隔离校验的通配符。
        assertEquals(state, PrivateChatListPartialChange.Load.Success(
            persistentListOf(session()),
        ).reduce(state))
    }

    @Test
    fun sameTimestampIncomingMetadataReplacesOldUnreadCount() {
        val old = session().copy(unreadCount = 3, peerName = "old")
        val incoming = old.copy(unreadCount = 0, peerName = "new", lastMessage = "updated")
        val state = PrivateChatListPartialChange.Load.Success(
            persistentListOf(incoming), selfUid,
        ).reduce(PrivateChatListUiState(sessions = persistentListOf(old), accountUid = selfUid))
        assertEquals(listOf(incoming), state.sessions)
    }

    @Test
    fun olderResponseDoesNotReplaceNewerSessionAndOrderIsDescending() {
        val latest = session().copy(lastTime = 200L)
        val other = session().copy(peerUid = 43L, lastTime = 300L)
        val state = PrivateChatListPartialChange.Load.Success(
            persistentListOf(session(), other), selfUid,
        ).reduce(PrivateChatListUiState(sessions = persistentListOf(latest), accountUid = selfUid))
        assertEquals(listOf(other, latest), state.sessions)
    }

    @Test
    fun failureRetryAndEmptySuccessHaveExplicitStates() {
        val started = PrivateChatListPartialChange.Load.Start(selfUid).reduce(PrivateChatListUiState())
        val failed = PrivateChatListPartialChange.Load.Failure(
            IllegalStateException("network error"), selfUid,
        ).reduce(started)
        assertFalse(failed.isLoading)
        assertEquals("network error", failed.error)
        val retry = PrivateChatListPartialChange.Load.Start(selfUid).reduce(failed)
        assertTrue(retry.isLoading)
        assertNull(retry.error)
        val empty = PrivateChatListPartialChange.Load.Success(
            persistentListOf(), selfUid,
        ).reduce(retry)
        assertFalse(empty.isLoading)
        assertTrue(empty.sessions.isEmpty())
        assertNull(empty.error)
    }

    @Test
    fun legacyUnboundStateConstructorsRemainCompatible() {
        val state = PrivateChatListPartialChange.Load.Success(persistentListOf(session()))
            .reduce(PrivateChatListUiState())
        assertEquals(listOf(session()), state.sessions)
        assertEquals(0L, state.accountUid)
    }

    @Test
    fun refreshIsVisibilityAndLifecycleBoundWithSerialConflation() {
        val page = source("ui/page/chat/PrivateChatListPage.kt")
        assertTrue(page.contains("LocalShouldLoad.current"))
        assertTrue(page.contains("if (!shouldLoad) return@LaunchedEffect"))
        assertTrue(page.contains("repeatOnLifecycle(Lifecycle.State.STARTED)"))
        assertTrue(page.contains("LaunchedEffect(accountUid)"))
        assertTrue(page.contains("PrivateChatListUiIntent.AccountChanged(accountUid)"))
        assertTrue(page.contains("state.accountUid == accountUid"))
        assertTrue(page.contains("PullToRefreshLayout("))
        val producer = source("ui/page/chat/PrivateChatListViewModel.kt")
        assertTrue(Regex("\\.conflate\\(\\)\\s*\\.flatMapConcat").containsMatchIn(producer))
        assertFalse(producer.contains("flatMapLatest"))
    }

    @Test
    fun privateNotificationUsesAbsoluteCountAndClearsZero() {
        val receiver = source("MainActivityV2.kt")
            .substringAfter("if (channel == NotifyJobService.CHANNEL_PRIVATE) {")
            .substringBefore("} else if")
        assertTrue(receiver.contains("foregroundPrivateCount = count.coerceAtLeast(0)"))
        assertFalse(receiver.contains("foregroundPrivateCount +="))
        assertTrue(receiver.contains("intent.getLongExtra(\"account_uid\", 0L)"))
        assertTrue(receiver.contains("AccountUtil.getUid()?.toLongOrNull()"))
        val service = source("services/NotifyJobService.kt")
            .substringAfter("suspend fun consumePrivateMessages")
        assertTrue(service.contains("it.isPrivateChat && it.peerUid > 0L"))
        val broadcast = service.indexOf(".putExtra(\"count\", incoming)")
        val zero = service.indexOf("if (incoming == 0)")
        val accountGuard = service.indexOf("AccountUtil.getBduss() != accountToken")
        assertTrue(accountGuard >= 0 && accountGuard < broadcast)
        assertTrue(service.contains(".putExtra(\"account_uid\", accountUid)"))
        assertTrue(broadcast >= 0 && zero > broadcast)
        assertTrue(service.substringAfter("if (incoming == 0)").substringBefore("return")
            .contains("manager.cancel(ID_PRIVATE)"))
    }

    private fun source(path: String): String {
        val app = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main/java").isDirectory }
        return File(app, "src/main/java/com/huanchengfly/tieba/post/$path").readText()
    }

    private fun session() = PrivateSession(peerUid, "peer", "", "hello", 100L)

    private fun httpMessage(senderUid: Long) = ImMessageApi.Message(
        msgId = 1L,
        fromImUk = 123L,
        fromUid = senderUid,
        category = 0,
        type = 0,
        text = "hello",
        imageUrl = "",
        thumbnailUrl = "",
        msgTime = 100L,
        rawContent = "{\"text\":\"hello\"}",
        msgKey = "key",
    )
}
