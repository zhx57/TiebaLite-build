package com.huanchengfly.tieba.post.repository

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.huanchengfly.tieba.post.api.retrofit.RetrofitTiebaApi
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

internal data class CloudMeme(val id: String, val imageUrl: String, val thumbnail: String?) {
    val displayUrl: String get() = thumbnail?.takeIf { it.isNotBlank() } ?: imageUrl
}

internal data class CloudMemeLists(val collected: List<CloudMeme>, val diy: List<CloudMeme>)

internal class CloudMemeProtocolException : IOException("云表情响应格式异常，请稍后重试")
internal class CloudMemeServiceException(val code: Int) : IOException("云表情服务返回错误（$code）")

internal object CloudMemeRepository {
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

internal data class CloudMemeState(
    val lists: CloudMemeLists? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** One instance belongs to one immutable account snapshot and one open panel. */
internal class CloudMemeSession(
    private val scope: CoroutineScope,
    private val load: suspend () -> CloudMemeLists,
) {
    private val mutableState = MutableStateFlow(CloudMemeState())
    val state: StateFlow<CloudMemeState> = mutableState
    private var generation = 0L
    private var closed = false
    private var job: Job? = null

    fun refresh() {
        if (closed || state.value.loading) return
        val requestGeneration = ++generation
        mutableState.value = state.value.copy(loading = true, error = null)
        job = scope.launch {
            try {
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
