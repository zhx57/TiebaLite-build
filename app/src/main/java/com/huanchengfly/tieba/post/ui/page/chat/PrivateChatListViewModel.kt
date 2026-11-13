package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import com.huanchengfly.tieba.post.api.chat.ws.PrivateChatCache
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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

    private var socket: ChatWebSocketClient? = null
    private var privateCursor = 0L

    override fun createInitialState(): PrivateChatListUiState = PrivateChatListUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<PrivateChatListUiIntent, PrivateChatListPartialChange, PrivateChatListUiState> =
        PrivateChatListPartialChangeProducer { socket }

    override fun dispatchEvent(partialChange: PrivateChatListPartialChange): UiEvent? =
        when (partialChange) {
            is PrivateChatListPartialChange.Load.Failure ->
                CommonUiEvent.Toast(partialChange.error.message ?: "加载私信失败")
            else -> null
        }

    override fun onCleared() {
        socket?.close()
        socket = null
        super.onCleared()
    }

    /**
     * 复用同一个连接，避免每次刷新都重新握手。
     */
    private fun obtainSocket(): ChatWebSocketClient =
        socket ?: ChatWebSocketClient().also { socket = it }

    /** 页面存活期间收到服务端推送就立即刷新，轮询只作为兜底。 */
    private var pollCookie: String = ""

    fun bindPushNotify(cookie: String) {
        pollCookie = cookie
        obtainSocket().onPushNotify = { _ ->
            if (pollCookie.isNotEmpty()) {
                send(PrivateChatListUiIntent.Poll(pollCookie))
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
                intentFlow.filterIsInstance<PrivateChatListUiIntent.CacheChanged>()
                    .map {
                        PrivateChatListPartialChange.Load.Success(
                            PrivateChatCache.loadAll(socketProvider()?.selfUid ?: 0L).toSessions(),
                            replace = false,
                        )
                    },
                intentFlow
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

        /**
         * 静默刷新会话列表，用于定时轮询与推送到达。
         */
        private fun PrivateChatListUiIntent.Poll.producePartialChange(): Flow<PrivateChatListPartialChange.Load> =
            flow {
                val client = socketProvider() ?: throw IllegalStateException("chat socket unavailable")
                client.connect(cookie)
                val messages = client.fetchPrivateMessagesSince(privateCursor)
                privateCursor = maxOf(privateCursor, client.privateFetchedMsgId)
                emit(
                    PrivateChatListPartialChange.Load.Success(
                        (PrivateChatCache.loadAll(client.selfUid) + messages).toSessions(),
                        replace = false,
                    )
                )
            }.catch { /* 静默失败，等下一次轮询 */ }

        private fun PrivateChatListUiIntent.Load.producePartialChange(): Flow<PrivateChatListPartialChange.Load> =
            flow {
                emit(PrivateChatListPartialChange.Load.Start)
                val client = socketProvider() ?: throw IllegalStateException("chat socket unavailable")
                client.connect(cookie)
                // 握手游标已是最新，直接增量只会拿到空列表；
                // 从头按批拉历史，才能看到所有历史会话与最近联系人。
                val messages = client.fetchPrivateHistory()
                privateCursor = client.privateFetchedMsgId
                emit(
                    PrivateChatListPartialChange.Load.Success(
                        (PrivateChatCache.loadAll(client.selfUid) + messages).toSessions(),
                        replace = true,
                    )
                )
            }.catch { emit(PrivateChatListPartialChange.Load.Failure(it)) }
    }

    init {
        // 提前建好连接对象，保证 Producer 拿到的实例与 onCleared 关闭的是同一个
        obtainSocket()
        viewModelScope.launch {
            PrivateChatCache.changes.collect {
                send(PrivateChatListUiIntent.CacheChanged)
            }
        }
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
                is Success -> oldState.copy(
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
 * 私信消息组的 userInfo 是发送者，自己发的消息 peerUid 会是自己，
 * 无法据此推断对话方，这里只按对方发来的消息聚合。
 */
private fun mergePrivateSessions(
    current: ImmutableList<PrivateSession>,
    incoming: ImmutableList<PrivateSession>,
): ImmutableList<PrivateSession> =
    (current + incoming)
        .groupBy { it.peerUid }
        .values
        .map { sessions -> sessions.maxBy { it.lastTime } }
        .sortedByDescending { it.lastTime }
        .toImmutableList()

fun List<ChatWsMessage>.toSessions(): ImmutableList<PrivateSession> {
    return filter { !it.isSelf && it.peerUid != 0L }
        .groupBy { it.peerUid }
        .mapNotNull { (peerUid, messages) ->
            val latest = messages.maxByOrNull { it.msgTime } ?: return@mapNotNull null
            PrivateSession(
                peerUid = peerUid,
                peerName = latest.peerName,
                peerPortrait = latest.peerPortrait,
                lastMessage = latest.content,
                lastTime = latest.msgTime,
                unreadCount = 0,
            )
        }
        .sortedByDescending { it.lastTime }
        .toImmutableList()
}
