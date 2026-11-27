package com.huanchengfly.tieba.post.models.database

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "chat_message",
    primaryKeys = ["accountUid", "kind", "conversationId", "localId"],
    indices = [
        Index(value = ["accountUid", "kind", "conversationId", "serverId"], unique = true),
        Index(value = ["accountUid", "kind", "conversationId", "msgKey"], unique = true),
        Index(value = ["accountUid", "kind", "conversationId", "sortTime", "localId"]),
    ],
)
data class ChatMessageEntity(
    val accountUid: Long,
    val kind: String,
    val conversationId: Long,
    val localId: String,
    val serverId: Long?,
    val msgKey: String?,
    val sortTime: Long,
    val payload: String,
)

@Entity(tableName = "chat_legacy_import")
data class ChatLegacyImport(
    @androidx.room.PrimaryKey val accountUid: Long,
)
