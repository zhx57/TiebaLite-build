package com.huanchengfly.tieba.post.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.api.chat.ws.PrivateChatCache
import com.huanchengfly.tieba.post.api.chat.ws.privateConversationUid
import com.huanchengfly.tieba.post.api.models.chat.ChatRoomMessage
import com.huanchengfly.tieba.post.api.models.chat.SendState
import com.huanchengfly.tieba.post.database.AppDatabase
import com.huanchengfly.tieba.post.models.database.ChatConversation
import com.huanchengfly.tieba.post.models.database.ChatLegacyImport
import com.huanchengfly.tieba.post.models.database.ChatMessageEntity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatHistoryRepository @Inject constructor(
    private val database: AppDatabase,
    @ApplicationContext private val context: Context,
) {
    private val dao get() = database.chatHistoryDao()

    companion object {
        const val GROUP = "group"
        const val PRIVATE = "private"
    }

    private fun validate(accountUid: Long, kind: String, id: Long) {
        require(accountUid > 0 && id > 0)
        require(kind == GROUP || kind == PRIVATE)
        require(kind != PRIVATE || accountUid != id)
    }

    fun observeGroup(accountUid: Long, roomId: Long, limit: Int = 60): Flow<List<ChatRoomMessage>> {
        validate(accountUid, GROUP, roomId)
        require(limit > 0)
        return dao.observeMessages(accountUid, GROUP, roomId, limit).map { rows ->
            rows.asReversed().map { ChatMessageCodec.decodeGroup(it.payload) }
        }
    }

    fun observePrivate(accountUid: Long, peerUid: Long, limit: Int = 60): Flow<List<ChatWsMessage>> {
        validate(accountUid, PRIVATE, peerUid)
        require(limit > 0)
        return dao.observeMessages(accountUid, PRIVATE, peerUid, limit).map { rows ->
            rows.asReversed().map { ChatMessageCodec.decodePrivate(it.payload) }
        }
    }

    suspend fun loadGroup(accountUid: Long, roomId: Long, limit: Int = 60): List<ChatRoomMessage> {
        validate(accountUid, GROUP, roomId)
        require(limit > 0)
        return dao.loadMessages(accountUid, GROUP, roomId, limit).asReversed()
            .map { ChatMessageCodec.decodeGroup(it.payload) }
    }

    suspend fun loadPrivate(accountUid: Long, peerUid: Long, limit: Int = 60): List<ChatWsMessage> {
        validate(accountUid, PRIVATE, peerUid)
        require(limit > 0)
        return dao.loadMessages(accountUid, PRIVATE, peerUid, limit).asReversed()
            .map { ChatMessageCodec.decodePrivate(it.payload) }
    }

    suspend fun findGroup(accountUid: Long, roomId: Long, message: ChatRoomMessage): ChatRoomMessage? {
        validate(accountUid, GROUP, roomId)
        return dao.matches(accountUid, GROUP, roomId, message.localId,
            message.serverMsgId, message.msgKey.takeIf { it.isNotBlank() })
            .firstOrNull()?.let { ChatMessageCodec.decodeGroup(it.payload) }
    }

    suspend fun saveGroup(accountUid: Long, roomId: Long, messages: List<ChatRoomMessage>) {
        validate(accountUid, GROUP, roomId)
        database.withTransaction {
            messages.forEach { message ->
                val row = row(accountUid, GROUP, roomId, message.localId, message.serverMsgId,
                    message.msgKey, if (message.serverMsgId != null && message.msgTime > 0) message.msgTime
                        else message.displayTime, ChatMessageCodec.encodeGroup(message))
                save(row, message.isSelf, message.isLocal, message.text.ifBlank { message.semantics.summary })
            }
        }
    }

    suspend fun findPrivate(accountUid: Long, peerUid: Long, message: ChatWsMessage): ChatWsMessage? {
        validate(accountUid, PRIVATE, peerUid)
        return dao.matches(accountUid, PRIVATE, peerUid, message.localId,
            message.serverMsgId, message.msgKey.takeIf { it.isNotBlank() })
            .firstOrNull()?.let { ChatMessageCodec.decodePrivate(it.payload) }
    }

    suspend fun savePrivate(accountUid: Long, peerUid: Long, messages: List<ChatWsMessage>) {
        validate(accountUid, PRIVATE, peerUid)
        // 使用显式会话参数接收未携带收件人的服务端自身消息。
        messages.forEach { message ->
            val owner = message.privateConversationUid(accountUid)
            require(owner == 0L || owner == peerUid) { "消息属于其他会话" }
        }
        database.withTransaction { savePrivateInTransaction(accountUid, peerUid, messages) }
    }

    private suspend fun savePrivateInTransaction(accountUid: Long, peerUid: Long, messages: List<ChatWsMessage>) {
        messages.forEach { original ->
            val message = original.copy(conversationUid = peerUid)
            val row = row(accountUid, PRIVATE, peerUid, message.localId, message.serverMsgId,
                message.msgKey, message.msgTime, ChatMessageCodec.encodePrivate(message))
            save(row, message.isSelf, message.isLocal, message.content.ifBlank { message.semantics.summary })
            if (!message.isSelf) {
                val conversation = dao.conversation(accountUid, PRIVATE, peerUid)!!
                dao.putConversation(conversation.copy(
                    name = message.peerName.ifBlank { conversation.name },
                    avatar = message.peerPortrait.ifBlank { conversation.avatar },
                ))
            }
        }
    }

    private fun row(accountUid: Long, kind: String, id: Long, localId: String, serverId: Long?,
                    msgKey: String, time: Long, payload: String) = ChatMessageEntity(
        accountUid, kind, id, localId.ifBlank { UUID.randomUUID().toString() },
        serverId, msgKey.takeIf { it.isNotBlank() }, time, payload,
    )

    private fun merge(old: ChatMessageEntity, incoming: ChatMessageEntity): ChatMessageEntity {
        val payload = if (old.kind == GROUP) {
            ChatMessageCodec.encodeGroup(ChatMessageCodec.mergeGroup(
                ChatMessageCodec.decodeGroup(old.payload).copy(localId = old.localId),
                ChatMessageCodec.decodeGroup(incoming.payload),
            ))
        } else {
            ChatMessageCodec.encodePrivate(ChatMessageCodec.mergePrivate(
                ChatMessageCodec.decodePrivate(old.payload).copy(localId = old.localId),
                ChatMessageCodec.decodePrivate(incoming.payload),
            ))
        }
        val serverId = if (old.kind == GROUP) ChatMessageCodec.decodeGroup(payload).serverMsgId
            else ChatMessageCodec.decodePrivate(payload).serverMsgId
        return old.copy(
            serverId = serverId ?: old.serverId,
            sortTime = if (incoming.serverId != null && incoming.sortTime > 0 &&
                incoming.serverId == serverId) incoming.sortTime else old.sortTime,
            msgKey = incoming.msgKey ?: old.msgKey,
            payload = payload,
        )
    }

    private fun withIdentity(row: ChatMessageEntity): ChatMessageEntity = row.copy(
        payload = if (row.kind == GROUP) ChatMessageCodec.encodeGroup(
            ChatMessageCodec.decodeGroup(row.payload).copy(localId = row.localId, msgKey = row.msgKey.orEmpty()),
        ) else ChatMessageCodec.encodePrivate(
            ChatMessageCodec.decodePrivate(row.payload).copy(localId = row.localId, msgKey = row.msgKey.orEmpty()),
        ),
    )

    /** 必须在 Room 事务中调用，使身份对账和会话变更保持原子性。 */
    private suspend fun save(incoming: ChatMessageEntity, isSelf: Boolean, isLocal: Boolean, summary: String) {
        val matches = dao.matches(incoming.accountUid, incoming.kind, incoming.conversationId,
            incoming.localId, incoming.serverId, incoming.msgKey)
        // 对账桥接待发送项与独立回显时，优先保留最早的本地逻辑消息身份。
        val ordered = matches.sortedWith(compareBy<ChatMessageEntity> { it.serverId != null }
            .thenBy { it.sortTime }.thenBy { it.localId != incoming.localId }.thenBy { it.localId })
        var saved = ordered.firstOrNull() ?: withIdentity(incoming)
        ordered.drop(1).forEach { saved = merge(saved, it) }
        if (ordered.isNotEmpty()) saved = merge(saved, incoming)
        ordered.filter { it.localId != saved.localId }.forEach {
            dao.deleteMatched(it.accountUid, it.kind, it.conversationId, it.localId)
        }
        saved = withIdentity(saved)
        dao.putMessage(saved)
        val current = dao.conversation(saved.accountUid, saved.kind, saved.conversationId)
            ?: ChatConversation(saved.accountUid, saved.kind, saved.conversationId)
        val isNew = matches.isEmpty()
        val localSend = isSelf && isLocal
        val afterHide = saved.sortTime > current.hiddenAt
        val reopen = current.hiddenAt != 0L && ((isNew && afterHide) || localSend)
        val latest = dao.loadMessages(saved.accountUid, saved.kind, saved.conversationId, 1).first()
        val latestSummary = messageSummary(latest)
        dao.putConversation(current.copy(
            summary = if (latest.sortTime >= current.lastMessageTime) latestSummary.ifBlank { summary } else current.summary,
            lastMessageTime = maxOf(current.lastMessageTime, latest.sortTime),
            unreadCount = if (isNew && !isSelf && saved.sortTime > current.readAt &&
                (current.hiddenAt == 0L || afterHide))
                (current.unreadCount.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else current.unreadCount,
            hiddenAt = if (reopen) 0 else current.hiddenAt,
        ))
    }

    private fun messageSummary(row: ChatMessageEntity): String = if (row.kind == GROUP) {
        ChatMessageCodec.decodeGroup(row.payload).let { message ->
            summary(message.text, message.semantics, message.images.isNotEmpty() ||
                message.localPhotoUri != null || message.cloudMeme != null,
                message.voiceUrl != null, message.sendState)
        }
    } else {
        ChatMessageCodec.decodePrivate(row.payload).let { message ->
            summary(message.content, message.semantics, message.images.isNotEmpty() ||
                message.localPhotoUri != null || message.cloudMeme != null, false, message.sendState)
        }
    }

    private fun summary(text: String, semantics: com.huanchengfly.tieba.post.api.models.chat.ChatMessageSemantics,
                        hasImage: Boolean, hasVoice: Boolean, state: SendState): String {
        val body = when {
            hasVoice || semantics.kind == com.huanchengfly.tieba.post.api.models.chat.ChatMessageKind.VOICE -> "[语音]"
            hasImage || semantics.kind == com.huanchengfly.tieba.post.api.models.chat.ChatMessageKind.IMAGE -> "[图片]"
            text.isNotBlank() -> text
            else -> semantics.summary.ifBlank { "未知消息" }
        }
        return when (state) {
            SendState.FAILED -> "[发送失败] $body"
            SendState.UNKNOWN -> "[结果未确认] $body"
            SendState.SENDING -> "[发送中] $body"
            SendState.SENT -> body
        }
    }

    fun observeConversations(accountUid: Long): Flow<List<ChatConversation>> {
        require(accountUid > 0)
        return dao.observeConversations(accountUid)
    }

    suspend fun upsertConversation(
        accountUid: Long, kind: String, conversationId: Long, name: String = "", avatar: String = "",
        forumId: Long = 0, summary: String = "", lastMessageTime: Long = 0, unreadCount: Int? = null,
    ) {
        validate(accountUid, kind, conversationId)
        require(unreadCount == null || unreadCount >= 0)
        database.withTransaction {
            val old = dao.conversation(accountUid, kind, conversationId)
                ?: ChatConversation(accountUid, kind, conversationId)
            val time = lastMessageTime
            dao.putConversation(old.copy(
                name = name.ifBlank { old.name }, avatar = avatar.ifBlank { old.avatar },
                forumId = forumId.takeIf { it > 0 } ?: old.forumId,
                summary = if (time >= old.lastMessageTime) summary.ifBlank { old.summary } else old.summary,
                lastMessageTime = maxOf(old.lastMessageTime, time),
                unreadCount = when {
                    time <= old.readAt -> old.unreadCount
                    time < old.lastMessageTime -> old.unreadCount
                    old.hiddenAt != 0L && time <= old.hiddenAt -> old.unreadCount
                    else -> unreadCount ?: old.unreadCount
                },
                hiddenAt = if (old.hiddenAt != 0L && time > old.hiddenAt) 0 else old.hiddenAt,
            ))
        }
    }

    private suspend fun changeConversation(accountUid: Long, kind: String, id: Long,
                                           change: (ChatConversation) -> ChatConversation) {
        validate(accountUid, kind, id)
        database.withTransaction {
            val old = dao.conversation(accountUid, kind, id) ?: ChatConversation(accountUid, kind, id)
            dao.putConversation(change(old))
        }
    }

    suspend fun markRead(accountUid: Long, kind: String, id: Long) =
        changeConversation(accountUid, kind, id) {
            it.copy(unreadCount = 0, readAt = maxOf(it.readAt, it.lastMessageTime, System.currentTimeMillis()))
        }

    suspend fun setPinned(accountUid: Long, kind: String, id: Long, pinned: Boolean) =
        changeConversation(accountUid, kind, id) { it.copy(pinned = pinned) }

    suspend fun hideConversation(accountUid: Long, kind: String, id: Long) =
        changeConversation(accountUid, kind, id) {
            it.copy(hiddenAt = maxOf(System.currentTimeMillis(), it.lastMessageTime), unreadCount = 0)
        }

    suspend fun reopenConversation(accountUid: Long, kind: String, id: Long) =
        changeConversation(accountUid, kind, id) { it.copy(hiddenAt = 0) }

    /** 仅在初次恢复会话且启动新发送任务前调用，不得在轮询中调用。 */
    suspend fun recoverInterrupted(accountUid: Long, kind: String, id: Long) {
        validate(accountUid, kind, id)
        database.withTransaction {
            dao.allMessages(accountUid, kind, id).forEach { row ->
                if (kind == GROUP) {
                    val message = ChatMessageCodec.decodeGroup(row.payload)
                    if (message.sendState == SendState.SENDING) dao.putMessage(row.copy(
                        payload = ChatMessageCodec.encodeGroup(message.copy(sendState = SendState.UNKNOWN)),
                    ))
                } else {
                    val message = ChatMessageCodec.decodePrivate(row.payload)
                    if (message.sendState == SendState.SENDING) dao.putMessage(row.copy(
                        payload = ChatMessageCodec.encodePrivate(message.copy(sendState = SendState.UNKNOWN)),
                    ))
                }
            }
            val latest = dao.loadMessages(accountUid, kind, id, 1).firstOrNull()
            val conversation = dao.conversation(accountUid, kind, id)
            if (latest != null && conversation != null && latest.sortTime >= conversation.lastMessageTime) {
                dao.putConversation(conversation.copy(summary = messageSummary(latest)))
            }
        }
    }

    suspend fun importLegacyPrivate(accountUid: Long) = withContext(Dispatchers.IO) {
        require(accountUid > 0)
        database.withTransaction {
            if (!dao.legacyImported(accountUid)) {
                PrivateChatCache.loadAll(accountUid).groupBy { it.privateConversationUid(accountUid) }
                    .forEach { (peer, messages) ->
                        require(peer > 0 && peer != accountUid) { "旧缓存会话无效" }
                        savePrivateInTransaction(accountUid, peer, messages)
                        val conversation = dao.conversation(accountUid, PRIVATE, peer)!!
                        dao.putConversation(conversation.copy(
                            unreadCount = 0,
                            readAt = maxOf(conversation.readAt, conversation.lastMessageTime),
                        ))
                    }
                dao.markLegacyImported(ChatLegacyImport(accountUid))
            }
        }
    }

    suspend fun retainAttachment(accountUid: Long, uri: Uri): Uri = withContext(Dispatchers.IO) {
        require(accountUid > 0)
        val directory = File(context.filesDir, "chat_attachments/$accountUid")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建附件目录")
        if (uri.scheme == "file") {
            val existing = uri.path?.let(::File)
            if (existing != null && existing.canonicalFile.parentFile == directory.canonicalFile && existing.isFile) {
                return@withContext uri
            }
        }
        val file = File(directory, UUID.randomUUID().toString())
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取附件")
            input.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
            Uri.fromFile(file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatHistoryEntryPoint {
    fun chatHistoryRepository(): ChatHistoryRepository
}
