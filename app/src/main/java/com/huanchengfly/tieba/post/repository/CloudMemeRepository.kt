package com.huanchengfly.tieba.post.repository

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.huanchengfly.tieba.post.api.retrofit.RetrofitTiebaApi
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal data class CloudMeme(val id: String, val imageUrl: String, val thumbnail: String?) {
    val displayUrl: String get() = thumbnail?.takeIf { it.isNotBlank() } ?: imageUrl
}

internal data class CloudMemeLists(val collected: List<CloudMeme>, val diy: List<CloudMeme>)

internal class CloudMemeProtocolException : IOException("云表情响应格式异常，请稍后重试")
internal class CloudMemeServiceException(val code: Int) : IOException("云表情服务返回错误（$code）")

internal object CloudMemeRepository {
    private var cache: CloudMemeCache? = null

    @Synchronized
    fun cache(filesDir: File): CloudMemeCache = cache ?: CloudMemeCache(
        File(filesDir, "cloud_memes"),
    ).also { cache = it }

    suspend fun load(bduss: String): CloudMemeLists = withContext(Dispatchers.IO) {
        require(bduss.isNotBlank())
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.getCloudMemes(bduss).use { parse(it.string()) }
    }

    fun parse(json: String): CloudMemeLists {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val codeElement = root.get("error_code") ?: throw CloudMemeProtocolException()
            val code = scalar(codeElement).toIntOrNull() ?: throw CloudMemeProtocolException()
            if (code != 0) throw CloudMemeServiceException(code)
            val data = root.getAsJsonObject("data") ?: throw CloudMemeProtocolException()
            return CloudMemeLists(parseList(data, "pic_ids"), parseList(data, "diy_pic_ids"))
        } catch (error: CloudMemeServiceException) {
            throw error
        } catch (error: Exception) {
            throw CloudMemeProtocolException()
        }
    }

    private fun scalar(value: JsonElement): String {
        if (!value.isJsonPrimitive || value.asJsonPrimitive.isBoolean) throw CloudMemeProtocolException()
        return value.asString
    }

    private fun parseList(data: JsonObject, name: String): List<CloudMeme> {
        val array = data.get(name)?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw CloudMemeProtocolException()
        return array.map { element ->
            val item = element.asJsonObject
            val id = scalar(item.get("pic_id") ?: throw CloudMemeProtocolException())
            if (id.isBlank()) throw CloudMemeProtocolException()
            val url = optionalString(item, "pic_url").orEmpty()
            val thumbnail = optionalString(item, "thumbnail")
            if (url.isBlank() && thumbnail.isNullOrBlank()) throw CloudMemeProtocolException()
            CloudMeme(id, url, thumbnail)
        }
    }

    private fun optionalString(item: JsonObject, name: String): String? {
        val value = item.get(name) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw CloudMemeProtocolException()
        return value.asString
    }
}

internal data class CloudMemeSnapshot(val lists: CloudMemeLists, val updatedAt: Long) {
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        now >= updatedAt && now - updatedAt < 5 * 60 * 1000L
}

/** Full server responses are reconciled locally; this is not server-side incremental loading. */
internal fun reconcileCloudMemes(previous: CloudMemeLists?, incoming: CloudMemeLists): CloudMemeLists {
    if (previous == null) return incoming
    fun reconcile(old: List<CloudMeme>, next: List<CloudMeme>): List<CloudMeme> {
        val byId = old.associateBy { it.id }
        val merged = next.map { meme -> byId[meme.id]?.takeIf { it == meme } ?: meme }
        return if (old == merged) old else merged
    }
    val collected = reconcile(previous.collected, incoming.collected)
    val diy = reconcile(previous.diy, incoming.diy)
    return if (collected === previous.collected && diy === previous.diy) previous
    else CloudMemeLists(collected, diy)
}

