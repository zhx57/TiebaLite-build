package com.huanchengfly.tieba.post.ui.page.chat

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.api.chat.http.ImHttpException
import com.huanchengfly.tieba.post.api.chat.http.ImMessageApi
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsConstants
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.api.chat.ws.privateConversationUid
import com.huanchengfly.tieba.post.api.models.chat.ChatCloudMeme
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageKind
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageSemantics
import com.huanchengfly.tieba.post.api.models.chat.SendState
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.components.uploadChatPhoto
import com.huanchengfly.tieba.post.repository.ChatHistoryRepository
import com.huanchengfly.tieba.post.utils.AccountUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@Stable
@HiltViewModel
class PrivateChatViewModel @Inject constructor(private val history: ChatHistoryRepository) :
    BaseViewModel<PrivateChatUiIntent, PrivateChatPartialChange, PrivateChatUiState, PrivateChatUiEvent>() {
    override fun createInitialState() = PrivateChatUiState()
    override fun createPartialChangeProducer(): PartialChangeProducer<PrivateChatUiIntent, PrivateChatPartialChange, PrivateChatUiState> =
        PrivateChatPartialChangeProducer { history }

    override fun dispatchEvent(partialChange: PrivateChatPartialChange): UiEvent? = when (partialChange) {
        is PrivateChatPartialChange.Load.Failure -> CommonUiEvent.Toast("加载私信失败，已保留本地历史")
        is PrivateChatPartialChange.Send.Failure -> CommonUiEvent.Toast("发送未完成，请查看消息状态后重试")
        else -> null
    }

    private class PrivateChatPartialChangeProducer(private val historyProvider: () -> ChatHistoryRepository) :
        PartialChangeProducer<PrivateChatUiIntent, PrivateChatPartialChange, PrivateChatUiState> {
        // BaseViewModel 在父类初始化期间创建 producer，延迟访问构造注入字段。
        private val history get() = historyProvider()
        private data class Session(val accountUid: Long, val peerUid: Long, val limit: Int = 60)
        private data class Account(val uid: Long, val token: String, val identifier: String)
        private val selected = MutableStateFlow<Session?>(null)
        private var visible: Pair<Long, Long>? = null
        private val writes = Mutex()
        private val sends = Mutex()
        private val initializedSessions = mutableSetOf<Pair<Long, Long>>()
        private val cursors = mutableMapOf<Pair<Long, Long>, Long>()

        private fun account(): Account {
            val uid = AccountUtil.getUid()?.toLongOrNull() ?: error("请先登录")
            val token = AccountUtil.getBduss().orEmpty()
            val identifier = ImMessageApi.buildClientIdentifier()
            check(uid > 0L && token.isNotBlank()) { "请先登录" }
            check(AccountUtil.getUid()?.toLongOrNull() == uid) { "账号已切换" }
            return Account(uid, token, identifier)
        }

        private fun active(account: Account) = AccountUtil.getUid()?.toLongOrNull() == account.uid
        private fun guard(account: Account) { check(active(account)) { "账号已切换，已取消发送" } }
        private fun current(account: Account, peerUid: Long) =
            active(account) && visible == (account.uid to peerUid) &&
                selected.value?.let { it.accountUid == account.uid && it.peerUid == peerUid } == true

        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<PrivateChatUiIntent>): Flow<PrivateChatPartialChange> = merge(
            // 不 conflate：加载历史和首次加载不能被轮询吞掉。
            intentFlow.filter { it is PrivateChatUiIntent.Load || it is PrivateChatUiIntent.Poll || it is PrivateChatUiIntent.LoadMore }
                .onEach {
                    if (it is PrivateChatUiIntent.Load) {
                        val uid = AccountUtil.getUid()?.toLongOrNull() ?: 0L
                        if (uid > 0L && it.accountUid == uid) visible = uid to it.peerUid
                    }
                }
                .flatMapConcat { intent -> refresh(intent) },
            intentFlow.filterIsInstance<PrivateChatUiIntent.Stop>().flatMapConcat { intent ->
                flow<PrivateChatPartialChange> {
                    if (visible == (intent.accountUid to intent.peerUid)) {
                        visible = null
                        selected.value = null
                    }
                }
            },
            selected.flatMapLatest { session ->
                flow<PrivateChatPartialChange> {
                    if (session == null) return@flow
                    val cached = history.loadPrivate(session.accountUid, session.peerUid, session.limit)
                    emit(snapshot(session, cached))
                    emitAll(history.observePrivate(session.accountUid, session.peerUid, session.limit).map {
                        if (visible == (session.accountUid to session.peerUid) && selected.value == session &&
                            AccountUtil.getUid()?.toLongOrNull() == session.accountUid
                        ) history.markRead(session.accountUid, ChatHistoryRepository.PRIVATE, session.peerUid)
                        snapshot(session, it)
                    })
                }.catch { if (it is CancellationException) throw it else emit(PrivateChatPartialChange.Load.Failure(it)) }
            },
            intentFlow.filterIsInstance<PrivateChatUiIntent.Send>().flatMapMerge { send(it) },
            intentFlow.filterIsInstance<PrivateChatUiIntent.Resend>().flatMapMerge { resend(it) },
        )

        private fun snapshot(session: Session, messages: List<ChatWsMessage>): PrivateChatPartialChange =
            PrivateChatPartialChange.Snapshot(messages.toImmutableList(), session.accountUid, session.peerUid, session.limit,
                AccountUtil.getUid()?.toLongOrNull() == session.accountUid && selected.value == session &&
                    visible == (session.accountUid to session.peerUid))

        private suspend fun initialize(account: Account, peerUid: Long) {
            writes.withLock {
                val key = account.uid to peerUid
                if (key !in initializedSessions) {
                    history.importLegacyPrivate(account.uid)
                    history.recoverInterrupted(account.uid, ChatHistoryRepository.PRIVATE, peerUid)
                    initializedSessions += key
                }
            }
        }

        private fun refresh(intent: PrivateChatUiIntent): Flow<PrivateChatPartialChange> = flow {
            val account = account()
            val peer = when (intent) {
                is PrivateChatUiIntent.Load -> intent.peerUid
                is PrivateChatUiIntent.Poll -> intent.peerUid
                is PrivateChatUiIntent.LoadMore -> intent.peerUid
                else -> error("不支持的私信刷新操作")
            }
            val requestedAccount = when (intent) {
                is PrivateChatUiIntent.Load -> intent.accountUid
                is PrivateChatUiIntent.Poll -> intent.accountUid
                is PrivateChatUiIntent.LoadMore -> intent.accountUid
                else -> return@flow
            }
            require(requestedAccount == account.uid) { "账号已切换，已取消刷新" }
            if (visible != (account.uid to peer)) return@flow
            val previous = selected.value
            val same = previous?.accountUid == account.uid && previous.peerUid == peer
            if (!same || intent is PrivateChatUiIntent.Load) emit(PrivateChatPartialChange.Load.Start(account.uid, peer))
            initialize(account, peer)
            guard(account)
            if (visible != (account.uid to peer)) return@flow
            val session = if (same) previous!! else Session(account.uid, peer)
            if (intent is PrivateChatUiIntent.LoadMore) {
                // UI 传入当前窗口水位，重复的边界事件不重复扩大窗口。
                if (!same || intent.loadedLimit != session.limit) return@flow
                val expanded = session.copy(limit = session.limit + 60)
                selected.value = expanded
                val local = history.loadPrivate(account.uid, peer, expanded.limit)
                emit(snapshot(expanded, local))
                if (local.size < expanded.limit) {
                    val oldest = local.filter { it.deliveryConfirmed && it.serverMsgId != null }.minOfOrNull { it.msgTime }
                    if (oldest != null && oldest > 0L) fetch(account, peer, beginTime = 0L, endTime = oldest)
                    emit(snapshot(expanded, history.loadPrivate(account.uid, peer, expanded.limit)))
                }
                return@flow
            }
            selected.value = session
            val local = history.loadPrivate(account.uid, peer, session.limit)
            emit(snapshot(session, local))
            if (intent is PrivateChatUiIntent.Load) {
                history.upsertConversation(account.uid, ChatHistoryRepository.PRIVATE, peer,
                    name = intent.peerName, avatar = intent.peerPortrait)
            }
            if (current(account, peer)) {
                history.reopenConversation(account.uid, ChatHistoryRepository.PRIVATE, peer)
                history.markRead(account.uid, ChatHistoryRepository.PRIVATE, peer)
            }
            val key = account.uid to peer
            cursors[key] = maxOf(cursors[key] ?: 0L, local.privateHttpCursorTimeMillis())
            fetch(account, peer, ((cursors[key] ?: 0L) - 1L).coerceAtLeast(0L))
            if (current(account, peer)) {
                history.markRead(account.uid, ChatHistoryRepository.PRIVATE, peer)
                emit(snapshot(session, history.loadPrivate(account.uid, peer, session.limit)))
            }
        }.catch {
            if (it is CancellationException) throw it
            emit(PrivateChatPartialChange.Load.Failure(it))
        }

        private suspend fun fetch(account: Account, peer: Long, beginTime: Long = 0L, endTime: Long = 0L) {
            guard(account)
            val incoming = ImMessageApi.fetchMessages(peer, beginTime, endTime, account.token)
                .mapNotNull { it.toChatWsMessage(account.uid, peer) }
            guard(account)
            if (incoming.isEmpty()) return
            writes.withLock {
                history.savePrivate(account.uid, peer, incoming)
                val key = account.uid to peer
                cursors[key] = maxOf(cursors[key] ?: 0L, incoming.privateHttpCursorTimeMillis())
            }
        }

        private fun send(intent: PrivateChatUiIntent.Send): Flow<PrivateChatPartialChange> = flow {
            val account = account()
            require(intent.accountUid == account.uid) { "账号已切换，已取消发送，未落盘的文字仍保留在原账号草稿" }
            initialize(account, intent.peerUid)
            guard(account)
            val hasImage = intent.cloudMeme != null || intent.photoUri != null
            val message = ChatWsMessage(
                msgId = pendingIds.decrementAndGet(), peerUid = intent.peerUid, conversationUid = intent.peerUid,
                senderUid = account.uid, peerName = "", peerPortrait = "", content = intent.content,
                msgTime = System.currentTimeMillis(), isSelf = true, sendState = SendState.SENDING,
                isLocal = true, deliveryConfirmed = false, localId = UUID.randomUUID().toString(),
                msgKey = ImMessageApi.createMsgKey(), localPhotoUri = intent.photoUri?.toString(),
                cloudMeme = intent.cloudMeme,
                images = intent.photoUri?.let { listOf(it.toString()) } ?: intent.cloudMeme?.let { listOf(it.sendImageUrl) }.orEmpty(),
                msgType = if (hasImage) ChatWsConstants.MSG_TYPE_PRIVATE_IMAGE else ChatWsConstants.MSG_TYPE_PRIVATE,
                semantics = ChatMessageSemantics(kind = if (hasImage) ChatMessageKind.IMAGE else ChatMessageKind.TEXT,
                    protocolSource = "local_outgoing", summary = if (hasImage) "图片消息" else intent.content,
                    transportType = if (hasImage) ChatWsConstants.MSG_TYPE_PRIVATE_IMAGE else ChatWsConstants.MSG_TYPE_PRIVATE,
                    businessType = if (hasImage) 2 else 1),
            )
            persist(account, intent.peerUid, message)
            if (current(account, intent.peerUid)) emit(PrivateChatPartialChange.Send.Accepted(account.uid, intent.peerUid, intent.draftId))
            transmit(account, intent.peerUid, message) { emit(it) }
        }.catch {
            if (it is CancellationException) throw it
            emit(PrivateChatPartialChange.Send.Failure(0L, it))
        }

        private fun resend(intent: PrivateChatUiIntent.Resend): Flow<PrivateChatPartialChange> = flow {
            val account = account()
            require(intent.accountUid == account.uid) { "账号已切换，已取消发送，未落盘的文字仍保留在原账号草稿" }
            initialize(account, intent.peerUid)
            // 整个重试串行，重复点击不能排队重发同一条正在发送的记录。
            if (!sends.tryLock()) return@flow
            try {
                val original = history.loadPrivate(account.uid, intent.peerUid, Int.MAX_VALUE)
                    .firstOrNull { it.msgId == intent.failedMsgId } ?: return@flow
                if (original.sendState != SendState.FAILED && original.sendState != SendState.UNKNOWN) return@flow
                fetch(account, intent.peerUid)
                val reconciled = history.findPrivate(account.uid, intent.peerUid, original) ?: return@flow
                if (reconciled.sendState == SendState.SENT || reconciled.serverMsgId != null) return@flow
                guard(account)
                val retry = reconciled.copy(sendState = SendState.SENDING, sendError = null, transmissionStarted = false,
                    localId = reconciled.localId.ifBlank { UUID.randomUUID().toString() },
                    msgKey = reconciled.msgKey.ifBlank { ImMessageApi.createMsgKey() })
                val saved = persist(account, intent.peerUid, retry)
                if (saved.sendState == SendState.SENT || saved.serverMsgId != null) return@flow
                transmit(account, intent.peerUid, saved) { emit(it) }
            } finally { sends.unlock() }
        }.catch {
            if (it is CancellationException) throw it
            emit(PrivateChatPartialChange.Send.Failure(intent.failedMsgId, it, accountUid = -1L))
        }

        private suspend fun persist(account: Account, peer: Long, message: ChatWsMessage): ChatWsMessage = writes.withLock {
            history.savePrivate(account.uid, peer, listOf(message))
            history.findPrivate(account.uid, peer, message) ?: message
        }

        private suspend fun transmit(account: Account, peer: Long, original: ChatWsMessage, emit: suspend (PrivateChatPartialChange) -> Unit) {
            var record = original
            try {
                record = history.findPrivate(account.uid, peer, record) ?: record
                if (record.sendState == SendState.SENT || record.serverMsgId != null) return
                guard(account)
                if (record.localPhotoUri != null && record.cloudMeme?.uploadedImageUrl.isNullOrBlank()) {
                    val retained = history.retainAttachment(account.uid, Uri.parse(record.localPhotoUri))
                    record = persist(account, peer, record.copy(localPhotoUri = retained.toString(), images = listOf(retained.toString())))
                    guard(account)
                    val image = uploadChatPhoto(retained)
                    guard(account)
                    record = persist(account, peer, record.copy(cloudMeme = image, images = listOf(image.sendImageUrl)))
                }
                if (record.sendState == SendState.SENT || record.serverMsgId != null) return
                if (current(account, peer)) emit(PrivateChatPartialChange.Send.Prepared(record.msgId, record.cloudMeme, record, account.uid, peer))
                guard(account)
                record = persist(account, peer, record.copy(transmissionStarted = true))
                if (record.sendState == SendState.SENT || record.serverMsgId != null) return
                // 切号检查在请求开始标记前后均执行；HTTP 使用起始时的账号快照。
                if (!active(account)) {
                    record = record.copy(transmissionStarted = false)
                    error("账号已切换，已取消发送")
                }
                val id = ImMessageApi.sendMessage(peerUid = peer, text = record.content,
                    imageUrl = record.cloudMeme?.sendImageUrl, thumbnailUrl = record.cloudMeme?.sendThumbnailUrl,
                    width = record.cloudMeme?.width ?: 0, height = record.cloudMeme?.height ?: 0,
                    msgKey = record.msgKey, accountBduss = account.token, clientIdentifier = account.identifier)
                val success = persist(account, peer, record.copy(msgId = id, isLocal = false, sendState = SendState.SENT,
                    sendError = null, deliveryConfirmed = false))
                if (current(account, peer)) emit(PrivateChatPartialChange.Send.Success(original.msgId, success, account.uid, peer))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val state = if (record.transmissionStarted && error !is ImHttpException) SendState.UNKNOWN else SendState.FAILED
                val saved = persist(account, peer, record.copy(sendState = state,
                    sendError = if (state == SendState.UNKNOWN) "发送结果未确认，重试可能重复" else (error.message ?: "发送失败")))
                if (current(account, peer) && saved.sendState != SendState.SENT) emit(PrivateChatPartialChange.Send.Failure(original.msgId, error, account.uid, peer))
            }
        }

        companion object {
            // 进程内全局单调，重建 ViewModel 不会复用旧负数 ID。
            private val pendingIds = AtomicLong(-System.currentTimeMillis() * 1000L)
        }
    }
}

