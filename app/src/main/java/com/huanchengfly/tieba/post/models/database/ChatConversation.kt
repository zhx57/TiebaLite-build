package com.huanchengfly.tieba.post.models.database

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "chat_conversation",
    primaryKeys = ["accountUid", "kind", "conversationId"],
    indices = [Index(value = ["accountUid", "pinned", "lastMessageTime", "conversationId"])],
)
data class ChatConversation(
    val accountUid: Long,
    val kind: String,
    val conversationId: Long,
    val name: String = "",
    val avatar: String = "",
    val forumId: Long = 0,
    val summary: String = "",
    /** 毫秒时间，协议中的秒应由调用方在入口转换。 */
    val lastMessageTime: Long = 0,
    val unreadCount: Int = 0,
    val pinned: Boolean = false,
    /** 隐藏时间水位，单位毫秒；零表示可见。 */
    val hiddenAt: Long = 0,
    /** 持久已读水位，单位毫秒；消息保存和服务端未读快照共同遵守。 */
    val readAt: Long = 0,
)
