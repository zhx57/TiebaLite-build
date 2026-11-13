package com.huanchengfly.tieba.post.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloudMemeStateTest {
    private val lists = CloudMemeLists(listOf(CloudMeme("a", "https://example.com/a", null)), emptyList())

    @Test
    fun refreshFailureRetainsListsAndReportsNotUpdated() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val session = CloudMemeSession(scope) {
                if (++calls == 1) lists else throw IOException("private detail")
            }
            session.refresh()
            assertSame(lists, session.state.value.lists)
            session.refresh()
            assertSame(lists, session.state.value.lists)
            assertNotNull(session.state.value.error)
            assertFalse(session.state.value.error!!.contains("private detail"))
            assertFalse(session.state.value.loading)
        } finally { scope.cancel() }
    }

    @Test
    fun oldAccountLateResponseCannotPopulateNewAccountOrReopenedSession() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val lateA = CompletableDeferred<CloudMemeLists>()
            val a = CloudMemeSession(scope) { withContext(NonCancellable) { lateA.await() } }
            a.refresh()
            a.close()
            val b = CloudMemeSession(scope) { CloudMemeLists(emptyList(), emptyList()) }
            b.refresh()
            b.close()
            val nextA = CloudMemeSession(scope) { CloudMemeLists(emptyList(), emptyList()) }
            nextA.refresh()
            lateA.complete(lists)
            assertNull(a.state.value.lists)
            assertNull(b.state.value.lists)
            assertTrue(nextA.state.value.lists!!.collected.isEmpty())
            assertNull(nextA.state.value.error)
        } finally { scope.cancel() }
    }

    @Test
    fun closeCancelsRequestClearsDataAndRefusesFurtherLoads() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var cancelled = false
            var calls = 0
            val pending = CompletableDeferred<CloudMemeLists>()
            val session = CloudMemeSession(scope) {
                calls++
                try { pending.await() } finally { cancelled = true }
            }
            session.refresh()
            session.refresh()
            assertEquals(1, calls)
            session.close()
            assertTrue(cancelled)
            assertEquals(CloudMemeState(), session.state.value)
            session.refresh()
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    @Test
    fun cancellationIsNotConvertedToAnError() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = CloudMemeSession(scope) { throw CancellationException("closed") }
            session.refresh()
            assertNull(session.state.value.error)
            session.close()
            assertEquals(CloudMemeState(), session.state.value)
        } finally { scope.cancel() }
    }
}
