package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.api.chat.ws.PrivateChatCache
import kotlinx.coroutines.flow.emitAll
import com.huanchengfly.tieba.post.api.chat.ws.privateConversationUid
import com.huanchengfly.tieba.post.utils.AccountUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import com.huanchengfly.tieba.post.api.chat.ws.ChatSocketHub
import com.huanchengfly.tieba.post.api.chat.ws.ChatWebSocketClient
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * 私信会话列表 ViewModel。
 *
 * WebSocket 只在页面存活期间保持连接，ViewModel 销毁时断开。
 */
@Stable
@HiltViewModel
class PrivateChatListViewModel @Inject constructor() :
    BaseViewModel<PrivateChatListUiIntent, PrivateChatListPartialChange, PrivateChatListUiState, PrivateChatListUiEvent>() {

    private val pushRefresh by lazy { Channel<PrivateChatListUiIntent.Poll>(Channel.CONFLATED) }
    private var unregisterPush: (() -> Unit)? = null
    private var privateCursor = 0L
    private var cursorAccountUid = 0L
    private val cacheAccountUid by lazy { MutableStateFlow(AccountUtil.getUid()?.toLongOrNull() ?: 0L) }

    private fun useAccount(selfUid: Long) {
        if (cursorAccountUid != selfUid) {
            cursorAccountUid = selfUid
            privateCursor = PrivateChatCache.syncCursor(selfUid)
        }
        cacheAccountUid.value = selfUid
    }

    override fun createInitialState(): PrivateChatListUiState = PrivateChatListUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<PrivateChatListUiIntent, PrivateChatListPartialChange, PrivateChatListUiState> =
        PrivateChatListPartialChangeProducer { obtainSocket() }

    override fun dispatchEvent(partialChange: PrivateChatListPartialChange): UiEvent? =
        when (partialChange) {
            is PrivateChatListPartialChange.Load.Failure ->
                CommonUiEvent.Toast(partialChange.error.message ?: "加载私信失败")
            else -> null
        }

    override fun onCleared() {
        // 连接在进程内共享，页面退出只解除推送订阅。
        unregisterPush?.invoke()
        unregisterPush = null
        super.onCleared()
    }

    /**
     * 取当前账号的共享连接，沿用主界面常驻连接，避免重复握手。
     */
    private fun obtainSocket(): ChatWebSocketClient =
        ChatSocketHub.get(AccountUtil.getCookie().orEmpty())

    /** 页面存活期间收到服务端推送就立即刷新，轮询只作为兜底。 */
    private var pollCookie: String = ""

    fun bindPushNotify(cookie: String) {
        pollCookie = cookie
        useAccount(AccountUtil.getUid()?.toLongOrNull() ?: 0L)
        val client = obtainSocket()
        unregisterPush?.invoke()
        unregisterPush = client.addPushNotifyListener { groupId ->
            if (pollCookie.isNotEmpty() && groupId == client.privateGroupId) {
                pushRefresh.trySend(PrivateChatListUiIntent.Poll(pollCookie))
            }
        }
    }

    private inner class PrivateChatListPartialChangeProducer(
        private val socketProvider: () -> ChatWebSocketClient?,
    ) : PartialChangeProducer<PrivateChatListUiIntent, PrivateChatListPartialChange, PrivateChatListUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(
            intentFlow: Flow<PrivateChatListUiIntent>,
        ): Flow<PrivateChatListPartialChange> =
            merge(
                flow {
                    emitAll(cacheAccountUid.flatMapLatest { selfUid ->
                        PrivateChatCache.observe(selfUid).map { messages ->
                            PrivateChatListPartialChange.Load.Success(
                                messages.toSessions(selfUid), replace = true, accountUid = selfUid,
                            )
                        }
                    })
                },
                merge(intentFlow, flow { emitAll(pushRefresh.receiveAsFlow()) })
                .filter {
                    it is PrivateChatListUiIntent.Load ||
                        it is PrivateChatListUiIntent.Poll
                }
                .conflate()
                .flatMapConcat {
                    when (it) {
                        is PrivateChatListUiIntent.Load -> it.producePartialChange()
                        is PrivateChatListUiIntent.Poll -> it.producePartialChange()
                        else -> error("Unexpected private chat list intent")
                    }
                },
            )

        private fun matchesAccount(accountUid: Long, cookie: String, client: ChatWebSocketClient? = null): Boolean =
            AccountUtil.getUid()?.toLongOrNull() == accountUid &&
                AccountUtil.getCookie().orEmpty() == cookie &&
                (client == null || client.selfUid == accountUid)

        /**
         * 静默刷新会话列表，用于定时轮询与推送到达。
         */
        private fun PrivateChatListUiIntent.Poll.producePartialChange(): Flow<PrivateChatListPartialChange.Load> =
            flow {
                val accountUid = AccountUtil.getUid()?.toLongOrNull() ?: 0L
                val requestCookie = cookie
                if (!matchesAccount(accountUid, requestCookie)) return@flow
                val client = socketProvider() ?: throw IllegalStateException("chat socket unavailable")
                client.connect(requestCookie)
                if (!matchesAccount(accountUid, requestCookie, client)) return@flow
                useAccount(accountUid)
                for (page in 0 until 20) {
                    val previousCursor = privateCursor
                    val messages = client.fetchPrivateMessagesSince(previousCursor)
                    if (!matchesAccount(accountUid, requestCookie, client)) return@flow
                    PrivateChatCache.saveIncoming(accountUid, messages, client.privateFetchedMsgId)
                    privateCursor = maxOf(previousCursor, client.privateFetchedMsgId)
                    if (privateCursor <= previousCursor || privateCursor >= client.privateLastMsgId) break
                }
                emit(PrivateChatListPartialChange.Load.Success(
                    PrivateChatCache.loadAll(accountUid).toSessions(accountUid), replace = true,
                    accountUid = accountUid,
                ))
            }.catch {
                if (it is kotlinx.coroutines.CancellationException) throw it
            }

        private fun PrivateChatListUiIntent.Load.producePartialChange(): Flow<PrivateChatListPartialChange.Load> =
            flow {
                val accountUid = AccountUtil.getUid()?.toLongOrNull() ?: 0L
                val requestCookie = cookie
                if (!matchesAccount(accountUid, requestCookie)) return@flow
                emit(PrivateChatListPartialChange.Load.Start)
                val client = socketProvider() ?: throw IllegalStateException("chat socket unavailable")
                client.connect(requestCookie)
                if (!matchesAccount(accountUid, requestCookie, client)) return@flow
                // 握手游标已是最新，直接增量只会拿到空列表；
                // 从头按批拉历史，才能看到所有历史会话与最近联系人。
                useAccount(accountUid)
                val messages = if (privateCursor == 0L) client.fetchPrivateHistory()
                    else client.fetchPrivateMessagesSince(privateCursor)
                if (!matchesAccount(accountUid, requestCookie, client)) return@flow
                PrivateChatCache.saveIncoming(accountUid, messages, client.privateFetchedMsgId)
                privateCursor = maxOf(privateCursor, client.privateFetchedMsgId)
                emit(
                    PrivateChatListPartialChange.Load.Success(
                        PrivateChatCache.loadAll(accountUid).toSessions(accountUid),
                        replace = true,
                        accountUid = accountUid,
                    )
                )
            }.catch {
                if (it is kotlinx.coroutines.CancellationException) throw it
                emit(PrivateChatListPartialChange.Load.Failure(it))
            }
    }

    init {
        // 提前建好连接对象，保证 Producer 拿到的实例与 onCleared 关闭的是同一个
        obtainSocket()
    }
}