// 保留旧测试和调用入口；前后台共用协议转换。
internal fun ImMessageApi.Message.toChatWsMessage(selfUid: Long, peerUid: Long): ChatWsMessage? {
    if (selfUid <= 0L || peerUid <= 0L || fromUid <= 0L || (fromUid != selfUid && fromUid != peerUid)) return null
    return ImMessageApi.toChatMessage(this, selfUid, peerUid)
}

sealed interface PrivateChatUiIntent : UiIntent {
    data class Load(val peerUid: Long, val peerName: String = "", val peerPortrait: String = "", val accountUid: Long = AccountUtil.getUid()?.toLongOrNull() ?: 0L) : PrivateChatUiIntent
    data class Stop(val peerUid: Long, val accountUid: Long) : PrivateChatUiIntent
    data class Poll(val peerUid: Long, val accountUid: Long = AccountUtil.getUid()?.toLongOrNull() ?: 0L) : PrivateChatUiIntent
    data class LoadMore(val peerUid: Long, val loadedLimit: Int, val accountUid: Long = AccountUtil.getUid()?.toLongOrNull() ?: 0L) : PrivateChatUiIntent
    data class Send(val peerUid: Long, val content: String, val cloudMeme: ChatCloudMeme? = null, val photoUri: Uri? = null,
        val draftId: String? = null, val accountUid: Long = AccountUtil.getUid()?.toLongOrNull() ?: 0L) : PrivateChatUiIntent
    data class Resend(val peerUid: Long, val failedMsgId: Long, val content: String, val cloudMeme: ChatCloudMeme? = null, val photoUri: Uri? = null,
        val accountUid: Long = AccountUtil.getUid()?.toLongOrNull() ?: 0L) : PrivateChatUiIntent
}

