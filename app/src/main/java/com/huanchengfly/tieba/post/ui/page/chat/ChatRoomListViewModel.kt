package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.api.chat.http.ChatRoomListApi
import com.huanchengfly.tieba.post.api.models.chat.ChatRoom
import com.huanchengfly.tieba.post.api.models.chat.ChatRoomMessage
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

@Stable
@HiltViewModel
class ChatRoomListViewModel @Inject constructor() :
    BaseViewModel<ChatRoomListUiIntent, ChatRoomListPartialChange, ChatRoomListUiState, ChatRoomListUiEvent>() {

    override fun createInitialState(): ChatRoomListUiState = ChatRoomListUiState()

    override fun createPartialChangeProducer() = ChatRoomListPartialChangeProducer

    override fun dispatchEvent(partialChange: ChatRoomListPartialChange): UiEvent? =
        when (partialChange) {
            is ChatRoomListPartialChange.Load.Failure ->
                CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            else -> null
        }

    object ChatRoomListPartialChangeProducer :
        PartialChangeProducer<ChatRoomListUiIntent, ChatRoomListPartialChange, ChatRoomListUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<ChatRoomListUiIntent>): Flow<ChatRoomListPartialChange> =
            intentFlow.filterIsInstance<ChatRoomListUiIntent.Load>()
                .flatMapLatest { it.producePartialChange() }

        private fun ChatRoomListUiIntent.Load.producePartialChange(): Flow<ChatRoomListPartialChange.Load> =
            flow {
                emit(ChatRoomListPartialChange.Load.Start)
                // 按贴吧请求必须传入 fid，而非 room_id。
                val rooms = ChatRoomListApi.fetchRoomsByFid(fid)
                emit(ChatRoomListPartialChange.Load.Success(rooms.toImmutableList()))
            }.catch {
                if (it is kotlinx.coroutines.CancellationException) throw it
                emit(ChatRoomListPartialChange.Load.Failure(it))
            }
    }
}

sealed interface ChatRoomListUiIntent : UiIntent {
    data class Load(val fid: Long) : ChatRoomListUiIntent
}

sealed interface ChatRoomListPartialChange : PartialChange<ChatRoomListUiState> {
    sealed class Load : ChatRoomListPartialChange {
        override fun reduce(oldState: ChatRoomListUiState): ChatRoomListUiState =
            when (this) {
                is Start -> oldState.copy(isLoading = true, error = null)
                is Success -> oldState.copy(isLoading = false, rooms = rooms, error = null)
                is Failure -> oldState.copy(isLoading = false, error = error)
            }

        data object Start : Load()

        data class Success(val rooms: ImmutableList<ChatRoom>) : Load()

        data class Failure(val error: Throwable) : Load()
    }
}

@Immutable
data class ChatRoomListUiState(
    val isLoading: Boolean = false,
    val rooms: ImmutableList<ChatRoom> = persistentListOf(),
    val error: Throwable? = null,
) : UiState

sealed interface ChatRoomListUiEvent : UiEvent
