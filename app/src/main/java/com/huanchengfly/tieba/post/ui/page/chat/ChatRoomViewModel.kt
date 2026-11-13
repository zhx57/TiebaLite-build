package com.huanchengfly.tieba.post.ui.page.chat

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import com.huanchengfly.tieba.post.api.chat.ChatApi
import com.huanchengfly.tieba.post.api.chat.blcp.ChatSendApi
import com.huanchengfly.tieba.post.api.models.chat.ChatMention
import com.huanchengfly.tieba.post.api.models.chat.ChatRobot
import com.huanchengfly.tieba.post.api.models.chat.ChatRoomMessage
import com.huanchengfly.tieba.post.api.models.chat.SendState
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * 群聊消息页 ViewModel。
 *
 * 状态里只保存一份按时间正序的消息列表（最早在前、最新在后），UI 层倒序渲染。
 * 三类拉取职责严格分离：Load 全量替换、LoadMore 头部追加、Poll 增量合并，
 * 轮询绝不删除已加载历史。发送使用 flatMapMerge 并发，每条消息带独立 pendingId，
 * 不再用全局 isSending 锁禁止连续发送。
 */
@Stable
@HiltViewModel
class ChatRoomViewModel @Inject constructor() :
    BaseViewModel<ChatRoomUiIntent, ChatRoomPartialChange, ChatRoomUiState, ChatRoomUiEvent>() {

    override fun createInitialState(): ChatRoomUiState = ChatRoomUiState()

    private var sender: ChatSendApi.Session? = null

    @Synchronized
    private fun sendSession() = sender ?: ChatSendApi.Session(viewModelScope).also { sender = it }

    @Synchronized
    fun closeSendSession() {
        sender?.release()
        sender = null
    }

    override fun onCleared() {
        closeSendSession()
        super.onCleared()
    }

    override fun createPartialChangeProducer() = ChatRoomPartialChangeProducer { sendSession() }

    override fun dispatchEvent(partialChange: ChatRoomPartialChange): UiEvent? =
        when (partialChange) {
            is ChatRoomPartialChange.Load.Failure ->
                CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is ChatRoomPartialChange.LoadMore.Failure ->
                CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is ChatRoomPartialChange.Send.Failure ->
                if (uiState.value.messages.any { it.echoForPendingId == partialChange.pendingId }) null
                else CommonUiEvent.Toast(partialChange.error.getErrorMessage())
            is ChatRoomPartialChange.Send.Unconfirmed ->
                if (uiState.value.messages.any { it.msgId == partialChange.pendingId && it.isLocal })
                    CommonUiEvent.Toast(UNCONFIRMED_SEND_MESSAGE) else null
            else -> null
        }

    class ChatRoomPartialChangeProducer(private val sender: () -> ChatSendApi.Session) :
        PartialChangeProducer<ChatRoomUiIntent, ChatRoomPartialChange, ChatRoomUiState> {

        /** 本地占位用递减负数，避免与服务端正数 msgId 冲突。 */
        private val pendingSeq = AtomicLong(0)

        private fun newPendingId(): Long = pendingSeq.decrementAndGet()

        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<ChatRoomUiIntent>): Flow<ChatRoomPartialChange> =
            merge(
                // 拉取串行执行且只保留最新待处理意图，不取消慢请求，也不堆积轮询。
                intentFlow
                    .filter { it is ChatRoomUiIntent.Load || it is ChatRoomUiIntent.Poll }
                    .conflate()
                    .flatMapConcat {
                        when (it) {
                            is ChatRoomUiIntent.Load -> it.producePartialChange()
                            is ChatRoomUiIntent.Poll -> it.producePartialChange()
                            else -> error("Unexpected message fetch intent")
                        }
                    },
                intentFlow.filterIsInstance<ChatRoomUiIntent.LoadMore>()
                    .flatMapMerge { it.producePartialChange() },
                intentFlow.filterIsInstance<ChatRoomUiIntent.Send>()
                    .flatMapMerge { it.producePartialChange() },
                intentFlow.filterIsInstance<ChatRoomUiIntent.Resend>()
                    .flatMapMerge { it.producePartialChange() },
                intentFlow.filterIsInstance<ChatRoomUiIntent.LoadRobots>()
                    .flatMapMerge { it.producePartialChange() },
            )

        private fun ChatRoomUiIntent.Load.producePartialChange(): Flow<ChatRoomPartialChange.Load> =
            flow<ChatRoomPartialChange.Load> {
                emit(ChatRoomPartialChange.Load.Start)
                val messages = ChatApi.getChatRoomMessages(roomId)
                    .sortedBy { it.msgTime }
                    .toImmutableList()
                emit(ChatRoomPartialChange.Load.Success(messages))
            }.catch<ChatRoomPartialChange.Load> {
                emit(ChatRoomPartialChange.Load.Failure(it))
            }

        /** 定时轮询：只上报新拉取到的一页，由 reduce 增量合并，不清空历史。 */
        private fun ChatRoomUiIntent.Poll.producePartialChange(): Flow<ChatRoomPartialChange.Poll> =
            flow<ChatRoomPartialChange.Poll> {
                val latest = ChatApi.getChatRoomMessages(roomId)
                emit(ChatRoomPartialChange.Poll.Success(latest.toImmutableList()))
            }.catch<ChatRoomPartialChange.Poll> {
                // 轮询失败不该打断用户，但需要留下日志便于排查
                Log.w("ChatRoom", "poll messages failed", it)
                emit(ChatRoomPartialChange.Poll.Failure)
            }

        /** 向上翻页：以当前最旧消息 msgId 为游标，只把旧消息追加到头部。 */
        private fun ChatRoomUiIntent.LoadMore.producePartialChange(): Flow<ChatRoomPartialChange.LoadMore> =
            flow<ChatRoomPartialChange.LoadMore> {
                emit(ChatRoomPartialChange.LoadMore.Start)
                val older = ChatApi.getChatRoomMessages(roomId, oldestMsgId)
                    .sortedBy { it.msgTime }
                    .toImmutableList()
                emit(ChatRoomPartialChange.LoadMore.Success(older))
            }.catch<ChatRoomPartialChange.LoadMore> {
                emit(ChatRoomPartialChange.LoadMore.Failure(it))
            }

        private fun ChatRoomUiIntent.LoadRobots.producePartialChange(): Flow<ChatRoomPartialChange.Robots> =
            flow<ChatRoomPartialChange.Robots> {
                emit(ChatRoomPartialChange.Robots.Start)
                emit(ChatRoomPartialChange.Robots.Success(ChatApi.getChatRoomRobots(roomId).toImmutableList()))
            }.catch { emit(ChatRoomPartialChange.Robots.Failure(it.getErrorMessage())) }

        private fun ChatRoomUiIntent.Send.producePartialChange(): Flow<ChatRoomPartialChange.Send> {
            val pendingId = newPendingId()
            val session = sender()
            return flow {
                emit(
                    ChatRoomPartialChange.Send.Start(
                        pendingId = pendingId,
                        text = text,
                        selfUid = selfUid,
                        selfName = selfName,
                        selfPortrait = selfPortrait,
                        mentions = mentions,
                        robotSkill = robotSkill,
                    )
                )
                session.sendChatRoomMessage(
                    chatroomId = roomId,
                    text = text,
                    forumId = forumId,
                    forumLevel = forumLevel,
                    accountBduss = bduss,
                    selfName = selfName,
                    selfPortrait = selfPortrait,
                    cuidGalaxy2 = cuidGalaxy2,
                    mentions = mentions,
                    robotSkill = robotSkill,
                )
                emit(ChatRoomPartialChange.Send.Success(pendingId))
                delay(20_000)
                emit(ChatRoomPartialChange.Send.Unconfirmed(pendingId))
            }.catch { emit(ChatRoomPartialChange.Send.Failure(pendingId, it)) }
        }

        private fun ChatRoomUiIntent.Resend.producePartialChange(): Flow<ChatRoomPartialChange.Send> {
            val pendingId = newPendingId()
            val session = sender()
            return flow {
                emit(
                    ChatRoomPartialChange.Send.Start(
                        pendingId = pendingId,
                        text = text,
                        selfUid = selfUid,
                        selfName = selfName,
                        selfPortrait = selfPortrait,
                        mentions = mentions,
                        robotSkill = robotSkill,
                        removeMsgId = failedMsgId,
                    )
                )
                session.sendChatRoomMessage(
                    chatroomId = roomId,
                    text = text,
                    forumId = forumId,
                    forumLevel = forumLevel,
                    accountBduss = bduss,
                    selfName = selfName,
                    selfPortrait = selfPortrait,
                    cuidGalaxy2 = cuidGalaxy2,
                    mentions = mentions,
                    robotSkill = robotSkill,
                )
                emit(ChatRoomPartialChange.Send.Success(pendingId))
                delay(20_000)
                emit(ChatRoomPartialChange.Send.Unconfirmed(pendingId))
            }.catch { emit(ChatRoomPartialChange.Send.Failure(pendingId, it)) }
        }
    }
}