internal fun List<ChatWsMessage>.privateHttpCursorTimeMillis(): Long = asSequence()
    .filter { it.serverMsgId != null && it.deliveryConfirmed && it.msgTime > 0L }.maxOfOrNull { it.msgTime } ?: 0L

internal fun List<ChatWsMessage>.forPrivatePeer(peerUid: Long, cached: List<ChatWsMessage> = emptyList()): List<ChatWsMessage> {
    val knownSelfIds = cached.filter { it.isSelf && (it.conversationUid == peerUid || (it.conversationUid == 0L && it.peerUid == peerUid)) }
        .mapTo(mutableSetOf()) { it.msgId }
    return filter { val owner = it.privateConversationUid(); owner == peerUid || (owner == 0L && it.isSelf && it.msgId in knownSelfIds) }
}

internal fun samePrivateIdentity(a: ChatWsMessage, b: ChatWsMessage): Boolean =
    a.msgId == b.msgId || (a.localId.isNotBlank() && a.localId == b.localId) ||
        (a.msgKey.isNotBlank() && a.msgKey == b.msgKey)

internal fun mergePrivateMessages(current: List<ChatWsMessage>, incoming: List<ChatWsMessage>): List<ChatWsMessage> {
    val result = current.toMutableList()
    incoming.forEach { message ->
        val matches = result.filter { samePrivateIdentity(it, message) }
        val local = matches.firstOrNull { it.localId.isNotBlank() } ?: matches.firstOrNull()
        val confirmed = (matches + message).firstOrNull { it.deliveryConfirmed && it.serverMsgId != null }
        val accepted = (matches + message).firstOrNull { it.serverMsgId != null }
        val source = confirmed ?: accepted ?: message
        result.removeAll { samePrivateIdentity(it, message) }
        result += source.copy(
            localId = local?.localId?.takeIf { it.isNotBlank() } ?: message.localId,
            msgKey = message.msgKey.ifBlank { local?.msgKey.orEmpty() },
            cloudMeme = local?.cloudMeme ?: message.cloudMeme,
            localPhotoUri = local?.localPhotoUri ?: message.localPhotoUri,
            deliveryConfirmed = confirmed != null,
            sendState = if (accepted != null) SendState.SENT else message.sendState,
            sendError = if (accepted != null) null else message.sendError,
            transmissionStarted = message.transmissionStarted || matches.any { it.transmissionStarted },
            conversationUid = message.conversationUid.takeIf { it > 0L } ?: local?.conversationUid ?: 0L,
            senderUid = message.senderUid.takeIf { it > 0L } ?: local?.senderUid ?: 0L,
            semantics = if (message.isSelf && local?.isSelf == true) local.semantics else message.semantics,
        )
    }
    return result.sortedWith(compareBy<ChatWsMessage> { it.msgTime }.thenBy { it.msgId })
}

