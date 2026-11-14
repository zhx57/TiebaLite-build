package com.huanchengfly.tieba.post.ui.page.chat

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import com.huanchengfly.tieba.post.api.chat.ChatApi
import com.huanchengfly.tieba.post.api.chat.blcp.ChatSendApi
import com.huanchengfly.tieba.post.api.models.chat.ChatCloudMeme
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageKind
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageSemantics
import com.huanchengfly.tieba.post.api.models.chat.ChatMention
import com.huanchengfly.tieba.post.api.models.chat.ChatRobot
import com.huanchengfly.tieba.post.api.models.chat.ChatRoomMessage
import com.huanchengfly.tieba.post.api.models.chat.SendState
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.components.ImageUploader
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
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
            ).withSendConfirmationTimeout()

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
                        cloudMeme = cloudMeme,
                    )
                )
                val preparedCloudMeme = cloudMeme?.let { ImageUploader("").uploadCloudMeme(it) }
                emit(ChatRoomPartialChange.Send.Prepared(pendingId, preparedCloudMeme))
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
                    cloudMeme = preparedCloudMeme,
                )
                emit(ChatRoomPartialChange.Send.Success(pendingId, preparedCloudMeme))
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
                        cloudMeme = cloudMeme,
                        removeMsgId = failedMsgId,
                    )
                )
                val preparedCloudMeme = cloudMeme?.let { ImageUploader("").uploadCloudMeme(it) }
                emit(ChatRoomPartialChange.Send.Prepared(pendingId, preparedCloudMeme))
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
                    cloudMeme = preparedCloudMeme,
                )
                emit(ChatRoomPartialChange.Send.Success(pendingId, preparedCloudMeme))
            }.catch { emit(ChatRoomPartialChange.Send.Failure(pendingId, it)) }
        }
    }
}

