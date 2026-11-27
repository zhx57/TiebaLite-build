package com.huanchengfly.tieba.post.ui.page.chat

import android.net.Uri
import kotlinx.coroutines.CancellationException
import com.huanchengfly.tieba.post.components.uploadChatPhoto
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.api.chat.http.ImHttpConstants
import com.huanchengfly.tieba.post.api.chat.http.ImMessageApi
import com.huanchengfly.tieba.post.api.chat.http.ImSessionApi
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsConstants
import com.huanchengfly.tieba.post.api.models.chat.ChatCloudMeme
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageKind
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageSemantics
import com.huanchengfly.tieba.post.api.chat.ws.PrivateChatCache
import com.huanchengfly.tieba.post.api.chat.ws.privateConversationUid
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
 * 私信聊天页 ViewModel。
 *
 * 与群聊一致：状态里只保存一份按时间正序的消息列表，UI 层倒序渲染。
 * 缓存先行展示，网络增量只做合并，绝不删除已有历史。
 */
@Stable
@HiltViewModel
class PrivateChatViewModel @Inject constructor() :
    BaseViewModel<PrivateChatUiIntent, PrivateChatPartialChange, PrivateChatUiState, PrivateChatUiEvent>() {

    override fun createInitialState(): PrivateChatUiState = PrivateChatUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<PrivateChatUiIntent, PrivateChatPartialChange, PrivateChatUiState> =
        PrivateChatPartialChangeProducer()

    override fun dispatchEvent(partialChange: PrivateChatPartialChange): UiEvent? =
        when (partialChange) {
            is PrivateChatPartialChange.Load.Failure ->
                CommonUiEvent.Toast(partialChange.error.message ?: "加载私信失败")
            is PrivateChatPartialChange.Send.Failure ->
                CommonUiEvent.Toast(partialChange.error.message ?: "发送私信失败，可点击失败消息重试")
            else -> null
        }

    private class PrivateChatPartialChangeProducer :
        PartialChangeProducer<PrivateChatUiIntent, PrivateChatPartialChange, PrivateChatUiState> {

        private val pendingSeq = AtomicLong(0)
        private fun newPendingId(): Long = pendingSeq.decrementAndGet()
        private var httpCursorTimeMillis: Long = 0L
        private var historyLoaded = false
        private var cursorAccountUid = 0L
        private var cursorPeerUid = 0L

        private fun updateHttpCursor(selfUid: Long, peerUid: Long, cached: List<ChatWsMessage>) {
            if (cursorAccountUid != selfUid || cursorPeerUid != peerUid) {
                httpCursorTimeMillis = 0L
                historyLoaded = false
                cursorAccountUid = selfUid
                cursorPeerUid = peerUid
            }
            // HTTP 按会话的服务端毫秒时间同步，与旧 WS 的账号级 msgId 游标无关。
            httpCursorTimeMillis = maxOf(httpCursorTimeMillis, cached.privateHttpCursorTimeMillis())
        }

        // 重叠拉取边界毫秒，避免漏掉同一毫秒的消息；缓存按 msgId 去重。
        private fun httpBeginTimeMillis(): Long = (httpCursorTimeMillis - 1L).coerceAtLeast(0L)

        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(
            intentFlow: Flow<PrivateChatUiIntent>,
        ): Flow<PrivateChatPartialChange> =
            merge(
                intentFlow
                    .filter {
                        it is PrivateChatUiIntent.Load ||
                            it is PrivateChatUiIntent.Poll
                    }
                    .conflate()
                    .flatMapConcat { intent ->
                        when (intent) {
                            is PrivateChatUiIntent.Load -> intent.producePartialChange()
                            is PrivateChatUiIntent.Poll -> if (historyLoaded &&
                                cursorAccountUid == AccountUtil.getUid()?.toLongOrNull() &&
                                cursorPeerUid == intent.peerUid
                            ) {
                                intent.producePartialChange()
                            } else {
                                PrivateChatUiIntent.Load(intent.peerUid).producePartialChange()
                            }
                            else -> error("unexpected private chat refresh intent")
                        }
                    },
                intentFlow.filterIsInstance<PrivateChatUiIntent.Send>()
                    .flatMapMerge { it.producePartialChange() },
                intentFlow.filterIsInstance<PrivateChatUiIntent.Resend>()
                    .flatMapMerge { it.producePartialChange() },
            )

        private fun PrivateChatUiIntent.Load.producePartialChange(): Flow<PrivateChatPartialChange.Load> =
            flow<PrivateChatPartialChange.Load> {
                val selfUid = AccountUtil.getUid()?.toLongOrNull() ?: 0L
                emit(PrivateChatPartialChange.Load.Start(selfUid, peerUid))

                // 先展示本地缓存，避免等待网络时页面空白。
                val initialCached = PrivateChatCache.load(selfUid, peerUid)
                updateHttpCursor(selfUid, peerUid, initialCached)
                if (initialCached.isNotEmpty()) {
                    emit(PrivateChatPartialChange.Load.Cached(initialCached.toImmutableList(), selfUid, peerUid))
                }

                // 官方消息拉取走 rest/3.0/im/fetch_msg，对方 uid 需要加密后放在 contacter。
                val incoming = ImMessageApi.fetchMessages(peerUid, beginTime = httpBeginTimeMillis())
                    .mapNotNull { it.toChatWsMessage(selfUid, peerUid) }
                if (AccountUtil.getUid()?.toLongOrNull() != selfUid) return@flow
                PrivateChatCache.saveIncoming(selfUid, incoming, incoming.maxOfOrNull { it.msgId } ?: 0L)
                val cached = PrivateChatCache.load(selfUid, peerUid)
                updateHttpCursor(selfUid, peerUid, cached)
                historyLoaded = true
                emit(PrivateChatPartialChange.Load.Success(
                    cached.toImmutableList(), selfUid, peerUid,
                ))
            }.catch<PrivateChatPartialChange.Load> {
                if (it is CancellationException) throw it
                emit(PrivateChatPartialChange.Load.Failure(it))
            }

        private fun PrivateChatUiIntent.Poll.producePartialChange(): Flow<PrivateChatPartialChange.Poll> =
            flow<PrivateChatPartialChange.Poll> {
                val selfUid = AccountUtil.getUid()?.toLongOrNull() ?: 0L
                updateHttpCursor(selfUid, peerUid, PrivateChatCache.load(selfUid, peerUid))
                val incoming = ImMessageApi.fetchMessages(peerUid, beginTime = httpBeginTimeMillis())
                    .mapNotNull { it.toChatWsMessage(selfUid, peerUid) }
                if (AccountUtil.getUid()?.toLongOrNull() != selfUid) return@flow
                PrivateChatCache.saveIncoming(selfUid, incoming, incoming.maxOfOrNull { it.msgId } ?: 0L)
                val cached = PrivateChatCache.load(selfUid, peerUid)
                updateHttpCursor(selfUid, peerUid, cached)
                emit(PrivateChatPartialChange.Poll.Success(
                    cached.toImmutableList(), selfUid, peerUid,
                ))
            }.catch<PrivateChatPartialChange.Poll> {
                if (it is CancellationException) throw it
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
                        cloudMeme = cloudMeme,
                        localPhotoUri = photoUri?.toString(),
                    )
                )
                val message = saveAndSend(content, peerUid, cloudMeme, photoUri) { prepared ->
                    emit(PrivateChatPartialChange.Send.Prepared(id, prepared))
                }
                emit(PrivateChatPartialChange.Send.Success(id, message))
            }.catch {
                if (it is CancellationException) throw it
                emit(PrivateChatPartialChange.Send.Failure(id, it))
            }
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
                        cloudMeme = cloudMeme,
                        localPhotoUri = photoUri?.toString(),
                    )
                )
                val message = saveAndSend(content, peerUid, cloudMeme, photoUri) { prepared ->
                    emit(PrivateChatPartialChange.Send.Prepared(id, prepared))
                }
                emit(PrivateChatPartialChange.Send.Success(id, message))
            }.catch {
                if (it is CancellationException) throw it
                emit(PrivateChatPartialChange.Send.Failure(id, it))
            }
        }

        private suspend fun saveAndSend(
            content: String,
            peerUid: Long,
            cloudMeme: ChatCloudMeme?,
            photoUri: Uri?,
            onPrepared: suspend (ChatCloudMeme?) -> Unit,
        ): ChatWsMessage {
            val accountUid = AccountUtil.getUid()?.toLongOrNull() ?: error("请先登录")
            val preparedCloudMeme = photoUri?.let { uploadChatPhoto(it) } ?: cloudMeme
            check(AccountUtil.getUid()?.toLongOrNull() == accountUid) {
                "账号已切换，已取消发送"
            }
            onPrepared(preparedCloudMeme)
            val sentAt = System.currentTimeMillis()
            // 官方发送私信走 rest/3.0/im/send_msg，content 是裸 JSON。
            val msgId = ImMessageApi.sendMessage(
                peerUid = peerUid,
                text = content,
                imageUrl = preparedCloudMeme?.sendImageUrl,
                thumbnailUrl = preparedCloudMeme?.sendThumbnailUrl,
                width = preparedCloudMeme?.width ?: 0,
                height = preparedCloudMeme?.height ?: 0,
            )
            val message = ChatWsMessage(
                msgId = msgId,
                peerUid = peerUid,
                conversationUid = peerUid,
                senderUid = accountUid,
                peerName = "",
                peerPortrait = "",
                content = content,
                msgTime = sentAt,
                isSelf = true,
                msgType = if (preparedCloudMeme != null) ChatWsConstants.MSG_TYPE_PRIVATE_IMAGE else ChatWsConstants.MSG_TYPE_PRIVATE,
                images = preparedCloudMeme?.let { listOf(it.sendImageUrl) }.orEmpty(),
                cloudMeme = preparedCloudMeme ?: cloudMeme,
                semantics = ChatMessageSemantics(
                    kind = if (preparedCloudMeme == null) ChatMessageKind.TEXT else ChatMessageKind.IMAGE,
                    protocolSource = "local_outgoing",
                    transportType = if (preparedCloudMeme == null) ChatWsConstants.MSG_TYPE_PRIVATE else ChatWsConstants.MSG_TYPE_PRIVATE_IMAGE,
                    businessType = if (preparedCloudMeme == null) 1 else 2,
                    summary = if (preparedCloudMeme == null) content else "图片消息",
                ),
            )
            PrivateChatCache.save(accountUid, peerUid, listOf(message))
            return message
        }
    }
}

