package com.huanchengfly.tieba.post.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloudMemeStateTest {
    private val lists = CloudMemeLists(listOf(CloudMeme("a", "https://example.com/a", null)), emptyList())

    @Test
    fun reconciliationReusesUnchangedObjectsAndHonorsDeletionAndServerOrder() {
        val a = lists.collected.single()
        val b = CloudMeme("b", "https://example.com/b", null)
        val c = CloudMeme("c", "https://example.com/c", null)
        val old = CloudMemeLists(listOf(a, b, c), listOf(a))
        assertSame(old, reconcileCloudMemes(old, old.copy(collected = old.collected.map { it.copy() })))
        val changed = reconcileCloudMemes(old, CloudMemeLists(listOf(c.copy(), a.copy(imageUrl = "new")), listOf(a.copy())))
        assertEquals(listOf("c", "a"), changed.collected.map { it.id })
        assertSame(c, changed.collected[0])
        assertNotSame(a, changed.collected[1])
        assertSame(old.diy, changed.diy)
    }

    @Test
    fun staleCacheIsVisibleDuringRefreshAndSurvivesFailure() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val pending = CompletableDeferred<CloudMemeLists>()
            val session = CloudMemeSession(scope, cached = { CloudMemeSnapshot(lists, 0) }) { pending.await() }
            session.open()
            assertSame(lists, session.state.value.lists)
            assertTrue(session.state.value.loading)
            pending.completeExceptionally(IOException("offline"))
            assertSame(lists, session.state.value.lists)
            assertNotNull(session.state.value.error)
            assertFalse(session.state.value.loading)
        } finally { scope.cancel() }
    }

    @Test
    fun freshCacheSkipsNetworkButManualRefreshBypassesIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val session = CloudMemeSession(scope, cached = {
                CloudMemeSnapshot(lists, System.currentTimeMillis())
            }) { calls++; lists }
            session.open()
            assertSame(lists, session.state.value.lists)
            assertEquals(0, calls)
            session.refresh()
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    @Test
    fun lateCacheReadCannotRestoreClosedAccount() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val pending = CompletableDeferred<CloudMemeSnapshot?>()
            var calls = 0
            val old = CloudMemeSession(scope, cached = {
                withContext(NonCancellable) { pending.await() }
            }) { calls++; lists }
            old.open()
            old.close()
            val next = CloudMemeSession(scope) { CloudMemeLists(emptyList(), emptyList()) }
            next.open()
            pending.complete(CloudMemeSnapshot(lists, System.currentTimeMillis()))
            assertEquals(CloudMemeState(), old.state.value)
            assertTrue(next.state.value.lists!!.collected.isEmpty())
            assertEquals(0, calls)
        } finally { scope.cancel() }
    }

    @Test
    fun diskAndMemoryCacheAreUidScopedFreshAndNeverPersistCredentials() = runBlocking {
        val directory = Files.createTempDirectory("cloud-memes").toFile()
        try {
            var time = 1000L
            var calls = 0
            val cache = CloudMemeCache(directory, now = { time }) { calls++; lists }
            assertSame(lists, cache.load("a", "secret-bduss"))
            assertSame(lists, cache.load("a", "secret-bduss"))
            assertEquals(1, calls)
            assertNull(cache.cached("b"))
            assertFalse(directory.listFiles()!!.single().readText().contains("secret-bduss"))
            val reopened = CloudMemeCache(directory, now = { time }) { calls++; lists }
            assertEquals(lists, reopened.cached("a")!!.lists)
            reopened.load("a", "secret-bduss")
            assertEquals(1, calls)
            time += 300_000L
            cache.load("a", "secret-bduss")
            assertEquals(2, calls)
            cache.load("a", "secret-bduss", force = true)
            assertEquals(3, calls)
            cache.load("b", "other-bduss")
            assertEquals(4, calls)
            assertEquals(2, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun corruptDiskAndWriteFailureDoNotFailSuccessfulNetwork() = runBlocking {
        val directory = Files.createTempDirectory("cloud-memes").toFile()
        try {
            CloudMemeCache(directory) { lists }.load("a", "secret")
            directory.listFiles()!!.single().writeText("{broken")
            val cache = CloudMemeCache(directory) { lists }
            assertNull(cache.cached("a"))
            assertSame(lists, cache.load("a", "secret"))
            val blocked = java.io.File(directory, "not-a-directory").apply { writeText("file") }
            val unwritable = CloudMemeCache(blocked) { lists }
            assertSame(lists, unwritable.load("a", "secret"))
            assertSame(lists, unwritable.cached("a")!!.lists)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun failedNetworkRetainsCachedSnapshotAndUnchangedRefreshRetainsIdentity() = runBlocking {
        val directory = Files.createTempDirectory("cloud-memes").toFile()
        try {
            var fail = false
            val cache = CloudMemeCache(directory) {
                if (fail) throw IOException("offline")
                lists.copy(collected = lists.collected.map { it.copy() })
            }
            val first = cache.load("a", "secret")
            assertSame(first, cache.load("a", "secret", force = true))
            fail = true
            val error = runCatching { cache.load("a", "secret", force = true) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertSame(first, cache.cached("a")!!.lists)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun cancelledNetworkCannotPopulateAccountCache() = runBlocking {
        val directory = Files.createTempDirectory("cloud-memes").toFile()
        try {
            val entered = CompletableDeferred<Unit>()
            val pending = CompletableDeferred<CloudMemeLists>()
            val cache = CloudMemeCache(directory) {
                entered.complete(Unit)
                withContext(NonCancellable) { pending.await() }
            }
            val job = launch { cache.load("a", "secret") }
            entered.await()
            job.cancel()
            pending.complete(lists)
            job.join()
            assertNull(cache.cached("a"))
            assertNull(cache.cached("b"))
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

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
