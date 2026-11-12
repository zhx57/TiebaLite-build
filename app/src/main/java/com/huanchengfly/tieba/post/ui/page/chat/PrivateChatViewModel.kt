package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.api.chat.ws.ChatWebSocketClient
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.api.chat.ws.PrivateChatCache
import com.huanchengfly.tieba.post.api.models.chat.SendState
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.CommonUiEvent
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.utils.AccountUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * 私信聊天页 ViewModel。
 *
 * 与群聊一致：状态里只保存一份按时间正序的消息列表，UI 层倒序渲染。
 * 缓存先行展示，网络增量只做合并，绝不删除已有历史。
 */
@Stable
@HiltViewModel
class PrivateChatViewModel @Inject constructor() :
    BaseViewModel<PrivateChatUiIntent, PrivateChatPartialChange, PrivateChatUiState, PrivateChatUiEvent>() {

    private var socket: ChatWebSocketClient? = null

    override fun createInitialState(): PrivateChatUiState = PrivateChatUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<PrivateChatUiIntent, PrivateChatPartialChange, PrivateChatUiState> =
        PrivateChatPartialChangeProducer { socket }

    override fun dispatchEvent(partialChange: PrivateChatPartialChange): UiEvent? =
        when (partialChange) {
            is PrivateChatPartialChange.Load.Failure ->
                CommonUiEvent.Toast(partialChange.error.message ?: "加载私信失败")
            else -> null
        }

    override fun onCleared() {
        socket?.close()
        socket = null
        super.onCleared()
    }

    private fun obtainSocket(): ChatWebSocketClient =
        socket ?: ChatWebSocketClient().also { socket = it }

    /** 当前会话的对端 uid，收到推送时用来触发对应会话的增量拉取。 */
    private var activePeerUid: Long = 0L

    /**
     * 绑定当前会话并注册推送回调。
     *
     * 服务端推送只通知哪个消息组有新消息，不携带消息体，
     * 因此收到后立即触发一次 Poll，做到近实时刷新（轮询只作为兜底）。
     */
    fun bindPeer(peerUid: Long) {
        activePeerUid = peerUid
        obtainSocket().onPushNotify = { _ ->
            val uid = activePeerUid
            if (uid != 0L) {
                send(PrivateChatUiIntent.Poll(uid))
            }
        }
    }

    init {
        obtainSocket()
    }

    private class PrivateChatPartialChangeProducer(
        private val socketProvider: () -> ChatWebSocketClient?,
    ) : PartialChangeProducer<PrivateChatUiIntent, PrivateChatPartialChange, PrivateChatUiState> {

        private val pendingSeq = AtomicLong(0)
        private fun newPendingId(): Long = -pendingSeq.decrementAndGet()

        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(
            intentFlow: Flow<PrivateChatUiIntent>,
        ): Flow<PrivateChatPartialChange> =
            merge(
                intentFlow.filterIsInstance<PrivateChatUiIntent.Load>()
                    .flatMapMerge { it.producePartialChange() },
                intentFlow.filterIsInstance<PrivateChatUiIntent.Poll>()
                    .flatMapMerge { it.producePartialChange() },
                intentFlow.filterIsInstance<PrivateChatUiIntent.Send>()
                    .flatMapMerge { it.producePartialChange() },
                intentFlow.filterIsInstance<PrivateChatUiIntent.Resend>()
                    .flatMapMerge { it.producePartialChange() },
            )

        private fun PrivateChatUiIntent.Load.producePartialChange(): Flow<PrivateChatPartialChange.Load> =
            flow<PrivateChatPartialChange.Load> {
                emit(PrivateChatPartialChange.Load.Start)
                val client = socketProvider() ?: error("chat socket unavailable")
                val selfUid = AccountUtil.getUid()?.toLongOrNull() ?: 0L

                // 先展示本地缓存
                val cached = PrivateChatCache.load(selfUid, peerUid)
                if (cached.isNotEmpty()) {
                    emit(PrivateChatPartialChange.Load.Cached(cached.toImmutableList()))
                }

                client.connect(AccountUtil.getCookie().orEmpty())
                // 握手游标已是最新一条，单次请求拿不到历史；
                // 从头按批拉取才能补齐整个会话，避免进入会话看到空白。
                val incoming = client.fetchPrivateHistory()
                val peerMessages = incoming
                    .filter { it.isSelf || it.peerUid == peerUid }
                    .map { if (it.isSelf) it.copy(peerUid = peerUid) else it }
                val merged = mergePrivateMessages(cached, peerMessages)
                PrivateChatCache.save(client.selfUid, peerUid, merged)
                emit(PrivateChatPartialChange.Load.Success(peerMessages.toImmutableList()))
            }.catch<PrivateChatPartialChange.Load> {
                emit(PrivateChatPartialChange.Load.Failure(it))
            }

        private fun PrivateChatUiIntent.Poll.producePartialChange(): Flow<PrivateChatPartialChange.Poll> =
            flow<PrivateChatPartialChange.Poll> {
                val client = socketProvider() ?: error("chat socket unavailable")
                client.connect(AccountUtil.getCookie().orEmpty())
                val incoming = client.fetchPrivateMessages()
                val peerMessages = incoming
                    .filter { it.isSelf || it.peerUid == peerUid }
                    .map { if (it.isSelf) it.copy(peerUid = peerUid) else it }
                emit(PrivateChatPartialChange.Poll.Success(peerMessages.toImmutableList()))
            }.catch<PrivateChatPartialChange.Poll> {
                emit(PrivateChatPartialChange.Poll.Failure)
            }

        private fun PrivateChatUiIntent.Send.producePartialChange(): Flow<PrivateChatPartialChange.Send> {
            val id = newPendingId()
            return flow {
                emit(
                    PrivateChatPartialChange.Send.Start(
                        pendingId = id,
                        content = content,
                        peerUid = peerUid,
                    )
                )
                saveAndSend(content, peerUid)
                emit(PrivateChatPartialChange.Send.Success(id))
            }.catch { emit(PrivateChatPartialChange.Send.Failure(id, it)) }
        }

        private fun PrivateChatUiIntent.Resend.producePartialChange(): Flow<PrivateChatPartialChange.Send> {
            val id = newPendingId()
            return flow {
                emit(
                    PrivateChatPartialChange.Send.Start(
                        pendingId = id,
                        content = content,
                        peerUid = peerUid,
                        removeMsgId = failedMsgId,
                    )
                )
                saveAndSend(content, peerUid)
                emit(PrivateChatPartialChange.Send.Success(id))
            }.catch { emit(PrivateChatPartialChange.Send.Failure(id, it)) }
        }

        private suspend fun saveAndSend(content: String, peerUid: Long) {
            val client = socketProvider() ?: error("chat socket unavailable")
            client.connect(AccountUtil.getCookie().orEmpty())
            val msgId = client.sendMessage(peerUid, content)
            val cached = PrivateChatCache.load(client.selfUid, peerUid).toMutableList()
            cached += ChatWsMessage(
                msgId = msgId,
                peerUid = peerUid,
                peerName = "",
                peerPortrait = "",
                content = content,
                msgTime = System.currentTimeMillis(),
                isSelf = true,
            )
            PrivateChatCache.save(client.selfUid, peerUid, cached)
        }
    }
}

