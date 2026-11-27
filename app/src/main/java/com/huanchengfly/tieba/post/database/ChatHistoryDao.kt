package com.huanchengfly.tieba.post.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.huanchengfly.tieba.post.models.database.ChatConversation
import com.huanchengfly.tieba.post.models.database.ChatLegacyImport
import com.huanchengfly.tieba.post.models.database.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatHistoryDao {
    @Query("SELECT * FROM chat_message WHERE accountUid = :accountUid AND kind = :kind AND conversationId = :id ORDER BY sortTime DESC, localId DESC LIMIT :limit")
    fun observeMessages(accountUid: Long, kind: String, id: Long, limit: Int): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_message WHERE accountUid = :accountUid AND kind = :kind AND conversationId = :id ORDER BY sortTime DESC, localId DESC LIMIT :limit")
    suspend fun loadMessages(accountUid: Long, kind: String, id: Long, limit: Int): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_message WHERE accountUid = :accountUid AND kind = :kind AND conversationId = :id")
    suspend fun allMessages(accountUid: Long, kind: String, id: Long): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_message WHERE accountUid = :accountUid AND kind = :kind AND conversationId = :id AND (localId = :localId OR (:serverId IS NOT NULL AND serverId = :serverId) OR (:msgKey IS NOT NULL AND msgKey = :msgKey))")
    suspend fun matches(accountUid: Long, kind: String, id: Long, localId: String, serverId: Long?, msgKey: String?): List<ChatMessageEntity>

    @Query("DELETE FROM chat_message WHERE accountUid = :accountUid AND kind = :kind AND conversationId = :id AND localId = :localId")
    suspend fun deleteMatched(accountUid: Long, kind: String, id: Long, localId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_conversation WHERE accountUid = :accountUid AND hiddenAt = 0 ORDER BY pinned DESC, lastMessageTime DESC, conversationId DESC, kind ASC")
    fun observeConversations(accountUid: Long): Flow<List<ChatConversation>>

    @Query("SELECT * FROM chat_conversation WHERE accountUid = :accountUid AND kind = :kind AND conversationId = :id")
    suspend fun conversation(accountUid: Long, kind: String, id: Long): ChatConversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putConversation(conversation: ChatConversation)

    @Query("SELECT EXISTS(SELECT 1 FROM chat_legacy_import WHERE accountUid = :accountUid)")
    suspend fun legacyImported(accountUid: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markLegacyImported(marker: ChatLegacyImport)
}