/**
 * 把官方 fetch_msg 的消息转成本地使用的消息模型。
 *
 * 官方消息里 from_pass_uk 解密后是发送者贴吧 uid，from_im_uk 是 IM 唯一键。
 */
internal fun ImMessageApi.Message.toChatWsMessage(selfUid: Long, peerUid: Long): ChatWsMessage? {
    // fetch_msg 已按 contacter 限定会话，但发送者仍必须是明确的会话参与者。
    if (selfUid <= 0L || peerUid <= 0L || fromUid <= 0L ||
        (fromUid != selfUid && fromUid != peerUid)
    ) return null
    val isImage = imageUrl.isNotBlank()
    val kind = when {
        isImage -> ChatMessageKind.IMAGE
        type == ImHttpConstants.TYPE_VOICE -> ChatMessageKind.VOICE
        else -> ChatMessageKind.TEXT
    }
    return ChatWsMessage(
        msgId = msgId,
        msgType = if (isImage) ChatWsConstants.MSG_TYPE_PRIVATE_IMAGE else ChatWsConstants.MSG_TYPE_PRIVATE,
        peerUid = peerUid,
        conversationUid = peerUid,
        peerName = "",
        peerPortrait = "",
        content = text,
        images = if (isImage) listOf(imageUrl) else emptyList(),
        msgTime = msgTime,
        isSelf = fromUid == selfUid,
        senderUid = fromUid,
        semantics = ChatMessageSemantics(
            kind = kind,
            protocolSource = "private_http",
            transportType = if (isImage) ChatWsConstants.MSG_TYPE_PRIVATE_IMAGE else ChatWsConstants.MSG_TYPE_PRIVATE,
            businessType = if (isImage) 2 else 1,
            summary = text.ifBlank { if (isImage) "图片消息" else "聊天消息" },
            rawPayload = ChatMessageSemantics.limitedRawPayload(rawContent),
        ),
    )
}

