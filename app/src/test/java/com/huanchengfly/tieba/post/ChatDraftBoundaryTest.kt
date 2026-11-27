package com.huanchengfly.tieba.post

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 源码边界检查不替代设备输入和账号切换验证。 */
class ChatDraftBoundaryTest {
    private val app = sequenceOf(File("."), File("app")).first { File(it, "src/main/java").isDirectory }
    private fun source(name: String) = File(app,
        "src/main/java/com/huanchengfly/tieba/post/ui/page/chat/$name.kt").readText()

    @Test
    fun privateDraftIsClearedOnlyAfterPersistenceAcknowledgesMatchingDraft() {
        val page = source("PrivateChatPage")
        val model = source("PrivateChatViewModel")
        assertTrue(page.contains("acceptedDraftId == draftId"))
        assertTrue(page.contains("draftId = draftId, accountUid = accountUid"))
        assertTrue(model.contains("require(intent.accountUid == account.uid)"))
        val save = model.indexOf("persist(account, intent.peerUid, message)")
        val accepted = model.indexOf("PrivateChatPartialChange.Send.Accepted", save)
        assertTrue(save >= 0 && accepted > save)
        assertFalse(page.contains("viewModel.send(PrivateChatUiIntent.Send(peerUid, text))"))
    }

    @Test
    fun groupComposerWaitsForLocalStoreAndKeepsUnpersistedDraft() {
        val page = source("ChatRoomPage")
        val model = source("ChatRoomViewModel")
        assertTrue(page.contains("enabled = composerEnabled"))
        assertTrue(page.contains("chatState.ready"))
        assertTrue(page.contains("chatState.draftPersisted"))
        assertTrue(model.contains("ChatRoomPartialChange.DraftResult(request.draftId, true)"))
        assertTrue(model.contains("ChatRoomPartialChange.DraftResult(intent.draftId, false)"))
    }
}