sealed interface ChatRoomUiIntent : UiIntent {
    /** 首次进入，加载最新一页。 */
    data class Load(val roomId: Long) : ChatRoomUiIntent

    data class LoadRobots(val roomId: Long) : ChatRoomUiIntent

    /** 定时增量拉取，不得清空已有历史。 */
    data class Poll(val roomId: Long) : ChatRoomUiIntent

    data class LoadMore(val roomId: Long, val oldestMsgId: Long) : ChatRoomUiIntent

    data class Send(
        val roomId: Long,
        val text: String,
        val forumId: Long,
        val forumLevel: Int = 0,
        val bduss: String,
        val selfName: String,
        val selfPortrait: String,
        val selfUid: Long,
        val cuidGalaxy2: String,
        val mentions: List<ChatMention> = emptyList(),
        val robotSkill: Int? = null,
    ) : ChatRoomUiIntent

    data class Resend(
        val roomId: Long,
        val failedMsgId: Long,
        val text: String,
        val forumId: Long,
        val forumLevel: Int = 0,
        val bduss: String,
        val selfName: String,
        val selfPortrait: String,
        val selfUid: Long,
        val cuidGalaxy2: String,
        val mentions: List<ChatMention> = emptyList(),
        val robotSkill: Int? = null,
    ) : ChatRoomUiIntent
}