sealed interface PrivateChatUiIntent : UiIntent {
    data class Load(val peerUid: Long) : PrivateChatUiIntent
    data class Poll(val peerUid: Long) : PrivateChatUiIntent
    data class Send(
        val peerUid: Long,
        val content: String,
        val cloudMeme: ChatCloudMeme? = null,
        val photoUri: Uri? = null,
    ) : PrivateChatUiIntent
    data class Resend(
        val peerUid: Long,
        val failedMsgId: Long,
        val content: String,
        val cloudMeme: ChatCloudMeme? = null,
        val photoUri: Uri? = null,
    ) : PrivateChatUiIntent
}

// 仅使用已由服务端确认的有效时间，本地发送时间即使已有正数 msgId 也不能推进 HTTP 游标。
internal fun List<ChatWsMessage>.privateHttpCursorTimeMillis(): Long =
    asSequence()
        .filter { it.serverMsgId != null && it.deliveryConfirmed && it.msgTime > 0L }
        .maxOfOrNull { it.msgTime } ?: 0L

// 拉取响应只有发送者；self 消息仅在缓存已确认属于当前会话时保留。
internal fun List<ChatWsMessage>.forPrivatePeer(
    peerUid: Long,
    cached: List<ChatWsMessage> = emptyList(),
): List<ChatWsMessage> {
    val knownSelfIds = cached.filter {
        it.isSelf && (it.conversationUid == peerUid ||
            (it.conversationUid == 0L && it.peerUid == peerUid))
    }.mapTo(mutableSetOf()) { it.msgId }
    return filter { message ->
        val owner = message.privateConversationUid()
        owner == peerUid || (owner == 0L && message.isSelf && message.msgId in knownSelfIds)
    }
}

