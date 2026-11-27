package com.huanchengfly.tieba.post.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.api.models.chat.ChatRoomMessage
import com.huanchengfly.tieba.post.api.models.chat.SendState
import com.huanchengfly.tieba.post.database.AppDatabase
import com.huanchengfly.tieba.post.database.MIGRATION_40_41
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatHistoryPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "chat-history-${UUID.randomUUID()}.db"
    private var database: AppDatabase? = null

    private fun open(): ChatHistoryRepository {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_40_41).build()
        database = db
        return ChatHistoryRepository(db, context)
    }

    @After
    fun cleanup() {
        database?.close()
        context.deleteDatabase(name)
    }

    private fun group(id: Long, state: SendState = SendState.SENT) = ChatRoomMessage(
        msgId = id, senderUid = 1, senderName = "测试用户", senderPortrait = "",
        contentType = 0, text = "相同内容", msgTime = 100L + kotlin.math.abs(id),
        isSelf = state != SendState.SENT, sendState = state,
        isLocal = state != SendState.SENT, localId = "local-$id",
    )

    @Test
    fun reopenKeepsHistoryStatesIsolationAndConversationSettings() = runBlocking {
        var repo = open()
        repo.saveGroup(1, 10, (1L..250L).map { group(it) })
        repo.saveGroup(1, 10, listOf(group(-1, SendState.FAILED), group(-2, SendState.SENDING)))
        repo.saveGroup(2, 10, listOf(group(999)))
        repo.setPinned(1, ChatHistoryRepository.GROUP, 10, true)
        repo.hideConversation(1, ChatHistoryRepository.GROUP, 10)
        database!!.close()
        repo = open()
        repo.recoverInterrupted(1, ChatHistoryRepository.GROUP, 10)
        val messages = repo.loadGroup(1, 10, 1000)
        assertEquals(252, messages.size)
        assertEquals(SendState.FAILED, messages.first { it.localId == "local--1" }.sendState)
        assertEquals(SendState.UNKNOWN, messages.first { it.localId == "local--2" }.sendState)
        assertEquals(listOf(999L), repo.loadGroup(2, 10).map { it.msgId })
        assertTrue(repo.observeConversations(1).first().isEmpty())
        repo.saveGroup(1, 10, listOf(group(1)))
        assertTrue(repo.observeConversations(1).first().isEmpty())
        repo.reopenConversation(1, ChatHistoryRepository.GROUP, 10)
        assertTrue(repo.observeConversations(1).first().single().pinned)
    }

    @Test
    fun exactIdentityReconciliationPreservesLocalIdAndAllowsRetryWithoutSentRegression() = runBlocking {
        val repo = open()
        val pending = group(-1, SendState.FAILED).copy(msgKey = "key-1")
        repo.saveGroup(1, 10, listOf(pending))
        repo.saveGroup(1, 10, listOf(pending.copy(sendState = SendState.SENDING)))
        assertEquals(SendState.SENDING, repo.loadGroup(1, 10).single().sendState)
        val confirmed = group(55).copy(localId = "echo", msgKey = "key-1", msgTime = 500)
        repo.saveGroup(1, 10, listOf(confirmed))
        repo.saveGroup(1, 10, listOf(pending.copy(sendState = SendState.UNKNOWN)))
        val saved = repo.loadGroup(1, 10).single()
        assertEquals(pending.localId, saved.localId)
        assertEquals(55L, saved.msgId)
        assertEquals(SendState.SENT, saved.sendState)
        repo.saveGroup(1, 10, listOf(confirmed.copy(localId = "other"), group(56)))
        assertEquals(2, repo.loadGroup(1, 10).size)
        assertEquals(500L, repo.observeConversations(1).first().single().lastMessageTime)
    }

    @Test
    fun bridgingIndependentEchoPreservesOriginalLocalIdentity() = runBlocking {
        val repo = open()
        val local = group(-1, SendState.UNKNOWN).copy(localId = "original", msgKey = "bridge-key")
        val echo = group(55).copy(localId = "server-echo", msgKey = "", msgTime = 500)
        repo.saveGroup(1, 10, listOf(local))
        repo.saveGroup(1, 10, listOf(echo))
        assertEquals(2, repo.loadGroup(1, 10).size)
        repo.saveGroup(1, 10, listOf(echo.copy(msgKey = "bridge-key")))
        val merged = repo.loadGroup(1, 10).single()
        assertEquals("original", merged.localId)
        assertEquals(55L, merged.msgId)
        assertEquals(SendState.SENT, merged.sendState)
    }

    @Test
    fun readWatermarkRejectsRepeatedServerUnreadAndPrivateHistorySurvivesReopen() = runBlocking {
        var repo = open()
        val message = ChatWsMessage(1, 2, "对端", "", "消息", 100, false,
            conversationUid = 2, localId = "private-1")
        repo.savePrivate(1, 2, listOf(message))
        repo.markRead(1, ChatHistoryRepository.PRIVATE, 2)
        repo.upsertConversation(1, ChatHistoryRepository.PRIVATE, 2, lastMessageTime = 100, unreadCount = 9)
        assertEquals(0, repo.observeConversations(1).first().single().unreadCount)
        database!!.close()
        repo = open()
        assertEquals(message, repo.loadPrivate(1, 2).single())
        val readAt = repo.observeConversations(1).first().single().readAt
        repo.upsertConversation(1, ChatHistoryRepository.PRIVATE, 2, lastMessageTime = readAt + 1, unreadCount = 2)
        assertEquals(2, repo.observeConversations(1).first().single().unreadCount)
        repo.upsertConversation(1, ChatHistoryRepository.PRIVATE, 2, lastMessageTime = 100, unreadCount = 9)
        assertEquals(2, repo.observeConversations(1).first().single().unreadCount)
    }

    @Test
    fun migrationFromExported40SchemaPreservesExistingDraft() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val schema = assets.open("database-v40.json").bufferedReader().use { JSONObject(it.readText()) }
            .getJSONObject("database")
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                fun sql(value: String) = value.replace("\${TABLE_NAME}", table)
                sqlite.execSQL(sql(entity.getString("createSql")))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) {
                    sqlite.execSQL(sql(indices.getJSONObject(i).getString("createSql")))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) sqlite.execSQL(setup.getString(index))
            sqlite.execSQL("INSERT INTO draft (id, hash, content) VALUES (1, 'migration-test', '旧草稿')")
            sqlite.version = 40
        }
        val repo = open()
        repo.saveGroup(1, 10, listOf(group(1)))
        database!!.openHelper.readableDatabase.query("SELECT content FROM draft WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("旧草稿", cursor.getString(0))
        }
        assertEquals(41, database!!.openHelper.readableDatabase.version)
        assertEquals(1, repo.loadGroup(1, 10).size)
    }
}