sealed interface PrivateChatUiIntent : UiIntent {
    data class Load(val peerUid: Long) : PrivateChatUiIntent
    data class Poll(val peerUid: Long) : PrivateChatUiIntent
    data class Send(val peerUid: Long, val content: String) : PrivateChatUiIntent
    data class Resend(
        val peerUid: Long,
        val failedMsgId: Long,
        val content: String,
    ) : PrivateChatUiIntent
}

/** 缓存与网络增量按 msgId 去重合并，按时间正序。 */
private fun mergePrivateMessages(
    current: List<ChatWsMessage>,
    incoming: List<ChatWsMessage>,
): List<ChatWsMessage> =
    (current + incoming)
        .associateBy { it.msgId }
        .values
        .sortedBy { it.msgTime }

/** 状态与网络增量合并，保留本地占位直到服务端回显同内容。 */
private fun mergeIntoState(
    current: List<ChatWsMessage>,
    incoming: List<ChatWsMessage>,
): ImmutableList<ChatWsMessage> {
    val existingIds = current.mapTo(HashSet()) { it.msgId }
    val serverSelfContents = incoming.filter { it.isSelf }.mapTo(HashSet()) { it.content }
    val kept = current.filterNot { local ->
        local.isLocal && local.sendState == SendState.SENT && local.content in serverSelfContents
    }
    val added = incoming.filterNot { it.msgId in existingIds }
    return (kept + added).sortedBy { it.msgTime }.toImmutableList()
}