/** 缓存与网络增量按 msgId 去重合并，按时间正序。 */
private fun mergePrivateMessages(
    current: List<ChatWsMessage>,
    incoming: List<ChatWsMessage>,
): List<ChatWsMessage> {
    val messages = current.associateByTo(linkedMapOf()) { it.msgId }
    incoming.forEach { message ->
        val existing = messages[message.msgId]
        messages[message.msgId] = message.copy(
            peerUid = if (message.isSelf && existing?.isSelf == true) existing.peerUid else message.peerUid,
            conversationUid = message.conversationUid.takeIf { it > 0L } ?: existing?.conversationUid ?: 0L,
            senderUid = message.senderUid.takeIf { it > 0L } ?: existing?.senderUid ?: 0L,
            isLocal = if (message.isSelf && existing?.isSelf == true) existing.isLocal else message.isLocal,
            cloudMeme = existing?.cloudMeme ?: message.cloudMeme,
            deliveryConfirmed = message.deliveryConfirmed || existing?.deliveryConfirmed == true,
            semantics = if (message.isSelf && existing?.isSelf == true) existing.semantics else message.semantics,
        )
    }
    return messages.values.sortedBy { it.msgTime }
}

/** 本地发送确认通过 pendingId 替换，不按相同文案删除其他消息。 */
private fun mergeIntoState(
    current: List<ChatWsMessage>,
    incoming: List<ChatWsMessage>,
): ImmutableList<ChatWsMessage> = mergePrivateMessages(current, incoming).toImmutableList()

sealed interface PrivateChatPartialChange : PartialChange<PrivateChatUiState> {
    sealed class Load : PrivateChatPartialChange {
        override fun reduce(oldState: PrivateChatUiState): PrivateChatUiState =
            when (this) {
                is Start -> if (oldState.accountUid != accountUid || oldState.peerUid != peerUid)
                    PrivateChatUiState(accountUid = accountUid, peerUid = peerUid)
                else oldState.copy(isLoading = true)
                is Cached -> if (
                    (accountUid != 0L && oldState.accountUid != accountUid) ||
                    (peerUid != 0L && oldState.peerUid != peerUid)
                ) oldState else oldState.copy(messages = mergeIntoState(oldState.messages, messages))
                is Success -> if (
                    (accountUid != 0L && oldState.accountUid != accountUid) ||
                    (peerUid != 0L && oldState.peerUid != peerUid)
                ) oldState else oldState.copy(
                    isLoading = false,
                    messages = mergeIntoState(oldState.messages, messages),
                )
                is Failure -> oldState.copy(isLoading = false)
            }

