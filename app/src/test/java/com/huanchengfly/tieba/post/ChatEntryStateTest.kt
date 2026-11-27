package com.huanchengfly.tieba.post.ui.page.chat

import com.huanchengfly.tieba.post.api.chat.ChatApiException
import com.huanchengfly.tieba.post.api.chat.http.ChatRoomListApi
import com.huanchengfly.tieba.post.api.models.chat.ChatRoom
import kotlinx.collections.immutable.persistentListOf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEntryStateTest {
    private fun room(id: Long, name: String = "群$id") =
        ChatRoom(id, 74075L, "图拉丁", name, "", 0, "")

    @Test
    fun subscriptionWinsDeduplication() {
        val subscribed = room(1, "已订阅")
        val result = ChatRoomListApi.mergeRooms(listOf(subscribed), listOf(room(1), room(2)))
        assertEquals(listOf(1L, 2L), result.map { it.roomId })
        assertSame(subscribed, result.first())
    }

    @Test
    fun progressRefreshAndFailureKeepVisibleRoomsUntilSuccessfulCompletion() {
        val old = ChatEntryUiState(rooms = persistentListOf(room(1)), hasLoaded = true, accountUid = "a")
        val start = ChatEntryPartialChange.Load.Start("a").reduce(old)
        assertTrue(start.isLoading)
        assertEquals(old.rooms, start.rooms)
        val progress = ChatEntryPartialChange.Load.Update(persistentListOf(room(2)), false, accountUid = "a").reduce(start)
        assertTrue(progress.isLoading)
        assertEquals(listOf(2L, 1L), progress.rooms.map { it.roomId })
        val error = IllegalStateException("请求失败")
        val partial = ChatEntryPartialChange.Load.Update(persistentListOf(room(2)), true, error, 1, "a").reduce(progress)
        assertFalse(partial.isLoading)
        assertTrue(partial.hasLoaded)
        assertSame(error, partial.error)
        assertEquals(2, partial.rooms.size)
        val failure = ChatEntryPartialChange.Load.Failure(error, "a").reduce(partial)
        assertEquals(partial.rooms, failure.rooms)
        val complete = ChatEntryPartialChange.Load.Update(persistentListOf(), true, accountUid = "a").reduce(failure)
        assertTrue(complete.rooms.isEmpty())
        assertEquals(null, complete.error)
    }

    @Test
    fun accountChangeClearsRoomsAndRejectsOldResults() {
        val old = ChatEntryUiState(rooms = persistentListOf(room(1)), hasLoaded = true, accountUid = "a")
        val switched = ChatEntryPartialChange.Load.Start("b").reduce(old)
        assertTrue(switched.rooms.isEmpty())
        assertFalse(switched.hasLoaded)
        assertEquals("b", switched.accountUid)
        assertEquals(switched, ChatEntryPartialChange.Load.Update(old.rooms, true, accountUid = "a").reduce(switched))
        assertEquals(switched, ChatEntryPartialChange.Load.Failure(Exception("旧请求"), "a").reduce(switched))
    }

    @Test
    fun emptyDataAndMissingOptionalArraysRemainLegal() {
        listOf("{\"data\":null}", "{\"data\":{}}", "{\"data\":{\"list\":[],\"banner\":{}}}")
            .forEach { assertTrue(ChatRoomListApi.parseRoomListResponse(JSONObject(it), 21841105L).isEmpty()) }
        assertTrue(ChatRoomListApi.parseSubscriptionRooms(JSONObject("{\"data\":{}}")).isEmpty())
    }

    @Test
    fun objectAndStringDataUseRequestedFidAndSeparateUnreadFromMemberCount() {
        val data = JSONObject("""{"list":[{"room_list":[{"room_id":"1","room_info":{"name":"群","unread_num":"5"}}]}]}""")
        listOf(data, data.toString()).forEach { encoded ->
            val parsed = ChatRoomListApi.parseRoomListResponse(JSONObject().put("data", encoded), 74075L).single()
            assertEquals(74075L, parsed.forumId)
            assertEquals(0, parsed.discussNum)
            assertEquals(5, parsed.unreadNum)
        }
        assertEquals(0, room(1).unreadNum)
    }

    @Test
    fun publicDiscoveryKeepsValidRoomsDespitePartialRecordsAndVisibilityFlags() {
        val data = JSONObject("""{"list":[null,1,{"room_list":[null,1,{"room_id":"bad"},{"room_id":"0"},{"room_id":"1"},{"room_id":"2","is_show":0,"room_info":{"name":"群2"}},{"room_id":"3","room_info":{"name":"群3"}}]}]}""")
        listOf(data, data.toString()).forEach { encoded ->
            val rooms = ChatRoomListApi.parseRoomListResponse(JSONObject().put("data", encoded), 74075L)
            assertEquals(listOf(1L, 2L, 3L), rooms.map { it.roomId })
            assertTrue(rooms.all { it.forumId == 74075L })
            assertEquals("", rooms.first().name)
            assertEquals("群2", rooms[1].name)
            assertEquals("群3", rooms.last().name)
        }
    }

    @Test
    fun subscriptionSkipsBrokenItemsWithoutDiscardingValidRooms() {
        val data = JSONObject("""{"room_list":[null,{"room_id":"bad"},{"room_id":"1"},{"room_id":"2","is_show":0},{"room_id":"3","room_info":{"name":"已订阅"}}]}""")
        val rooms = ChatRoomListApi.parseSubscriptionRooms(JSONObject().put("data", data))
        assertEquals(listOf(1L, 3L), rooms.map { it.roomId })
        assertEquals("已订阅", rooms.last().name)
    }

    /** 源码约束不替代详情页真机导航及渲染验证。 */
    @Test
    fun forumDetailUsesExistingManagersAndExactExperienceWithoutAnotherRequest() {
        val app = sequenceOf(java.io.File("."), java.io.File("app"))
            .first { java.io.File(it, "src/main/java").isDirectory }
        val root = java.io.File(app, "src/main/java/com/huanchengfly/tieba/post/ui/page/forum")
        val forum = java.io.File(root, "ForumPage.kt").readText()
        val detail = java.io.File(root, "detail/ForumDetailPage.kt").readText()
        assertTrue(forum.contains("memberInfo = holder.get { this }"))
        assertTrue(detail.contains("memberInfo: ForumInfo? = null"))
        assertTrue(detail.contains("memberInfo.managers.forEach"))
        assertTrue(detail.contains("manager.show_name.ifBlank { manager.name }"))
        assertTrue(detail.contains("onOpenUser(manager.id)"))
        assertTrue(detail.contains("UserProfilePageDestination(it)"))
        assertTrue(detail.contains("\${memberInfo.cur_score}"))
        assertTrue(detail.contains("\${memberInfo.levelup_score}"))
        assertTrue(detail.contains("LocalAccount.current == null"))
        assertTrue(detail.contains("登录后查看"))
        assertFalse(detail.contains("cur_score.getShortNumString"))
        assertFalse(detail.contains("getFrsPage"))
    }

    @Test
    fun nonEmptyMalformedStructuresAreProtocolFailures() {
        listOf(
            """{"data":"not-json"}""",
            """{"data":[]}""",
            """{"data":{"list":{}}}""",
            """{"data":{"list":[{"room_list":{}}]}}""",
        ).forEach { raw ->
            try {
                ChatRoomListApi.parseRoomListResponse(JSONObject(raw))
                throw AssertionError("应拒绝错误结构：$raw")
            } catch (_: ChatApiException) {
                // 非空错误类型必须与合法无群区分。
            }
        }
    }
}