sealed interface PrivateChatPartialChange : PartialChange<PrivateChatUiState> {
    sealed class Load : PrivateChatPartialChange {
        override fun reduce(oldState: PrivateChatUiState): PrivateChatUiState =
            when (this) {
                is Start -> oldState.copy(isLoading = true)
                is Cached -> oldState.copy(messages = mergeIntoState(oldState.messages, messages))
                is Success -> oldState.copy(
                    isLoading = false,
                    messages = mergeIntoState(oldState.messages, messages),
                )
                is Failure -> oldState.copy(isLoading = false)
            }

        data object Start : Load()
        data class Cached(val messages: ImmutableList<ChatWsMessage>) : Load()
        data class Success(val messages: ImmutableList<ChatWsMessage>) : Load()
        data class Failure(val error: Throwable) : Load()
    }

    sealed class Poll : PrivateChatPartialChange {
        override fun reduce(oldState: PrivateChatUiState): PrivateChatUiState =
            when (this) {
                is Success -> oldState.copy(messages = mergeIntoState(oldState.messages, latest))
                is Failure -> oldState
            }

        data class Success(val latest: ImmutableList<ChatWsMessage>) : Poll()
        data object Failure : Poll()
    }

    sealed class Send : PrivateChatPartialChange {
        override fun reduce(oldState: PrivateChatUiState): PrivateChatUiState =
            when (this) {
                is Start -> {
                    val base = oldState.messages.filterNot { it.msgId == removeMsgId }
                    oldState.copy(
                        messages = (base + ChatWsMessage(
                            msgId = pendingId,
                            peerUid = peerUid,
                            peerName = "",
                            peerPortrait = "",
                            content = content,
                            msgTime = System.currentTimeMillis(),
                            isSelf = true,
                            sendState = SendState.SENDING,
                            isLocal = true,
                        )).toImmutableList(),
                    )
                }

                is Success -> oldState.copy(
                    messages = oldState.messages.map {
                        if (it.msgId == pendingId) it.copy(sendState = SendState.SENT) else it
                    }.toImmutableList(),
                )

                is Failure -> oldState.copy(
                    messages = oldState.messages.map {
                        if (it.msgId == pendingId) it.copy(sendState = SendState.FAILED) else it
                    }.toImmutableList(),
                )
            }

        data class Start(
            val pendingId: Long,
            val content: String,
            val peerUid: Long,
            val removeMsgId: Long? = null,
        ) : Send()

        data class Success(val pendingId: Long) : Send()
        data class Failure(val pendingId: Long, val error: Throwable) : Send()
    }
}

@Immutable
data class PrivateChatUiState(
    val isLoading: Boolean = true,
    val messages: ImmutableList<ChatWsMessage> = persistentListOf(),
) : UiState

sealed interface PrivateChatUiEvent : UiEvent