        data class Start(val accountUid: Long, val peerUid: Long) : Load()
        data class Cached(
            val messages: ImmutableList<ChatWsMessage>,
            val accountUid: Long = 0L,
            val peerUid: Long = 0L,
        ) : Load()
        data class Success(
            val messages: ImmutableList<ChatWsMessage>,
            val accountUid: Long = 0L,
            val peerUid: Long = 0L,
        ) : Load()
        data class Failure(val error: Throwable) : Load()
    }

    sealed class Poll : PrivateChatPartialChange {
        override fun reduce(oldState: PrivateChatUiState): PrivateChatUiState =
            when (this) {
                is Success -> if (
                    (accountUid != 0L && oldState.accountUid != accountUid) ||
                    (peerUid != 0L && oldState.peerUid != peerUid)
                ) oldState else oldState.copy(messages = mergeIntoState(oldState.messages, latest))
                is Failure -> oldState
            }

        data class Success(
            val latest: ImmutableList<ChatWsMessage>,
            val accountUid: Long = 0L,
            val peerUid: Long = 0L,
        ) : Poll()
        data object Failure : Poll()
    }

    sealed class Send : PrivateChatPartialChange {
        override fun reduce(oldState: PrivateChatUiState): PrivateChatUiState =
            when (this) {
                is Start -> {
                    val base = oldState.messages.filterNot { it.msgId == removeMsgId }
                    val hasImage = cloudMeme != null || localPhotoUri != null
                    oldState.copy(
                        messages = (base + ChatWsMessage(
                            msgId = pendingId,
                            peerUid = peerUid,
                            conversationUid = peerUid,
                            peerName = "",
                            peerPortrait = "",
                            content = content,
                            msgTime = System.currentTimeMillis(),
                            isSelf = true,
                            sendState = SendState.SENDING,
                            isLocal = true,
                            msgType = if (hasImage) ChatWsConstants.MSG_TYPE_PRIVATE_IMAGE else ChatWsConstants.MSG_TYPE_PRIVATE,
                            images = localPhotoUri?.let { listOf(it) }
                                ?: cloudMeme?.let { listOf(it.sendImageUrl) }.orEmpty(),
                            cloudMeme = cloudMeme,
                            localPhotoUri = localPhotoUri,
                            semantics = ChatMessageSemantics(
                                kind = if (hasImage) ChatMessageKind.IMAGE else ChatMessageKind.TEXT,
                                protocolSource = "local_outgoing",
                                transportType = if (hasImage) ChatWsConstants.MSG_TYPE_PRIVATE_IMAGE else ChatWsConstants.MSG_TYPE_PRIVATE,
                                businessType = if (hasImage) 2 else 1,
                                summary = if (hasImage) "图片消息" else content,
                            ),
                        )).toImmutableList(),
                        sendRequestId = oldState.sendRequestId + 1,
                    )
                }

                is Prepared -> oldState.copy(messages = oldState.messages.map {
                    if (it.msgId == pendingId) it.copy(
                        cloudMeme = cloudMeme ?: it.cloudMeme,
                        images = cloudMeme?.let { meme -> listOf(meme.sendImageUrl) } ?: it.images,
                    ) else it
                }.toImmutableList())

                is Success -> if (
                    (oldState.accountUid > 0L && message.senderUid != oldState.accountUid) ||
                    (oldState.peerUid > 0L && message.conversationUid != oldState.peerUid)
                ) oldState else oldState.copy(
                    messages = mergeIntoState(
                        oldState.messages.filterNot { it.msgId == pendingId },
                        listOf(message),
                    ),
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
            val cloudMeme: ChatCloudMeme? = null,
            val localPhotoUri: String? = null,
        ) : Send()

        data class Prepared(val pendingId: Long, val cloudMeme: ChatCloudMeme?) : Send()
        data class Success(val pendingId: Long, val message: ChatWsMessage) : Send()
        data class Failure(val pendingId: Long, val error: Throwable) : Send()
    }
}

@Immutable
data class PrivateChatUiState(
    val sendRequestId: Long = 0L,
    val isLoading: Boolean = true,
    val messages: ImmutableList<ChatWsMessage> = persistentListOf(),
    val accountUid: Long = 0L,
    val peerUid: Long = 0L,
) : UiState

sealed interface PrivateChatUiEvent : UiEvent
