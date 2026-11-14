package com.huanchengfly.tieba.post.api.chat.blcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ChatConnectionDeadlineTest {
    @Test
    fun deadlineStartsAtLcmLoginSuccessAndUsesHalfInterval() {
        val loginSuccessAt = 1_000_000L
        val deadline = ChatConnectionDeadline.fromLoginSuccess(loginSuccessAt, 10_000L)

        assertFalse(deadline.isExpired(loginSuccessAt + TimeUnit.MILLISECONDS.toNanos(4_999L)))
        assertTrue(deadline.isExpired(loginSuccessAt + TimeUnit.MILLISECONDS.toNanos(5_000L)))
    }

    @Test
    fun deadlineCapsCleanupDelayAndHandlesMissingInterval() {
        val loginSuccessAt = 2_000_000L
        val longInterval = ChatConnectionDeadline.fromLoginSuccess(loginSuccessAt, 120_000L)
        val missingInterval = ChatConnectionDeadline.fromLoginSuccess(loginSuccessAt, 0L)

        assertTrue(longInterval.isExpired(loginSuccessAt + TimeUnit.SECONDS.toNanos(30)))
        assertTrue(missingInterval.isExpired(loginSuccessAt + TimeUnit.SECONDS.toNanos(30)))
    }
}
