package com.huanchengfly.tieba.post.repository

import com.huanchengfly.tieba.post.ui.widgets.compose.CloudMemeCollectOutcome
import com.huanchengfly.tieba.post.ui.widgets.compose.collectPostImageAsCloudMeme
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
    fun collectMemeRejectsAccountSwitchBeforeSubmitting() = runBlocking {
        val directory = Files.createTempDirectory("cloud-memes").toFile()
        try {
            var fetches = 0
            val cache = CloudMemeCache(directory) { fetches++; lists }
            val error = runCatching {
                cache.collectMeme(
                    uid = "a",
                    bduss = "secret",
                    picId = "pic_1",
                    expectedAccount = { "b" to "other" },
                )
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals(0, fetches)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun collectMemeAppendsWithoutDroppingExistingCollectedItems() = runBlocking {
        val directory = Files.createTempDirectory("cloud-memes").toFile()
        try {
            val existing = CloudMeme("a", "https://example.com/a", null)
            val added = CloudMeme("b", "https://example.com/b", null)
            val diy = CloudMeme("d", "https://example.com/d", null)
            var server = CloudMemeLists(listOf(existing), listOf(diy))
            val another = CloudMeme("c", "https://example.com/c", null)
            var writes = 0
            val cache = CloudMemeCache(directory, submit = { bduss, ids, validate ->
                validate()
                assertEquals("secret", bduss)
                assertEquals(listOf("a", "c", "b"), ids)
                writes++
                server = server.copy(collected = ids.map { id ->
                    listOf(existing, another, added).single { it.id == id }
                })
            }) { server }
            cache.load("u", "secret")
            // 写入前必须读取最新列表，不能用旧快照覆盖其他设备的更新。
            server = server.copy(collected = listOf(existing, another, existing))
            val result = cache.collectMeme(
                uid = "u",
                bduss = "secret",
                picId = "b",
                expectedAccount = { "u" to "secret" },
            )
            assertEquals(1, writes)
            assertEquals(listOf("a", "c", "b"), result.collected.map { it.id })
            assertEquals(listOf(diy), result.diy)
            assertEquals(result, cache.cached("u")!!.lists)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun collectMemeTreatsExistingIdAsIdempotentWithoutDuplicate() = runBlocking {
        val directory = Files.createTempDirectory("cloud-memes").toFile()
        try {
            val cache = CloudMemeCache(directory) { lists }
            val result = cache.collectMeme(
                uid = "u",
                bduss = "secret",
                picId = "a",
                expectedAccount = { "u" to "secret" },
            )
            assertEquals(listOf("a"), result.collected.map { it.id })
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun legacyCacheCanPreviewWhileReplyRefreshesMetadata() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val pending = CompletableDeferred<CloudMemeLists>()
            val session = CloudMemeSession(scope,
                cached = { CloudMemeSnapshot(lists, System.currentTimeMillis()) },
                requiresMetadata = true,
            ) { pending.await() }
            session.open()
            assertSame(lists, session.state.value.lists)
            assertTrue(session.state.value.loading)
            val ready = CloudMemeLists(listOf(CloudMeme("12", "https://example.com/m", null, "5", 60, 40)), emptyList())
            pending.complete(ready)
            assertEquals("5_12", session.state.value.lists!!.collected.single().replyIdPart)
        } finally { scope.cancel() }
    }

    @Test
    fun metadataSurvivesReopeningDiskCache() = runBlocking {
        val directory = Files.createTempDirectory("cloud-memes").toFile()
        try {
            val modern = lists.copy(collected = listOf(CloudMeme("12", "https://example.com/m", null, "5", 60, 40)))
            CloudMemeCache(directory) { modern }.load("a", "secret")
            assertEquals(modern, CloudMemeCache(directory).cached("a")!!.lists)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun collectionChecksAccountAfterReadingAndAtTheWriteBoundary() = runBlocking {
        val directory = Files.createTempDirectory("cloud-memes").toFile()
        try {
            val original = "a" to "secret"
            for (switchInFetch in listOf(true, false)) {
                var account = original
                var submitCalls = 0
                var writes = 0
                val cache = CloudMemeCache(directory, submit = { _, _, validate ->
                    submitCalls++
                    account = "b" to "other"
                    validate()
                    writes++
                }) {
                    if (switchInFetch) account = "b" to "other"
                    lists
                }
                val result = collectPostImageAsCloudMeme("", "42", original, { account }, cache) { _, _ ->
                    error("贴纸自带 ID 不应发起转换请求")
                }
                assertEquals(CloudMemeCollectOutcome.AccountChanged, result)
                assertEquals(if (switchInFetch) 0 else 1, submitCalls)
                assertEquals(0, writes)
                assertNull(cache.cached("b"))
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun ordinaryPostImageUsesOnlyConvertedIdAndFailureDoesNotWrite() = runBlocking {
        val directory = Files.createTempDirectory("cloud-memes").toFile()
        try {
            val snapshot = "a" to "secret"
            val url = "https://example.com/original.jpg?quality=full"
            var writes = 0
            var failFetch = false
            var server = lists
            val cache = CloudMemeCache(directory, submit = { _, ids, validate ->
                validate()
                assertEquals(listOf("a", "42"), ids)
                writes++
                server = lists.copy(collected = lists.collected + CloudMeme("42", url, null))
            }) {
                if (failFetch) throw IOException("offline")
                server
            }
            val invalid = collectPostImageAsCloudMeme(url, null, snapshot, { snapshot }, cache) { _, _ ->
                CloudMemeRepository.parsePicId("""{"error_code":0,"pics":[{}]}""")
            }
            assertTrue(invalid is CloudMemeCollectOutcome.Failed)
            failFetch = true
            val offline = collectPostImageAsCloudMeme(url, null, snapshot, { snapshot }, cache) { _, _ -> "42" }
            assertTrue(offline is CloudMemeCollectOutcome.Failed)
            assertEquals(0, writes)
            failFetch = false
            val confirmed = collectPostImageAsCloudMeme(url, null, snapshot, { snapshot }, cache) { bduss, original ->
                assertEquals("secret", bduss)
                assertEquals(url, original)
                "42"
            }
            assertEquals(CloudMemeCollectOutcome.Collected, confirmed)
            assertEquals(1, writes)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun reopenedReplyPanelReadsConfirmedCollectionFromTheSharedCache() = runBlocking {
        val directory = Files.createTempDirectory("cloud-memes").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val snapshot = "a" to "credential"
            val old = CloudMeme("7", "https://example.com/old.gif", null, "0", 40, 30)
            val added = CloudMeme("42", "https://example.com/new.gif", null, "5", 60, 40)
            var server = CloudMemeLists(listOf(old), emptyList())
            val cache = CloudMemeCache(directory, submit = { _, ids, validate ->
                validate()
                assertEquals(listOf("7", "42"), ids)
                server = server.copy(collected = listOf(old, added))
            }) { server }
            cache.load(snapshot.first, snapshot.second)
            val outcome = collectPostImageAsCloudMeme(added.imageUrl, added.id, snapshot, { snapshot }, cache) { _, _ ->
                error("原生贴纸不应转换 ID")
            }
            assertEquals(CloudMemeCollectOutcome.Collected, outcome)
            val confirmed = checkNotNull(cache.cached(snapshot.first))
            val session = CloudMemeSession(scope, cached = { confirmed }, requiresMetadata = true) {
                error("重开面板应直接读取刚确认的完整缓存")
            }
            session.open()
            assertEquals(listOf("7", "5_42"), session.state.value.lists!!.collected.map { it.replyIdPart })
            assertNull(session.state.value.error)
            session.close()
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun invalidNativeStickerIdDoesNotFallBackToImageConversionOrWrite() = runBlocking {
        val directory = Files.createTempDirectory("cloud-memes").toFile()
        try {
            val snapshot = "a" to "credential"
            var fetches = 0
            var resolves = 0
            var writes = 0
            val cache = CloudMemeCache(directory, submit = { _, _, _ -> writes++ }) {
                fetches++
                lists
            }
            val result = collectPostImageAsCloudMeme("https://example.com/image.gif", "0", snapshot, { snapshot }, cache) { _, _ ->
                resolves++
                "42"
            }
            assertTrue(result is CloudMemeCollectOutcome.Failed)
            assertEquals(0, resolves)
            assertEquals(0, fetches)
            assertEquals(0, writes)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun collectionCancellationAfterLatestFetchPreventsWrite() = runBlocking {
        val directory = Files.createTempDirectory("cloud-memes").toFile()
        try {
            val entered = CompletableDeferred<Unit>()
            val pending = CompletableDeferred<CloudMemeLists>()
            var writes = 0
            val cache = CloudMemeCache(directory, submit = { _, _, validate ->
                validate()
                writes++
            }) {
                entered.complete(Unit)
                withContext(NonCancellable) { pending.await() }
            }
            val job = launch { cache.collectMeme("a", "secret", "42") { "a" to "secret" } }
            entered.await()
            job.cancel()
            pending.complete(lists)
            job.join()
            assertEquals(0, writes)
            assertNull(cache.cached("a"))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun collectionTimeoutIsNotRetriedOrReportedAsConfirmed() = runBlocking {
        val directory = Files.createTempDirectory("cloud-memes").toFile()
        try {
            var fetches = 0
            var attempts = 0
            val cache = CloudMemeCache(directory, submit = { _, _, validate ->
                validate()
                attempts++
                throw java.net.SocketTimeoutException("timeout")
            }) {
                fetches++
                lists
            }
            val snapshot = "a" to "secret"
            val outcome = collectPostImageAsCloudMeme("", "42", snapshot, { snapshot }, cache) { _, _ ->
                error("贴纸自带 ID 不应发起转换请求")
            }
            assertTrue(outcome is CloudMemeCollectOutcome.Failed)
            assertEquals(1, attempts)
            assertEquals(1, fetches)
            assertNull(cache.cached("a"))
        } finally { directory.deleteRecursively() }
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