internal class CloudMemeCache(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetch: suspend (String) -> CloudMemeLists = CloudMemeRepository::load,
) {
    private val memory = ConcurrentHashMap<String, CloudMemeSnapshot>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lock(uid: String): Mutex = locks.getOrPut(uid) { Mutex() }

    private fun file(uid: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$name.json")
    }

    private fun read(uid: String): CloudMemeSnapshot? {
        memory[uid]?.let { return it }
        return runCatching {
            val root = JsonParser.parseString(file(uid).readText()).asJsonObject
            val updatedAt = root.get("updated_at").asLong
            val lists = CloudMemeRepository.parse(root.getAsJsonObject("response").toString())
            CloudMemeSnapshot(lists, updatedAt).also { memory[uid] = it }
        }.getOrNull()
    }

    suspend fun cached(uid: String): CloudMemeSnapshot? = withContext(Dispatchers.IO) {
        require(uid.isNotBlank())
        lock(uid).withLock { read(uid) }
    }

    suspend fun load(uid: String, bduss: String, force: Boolean = false): CloudMemeLists =
        withContext(Dispatchers.IO) {
            require(uid.isNotBlank() && bduss.isNotBlank())
            lock(uid).withLock {
                val previous = read(uid)
                if (!force && previous?.isFresh(now()) == true) return@withLock previous.lists
                val lists = reconcileCloudMemes(previous?.lists, fetch(bduss))
                currentCoroutineContext().ensureActive()
                val snapshot = CloudMemeSnapshot(lists, now())
                memory[uid] = snapshot
                // Persistence is best-effort and must not turn a successful response into an error.
                runCatching { write(uid, snapshot) }
                lists
            }
        }

    private fun write(uid: String, snapshot: CloudMemeSnapshot) {
        fun array(memes: List<CloudMeme>) = JsonArray().apply {
            memes.forEach { meme ->
                add(JsonObject().apply {
                    addProperty("pic_id", meme.id)
                    addProperty("pic_url", meme.imageUrl)
                    meme.thumbnail?.let { addProperty("thumbnail", it) }
                })
            }
        }
        val response = JsonObject().apply {
            addProperty("error_code", 0)
            add("data", JsonObject().apply {
                add("pic_ids", array(snapshot.lists.collected))
                add("diy_pic_ids", array(snapshot.lists.diy))
            })
        }
        val root = JsonObject().apply {
            addProperty("updated_at", snapshot.updatedAt)
            add("response", response)
        }
        directory.mkdirs()
        val target = file(uid)
        val temporary = File(directory, "${target.name}.tmp")
        try {
            temporary.writeText(root.toString())
            if (!temporary.renameTo(target)) throw IOException("Cannot replace cloud meme snapshot")
        } finally {
            temporary.delete()
        }
    }
}

internal data class CloudMemeState(
    val lists: CloudMemeLists? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** One instance belongs to one immutable account snapshot and one open panel. */
internal class CloudMemeSession(
    private val scope: CoroutineScope,
    private val cached: suspend () -> CloudMemeSnapshot? = { null },
    private val load: suspend () -> CloudMemeLists,
) {
    private val mutableState = MutableStateFlow(CloudMemeState())
    val state: StateFlow<CloudMemeState> = mutableState
    private var generation = 0L
    private var closed = false
    private var job: Job? = null

    fun open() = request(force = false)

    fun refresh() = request(force = true)

    private fun request(force: Boolean) {
        if (closed || state.value.loading) return
        val requestGeneration = ++generation
        mutableState.value = state.value.copy(loading = true, error = null)
        job = scope.launch {
            try {
                val snapshot = if (!force) cached() else null
                if (closed || generation != requestGeneration) return@launch
                if (snapshot != null) {
                    val fresh = snapshot.isFresh()
                    mutableState.value = CloudMemeState(snapshot.lists, loading = !fresh)
                    if (fresh) return@launch
                }
                val lists = load()
                if (!closed && generation == requestGeneration) {
                    mutableState.value = CloudMemeState(lists = lists)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!closed && generation == requestGeneration) {
                    val message = when (error) {
                        is CloudMemeProtocolException -> error.message!!
                        is CloudMemeServiceException -> error.message!!
                        is TiebaException -> "云表情服务返回错误（${error.code}），请重试或重新登录"
                        is HttpException -> "云表情请求失败（HTTP ${error.code()}）"
                        else -> "云表情加载失败，请检查网络后重试"
                    }
                    mutableState.value = state.value.copy(loading = false, error = message)
                }
            }
        }
    }

    fun close() {
        closed = true
        generation++
        job?.cancel()
        mutableState.value = CloudMemeState()
    }
}
