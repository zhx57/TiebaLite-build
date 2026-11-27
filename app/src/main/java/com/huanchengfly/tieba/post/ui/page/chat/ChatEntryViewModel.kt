package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshotFlow
import com.huanchengfly.tieba.post.utils.AccountUtil
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * 群聊列表 ViewModel。
 *
 * 订阅群先展示，关注贴吧的公开群逐批补充；刷新和失败期间保留已有群。
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
            merge(
                intentFlow.filterIsInstance<ChatEntryUiIntent.Load>(),
                snapshotFlow { AccountUtil.getUid() }.distinctUntilChanged().drop(1)
                    .map { ChatEntryUiIntent.Load },
            ).flatMapLatest { it.producePartialChange() }

        private fun ChatEntryUiIntent.Load.producePartialChange(): Flow<ChatEntryPartialChange.Load> =
            flow {
                val uid = AccountUtil.getUid()
                emit(ChatEntryPartialChange.Load.Start(uid))
                if (uid == null) {
                    emit(ChatEntryPartialChange.Load.Update(persistentListOf(), true, accountUid = uid))
                    return@flow
                }
                ChatRoomListApi.fetchAllRooms().catch { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    emit(ChatRoomListApi.RoomListUpdate(emptyList(), true, e, 1))
                }.collect { update ->
                    if (AccountUtil.getUid() != uid) {
                        emit(ChatEntryPartialChange.Load.Start(AccountUtil.getUid()))
                        throw kotlinx.coroutines.CancellationException("账号已切换")
                    }
                    emit(ChatEntryPartialChange.Load.Update(
                        update.rooms.toImmutableList(), update.isComplete,
                        update.error, update.failedCount, uid,
                    ))
                }
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
                is Start -> if (oldState.accountUid == accountUid) {
                    oldState.copy(isLoading = true, error = null, failedCount = 0)
                } else ChatEntryUiState(isLoading = true, accountUid = accountUid)
                is Update -> if (oldState.accountUid != accountUid) oldState else oldState.copy(
                    isLoading = !isComplete,
                    hasLoaded = oldState.hasLoaded || isComplete,
                    rooms = when {
                        error != null || !isComplete ->
                            ChatRoomListApi.mergeRooms(rooms, oldState.rooms).toImmutableList()
                        else -> rooms
                    },
                    error = error,
                    failedCount = failedCount,
                )
                is Failure -> if (oldState.accountUid != accountUid) oldState else oldState.copy(
                    isLoading = false, hasLoaded = true, error = error, failedCount = 1,
                )
            }

        data class Start(val accountUid: String?) : Load()

        data class Update(
            val rooms: ImmutableList<ChatRoom>,
            val isComplete: Boolean,
            val error: Throwable? = null,
            val failedCount: Int = 0,
            val accountUid: String?,
        ) : Load()

        data class Failure(val error: Throwable, val accountUid: String?) : Load()
    }
}

@Immutable
data class ChatEntryUiState(
    val isLoading: Boolean = false,
    val rooms: ImmutableList<ChatRoom> = persistentListOf(),
    val hasLoaded: Boolean = false,
    val error: Throwable? = null,
    val failedCount: Int = 0,
    val accountUid: String? = null,
) : UiState

sealed interface ChatEntryUiEvent : UiEvent
