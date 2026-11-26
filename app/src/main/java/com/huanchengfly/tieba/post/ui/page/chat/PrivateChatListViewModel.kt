package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.api.chat.http.ImSessionApi
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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * 私信会话列表 ViewModel。
 *
 * 会话列表严格按官方实现走 HTTP 接口 rest/3.0/im/fetch_session，
 * 不再依赖 WebSocket 拉取会话：官方客户端的消息中心本身就是这个接口驱动的，
 * WebSocket 只负责在线状态与推送通知。
 */
@Stable
@HiltViewModel
class PrivateChatListViewModel @Inject constructor() :
    BaseViewModel<PrivateChatListUiIntent, PrivateChatListPartialChange, PrivateChatListUiState, PrivateChatListUiEvent>() {

    override fun createInitialState(): PrivateChatListUiState = PrivateChatListUiState()

    override fun createPartialChangeProducer() = PrivateChatListPartialChangeProducer

    override fun dispatchEvent(partialChange: PrivateChatListPartialChange): UiEvent? =
        when (partialChange) {
            is PrivateChatListPartialChange.Load.Failure ->
                CommonUiEvent.Toast(partialChange.error.message ?: "加载私信失败")
            else -> null
        }

    object PrivateChatListPartialChangeProducer :
        PartialChangeProducer<PrivateChatListUiIntent, PrivateChatListPartialChange, PrivateChatListUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(
            intentFlow: Flow<PrivateChatListUiIntent>,
        ): Flow<PrivateChatListPartialChange> =
            intentFlow.filterIsInstance<PrivateChatListUiIntent.Load>()
                .flatMapConcat { it.producePartialChange() }

        private fun PrivateChatListUiIntent.Load.producePartialChange(): Flow<PrivateChatListPartialChange.Load> =
            flow {
                emit(PrivateChatListPartialChange.Load.Start)
                val sessions = ImSessionApi.fetchSessions()
                emit(PrivateChatListPartialChange.Load.Success(sessions.toSessionModels()))
            }.catch {
                if (it is kotlinx.coroutines.CancellationException) throw it
                emit(PrivateChatListPartialChange.Load.Failure(it))
            }
    }
}

sealed interface PrivateChatListUiIntent : UiIntent {
    data object Load : PrivateChatListUiIntent
}

sealed interface PrivateChatListPartialChange : PartialChange<PrivateChatListUiState> {
    sealed class Load : PrivateChatListPartialChange {
        override fun reduce(oldState: PrivateChatListUiState): PrivateChatListUiState =
            when (this) {
                is Start -> oldState.copy(isLoading = true)
                // 官方 fetch_session 每次返回全量会话，这里按 peerUid 合并并保留较新的一条，
                // 避免网络抖动时旧会话被清掉。
                is Success -> oldState.copy(
                    isLoading = false,
                    sessions = mergeSessions(oldState.sessions, sessions),
                )
                is Failure -> oldState.copy(isLoading = false)
            }

        data object Start : Load()

        data class Success(
            val sessions: ImmutableList<PrivateSession>,
        ) : Load()

        data class Failure(val error: Throwable) : Load()
    }
}

/**
 * 合并会话列表。
 *
 * 同一个 peerUid 保留 lastTime 更新的那条，并按时间倒序排列。
 */
private fun mergeSessions(
    current: ImmutableList<PrivateSession>,
    incoming: ImmutableList<PrivateSession>,
): ImmutableList<PrivateSession> =
    (current + incoming)
        .groupBy { it.peerUid }
        .map { (_, sessions) -> sessions.maxBy { it.lastTime } }
        .sortedByDescending { it.lastTime }
        .toImmutableList()

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
 * 把官方会话列表转成界面需要的模型。
 *
 * 官方会话里没有昵称与头像，只给出对方 uid 与 IM 唯一键，
 * 昵称头像由进入会话时再拉取，这里先显示 uid。
 */
private fun List<ImSessionApi.Session>.toSessionModels(): ImmutableList<PrivateSession> =
    filter { it.isPrivateChat && it.peerUid > 0L }
        .distinctBy { it.peerUid }
        .sortedByDescending { it.lastTime }
        .map { session ->
            PrivateSession(
                peerUid = session.peerUid,
                peerName = session.peerUid.toString(),
                peerPortrait = "",
                lastMessage = session.summary,
                lastTime = session.lastTime,
                unreadCount = session.unreadNum,
            )
        }
        .toImmutableList()