internal fun Flow<ChatRoomPartialChange>.withSendConfirmationTimeout(
    timeoutMillis: Long = 20_000,
): Flow<ChatRoomPartialChange> = channelFlow {
    collect { change ->
        send(change)
        if (change is ChatRoomPartialChange.Send.Success) {
            launch {
                delay(timeoutMillis)
                send(ChatRoomPartialChange.Send.Unconfirmed(change.pendingId))
            }
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
        val cloudMeme: ChatCloudMeme? = null,
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
        val cloudMeme: ChatCloudMeme? = null,
    ) : ChatRoomUiIntent
}

/** 增量合并服务端消息，并在上传完成后关联已见的回显。 */
internal fun mergeServerMessages(
    current: List<ChatRoomMessage>,
    incoming: List<ChatRoomMessage>,
): ImmutableList<ChatRoomMessage> {
    val merged = current.toMutableList()
    val reserved = current.mapNotNullTo(HashSet()) { it.echoForPendingId }
    val locals = current.filter {
        it.isLocal && (it.sendState != SendState.FAILED || it.transportAccepted) && it.msgId !in reserved
    }.toMutableList()
    fun comparableImage(url: String): String = url.toHttpUrlOrNull()?.let {
        val port = if (it.port == (if (it.scheme == "https") 443 else 80)) "" else ":${it.port}"
        it.host + port + it.encodedPath + (it.encodedQuery?.let { query -> "?$query" } ?: "")
    } ?: url
    val servers = incoming.filterNot { it.isLocal }.distinctBy { it.msgId }.sortedBy { it.msgTime }
    var changed = false
    for (server in servers) {
        val existingIndex = merged.indexOfFirst { it.msgId == server.msgId && !it.isLocal }
        val existing = existingIndex.takeIf { it >= 0 }?.let { merged[it] }
        val candidate = if (server.isSelf && existing?.echoForPendingId == null) locals.firstOrNull { local ->
            local.text == server.text && local.contentType == server.contentType &&
                local.images.map(::comparableImage) == server.images.map(::comparableImage) &&
                local.robotSkill == server.robotSkill &&
                local.mentions.map { it.baiduUk } == server.mentions.map { it.baiduUk } &&
                (existing != null || (
                    server.msgTime >= local.msgTime - 5_000L &&
                        server.msgTime <= local.msgTime + 120_000L
                ))
        } else null
        if (candidate != null) {
            locals.remove(candidate)
            val message = server.copy(
                displayTime = candidate.displayTime,
                echoForPendingId = candidate.msgId,
                cloudMeme = candidate.cloudMeme,
            )
            val candidateIndex = merged.indexOf(candidate)
            if (existingIndex >= 0) {
                merged.removeAt(existingIndex)
                merged[candidateIndex - if (existingIndex < candidateIndex) 1 else 0] = message
            } else {
                merged[candidateIndex] = message
            }
            changed = true
        } else if (existingIndex < 0) {
            var low = 0
            var high = merged.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (merged[mid].displayTime <= server.msgTime) low = mid + 1 else high = mid
            }
            merged.add(low, server)
            changed = true
        }
    }
    if (!changed) return current as? ImmutableList<ChatRoomMessage> ?: current.toImmutableList()
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
                is Prepared -> oldState.copy(messages = oldState.messages.map {
                    if (it.msgId == pendingId) it.copy(
                        images = preparedCloudMeme?.let { meme -> listOf(meme.sendImageUrl) } ?: it.images,
                        cloudMeme = preparedCloudMeme ?: it.cloudMeme,
                    ) else it
                }.toImmutableList())

                is Start -> {
                    val base = oldState.messages.filterNot { it.msgId == removeMsgId }
                    oldState.copy(
                        messages = (base + ChatRoomMessage(
                            msgId = pendingId,
                            senderUid = selfUid,
                            senderName = selfName,
                            senderPortrait = selfPortrait,
                            contentType = if (cloudMeme == null) 0 else 1,
                            text = text,
                            images = cloudMeme?.let { listOf(it.sendImageUrl) }.orEmpty(),
                            msgTime = System.currentTimeMillis(),
                            isSelf = true,
                            sendState = SendState.SENDING,
                            isLocal = true,
                            mentions = mentions,
                            robotSkill = robotSkill,
                            cloudMeme = cloudMeme,
                            semantics = ChatMessageSemantics(
                                kind = if (cloudMeme == null) ChatMessageKind.TEXT else ChatMessageKind.IMAGE,
                                protocolSource = "local_outgoing",
                                transportType = if (cloudMeme == null) 0 else 1,
                                businessType = if (cloudMeme == null) 1 else 2,
                                summary = if (cloudMeme == null) text else "图片消息",
                            ),
                        )).toImmutableList(),
                        sendRequestId = oldState.sendRequestId + 1,
                    )
                }

                is Success -> {
                    val echo = oldState.messages.firstOrNull { it.echoForPendingId == pendingId }
                    oldState.copy(messages = oldState.messages.mapNotNull {
                        when {
                            it.msgId == pendingId && echo != null -> null
                            it.msgId == pendingId -> it.copy(
                                transportAccepted = true,
                                images = preparedCloudMeme?.let { meme -> listOf(meme.sendImageUrl) } ?: it.images,
                                cloudMeme = preparedCloudMeme ?: it.cloudMeme,
                            )
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

        data class Prepared(
            val pendingId: Long,
            val preparedCloudMeme: ChatCloudMeme?,
        ) : Send()

        data class Start(
            val pendingId: Long,
            val text: String,
            val selfUid: Long,
            val selfName: String,
            val selfPortrait: String,
            val removeMsgId: Long? = null,
            val mentions: List<ChatMention> = emptyList(),
            val robotSkill: Int? = null,
            val cloudMeme: ChatCloudMeme? = null,
        ) : Send()

        data class Success(
            val pendingId: Long,
            val preparedCloudMeme: ChatCloudMeme? = null,
        ) : Send()
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