/** 只插入新 ID；轮询无变化时保留列表引用，不重排整份历史。 */
internal fun mergeServerMessages(
    current: List<ChatRoomMessage>,
    incoming: List<ChatRoomMessage>,
): ImmutableList<ChatRoomMessage> {
    val existingIds = current.mapTo(HashSet()) { it.msgId }
    val added = incoming.filter { !it.isLocal && existingIds.add(it.msgId) }
    if (added.isEmpty()) return current as? ImmutableList<ChatRoomMessage> ?: current.toImmutableList()
    val reserved = current.mapNotNullTo(HashSet()) { it.echoForPendingId }
    val locals = current.filter {
        it.isLocal && (it.sendState != SendState.FAILED || it.transportAccepted) && it.msgId !in reserved
    }.toMutableList()
    val merged = current.toMutableList()
    for (server in added.sortedBy { it.msgTime }) {
        val candidate = if (server.isSelf) locals.firstOrNull { local ->
            local.text == server.text && local.robotSkill == server.robotSkill &&
                local.mentions.map { it.baiduUk } == server.mentions.map { it.baiduUk } &&
                server.msgTime >= local.msgTime - 5_000L &&
                server.msgTime <= local.msgTime + 120_000L
        } else null
        var message = server
        if (candidate != null) {
            locals.remove(candidate)
            merged.remove(candidate)
            // 历史回显才是成功依据；保留关联，防止迟到的超时覆盖成功。
            message = server.copy(echoForPendingId = candidate.msgId)
        }
        var low = 0
        var high = merged.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (merged[mid].msgTime <= message.msgTime) low = mid + 1 else high = mid
        }
        merged.add(low, message)
    }
    return merged.toImmutableList()
}

internal const val UNCONFIRMED_SEND_MESSAGE =
    "接口已返回，但暂未在群历史中确认此消息。可能仍在审核或同步，请先检查再重发，避免重复。"

sealed interface ChatRoomPartialChange : PartialChange<ChatRoomUiState> {
    sealed class Load : ChatRoomPartialChange {
        override fun reduce(oldState: ChatRoomUiState): ChatRoomUiState =
            when (this) {
                is Start -> oldState.copy(isLoading = true)
                is Success -> oldState.copy(
                    isLoading = false,
                    messages = mergeServerMessages(oldState.messages, messages),
                    hasMore = messages.size >= 60,
                )
                is Failure -> oldState.copy(isLoading = false)
            }

        data object Start : Load()
        data class Success(val messages: ImmutableList<ChatRoomMessage>) : Load()
        data class Failure(val error: Throwable) : Load()
    }

    sealed class Poll : ChatRoomPartialChange {
        override fun reduce(oldState: ChatRoomUiState): ChatRoomUiState =
            when (this) {
                is Success -> oldState.copy(messages = mergeServerMessages(oldState.messages, latest))
                is Failure -> oldState
            }

