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

internal data class CloudMeme(
    val id: String,
    val imageUrl: String,
    val thumbnail: String?,
    val packageId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** 官方回帖编辑器展示用的原始标记，收藏列表接口会直接返回。 */
    val sharpText: String? = null,
) {
    val displayUrl: String get() = thumbnail?.takeIf { it.isNotBlank() } ?: imageUrl
    val replyIdPart: String?
        get() {
            if (!id.isCloudMemePicId() || (width ?: 0) <= 0 || (height ?: 0) <= 0) return null
            val pack = packageId.orEmpty()
            if (pack.isEmpty() || pack == "0") return id
            return if (pack.isCloudMemePicId()) "${pack}_$id" else null
        }
}

/** 官方表情包组：回帖面板按包展示，包内条目需单独请求详情。 */
internal data class CloudMemePackage(
    val id: String,
    val name: String,
    val cover: String?,
    val memes: List<CloudMeme>,
)

internal fun String.isCloudMemePicId(): Boolean =
    isNotEmpty() && all { it in '0'..'9' } && toULongOrNull()?.let { it > 0uL } == true

internal data class CloudMemeLists(
    val collected: List<CloudMeme>,
    val diy: List<CloudMeme>,
    val picUpdateTime: Long? = null,
    val diyPicUpdateTime: Long? = null,
    /** 表达式包含的包 ID 列表，回帖编辑器按包组织表情。 */
    val packageIds: List<String> = emptyList(),
    val packageUpdateTime: Long? = null,
)

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

    suspend fun resolvePicId(bduss: String, imageUrl: String): String = withContext(Dispatchers.IO) {
        require(bduss.isNotBlank() && imageUrl.isNotBlank())
        val urls = JsonArray().apply { add(imageUrl) }.toString()
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.resolveCloudMemeId(bduss, urls).use { parsePicId(it.string()) }
    }

    /** 拉取单个表情包详情。包内条目带真实宽高，才能生成合法的回帖贴纸标记。 */
    suspend fun loadPackage(bduss: String, packageId: String): CloudMemePackage =
        withContext(Dispatchers.IO) {
            require(bduss.isNotBlank() && packageId.isNotBlank())
            RetrofitTiebaApi.OFFICIAL_TIEBA_API.getMemePackage(bduss, packageId)
                .use { parsePackage(it.string(), packageId) }
        }

    fun parsePackage(json: String, expectedPackageId: String): CloudMemePackage {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.get("error_code")?.let(::scalar)?.toIntOrNull()
                ?: throw CloudMemeProtocolException()
            if (code != 0) throw CloudMemeServiceException(code)
            val data = root.getAsJsonObject("data") ?: root
            val id = optionalString(data, "id")?.takeIf { it.isNotBlank() } ?: expectedPackageId
            if (id != expectedPackageId) throw CloudMemeProtocolException()
            val pics = data.get("pics")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw CloudMemeProtocolException()
            val memes = pics.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                parsePackageItem(element.asJsonObject, id)
            }
            if (memes.isEmpty()) throw CloudMemeProtocolException()
            return CloudMemePackage(
                id = id,
                name = optionalString(data, "name").orEmpty(),
                cover = optionalString(data, "cover")?.takeIf { it.isNotBlank() },
                memes = memes,
            )
        } catch (error: CloudMemeServiceException) {
            throw error
        } catch (error: Exception) {
            throw CloudMemeProtocolException()
        }
    }

    /** 包内条目单条异常只跳过该条，避免一个坏字段拖垮整个包。 */
    private fun parsePackageItem(item: JsonObject, packageId: String): CloudMeme? = runCatching {
        val id = optionalString(item, "id")?.takeIf { it.isCloudMemePicId() } ?: return null
        val url = optionalString(item, "url").orEmpty()
        val thumbnail = optionalString(item, "thumbnail")?.takeIf { it.isNotBlank() }
        if (url.isBlank() && thumbnail == null) return null
        val width = optionalPositiveInt(item, "width")
        val height = optionalPositiveInt(item, "height")
        CloudMeme(
            id = id,
            imageUrl = url,
            thumbnail = thumbnail,
            packageId = packageId,
            width = width,
            height = height,
        )
    }.getOrNull()

    fun parsePicId(json: String): String {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.get("error_code")?.let(::scalar)?.toIntOrNull()
                ?: throw CloudMemeProtocolException()
            if (code != 0) throw CloudMemeServiceException(code)
            val pics = root.get("pics")?.asJsonArray
            val resolved = if (pics == null || pics.size() == 0) {
                root.getAsJsonObject("data")?.getAsJsonArray("pics")
            } else pics
            // 单张原图只接受一个明确的服务端 ID，不从地址或多项响应中猜测。
            if (resolved == null || resolved.size() != 1) throw CloudMemeProtocolException()
            return resolved[0].asJsonObject.get("pic_id")?.let(::scalar)
                ?.takeIf { it.isCloudMemePicId() } ?: throw CloudMemeProtocolException()
        } catch (error: CloudMemeServiceException) {
            throw error
        } catch (error: Exception) {
            throw CloudMemeProtocolException()
        }
    }

    suspend fun submitCollected(
        bduss: String,
        ids: List<String>,
        validate: suspend () -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(bduss.isNotBlank())
        val uniqueIds = ids.filter(String::isNotBlank).distinct()
        val pics = if (uniqueIds.isEmpty()) {
            "all_delete"
        } else {
            JsonArray().apply {
                uniqueIds.forEach { id -> add(JsonObject().apply { addProperty("pic_id", id) }) }
            }.toString()
        }
        val api = RetrofitTiebaApi.OFFICIAL_TIEBA_API
        validate()
        api.updateCloudMemes(
            bduss = bduss,
            pics = pics,
            picUpdateTime = System.currentTimeMillis().toString(),
        ).use { response ->
            try {
                val root = JsonParser.parseString(response.string()).asJsonObject
                val code = root.get("error_code")?.let(::scalar)?.toIntOrNull()
                    ?: throw CloudMemeProtocolException()
                if (code != 0) throw CloudMemeServiceException(code)
            } catch (error: CloudMemeServiceException) {
                throw error
            } catch (error: Exception) {
                throw CloudMemeProtocolException()
            }
        }
    }

    fun parse(json: String): CloudMemeLists {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val codeElement = root.get("error_code") ?: throw CloudMemeProtocolException()
            val code = scalar(codeElement).toIntOrNull() ?: throw CloudMemeProtocolException()
            if (code != 0) throw CloudMemeServiceException(code)
            val data = root.getAsJsonObject("data") ?: throw CloudMemeProtocolException()
            return CloudMemeLists(
                collected = parseList(data, "pic_ids"),
                diy = parseList(data, "diy_pic_ids"),
                picUpdateTime = optionalLong(data, "pic_update_time"),
                diyPicUpdateTime = optionalLong(data, "diy_pic_update_time"),
                packageIds = parsePackageIds(data),
                packageUpdateTime = optionalLong(data, "pkg_update_time"),
            )
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
            CloudMeme(
                id, url, thumbnail,
                packageId = item.get("pck_id")?.takeUnless { it.isJsonNull }?.let { value ->
                    // 异常值不可用于回帖，但不影响条目预览及聊天。
                    if (value.isJsonPrimitive) value.asString else value.toString()
                },
                width = optionalPositiveInt(item, "width"),
                height = optionalPositiveInt(item, "height"),
                sharpText = optionalString(item, "sharp_text")?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * 官方返回的 package_ids 可能是下划线分隔的字符串，也可能是数组。
     * 两种情况都按包 ID 拆分，并丢弃非数字项。
     */
    private fun parsePackageIds(data: JsonObject): List<String> {
        val value = data.get("package_ids") ?: return emptyList()
        if (value.isJsonNull) return emptyList()
        val raw = when {
            value.isJsonArray -> value.asJsonArray.mapNotNull { element ->
                if (element.isJsonPrimitive) element.asString else null
            }
            value.isJsonPrimitive -> value.asString.split('_')
            else -> return emptyList()
        }
        return raw.map(String::trim).filter(String::isCloudMemePicId).distinct()
    }

    private fun optionalString(item: JsonObject, name: String): String? {
        val value = item.get(name) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) throw CloudMemeProtocolException()
        return value.asString
    }

    private fun optionalPositiveInt(item: JsonObject, name: String): Int? =
        optionalLong(item, name)?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()

    private fun optionalLong(item: JsonObject, name: String): Long? {
        val value = item.get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return value.asString.toLongOrNull()
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
    return if (collected === previous.collected && diy === previous.diy &&
        incoming.picUpdateTime == previous.picUpdateTime &&
        incoming.diyPicUpdateTime == previous.diyPicUpdateTime &&
        incoming.packageIds == previous.packageIds &&
        incoming.packageUpdateTime == previous.packageUpdateTime
    ) previous
    else CloudMemeLists(
        collected = collected,
        diy = diy,
        picUpdateTime = incoming.picUpdateTime,
        diyPicUpdateTime = incoming.diyPicUpdateTime,
        packageIds = incoming.packageIds,
        packageUpdateTime = incoming.packageUpdateTime,
    )
}

internal class CloudMemeCache(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val submit: suspend (String, List<String>, suspend () -> Unit) -> Unit = CloudMemeRepository::submitCollected,
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

    suspend fun addCollected(
        uid: String,
        bduss: String,
        memeId: String,
        validateAccount: () -> Unit = {},
    ): CloudMemeLists = withContext(Dispatchers.IO) {
        require(uid.isNotBlank() && bduss.isNotBlank() && memeId.isNotBlank())
        suspend fun validate() {
            currentCoroutineContext().ensureActive()
            validateAccount()
        }
        lock(uid).withLock {
            validate()
            val latest = fetch(bduss)
            validate()
            if (latest.collected.any { it.id == memeId }) {
                memory[uid] = CloudMemeSnapshot(latest, now())
                runCatching { write(uid, memory.getValue(uid)) }
                return@withLock latest
            }
            val ids = (latest.collected.map { it.id } + memeId).distinct()
            validate()
            submit(bduss, ids) { validate() }
            validate()
            val confirmed = fetch(bduss)
            validate()
            if (confirmed.collected.none { it.id == memeId }) {
                throw CloudMemeProtocolException()
            }
            val snapshot = CloudMemeSnapshot(confirmed, now())
            memory[uid] = snapshot
            runCatching { write(uid, snapshot) }
            confirmed
        }
    }

    private fun write(uid: String, snapshot: CloudMemeSnapshot) {
        fun array(memes: List<CloudMeme>) = JsonArray().apply {
            memes.forEach { meme ->
                add(JsonObject().apply {
                    addProperty("pic_id", meme.id)
                    addProperty("pic_url", meme.imageUrl)
                    meme.thumbnail?.let { addProperty("thumbnail", it) }
                    meme.packageId?.let { addProperty("pck_id", it) }
                    meme.width?.let { addProperty("width", it) }
                    meme.height?.let { addProperty("height", it) }
                    meme.sharpText?.let { addProperty("sharp_text", it) }
                })
            }
        }
        val response = JsonObject().apply {
            addProperty("error_code", 0)
            add("data", JsonObject().apply {
                add("pic_ids", array(snapshot.lists.collected))
                add("diy_pic_ids", array(snapshot.lists.diy))
                if (snapshot.lists.packageIds.isNotEmpty()) {
                    addProperty("package_ids", snapshot.lists.packageIds.joinToString("_"))
                }
                snapshot.lists.picUpdateTime?.let { addProperty("pic_update_time", it) }
                snapshot.lists.diyPicUpdateTime?.let { addProperty("diy_pic_update_time", it) }
                snapshot.lists.packageUpdateTime?.let { addProperty("pkg_update_time", it) }
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
    private val requiresMetadata: Boolean = false,
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
                    val fresh = snapshot.isFresh() && (!requiresMetadata ||
                        (snapshot.lists.collected + snapshot.lists.diy).all { it.replyIdPart != null })
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

/**
 * 把一张带可靠官方资源标识的图片加入当前账号的云收藏。
 *
 * 调用方必须传入贴吧官方 [picId]，URL 文件名与列表序号不能当作资源标识。
 * 收藏结果以服务端重新拉取确认为准，避免出现本地提示成功但服务端未生效。
 */
internal suspend fun CloudMemeCache.collectMeme(
    uid: String,
    bduss: String,
    picId: String,
    expectedAccount: () -> Pair<String, String>,
): CloudMemeLists {
    require(picId.isNotBlank()) { "缺少可用于收藏的图片资源标识" }
    return addCollected(uid, bduss, picId) {
        check(expectedAccount() == (uid to bduss)) { "收藏期间账号已切换" }
    }
}
