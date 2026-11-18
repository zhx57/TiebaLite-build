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
    fun longServerIntervalIsNotForcedDownToThirtySeconds() {
        val loginSuccessAt = 2_000_000L
        val deadline = ChatConnectionDeadline.fromLoginSuccess(loginSuccessAt, 120_000L)

        // 服务端要求 120 秒时按 60 秒心跳，30 秒时连接仍然有效。
        assertFalse(deadline.isExpired(loginSuccessAt + TimeUnit.SECONDS.toNanos(30)))
        assertTrue(deadline.isExpired(loginSuccessAt + TimeUnit.SECONDS.toNanos(60)))
    }

    @Test
    fun missingIntervalFallsBackToThirtySeconds() {
        val loginSuccessAt = 3_000_000L
        val deadline = ChatConnectionDeadline.fromLoginSuccess(loginSuccessAt, 0L)

        assertFalse(deadline.isExpired(loginSuccessAt + TimeUnit.MILLISECONDS.toNanos(29_999L)))
        assertTrue(deadline.isExpired(loginSuccessAt + TimeUnit.MILLISECONDS.toNanos(30_000L)))
    }

    @Test
    fun successfulHeartbeatExtendsTheSameConnection() {
        val loginSuccessAt = 4_000_000L
        val deadline = ChatConnectionDeadline.fromLoginSuccess(loginSuccessAt, 10_000L)
        val expiredAt = loginSuccessAt + TimeUnit.MILLISECONDS.toNanos(5_000L)
        assertTrue(deadline.isExpired(expiredAt))

        // 心跳成功后从当前时刻重新计时，连接不需要重建。
        deadline.extendFrom(expiredAt)
        assertFalse(deadline.isExpired(expiredAt))
        assertFalse(deadline.isExpired(expiredAt + TimeUnit.MILLISECONDS.toNanos(4_999L)))
        assertTrue(deadline.isExpired(expiredAt + TimeUnit.MILLISECONDS.toNanos(5_000L)))
    }
}
