package com.huanchengfly.tieba.post.ui.page.chat

import android.util.Log
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.chat.ChatApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huanchengfly.tieba.post.api.chat.http.ImSessionApi
import com.huanchengfly.tieba.post.models.database.ChatConversation
import com.huanchengfly.tieba.post.repository.ChatHistoryRepository
import com.huanchengfly.tieba.post.utils.AccountUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationsUiState(
    val accountUid: Long = 0L,
    val conversations: List<ChatConversation> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val repository: ChatHistoryRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ConversationsUiState())
    val state = mutableState.asStateFlow()
    private var observeJob: Job? = null
    private var refreshJob: Job? = null
    private var importJob: Job? = null
    private val actionJobs = mutableListOf<Job>()
    private val resolvedProfiles = mutableSetOf<Long>()

    fun bindAccount(accountUid: Long) {
        if (mutableState.value.accountUid == accountUid && observeJob != null) return
        observeJob?.cancel()
        refreshJob?.cancel()
        importJob?.cancel()
        actionJobs.forEach { it.cancel() }
        actionJobs.clear()
        resolvedProfiles.clear()
        mutableState.value = if (mutableState.value.accountUid == accountUid) {
            mutableState.value.copy(error = null, refreshing = false)
        } else ConversationsUiState(accountUid = accountUid, loading = accountUid > 0L)
        if (accountUid <= 0L) return
        observeJob = viewModelScope.launch {
            repository.observeConversations(accountUid)
                .catch { error ->
                    if (error is CancellationException) throw error
                    updateAccount(accountUid) { it.copy(loading = false, error = "读取本地会话失败，请重试") }
                }
                .collect { conversations ->
                    updateAccount(accountUid) { it.copy(conversations = conversations, loading = false) }
                }
        }
        importJob = viewModelScope.launch {
            try {
                repository.importLegacyPrivate(accountUid)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                updateAccount(accountUid) { it.copy(error = "旧私信导入失败，请重试") }
            }
        }
    }

    fun refresh(accountUid: Long) {
        if (!isCurrentAccount(accountUid) || refreshJob?.isActive == true) return
        val account = AccountUtil.currentAccount?.takeIf { it.uid.toLongOrNull() == accountUid } ?: return
        val accountBduss = account.bduss.takeIf { it.isNotBlank() } ?: return
        val accountSToken = account.sToken
        refreshJob = viewModelScope.launch {
            updateAccount(accountUid) { it.copy(refreshing = true, error = null) }
            try {
                // 只同步已持久化的可见群，包含尚无消息的群，不做全量发现。
                val groups = repository.observeConversations(accountUid).first()
                    .filter { it.kind == ChatHistoryRepository.GROUP }
                currentCoroutineContext().ensureActive()
                if (!isCurrentAccount(accountUid)) return@launch
                var privateSyncFailed = false
                // Session 没有可证明的 roomId，绝不把解密 peerUid 当作群 ID。
                try {
                    val sessions = ImSessionApi.fetchSessions(accountBduss = accountBduss)
                    for (session in sessions) {
                        currentCoroutineContext().ensureActive()
                        if (!isCurrentAccount(accountUid)) return@launch
                        if (!session.isPrivateChat || session.peerUid <= 0L || session.peerUid == accountUid || session.paUid != 0L) continue
                        repository.upsertConversation(
                            accountUid = accountUid,
                            kind = "private",
                            conversationId = session.peerUid,
                            summary = session.summary,
                            lastMessageTime = session.lastTime,
                            unreadCount = session.unreadNum.coerceAtLeast(0),
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    privateSyncFailed = true
                    Log.w("Conversations", "private sync failed: ${error.javaClass.simpleName}")
                    updateAccount(accountUid) { it.copy(error = "私信同步失败，已保留缓存") }
                }
                var failedGroups = 0
                // 全部分批处理，不用数量上限静默遗漏群；单群失败不取消其他同步。
                for (batch in groups.chunked(4)) {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentAccount(accountUid)) return@launch
                    val results = coroutineScope {
                        batch.map { group ->
                            async {
                                try {
                                    currentCoroutineContext().ensureActive()
                                    if (!isCurrentAccount(accountUid)) return@async Result.success(Unit)
                                    val messages = ChatApi.getChatRoomMessages(
                                        group.conversationId,
                                        accountBduss = accountBduss,
                                        selfUid = accountUid,
                                    )
                                    currentCoroutineContext().ensureActive()
                                    if (!isCurrentAccount(accountUid)) return@async Result.success(Unit)
                                    repository.saveGroup(accountUid, group.conversationId, messages)
                                    Result.success(Unit)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    Log.w("Conversations", "group sync failed: ${error.javaClass.simpleName}")
                                    Result.failure<Unit>(error)
                                }
                            }
                        }.awaitAll()
                    }
                    failedGroups += results.count { it.isFailure }
                    if (failedGroups > 0) {
                        updateAccount(accountUid) {
                            it.copy(error = if (privateSyncFailed)
                                "私信及 ${failedGroups} 个群同步失败，已保留缓存"
                            else "${failedGroups} 个群同步失败，已保留缓存")
                        }
                    }
                }
                // 每个账号生命周期内最多查询一次成功资料，每轮限制五个，避免逐项阻塞会话同步。
                val peers = mutableState.value.conversations.filter { it.kind == "private" }
                    .map { it.conversationId }.filter { it !in resolvedProfiles }.take(5)
                for (peerUid in peers) {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentAccount(accountUid)) return@launch
                    try {
                        val user = TiebaApi.getInstance().getUserInfoFlow(peerUid, accountBduss, accountSToken)
                            .first().data_?.user ?: continue
                        currentCoroutineContext().ensureActive()
                        if (!isCurrentAccount(accountUid)) return@launch
                        repository.upsertConversation(
                            accountUid = accountUid, kind = "private", conversationId = peerUid,
                            name = user.nameShow.orEmpty(), avatar = user.portrait.orEmpty(),
                        )
                        resolvedProfiles += peerUid
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w("Conversations", "profile sync failed: ${error.javaClass.simpleName}")
                        updateAccount(accountUid) { it.copy(error = it.error ?: "部分用户资料暂不可用，聊天记录不受影响") }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("Conversations", "sync failed: ${error.javaClass.simpleName}")
                updateAccount(accountUid) { it.copy(error = "暂时无法同步，仍可查看本地聊天记录") }
            } finally {
                updateAccount(accountUid) { it.copy(loading = false, refreshing = false) }
            }
        }
    }

    fun retry(accountUid: Long) {
        if (!isCurrentAccount(accountUid)) return
        observeJob?.cancel()
        observeJob = null
        bindAccount(accountUid)
        refresh(accountUid)
    }

    fun togglePinned(conversation: ChatConversation) = change(conversation) {
        repository.setPinned(it.accountUid, it.kind, it.conversationId, !it.pinned)
    }

    fun hide(conversation: ChatConversation) = change(conversation) {
        repository.hideConversation(it.accountUid, it.kind, it.conversationId)
    }

    private fun change(conversation: ChatConversation, action: suspend (ChatConversation) -> Unit) {
        if (!isCurrentAccount(conversation.accountUid)) return
        actionJobs.removeAll { it.isCompleted }
        actionJobs += viewModelScope.launch {
            try {
                if (isCurrentAccount(conversation.accountUid)) action(conversation)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                updateAccount(conversation.accountUid) { it.copy(error = "会话操作失败，请重试") }
            }
        }
    }

    private fun isCurrentAccount(uid: Long): Boolean = uid > 0L &&
        mutableState.value.accountUid == uid && AccountUtil.getUid()?.toLongOrNull() == uid

    private fun updateAccount(uid: Long, transform: (ConversationsUiState) -> ConversationsUiState) {
        mutableState.update { if (it.accountUid == uid) transform(it) else it }
    }
}