private fun mergeIntoState(current: List<ChatWsMessage>, incoming: List<ChatWsMessage>): ImmutableList<ChatWsMessage> =
    mergePrivateMessages(current, incoming).toImmutableList()

sealed interface PrivateChatPartialChange : PartialChange<PrivateChatUiState> {
    data class Snapshot(val messages: ImmutableList<ChatWsMessage>, val accountUid: Long, val peerUid: Long,
        val limit: Int, val active: Boolean = true) : PrivateChatPartialChange {
        override fun reduce(oldState: PrivateChatUiState) = if (!active || oldState.accountUid != accountUid || oldState.peerUid != peerUid) oldState
        else oldState.copy(messages = messages, isLoading = false, historyLimit = limit)
    }

    // 旧 PartialChange 保留给兼容测试；运行页面只用持久层 Snapshot 更新消息。
    sealed class Load : PrivateChatPartialChange {
        override fun reduce(oldState: PrivateChatUiState): PrivateChatUiState = when (this) {
            is Start -> if (oldState.accountUid != accountUid || oldState.peerUid != peerUid) PrivateChatUiState(accountUid = accountUid, peerUid = peerUid) else oldState.copy(isLoading = true)
            is Cached -> if (!oldState.accepts(accountUid, peerUid)) oldState else oldState.copy(messages = mergeIntoState(oldState.messages, messages))
            is Success -> if (!oldState.accepts(accountUid, peerUid)) oldState else oldState.copy(isLoading = false, messages = mergeIntoState(oldState.messages, messages))
            is Failure -> oldState.copy(isLoading = false)
        }
        data class Start(val accountUid: Long, val peerUid: Long) : Load()
        data class Cached(val messages: ImmutableList<ChatWsMessage>, val accountUid: Long = 0L, val peerUid: Long = 0L) : Load()
        data class Success(val messages: ImmutableList<ChatWsMessage>, val accountUid: Long = 0L, val peerUid: Long = 0L) : Load()
        data class Failure(val error: Throwable) : Load()
    }

