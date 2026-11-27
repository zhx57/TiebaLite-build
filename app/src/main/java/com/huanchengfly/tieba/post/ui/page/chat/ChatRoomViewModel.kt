package com.huanchengfly.tieba.post.ui.page.chat

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
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
import com.huanchengfly.tieba.post.components.uploadChatPhoto
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * 群聊消息页 ViewModel。
 *
 * 状态里只保存一份按时间正序的消息列表（最早在前、最新在后），UI 层倒序渲染。
 * 页面只消费本地历史快照，网络拉取和发送状态先写仓库。
 * 每个账号及群拥有独立任务，切换或离开取消观察与发送；重试保留消息身份。
 */
@Stable
@HiltViewModel
class ChatRoomViewModel @Inject constructor(
    private val history: com.huanchengfly.tieba.post.repository.ChatHistoryRepository,
) :
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

    override fun createPartialChangeProducer() = ChatRoomPartialChangeProducer({ history }, { closeSendSession() }) { sendSession() }

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
            is ChatRoomPartialChange.ForSession ->
                if (uiState.value.sessionToken == partialChange.token) dispatchEvent(partialChange.change) else null
            else -> null
        }

    class ChatRoomPartialChangeProducer(
        private val historyProvider: () -> com.huanchengfly.tieba.post.repository.ChatHistoryRepository,
        private val closeSender: () -> Unit,
        private val sender: () -> ChatSendApi.Session,
    ) : PartialChangeProducer<ChatRoomUiIntent, ChatRoomPartialChange, ChatRoomUiState> {
        private val history get() = historyProvider()

        override fun toPartialChangeFlow(intentFlow: Flow<ChatRoomUiIntent>): Flow<ChatRoomPartialChange> = channelFlow {
            var active: ChatRoomUiIntent.Load? = null
            var generation = ""
            var sessionJob: kotlinx.coroutines.Job? = null
            var observer: kotlinx.coroutines.Job? = null
            var fetch: kotlinx.coroutines.Job? = null
            var paging: kotlinx.coroutines.Job? = null
            var ready = false
            var visible = true
            var limit = 60
            val sending = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
            val recovered = HashSet<Pair<Long, Long>>()
            suspend fun publish(token: String, change: ChatRoomPartialChange) {
                if (token == generation) send(ChatRoomPartialChange.ForSession(token, change))
            }
            intentFlow.collect { intent ->
                if (intent is ChatRoomUiIntent.Load) {
                    closeSender()
                    sessionJob?.cancelAndJoin()
                    active = intent
                    generation = java.util.UUID.randomUUID().toString()
                    val token = generation
                    sessionJob = kotlinx.coroutines.Job(coroutineContext[kotlinx.coroutines.Job])
                    ready = false
                    visible = true
                    limit = 60
                    sending.clear()
                    send(ChatRoomPartialChange.OpenSession(token, intent.accountUid, intent.roomId))
                    launch(sessionJob!!) {
                        try {
                            if (intent.accountUid <= 0) return@launch
                            if ((intent.accountUid to intent.roomId) !in recovered) {
                                history.recoverInterrupted(intent.accountUid, "group", intent.roomId)
                                recovered.add(intent.accountUid to intent.roomId)
                            }
                            history.reopenConversation(intent.accountUid, "group", intent.roomId)
                            history.upsertConversation(intent.accountUid, "group", intent.roomId,
                                name = intent.roomName, forumId = intent.forumId)
                            val cached = history.loadGroup(intent.accountUid, intent.roomId, limit)
                            publish(token, ChatRoomPartialChange.LocalSnapshot(cached.toImmutableList()))
                            if (visible) history.markRead(intent.accountUid, "group", intent.roomId)
                            observer = launch {
                                history.observeGroup(intent.accountUid, intent.roomId, limit).distinctUntilChanged().collect { messages ->
                                    publish(token, ChatRoomPartialChange.LocalSnapshot(messages.toImmutableList()))
                                    if (visible) history.markRead(intent.accountUid, "group", intent.roomId)
                                }
                            }
                            ready = true
                            publish(token, ChatRoomPartialChange.Ready(true))
                            fetch = launch {
                                try {
                                    history.saveGroup(intent.accountUid, intent.roomId, ChatApi.getChatRoomMessages(intent.roomId, accountBduss = intent.accountBduss, selfUid = intent.accountUid))
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { publish(token, ChatRoomPartialChange.Load.Failure(e)) }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { publish(token, ChatRoomPartialChange.Load.Failure(e)) }
                    }
                    return@collect
                }
                val room = active ?: return@collect
                val token = generation
                val job = sessionJob ?: return@collect
                when (intent) {
                    is ChatRoomUiIntent.Stop -> if (intent.roomId == room.roomId && intent.accountUid == room.accountUid) {
                        publish(token, ChatRoomPartialChange.Ready(false))
                        generation = ""
                        ready = false
                        active = null
                        closeSender()
                        sessionJob?.cancelAndJoin()
                    }
                    is ChatRoomUiIntent.Visibility -> if (intent.roomId == room.roomId && intent.accountUid == room.accountUid) {
                        visible = intent.visible
                        if (visible && ready) launch(job) { history.markRead(room.accountUid, "group", room.roomId) }
                    }
                    is ChatRoomUiIntent.Poll -> if (ready && intent.roomId == room.roomId &&
                        intent.accountUid == room.accountUid && fetch?.isActive != true) {
                        fetch = launch(job) {
                            try { history.saveGroup(room.accountUid, room.roomId, ChatApi.getChatRoomMessages(room.roomId, accountBduss = room.accountBduss, selfUid = room.accountUid)) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { Log.w("ChatRoom", "poll failed: ${e.javaClass.simpleName}") }
                        }
                    }
                    is ChatRoomUiIntent.LoadMore -> if (ready && intent.roomId == room.roomId &&
                        intent.accountUid == room.accountUid && paging?.isActive != true) {
                        paging = launch(job) {
                            publish(token, ChatRoomPartialChange.LoadMore.Start)
                            try {
                                limit += 60
                                observer?.cancelAndJoin()
                                var cached = history.loadGroup(room.accountUid, room.roomId, limit)
                                publish(token, ChatRoomPartialChange.LocalSnapshot(cached.toImmutableList()))
                                var hasMore = true
                                if (cached.size < limit) {
                                    val cursor = cached.firstOrNull { !it.isLocal && it.msgId > 0 }?.msgId ?: 0L
                                    val older = ChatApi.getChatRoomMessages(room.roomId, cursor, accountBduss = room.accountBduss, selfUid = room.accountUid)
                                    history.saveGroup(room.accountUid, room.roomId, older)
                                    hasMore = older.size >= 60
                                    cached = history.loadGroup(room.accountUid, room.roomId, limit)
                                }
                                publish(token, ChatRoomPartialChange.LocalSnapshot(cached.toImmutableList(), hasMore))
                                publish(token, ChatRoomPartialChange.PageComplete)
                                observer = launch(job) {
                                    history.observeGroup(room.accountUid, room.roomId, limit).distinctUntilChanged().collect { messages ->
                                        publish(token, ChatRoomPartialChange.LocalSnapshot(messages.toImmutableList()))
                                        if (visible) history.markRead(room.accountUid, "group", room.roomId)
                                    }
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                publish(token, ChatRoomPartialChange.LoadMore.Failure(e))
                                // 网络分页失败不能中止本地历史的实时观察。
                                observer = launch(job) {
                                    history.observeGroup(room.accountUid, room.roomId, limit).distinctUntilChanged().collect { messages ->
                                        publish(token, ChatRoomPartialChange.LocalSnapshot(messages.toImmutableList()))
                                        if (visible) history.markRead(room.accountUid, "group", room.roomId)
                                    }
                                }
                            }
                        }
                    }
                    is ChatRoomUiIntent.Send -> if (ready && intent.roomId == room.roomId && intent.selfUid == room.accountUid &&
                        (intent.sessionToken.isBlank() || intent.sessionToken == token)) {
                        launch(job) { persistSend(intent, null, room, sending) { publish(token, it) } }
                    } else if (intent.draftId.isNotBlank()) {
                        publish(token, ChatRoomPartialChange.DraftResult(intent.draftId, false))
                    }
                    is ChatRoomUiIntent.Resend -> if (ready && intent.roomId == room.roomId && intent.selfUid == room.accountUid) {
                        launch(job) {
                            persistSend(ChatRoomUiIntent.Send(intent.roomId, intent.text, intent.forumId,
                                intent.forumLevel, intent.bduss, intent.selfName, intent.selfPortrait,
                                intent.selfUid, intent.cuidGalaxy2, intent.mentions, intent.robotSkill,
                                intent.cloudMeme, intent.photoUri), intent.failedMsgId, room, sending) { publish(token, it) }
                        }
                    }
                    is ChatRoomUiIntent.LoadRobots -> if (intent.roomId == room.roomId) launch(job) {
                        intent.producePartialChange().collect { publish(token, it) }
                    }
                    else -> Unit
                }
            }
        }

        private fun ChatRoomUiIntent.LoadRobots.producePartialChange(): Flow<ChatRoomPartialChange.Robots> =
            flow<ChatRoomPartialChange.Robots> {
                emit(ChatRoomPartialChange.Robots.Start)
                emit(ChatRoomPartialChange.Robots.Success(ChatApi.getChatRoomRobots(roomId).toImmutableList()))
            }.catch {
                if (it is CancellationException) throw it
                emit(ChatRoomPartialChange.Robots.Failure(it.getErrorMessage()))
            }

        private suspend fun persistSend(
            request: ChatRoomUiIntent.Send,
            retryId: Long?,
            room: ChatRoomUiIntent.Load,
            sending: MutableSet<Long>,
            publish: suspend (ChatRoomPartialChange) -> Unit,
        ) {
            val pendingId = retryId ?: newGroupPendingId()
            if (!sending.add(pendingId)) return
            lateinit var message: ChatRoomMessage
            var persisted = false
            try {
                var previous = retryId?.let { id ->
                    history.loadGroup(room.accountUid, room.roomId, Int.MAX_VALUE)
                        .firstOrNull { it.msgId == id && it.isLocal }
                }
                if (retryId != null && (previous == null || previous.sendState == SendState.SENDING ||
                        previous.sendState == SendState.SENT)) return
                if (previous?.sendState == SendState.UNKNOWN) {
                    // 用户已确认重复风险；先按精确键同步，已送达则不再发送。
                    history.saveGroup(room.accountUid, room.roomId, ChatApi.getChatRoomMessages(room.roomId, accountBduss = room.accountBduss, selfUid = room.accountUid))
                    previous = history.findGroup(room.accountUid, room.roomId, previous) ?: return
                    if (!previous.isLocal || previous.sendState == SendState.SENT) return
                }
                message = previous?.copy(sendState = SendState.SENDING, sendError = null,
                    transportAccepted = false, transmissionStarted = false) ?: ChatRoomPartialChange.Send.Start(
                    pendingId, request.text, request.selfUid, request.selfName, request.selfPortrait,
                    mentions = request.mentions, robotSkill = request.robotSkill,
                    cloudMeme = request.cloudMeme, localPhotoUri = request.photoUri?.toString(),
                ).reduce(ChatRoomUiState()).messages.single().copy(localId = java.util.UUID.randomUUID().toString())
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    history.saveGroup(room.accountUid, room.roomId, listOf(message))
                    persisted = true
                }
                publish(ChatRoomPartialChange.SendRequested)
                if (request.draftId.isNotBlank()) publish(ChatRoomPartialChange.DraftResult(request.draftId, true))
                suspend fun update(updated: ChatRoomMessage) {
                    history.saveGroup(room.accountUid, room.roomId, listOf(updated))
                    message = history.findGroup(room.accountUid, room.roomId, updated) ?: updated
                }
                val source = message.localPhotoUri?.let(Uri::parse)
                if (message.cloudMeme == null && source != null) {
                    val retained = history.retainAttachment(room.accountUid, source)
                    update(message.copy(localPhotoUri = retained.toString(), images = listOf(retained.toString())))
                    val prepared = uploadChatPhoto(retained)
                    update(message.copy(cloudMeme = prepared, images = listOf(prepared.sendImageUrl)))
                }
                if (message.sendState == SendState.SENT || message.serverMsgId != null) return
                val prepared = message.cloudMeme
                sender().sendChatRoomMessage(
                    chatroomId = room.roomId, text = message.text, forumId = request.forumId,
                    forumLevel = request.forumLevel, accountBduss = request.bduss,
                    selfName = request.selfName, selfPortrait = request.selfPortrait,
                    cuidGalaxy2 = request.cuidGalaxy2, mentions = message.mentions,
                    robotSkill = message.robotSkill, cloudMeme = prepared,
                    onTransmitting = { update(message.copy(transmissionStarted = true)) },
                    msgKey = message.msgKey.ifBlank { null },
                    onMessageKey = { key -> update(message.copy(msgKey = key)) },
                )
                update(message.copy(transportAccepted = true, transmissionStarted = true))
                if (message.sendState == SendState.SENT || message.serverMsgId != null) return
                delay(20_000)
                message = history.findGroup(room.accountUid, room.roomId, message) ?: message
                if (message.sendState != SendState.SENT && message.serverMsgId == null) {
                    update(message.copy(sendState = SendState.UNKNOWN, sendError = UNCONFIRMED_SEND_MESSAGE))
                }
            } catch (error: Exception) {
                if (persisted) {
                    val failed = message
                    val state = groupSendFailureState(failed.transmissionStarted, error)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        history.saveGroup(room.accountUid, room.roomId, listOf(failed.copy(
                            sendState = state,
                            sendError = if (state == SendState.UNKNOWN) UNCONFIRMED_SEND_MESSAGE
                                else if (error is CancellationException) "发送在传输前已中断，请重试"
                                else error.getErrorMessage(),
                        )))
                    }
                } else if (error !is CancellationException) {
                    if (request.draftId.isNotBlank()) publish(ChatRoomPartialChange.DraftResult(request.draftId, false))
                    publish(ChatRoomPartialChange.Load.Failure(error))
                }
                if (error is CancellationException) throw error
            } finally {
                sending.remove(pendingId)
            }
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
    data class Stop(val roomId: Long, val accountUid: Long) : ChatRoomUiIntent
    data class Visibility(val roomId: Long, val accountUid: Long, val visible: Boolean) : ChatRoomUiIntent
    /** 首次进入，加载最新一页。 */
    data class Load(
        val roomId: Long,
        val accountUid: Long = 0L,
        val roomName: String = "",
        val forumId: Long = 0L,
        val accountBduss: String = "",
    ) : ChatRoomUiIntent {
        override fun toString() = "Load(roomId=$roomId, accountUid=$accountUid)"
    }

    data class LoadRobots(val roomId: Long) : ChatRoomUiIntent

    /** 定时增量拉取，不得清空已有历史。 */
    data class Poll(val roomId: Long, val accountUid: Long = 0L) : ChatRoomUiIntent

    data class LoadMore(val roomId: Long, val oldestMsgId: Long, val accountUid: Long = 0L) : ChatRoomUiIntent

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
        val photoUri: Uri? = null,
        val draftId: String = "",
        val sessionToken: String = "",
    ) : ChatRoomUiIntent {
        override fun toString() = "Outgoing(roomId=$roomId, accountUid=$selfUid)"
    }

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
        val photoUri: Uri? = null,
    ) : ChatRoomUiIntent {
        override fun toString() = "Outgoing(roomId=$roomId, accountUid=$selfUid)"
    }
}

/** 增量合并服务端消息，并在上传完成后关联已见的回显。 */
internal fun mergeServerMessages(
    current: List<ChatRoomMessage>,
    incoming: List<ChatRoomMessage>,
): ImmutableList<ChatRoomMessage> {
    val merged = current.toMutableList()
    var changed = false
    for (server in incoming.filterNot { it.isLocal }.distinctBy { it.msgId }) {
        val existing = merged.indexOfFirst { !it.isLocal && it.msgId == server.msgId }
        val local = merged.indexOfFirst {
            it.isLocal && it.msgKey.isNotBlank() && it.msgKey == server.msgKey
        }
        if (local >= 0) {
            val pending = merged[local]
            merged[local] = server.copy(localId = pending.localId, displayTime = pending.displayTime,
                echoForPendingId = pending.msgId, cloudMeme = pending.cloudMeme ?: server.cloudMeme)
            if (existing >= 0) merged.removeAt(existing)
            changed = true
        } else if (existing < 0) {
            merged.add(server)
            changed = true
        }
    }
    if (!changed) return current as? ImmutableList<ChatRoomMessage> ?: current.toImmutableList()
    // 仅精确身份能够确认送达，正文或时间相同不构成证据。
    return merged.sortedBy { it.displayTime }.toImmutableList()
}

internal const val UNCONFIRMED_SEND_MESSAGE =
    "接口已返回，但暂未在群历史中确认此消息。可能仍在审核或同步，请先检查再重发，避免重复。"

internal fun newGroupPendingId(): Long =
    -(java.util.UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1L)

internal fun groupSendFailureState(transmissionStarted: Boolean, error: Throwable): SendState {
    val rejected = generateSequence(error) { it.cause }.any {
        it is com.huanchengfly.tieba.post.api.chat.blcp.BlcpBusinessException ||
            it is com.huanchengfly.tieba.post.api.chat.ChatApiException
    }
    return if (transmissionStarted && !rejected) SendState.UNKNOWN else SendState.FAILED
}

sealed interface ChatRoomPartialChange : PartialChange<ChatRoomUiState> {
    data class Ready(val ready: Boolean) : ChatRoomPartialChange {
        override fun reduce(oldState: ChatRoomUiState) = oldState.copy(ready = ready)
    }
    data class DraftResult(val draftId: String, val persisted: Boolean) : ChatRoomPartialChange {
        override fun reduce(oldState: ChatRoomUiState) = oldState.copy(
            completedDraftId = draftId, draftPersisted = persisted,
        )
    }
    data class OpenSession(val token: String, val accountUid: Long, val roomId: Long) : ChatRoomPartialChange {
        override fun reduce(oldState: ChatRoomUiState) = ChatRoomUiState(
            sessionToken = token, accountUid = accountUid, roomId = roomId,
            isLoading = accountUid > 0,
        )
    }
    data class ForSession(val token: String, val change: ChatRoomPartialChange) : ChatRoomPartialChange {
        override fun reduce(oldState: ChatRoomUiState) =
            if (oldState.sessionToken == token) change.reduce(oldState) else oldState
    }
    data class LocalSnapshot(
        val messages: ImmutableList<ChatRoomMessage>, val hasMore: Boolean? = null,
    ) : ChatRoomPartialChange {
        override fun reduce(oldState: ChatRoomUiState) = oldState.copy(
            messages = messages, isLoading = false, hasMore = hasMore ?: oldState.hasMore,
        )
    }
    data object PageComplete : ChatRoomPartialChange {
        override fun reduce(oldState: ChatRoomUiState) = oldState.copy(isLoadingMore = false)
    }
    data object SendRequested : ChatRoomPartialChange {
        override fun reduce(oldState: ChatRoomUiState) = oldState.copy(sendRequestId = oldState.sendRequestId + 1)
    }
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
                is Transmitting -> oldState.copy(messages = oldState.messages.map { message ->
                    if (message.msgId == pendingId) message.copy(
                        transmissionStarted = true,
                        msgTime = System.currentTimeMillis(),
                        knownServerIdsAtSend = oldState.messages.filterNot { it.isLocal }
                            .mapTo(HashSet()) { it.msgId },
                    ) else message
                }.toImmutableList())

                is Prepared -> {
                    val prepared = oldState.messages.map {
                        if (it.msgId == pendingId) it.copy(
                            images = preparedCloudMeme?.let { meme -> listOf(meme.sendImageUrl) } ?: it.images,
                            cloudMeme = preparedCloudMeme ?: it.cloudMeme,
                        ) else it
                    }.toImmutableList()
                    oldState.copy(messages = mergeServerMessages(prepared, prepared.filterNot { it.isLocal }))
                }

                is Start -> {
                    val base = oldState.messages.filterNot { it.msgId == removeMsgId }
                    oldState.copy(
                        messages = (base + ChatRoomMessage(
                            msgId = pendingId,
                            senderUid = selfUid,
                            senderName = selfName,
                            senderPortrait = selfPortrait,
                            contentType = if (cloudMeme == null && localPhotoUri == null) 0 else 1,
                            text = text,
                            images = cloudMeme?.let { listOf(it.sendImageUrl) }
                                ?: localPhotoUri?.let { listOf(it) }.orEmpty(),
                            msgTime = System.currentTimeMillis(),
                            isSelf = true,
                            sendState = SendState.SENDING,
                            isLocal = true,
                            localPhotoUri = localPhotoUri,
                            knownServerIdsAtSend = base.filterNot { it.isLocal }.mapTo(HashSet()) { it.msgId },
                            transmissionStarted = false,
                            mentions = mentions,
                            robotSkill = robotSkill,
                            cloudMeme = cloudMeme,
                            semantics = ChatMessageSemantics(
                                kind = if (cloudMeme == null && localPhotoUri == null) ChatMessageKind.TEXT else ChatMessageKind.IMAGE,
                                protocolSource = "local_outgoing",
                                transportType = if (cloudMeme == null && localPhotoUri == null) 0 else 1,
                                businessType = if (cloudMeme == null && localPhotoUri == null) 0 else 1,
                                summary = if (cloudMeme == null && localPhotoUri == null) text else "图片消息",
                            ),
                        )).toImmutableList(),
                        sendRequestId = oldState.sendRequestId + 1,
                    )
                }

                is Success -> {
                    val echo = oldState.messages.firstOrNull { it.echoForPendingId == pendingId }
                    val accepted = oldState.messages.mapNotNull {
                        when {
                            it.msgId == pendingId && echo != null -> null
                            it.msgId == pendingId -> it.copy(
                                transportAccepted = true,
                                transmissionStarted = true,
                                images = preparedCloudMeme?.let { meme -> listOf(meme.sendImageUrl) } ?: it.images,
                                cloudMeme = preparedCloudMeme ?: it.cloudMeme,
                            )
                            else -> it
                        }
                    }.toImmutableList()
                    oldState.copy(messages = mergeServerMessages(accepted, accepted.filterNot { it.isLocal }))
                }

                is Unconfirmed -> oldState.copy(messages = oldState.messages.map {
                    if (it.msgId == pendingId && it.isLocal && it.transportAccepted)
                        it.copy(sendState = SendState.UNKNOWN, sendError = UNCONFIRMED_SEND_MESSAGE)
                    else it
                }.toImmutableList())

                is Failure -> {
                    val echoed = oldState.messages.any { it.echoForPendingId == pendingId }
                    oldState.copy(messages = oldState.messages.mapNotNull {
                        when {
                            it.msgId == pendingId && echoed -> null
                            it.msgId == pendingId -> it.copy(sendState = groupSendFailureState(it.transmissionStarted, error),
                                sendError = if (groupSendFailureState(it.transmissionStarted, error) == SendState.UNKNOWN)
                                    UNCONFIRMED_SEND_MESSAGE else error.getErrorMessage())
                            else -> it
                        }
                    }.toImmutableList())
                }
            }

        data class Transmitting(val pendingId: Long) : Send()

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
            val localPhotoUri: String? = null,
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
    val ready: Boolean = false,
    val completedDraftId: String = "",
    val draftPersisted: Boolean = false,
    val sessionToken: String = "",
    val accountUid: Long = 0L,
    val roomId: Long = 0L,
) : UiState

sealed interface ChatRoomUiEvent : UiEvent
