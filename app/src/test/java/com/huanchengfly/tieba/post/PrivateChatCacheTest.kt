package com.huanchengfly.tieba.post.api.chat.ws

import android.content.SharedPreferences
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageSemantics
import com.huanchengfly.tieba.post.ui.page.chat.PrivateChatListPartialChange
import com.huanchengfly.tieba.post.ui.page.chat.PrivateChatListUiState
import com.huanchengfly.tieba.post.ui.page.chat.PrivateChatPartialChange
import com.huanchengfly.tieba.post.ui.page.chat.PrivateChatUiState
import com.huanchengfly.tieba.post.ui.page.chat.toSessions
import java.lang.reflect.Proxy
import kotlinx.collections.immutable.persistentListOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PrivateChatCacheTest {
    private val disk = linkedMapOf<String, String>()
    private val prefsDelegate = PrivateChatCache::class.java.getDeclaredField("prefs\$delegate")
        .apply { isAccessible = true }.get(PrivateChatCache)
    private val prefsValue = prefsDelegate.javaClass.getDeclaredField("_value")
        .apply { isAccessible = true }
    private var originalPrefsValue: Any? = null
    private val accounts = PrivateChatCache::class.java.getDeclaredField("accountMessages")
        .apply { isAccessible = true }.get(PrivateChatCache) as MutableMap<*, *>
    private var selfUid = 0L
    private val peerUid = 42L

    @Before
    fun setUp() {
        originalPrefsValue = prefsValue.get(prefsDelegate)
        prefsValue.set(prefsDelegate, memoryPreferences())
        // Each test uses a fresh account so singleton StateFlows cannot leak between tests.
        selfUid = 910000L
        while (accounts.containsKey(selfUid) || accounts.containsKey(selfUid + 10000L)) selfUid++
    }

    @After
    fun tearDown() {
        prefsValue.set(prefsDelegate, originalPrefsValue)
        accounts.remove(selfUid)
        accounts.remove(selfUid + 10000L)
    }

    @Test
    fun version3RoundTripKeepsConversationAndSenderSeparate() {
        val incoming = message(1)
        val outgoing = message(2, self = true).copy(
            conversationUid = peerUid, senderUid = selfUid,
        )
        PrivateChatCache.save(selfUid, peerUid, listOf(outgoing, incoming))

        val root = JSONObject(disk.getValue("${selfUid}_$peerUid"))
        assertEquals(3, root.getInt("version"))
        assertEquals(2, root.getJSONArray("messages").length())
        val loaded = PrivateChatCache.load(selfUid, peerUid)
        assertEquals(listOf(1L, 2L), loaded.map { it.msgId })
        assertEquals(listOf(peerUid, peerUid), loaded.map { it.conversationUid })
        assertEquals(listOf(peerUid, selfUid), loaded.map { it.senderUid })
        assertEquals(loaded, PrivateChatCache.loadAll(selfUid))
    }

    @Test
    fun legacyArrayAndVersion2EnvelopeRecoverOwnershipAndUpgradeOnSave() {
        for (wrapped in listOf(false, true)) {
            val messages = JSONArray().put(legacyMessage(2, true)).put(legacyMessage(1, false))
            disk["${selfUid}_$peerUid"] = if (wrapped) {
                JSONObject().put("version", 2).put("messages", messages).toString()
            } else messages.toString()

            val loaded = PrivateChatCache.load(selfUid, peerUid)
            assertEquals(listOf(1L, 2L), loaded.map { it.msgId })
            assertEquals(listOf(peerUid, peerUid), loaded.map { it.conversationUid })
            assertEquals(listOf(peerUid, selfUid), loaded.map { it.senderUid })
            assertEquals("private_chat_legacy_cache", loaded.first().semantics.protocolSource)
            PrivateChatCache.save(selfUid, peerUid, emptyList())
            assertEquals(3, JSONObject(disk.getValue("${selfUid}_$peerUid")).getInt("version"))
            assertEquals(loaded, PrivateChatCache.load(selfUid, peerUid))
        }
    }

    @Test
    fun cacheEnvelopeRejectsMessagesOwnedByAnotherConversation() {
        val messages = JSONArray()
            .put(legacyMessage(1, false).put("conversationUid", peerUid))
            .put(legacyMessage(2, false).put("conversationUid", 99L))
        disk["${selfUid}_$peerUid"] = JSONObject().put("version", 3)
            .put("messages", messages).toString()
        assertEquals(listOf(1L), PrivateChatCache.load(selfUid, peerUid).map { it.msgId })
    }

    @Test
    fun unknownSelfMessageNeverUsesSenderAsRecipientOrGuessesRecentPeer() {
        PrivateChatCache.saveIncoming(selfUid, listOf(message(1)))
        val unknown = message(2, self = true).copy(peerUid = selfUid, senderUid = selfUid)
        val misleading = message(3, self = true)
        assertEquals(0L, unknown.privateConversationUid(selfUid))
        assertEquals(0L, misleading.privateConversationUid(selfUid))
        assertTrue(listOf(unknown, misleading).toSessions(selfUid).isEmpty())

        PrivateChatCache.saveIncoming(selfUid, listOf(unknown, misleading))
        PrivateChatCache.save(selfUid, peerUid, listOf(unknown, misleading))
        assertEquals(listOf(1L), PrivateChatCache.loadAll(selfUid).map { it.msgId })
        assertFalse(disk.containsKey("${selfUid}_$selfUid"))
    }

    @Test
    fun localOutgoingAndExplicitConversationHaveKnownRecipient() {
        assertEquals(peerUid, message(1, self = true).copy(isLocal = true)
            .privateConversationUid(selfUid))
        assertEquals(peerUid, message(2, self = true).copy(
            semantics = ChatMessageSemantics(protocolSource = "local_outgoing"),
        ).privateConversationUid(selfUid))
        assertEquals(peerUid, message(3, self = true).copy(
            peerUid = selfUid, conversationUid = peerUid,
        ).privateConversationUid(selfUid))
    }

    @Test
    fun knownMessageIdRestoresSelfEchoOwnershipWithoutCreatingSelfSession() {
        val local = message(10, self = true).copy(
            isLocal = true, conversationUid = peerUid, senderUid = selfUid,
            semantics = ChatMessageSemantics(protocolSource = "local_outgoing", summary = "sent"),
        )
        PrivateChatCache.save(selfUid, peerUid, listOf(message(1), local))
        val echo = local.copy(
            peerUid = selfUid, conversationUid = 0L, senderUid = 0L,
            peerName = "my name", peerPortrait = "my avatar", isLocal = false,
            content = "confirmed", semantics = ChatMessageSemantics(),
        )
        PrivateChatCache.saveIncoming(selfUid, listOf(echo))

        val loaded = PrivateChatCache.loadAll(selfUid)
        assertEquals(2, loaded.size)
        val restored = loaded.single { it.msgId == 10L }
        assertEquals(peerUid, restored.conversationUid)
        assertEquals(selfUid, restored.senderUid)
        assertEquals("local_outgoing", restored.semantics.protocolSource)
        assertEquals("confirmed", loaded.toSessions(selfUid).single().lastMessage)
        assertEquals(peerUid, loaded.toSessions(selfUid).single().peerUid)
    }

    @Test
    fun latestSelfSummaryUsesPeerProfileAndFallsBackToSemanticSummary() {
        val messages = listOf(
            message(1).copy(peerPortrait = ""),
            message(2).copy(peerName = "", peerPortrait = "peer avatar"),
            message(3, self = true).copy(
                conversationUid = peerUid, senderUid = selfUid,
                peerName = "my name", peerPortrait = "my avatar", content = "",
                semantics = ChatMessageSemantics(summary = "图片消息"),
            ),
        )
        val session = messages.toSessions(selfUid).single()
        assertEquals(peerUid, session.peerUid)
        assertEquals("peer name", session.peerName)
        assertEquals("peer avatar", session.peerPortrait)
        assertEquals("图片消息", session.lastMessage)
        assertEquals(3L, session.lastTime)
    }

    @Test
    fun blankProfileEchoPreservesExistingNameAndAvatar() {
        PrivateChatCache.saveIncoming(selfUid, listOf(message(1)))
        PrivateChatCache.saveIncoming(selfUid, listOf(message(1).copy(
            peerName = "", peerPortrait = "", content = "updated",
        )))
        val loaded = PrivateChatCache.load(selfUid, peerUid).single()
        assertEquals("peer name", loaded.peerName)
        assertEquals("peer avatar", loaded.peerPortrait)
        assertEquals("updated", loaded.content)
    }

    @Test
    fun trimmingRetainsPeerNameAndAvatarEvenAfterManyOutgoingMessages() {
        val profile = listOf(message(1).copy(peerPortrait = ""), message(2).copy(peerName = ""))
        val outgoing = (3L..250L).map { id ->
            message(id, self = true).copy(conversationUid = peerUid, senderUid = selfUid)
        }
        PrivateChatCache.save(selfUid, peerUid, profile + outgoing)
        val loaded = PrivateChatCache.load(selfUid, peerUid)
        assertEquals(200, loaded.size)
        assertTrue(loaded.any { it.msgId == 1L })
        assertTrue(loaded.any { it.msgId == 2L })
        val session = loaded.toSessions(selfUid).single()
        assertEquals("peer name", session.peerName)
        assertEquals("peer avatar", session.peerPortrait)
        assertEquals("message 250", session.lastMessage)
    }

    @Test
    fun emptyIncrementLeavesDiskObservedMessagesAndSessionsIntact() {
        val observed = PrivateChatCache.observe(selfUid)
        PrivateChatCache.saveIncoming(selfUid, listOf(message(1)))
        val before = observed.value
        val stored = disk.toMap()
        PrivateChatCache.saveIncoming(selfUid, emptyList())
        assertEquals(stored, disk)
        assertEquals(before, observed.value)
        PrivateChatCache.save(selfUid, peerUid, emptyList())
        assertEquals(before, observed.value)
        val state = PrivateChatListUiState(sessions = before.toSessions(selfUid))
        val reduced = PrivateChatListPartialChange.Load.Success(
            PrivateChatCache.loadAll(selfUid).toSessions(selfUid), replace = true,
        ).reduce(state)
        assertEquals(state, reduced)
        val detail = PrivateChatUiState(isLoading = false, messages = persistentListOf(message(1)))
        assertEquals(detail, PrivateChatPartialChange.Poll.Success(persistentListOf()).reduce(detail))
        assertEquals(detail, PrivateChatPartialChange.Load.Success(persistentListOf()).reduce(detail))
    }

    @Test
    fun accountsHaveIndependentStorageObserversAndKnownMessageIds() {
        val otherUid = selfUid + 10000L
        val first = PrivateChatCache.observe(selfUid)
        val second = PrivateChatCache.observe(otherUid)
        PrivateChatCache.saveIncoming(selfUid, listOf(message(1)))
        assertTrue(second.value.isEmpty())
        PrivateChatCache.saveIncoming(otherUid, listOf(message(1, self = true).copy(peerUid = otherUid)))
        assertTrue(second.value.isEmpty())
        PrivateChatCache.saveIncoming(otherUid, listOf(message(2).copy(content = "other account")))
        assertEquals(listOf(1L), first.value.map { it.msgId })
        assertEquals(listOf(2L), second.value.map { it.msgId })
        assertEquals("message 1", PrivateChatCache.loadAll(selfUid).single().content)
        assertEquals("other account", PrivateChatCache.loadAll(otherUid).single().content)
        assertTrue(PrivateChatCache.loadAll(0L).isEmpty())
        assertTrue(PrivateChatCache.load(selfUid, selfUid).isEmpty())
    }

    @Test
    fun invalidAccountAndPeerCannotWriteCache() {
        PrivateChatCache.saveIncoming(0L, listOf(message(1)))
        PrivateChatCache.saveIncoming(-1L, listOf(message(1)))
        PrivateChatCache.save(selfUid, 0L, listOf(message(1)))
        PrivateChatCache.save(selfUid, selfUid, listOf(message(1)))
        assertTrue(disk.isEmpty())
    }

    @Test
    fun consumedCursorIsPersistedAndNeverMovesBackward() {
        assertEquals(0L, PrivateChatCache.syncCursor(selfUid))
        disk["cursor_$selfUid"] = "100"
        assertEquals(100L, PrivateChatCache.syncCursor(selfUid))

        for (cursor in listOf(90L, 100L, 0L, -1L)) {
            PrivateChatCache.saveIncoming(selfUid, listOf(message(1)), consumedCursor = cursor)
            assertEquals(100L, PrivateChatCache.syncCursor(selfUid))
            assertEquals("100", disk["cursor_$selfUid"])
        }
        PrivateChatCache.saveIncoming(selfUid, listOf(message(2)), consumedCursor = 150L)
        assertEquals(150L, PrivateChatCache.syncCursor(selfUid))
        assertEquals("150", disk["cursor_$selfUid"])
        assertEquals(listOf(1L, 2L), PrivateChatCache.loadAll(selfUid).map { it.msgId })
    }

    @Test
    fun consumedCursorsAreAccountScopedAndRejectInvalidAccounts() {
        val otherUid = selfUid + 10000L
        PrivateChatCache.saveIncoming(selfUid, emptyList(), consumedCursor = 120L)
        assertEquals(0L, PrivateChatCache.syncCursor(otherUid))
        PrivateChatCache.saveIncoming(otherUid, emptyList(), consumedCursor = 30L)
        assertEquals(120L, PrivateChatCache.syncCursor(selfUid))
        assertEquals(30L, PrivateChatCache.syncCursor(otherUid))
        assertEquals("120", disk["cursor_$selfUid"])
        assertEquals("30", disk["cursor_$otherUid"])

        PrivateChatCache.saveIncoming(0L, emptyList(), consumedCursor = 999L)
        PrivateChatCache.saveIncoming(-1L, emptyList(), consumedCursor = 999L)
        assertEquals(0L, PrivateChatCache.syncCursor(0L))
        assertEquals(0L, PrivateChatCache.syncCursor(-1L))
        assertFalse(disk.containsKey("cursor_0"))
        assertFalse(disk.containsKey("cursor_-1"))
    }

    @Test
    fun ordinarySavesAndIncomingWithoutConsumedCursorNeverAdvanceCursor() {
        val outgoing = message(1000, self = true).copy(
            isLocal = true, conversationUid = peerUid, senderUid = selfUid,
        )
        PrivateChatCache.save(selfUid, peerUid, listOf(outgoing))
        PrivateChatCache.saveIncoming(selfUid, listOf(message(2000)))
        assertEquals(0L, PrivateChatCache.syncCursor(selfUid))
        assertFalse(disk.containsKey("cursor_$selfUid"))

        PrivateChatCache.saveIncoming(selfUid, emptyList(), consumedCursor = 50L)
        PrivateChatCache.save(selfUid, peerUid, listOf(outgoing.copy(msgId = 3000L)))
        PrivateChatCache.saveIncoming(selfUid, listOf(message(4000)), consumedCursor = null)
        PrivateChatCache.saveIncoming(selfUid, emptyList())
        assertEquals(50L, PrivateChatCache.syncCursor(selfUid))
        assertEquals("50", disk["cursor_$selfUid"])
    }

    @Test
    fun emptyNetworkAckAdvancesCursorWithoutClearingCachedOrObservedMessages() {
        val observed = PrivateChatCache.observe(selfUid)
        PrivateChatCache.saveIncoming(selfUid, listOf(message(1)), consumedCursor = 10L)
        val before = observed.value
        val storedMessages = disk.getValue("${selfUid}_$peerUid")

        PrivateChatCache.saveIncoming(selfUid, emptyList(), consumedCursor = 20L)
        assertEquals(20L, PrivateChatCache.syncCursor(selfUid))
        assertEquals("20", disk["cursor_$selfUid"])
        assertEquals(storedMessages, disk["${selfUid}_$peerUid"])
        assertEquals(before, PrivateChatCache.loadAll(selfUid))
        assertEquals(before, observed.value)
    }

    private fun message(id: Long, self: Boolean = false) = ChatWsMessage(
        msgId = id, peerUid = peerUid, peerName = "peer name", peerPortrait = "peer avatar",
        content = "message $id", msgTime = id, isSelf = self,
    )

    private fun legacyMessage(id: Long, self: Boolean) = JSONObject().apply {
        put("msgId", id)
        put("peerUid", peerUid)
        put("peerName", "peer name")
        put("peerPortrait", "peer avatar")
        put("content", "message $id")
        put("msgTime", id)
        put("isSelf", self)
        put("msgType", 1)
    }

    private fun memoryPreferences(): SharedPreferences {
        val pending = linkedMapOf<String, String>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    pending[args!![0] as String] = args[1] as String
                    proxy
                }
                "putLong" -> {
                    pending[args!![0] as String] = (args[1] as Long).toString()
                    proxy
                }
                "apply", "commit" -> {
                    disk.putAll(pending)
                    pending.clear()
                    if (method.name == "commit") true else null
                }
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> disk.toMap()
                "getString" -> disk[args!![0] as String] ?: args[1]
                "getLong" -> disk[args!![0] as String]?.toLong() ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences
    }
}