    sealed class Poll : PrivateChatPartialChange {
        override fun reduce(oldState: PrivateChatUiState) = when (this) {
            is Success -> if (!oldState.accepts(accountUid, peerUid)) oldState else oldState.copy(messages = mergeIntoState(oldState.messages, latest))
            Failure -> oldState
        }
        data class Success(val latest: ImmutableList<ChatWsMessage>, val accountUid: Long = 0L, val peerUid: Long = 0L) : Poll()
        data object Failure : Poll()
    }

    sealed class Send : PrivateChatPartialChange {
        override fun reduce(oldState: PrivateChatUiState): PrivateChatUiState = when (this) {
            is Accepted -> if (oldState.accepts(accountUid, peerUid)) oldState.copy(sendRequestId = oldState.sendRequestId + 1, acceptedDraftId = draftId ?: oldState.acceptedDraftId) else oldState
            is Start -> {
                val image = cloudMeme != null || localPhotoUri != null
                oldState.copy(messages = (oldState.messages.filterNot { it.msgId == removeMsgId } + ChatWsMessage(
                    msgId = pendingId, peerUid = peerUid, conversationUid = peerUid, peerName = "", peerPortrait = "", content = content,
                    msgTime = System.currentTimeMillis(), isSelf = true, isLocal = true, sendState = SendState.SENDING,
                    cloudMeme = cloudMeme, localPhotoUri = localPhotoUri,
                    images = localPhotoUri?.let { listOf(it) } ?: cloudMeme?.let { listOf(it.sendImageUrl) }.orEmpty(),
                    msgType = if (image) ChatWsConstants.MSG_TYPE_PRIVATE_IMAGE else ChatWsConstants.MSG_TYPE_PRIVATE,
                    semantics = ChatMessageSemantics(kind = if (image) ChatMessageKind.IMAGE else ChatMessageKind.TEXT, protocolSource = "local_outgoing"),
                )).toImmutableList(), sendRequestId = oldState.sendRequestId + 1)
            }
            is Prepared -> if (record != null || !oldState.accepts(accountUid, peerUid)) oldState else oldState.copy(messages = oldState.messages.map {
                if (it.msgId == pendingId) it.copy(cloudMeme = cloudMeme ?: it.cloudMeme, images = cloudMeme?.let { meme -> listOf(meme.sendImageUrl) } ?: it.images) else it
            }.toImmutableList())
            is Success -> if (accountUid != 0L || (oldState.accountUid > 0L && message.senderUid != oldState.accountUid) ||
                (oldState.peerUid > 0L && message.conversationUid != oldState.peerUid)) oldState else oldState.copy(
                messages = mergeIntoState(oldState.messages.filterNot { it.msgId == pendingId }, listOf(message)))
            is Failure -> if (accountUid != 0L) oldState else oldState.copy(messages = oldState.messages.map {
                if (it.msgId == pendingId) it.copy(sendState = SendState.FAILED) else it
            }.toImmutableList())
        }
        data class Accepted(val accountUid: Long, val peerUid: Long, val draftId: String? = null) : Send()
        data class Start(val pendingId: Long, val content: String, val peerUid: Long, val removeMsgId: Long? = null,
            val cloudMeme: ChatCloudMeme? = null, val localPhotoUri: String? = null) : Send()
        data class Prepared(val pendingId: Long, val cloudMeme: ChatCloudMeme?, val record: ChatWsMessage? = null,
            val accountUid: Long = 0L, val peerUid: Long = 0L) : Send()
        data class Success(val pendingId: Long, val message: ChatWsMessage, val accountUid: Long = 0L, val peerUid: Long = 0L) : Send()
        data class Failure(val pendingId: Long, val error: Throwable, val accountUid: Long = 0L, val peerUid: Long = 0L) : Send()
    }
}

private fun PrivateChatUiState.accepts(account: Long, peer: Long) = (account == 0L || accountUid == account) && (peer == 0L || peerUid == peer)

@Immutable
data class PrivateChatUiState(val sendRequestId: Long = 0L, val isLoading: Boolean = true,
    val messages: ImmutableList<ChatWsMessage> = persistentListOf(), val accountUid: Long = 0L, val peerUid: Long = 0L,
    val historyLimit: Int = 60, val acceptedDraftId: String? = null) : UiState

sealed interface PrivateChatUiEvent : UiEvent
