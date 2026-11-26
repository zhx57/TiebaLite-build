package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.api.chat.http.ChatRoomListApi
import com.huanchengfly.tieba.post.api.models.chat.ChatRoom
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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * 群聊列表 ViewModel。
 *
 * 官方消息中心的聊天 Tab 用 c/u/chat/getChatSubscriptionList 一次拿回用户加入的全部群，
 * 不需要先选贴吧再按 fid 拉。这里对齐官方，直接拉全量群列表。
 */
@Stable
@HiltViewModel
class ChatEntryViewModel @Inject constructor() :
    BaseViewModel<ChatEntryUiIntent, ChatEntryPartialChange, ChatEntryUiState, ChatEntryUiEvent>() {

    override fun createInitialState(): ChatEntryUiState = ChatEntryUiState()

    override fun createPartialChangeProducer() = ChatEntryPartialChangeProducer

    override fun dispatchEvent(partialChange: ChatEntryPartialChange): UiEvent? =
        when (partialChange) {
            is ChatEntryPartialChange.Load.Failure ->
                CommonUiEvent.Toast(partialChange.error.message ?: "加载群聊列表失败")
            else -> null
        }

    object ChatEntryPartialChangeProducer :
        PartialChangeProducer<ChatEntryUiIntent, ChatEntryPartialChange, ChatEntryUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(
            intentFlow: Flow<ChatEntryUiIntent>,
        ): Flow<ChatEntryPartialChange> =
            intentFlow.filterIsInstance<ChatEntryUiIntent.Load>()
                .flatMapConcat { it.producePartialChange() }

        private fun ChatEntryUiIntent.Load.producePartialChange(): Flow<ChatEntryPartialChange.Load> =
            flow {
                emit(ChatEntryPartialChange.Load.Start)
                val rooms = ChatRoomListApi.fetchAllRooms()
                emit(ChatEntryPartialChange.Load.Success(rooms.toImmutableList()))
            }.catch {
                if (it is kotlinx.coroutines.CancellationException) throw it
                emit(ChatEntryPartialChange.Load.Failure(it))
            }
    }
}

sealed interface ChatEntryUiIntent : UiIntent {
    data object Load : ChatEntryUiIntent
}

sealed interface ChatEntryPartialChange : PartialChange<ChatEntryUiState> {
    sealed class Load : ChatEntryPartialChange {
        override fun reduce(oldState: ChatEntryUiState): ChatEntryUiState =
            when (this) {
                is Start -> oldState.copy(isLoading = true)
                is Success -> oldState.copy(isLoading = false, rooms = rooms)
                is Failure -> oldState.copy(isLoading = false)
            }

        data object Start : Load()

        data class Success(
            val rooms: ImmutableList<ChatRoom>,
        ) : Load()

        data class Failure(val error: Throwable) : Load()
    }
}

@Immutable
data class ChatEntryUiState(
    val isLoading: Boolean = false,
    val rooms: ImmutableList<ChatRoom> = persistentListOf(),
) : UiState

sealed interface ChatEntryUiEvent : UiEvent
