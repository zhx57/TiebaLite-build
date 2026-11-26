package com.huanchengfly.tieba.post.ui.page.chat

import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.api.chat.ws.privateConversationUid
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * 把本地缓存的私信消息聚合成会话。
 *
 * 消息中心的会话列表现在由官方 fetch_session 提供，
 * 但会话页在离线时仍会读取本地缓存，这里保留聚合逻辑供缓存层与测试使用。
 */
internal fun List<ChatWsMessage>.toSessions(selfUid: Long = 0L): ImmutableList<PrivateSession> {
    return filter { it.privateConversationUid(selfUid) > 0L }
        .groupBy { it.privateConversationUid(selfUid) }
        .mapNotNull { (peerUid, messages) ->
            val latest = messages.maxByOrNull { it.msgTime } ?: return@mapNotNull null
            val profileMessages = messages.filter { !it.isSelf &&
                (it.senderUid == 0L || it.senderUid == peerUid) }.sortedByDescending { it.msgTime }
            PrivateSession(
                peerUid = peerUid,
                peerName = profileMessages.firstOrNull { it.peerName.isNotBlank() }?.peerName.orEmpty(),
                peerPortrait = profileMessages.firstOrNull { it.peerPortrait.isNotBlank() }?.peerPortrait.orEmpty(),
                lastMessage = latest.content.ifBlank { latest.semantics.summary },
                lastTime = latest.msgTime,
                unreadCount = 0,
            )
        }
        .sortedByDescending { it.lastTime }
        .toImmutableList()
}
