package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.GetForumListBean
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
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * 群聊入口页的 ViewModel：拉取当前账号关注的贴吧列表，作为进入吧群的入口。
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
                CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            else -> null
        }

    object ChatEntryPartialChangeProducer :
        PartialChangeProducer<ChatEntryUiIntent, ChatEntryPartialChange, ChatEntryUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(
            intentFlow: Flow<ChatEntryUiIntent>,
        ): Flow<ChatEntryPartialChange> =
            intentFlow.filterIsInstance<ChatEntryUiIntent.Load>()
                .flatMapConcat {
                    flow {
                        emit(ChatEntryPartialChange.Load.Start)
                        val bean = TiebaApi.getInstance().getForumListFlow().first()
                        emit(
                            ChatEntryPartialChange.Load.Success(
                                bean.forumInfo.toImmutableList()
                            )
                        )
                    }.catch { emit(ChatEntryPartialChange.Load.Failure(it)) }
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
                is Success -> oldState.copy(isLoading = false, forums = forums)
                is Failure -> oldState.copy(isLoading = false)
            }

        data object Start : Load()

        data class Success(
            val forums: ImmutableList<GetForumListBean.ForumInfo>,
        ) : Load()

        data class Failure(val error: Throwable) : Load()
    }
}

@Immutable
data class ChatEntryUiState(
    val isLoading: Boolean = false,
    val forums: ImmutableList<GetForumListBean.ForumInfo> = persistentListOf(),
) : UiState

sealed interface ChatEntryUiEvent : UiEvent