        data class Success(val latest: ImmutableList<ChatRoomMessage>) : Poll()
        data object Failure : Poll()
    }

    sealed class LoadMore : ChatRoomPartialChange {
        override fun reduce(oldState: ChatRoomUiState): ChatRoomUiState =
            when (this) {
                is Start -> oldState.copy(isLoadingMore = true)
                is Success -> {
                    val existingIds = oldState.messages.mapTo(HashSet()) { it.msgId }
                    val added = older.filterNot { it.msgId in existingIds }
                    oldState.copy(
                        isLoadingMore = false,
                        messages = (added + oldState.messages).toImmutableList(),
                        hasMore = added.isNotEmpty() && older.size >= 60,
                    )
                }
                is Failure -> oldState.copy(isLoadingMore = false)
            }

        data object Start : LoadMore()
        data class Success(val older: ImmutableList<ChatRoomMessage>) : LoadMore()
        data class Failure(val error: Throwable) : LoadMore()
    }

    sealed class Robots : ChatRoomPartialChange {
        override fun reduce(oldState: ChatRoomUiState): ChatRoomUiState = when (this) {
            Start -> oldState.copy(isLoadingRobots = true, robotError = null)
            is Success -> oldState.copy(isLoadingRobots = false, robots = robots, robotError = null)
            is Failure -> oldState.copy(isLoadingRobots = false, robotError = message)
        }
        data object Start : Robots()
        data class Success(val robots: ImmutableList<ChatRobot>) : Robots()
        data class Failure(val message: String) : Robots()
    }

    sealed class Send : ChatRoomPartialChange {
        override fun reduce(oldState: ChatRoomUiState): ChatRoomUiState =
            when (this) {
                is Start -> {
                    val base = oldState.messages.filterNot { it.msgId == removeMsgId }
                    oldState.copy(
                        messages = (base + ChatRoomMessage(
                            msgId = pendingId,
                            senderUid = selfUid,
                            senderName = selfName,
                            senderPortrait = selfPortrait,
                            contentType = 0,
                            text = text,
                            msgTime = System.currentTimeMillis(),
                            isSelf = true,
                            sendState = SendState.SENDING,
                            isLocal = true,
                            mentions = mentions,
                            robotSkill = robotSkill,
                        )).toImmutableList(),
                        sendRequestId = oldState.sendRequestId + 1,
                    )
                }

                is Success -> {
                    val echo = oldState.messages.firstOrNull { it.echoForPendingId == pendingId }
                    oldState.copy(messages = oldState.messages.mapNotNull {
                        when {
                            it.msgId == pendingId && echo != null -> null
                            it.msgId == pendingId -> it.copy(transportAccepted = true)
                            else -> it
                        }
                    }.toImmutableList())
                }

                is Unconfirmed -> oldState.copy(messages = oldState.messages.map {
                    if (it.msgId == pendingId && it.isLocal && it.transportAccepted)
                        it.copy(sendState = SendState.FAILED, sendError = UNCONFIRMED_SEND_MESSAGE)
                    else it
                }.toImmutableList())

                is Failure -> {
                    val echoed = oldState.messages.any { it.echoForPendingId == pendingId }
                    oldState.copy(messages = oldState.messages.mapNotNull {
                        when {
                            it.msgId == pendingId && echoed -> null
                            it.msgId == pendingId -> it.copy(sendState = SendState.FAILED, sendError = error.getErrorMessage())
                            else -> it
                        }
                    }.toImmutableList())
                }
            }

        data class Start(
            val pendingId: Long,
            val text: String,
            val selfUid: Long,
            val selfName: String,
            val selfPortrait: String,
            val removeMsgId: Long? = null,
            val mentions: List<ChatMention> = emptyList(),
            val robotSkill: Int? = null,
        ) : Send()

        data class Success(val pendingId: Long) : Send()
        data class Unconfirmed(val pendingId: Long) : Send()
        data class Failure(val pendingId: Long, val error: Throwable) : Send()
    }
}

@Immutable
data class ChatRoomUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val messages: ImmutableList<ChatRoomMessage> = persistentListOf(),
    val robots: ImmutableList<ChatRobot> = persistentListOf(),
    val isLoadingRobots: Boolean = false,
    val robotError: String? = null,
    val sendRequestId: Long = 0L,
) : UiState

sealed interface ChatRoomUiEvent : UiEvent