sealed interface PrivateChatListUiIntent : UiIntent {
    data object CacheChanged : PrivateChatListUiIntent

    data class Load(val cookie: String) : PrivateChatListUiIntent

    /** 静默刷新，用于定时轮询与推送到达。 */
    data class Poll(val cookie: String) : PrivateChatListUiIntent
}

sealed interface PrivateChatListPartialChange : PartialChange<PrivateChatListUiState> {
    sealed class Load : PrivateChatListPartialChange {
        override fun reduce(oldState: PrivateChatListUiState): PrivateChatListUiState =
            when (this) {
                is Start -> oldState.copy(isLoading = true)
                is Success -> if (
                    accountUid != 0L && AccountUtil.getUid()?.toLongOrNull() != accountUid
                ) oldState else oldState.copy(
                    isLoading = false,
                    sessions = if (replace) {
                        sessions
                    } else {
                        mergePrivateSessions(oldState.sessions, sessions)
                    },
                )
                is Failure -> oldState.copy(isLoading = false)
            }

        data object Start : Load()

        data class Success(
            val sessions: ImmutableList<PrivateSession>,
            val replace: Boolean,
            val accountUid: Long = 0L,
        ) : Load()

        data class Failure(val error: Throwable) : Load()
    }
}

/**
 * 一个私信会话。
 */
@Immutable
data class PrivateSession(
    val peerUid: Long,
    val peerName: String,
    val peerPortrait: String,
    val lastMessage: String,
    val lastTime: Long,
    val unreadCount: Int = 0,
)

@Immutable
data class PrivateChatListUiState(
    val isLoading: Boolean = false,
    val sessions: ImmutableList<PrivateSession> = persistentListOf(),
) : UiState

sealed interface PrivateChatListUiEvent : UiEvent

/**
 * 把消息列表聚合成会话列表。
 *
 * 私信消息组的 userInfo 是发送者，网络历史中的 self 消息没有收件人信息，
 * 只有本地发送记录携带了明确的会话归属，才能参与会话摘要。
 */
private fun mergePrivateSessions(
    current: ImmutableList<PrivateSession>,
    incoming: ImmutableList<PrivateSession>,
): ImmutableList<PrivateSession> =
    (current + incoming)
        .groupBy { it.peerUid }
        .values
        .map { sessions ->
            val sorted = sessions.sortedByDescending { it.lastTime }
            sorted.first().copy(
                peerName = sorted.firstOrNull { it.peerName.isNotBlank() }?.peerName.orEmpty(),
                peerPortrait = sorted.firstOrNull { it.peerPortrait.isNotBlank() }?.peerPortrait.orEmpty(),
            )
        }
        .sortedByDescending { it.lastTime }
        .toImmutableList()

fun List<ChatWsMessage>.toSessions(selfUid: Long = 0L): ImmutableList<PrivateSession> {
    return filter { it.privateConversationUid(selfUid) > 0L }
        .groupBy { it.privateConversationUid(selfUid) }
        .mapNotNull { (peerUid, messages) ->
            val latest = messages.maxByOrNull { it.msgTime } ?: return@mapNotNull null
            val profileMessages = messages.filter { !it.isSelf &&
                (it.senderUid == 0L || it.senderUid == peerUid) }.sortedByDescending { it.msgTime }
            PrivateSession(
                peerUid = peerUid,
                peerName = profileMessages.firstOrNull { it.peerName.isNotBlank() }?.peerName.orEmpty(),
                peerPortrait = profileMessages.firstOrNull { it.peerPortrait.isNotBlank() }?.peerPortrait.orEmpty(),
                lastMessage = latest.content.ifBlank { latest.semantics.summary },
                lastTime = latest.msgTime,
                unreadCount = 0,
            )
        }
        .sortedByDescending { it.lastTime }
        .toImmutableList()
}
