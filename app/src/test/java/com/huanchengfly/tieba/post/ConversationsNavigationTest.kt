package com.huanchengfly.tieba.post.ui.page.main

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationsNavigationTest {
    @Test
    fun legacyStartPreservesDestinationWithChatInserted() {
        assertEquals(listOf(0, 1, 2, 4), (0..3).map { mapLegacyMainStartIndex(it, false) })
        assertEquals(listOf(0, 0, 1, 3), (0..3).map { mapLegacyMainStartIndex(it, true) })
    }

    @Test
    fun invalidStoredStartCannotExceedPagerBounds() {
        for (hidden in listOf(false, true)) {
            val size = if (hidden) 4 else 5
            for (value in listOf(Int.MIN_VALUE, -1, 0, 1, 2, 3, 4, Int.MAX_VALUE)) {
                assertTrue(mapLegacyMainStartIndex(value, hidden) in 0 until size)
            }
        }
    }

    @Test
    fun exploreTogglePreservesDestinationIdentity() {
        assertEquals(listOf(0, 0, 1, 2, 3), (0..4).map { mapMainIndexForExplore(it, false, true) })
        assertEquals(listOf(0, 2, 3, 4), (0..3).map { mapMainIndexForExplore(it, true, false) })
        for (hidden in listOf(false, true)) {
            val size = if (hidden) 4 else 5
            for (index in 0 until size) assertEquals(index, mapMainIndexForExplore(index, hidden, hidden))
        }
    }

    @Test
    fun notificationsRetainOnlyForumTabsAndRedirectLegacyChatLinks() {
        val source = source("ui/page/main/notifications/NotificationsPage.kt")
        assertFalse(source.contains("PrivateChatListPage()"))
        assertFalse(source.contains("ChatEntryPage()"))
        assertTrue(source.contains("2 -> navigator.navigate(ConversationsPageDestination)"))
        assertTrue(source.contains("3 -> navigator.navigate(ChatEntryPageDestination)"))
        assertEquals(2, Regex("initialPage = initialTab.coerceIn").findAll(source).count())
    }

    @Test
    fun recentListNeverMarksReadOrInfersGroupIdFromPeerUid() {
        val vm = source("ui/page/chat/ConversationsViewModel.kt")
        val page = source("ui/page/chat/ConversationsPage.kt")
        assertTrue(vm.contains("if (!session.isPrivateChat"))
        assertTrue(vm.contains("kind = \"private\""))
        assertTrue(vm.contains("fetchSessions(accountBduss = accountBduss)"))
        assertFalse(vm.contains("markRead("))
        assertFalse(page.contains("markRead("))
        assertFalse(vm.contains("ChatRoomListApi"))
        assertFalse(source("ui/page/chat/PrivateChatListPage.kt").contains("ACTION_CLEAR_PRIVATE"))
        assertFalse(vm.contains("println("))
    }

    private fun source(path: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .first { it.isDirectory }
        return File(root, "com/huanchengfly/tieba/post/$path").readText()
    }
}
