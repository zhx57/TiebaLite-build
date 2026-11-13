package com.huanchengfly.tieba.post.repository

import com.huanchengfly.tieba.post.api.retrofit.interfaces.OfficialTiebaApi
import com.huanchengfly.tieba.post.api.retrofit.interceptors.CommonParamInterceptor
import com.huanchengfly.tieba.post.api.retrofit.interceptors.SortAndSignInterceptor
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import java.security.MessageDigest

class CloudMemeContractTest {
    private val pic = """{"pic_id":"123","pic_url":"https://example.com/full.gif","thumbnail":"https://example.com/thumb.gif"}"""
    private fun response(collected: String = "[]", diy: String = "[]") =
        """{"error_code":"0","data":{"pic_ids":$collected,"diy_pic_ids":$diy}}"""

    @Test
    fun parsesBothListsAndRealEmptyArrays() {
        val both = CloudMemeRepository.parse(response("[$pic]", "[$pic]"))
        assertEquals("123", both.collected.single().id)
        assertEquals("https://example.com/thumb.gif", both.diy.single().displayUrl)
        assertTrue(CloudMemeRepository.parse(response()).collected.isEmpty())
        assertTrue(CloudMemeRepository.parse(response(diy = "[$pic]")).collected.isEmpty())
        assertTrue(CloudMemeRepository.parse(response(collected = "[$pic]")).diy.isEmpty())
    }

    @Test
    fun usesFullImageWhenThumbnailIsMissingAndIgnoresUnknownFields() {
        val raw = """{"error_code":0,"new_field":{},"data":{"pic_ids":[{"pic_id":"x","pic_url":"https://example.com/full"}],"diy_pic_ids":[]}}"""
        assertEquals("https://example.com/full", CloudMemeRepository.parse(raw).collected.single().displayUrl)
    }

    @Test
    fun rejectsMissingOrChangedFieldsRatherThanReturningEmptySuccess() {
        listOf(
            "{}", "{", "[]", """{"data":{"pic_ids":[],"diy_pic_ids":[]}}""",
            """{"error_code":0}""", """{"error_code":0,"data":null}""",
            """{"error_code":false,"data":{"pic_ids":[],"diy_pic_ids":[]}}""",
            """{"error_code":0,"data":{"pic_ids":[]}}""",
            response("null"), response("{}"), response("\"[]\""),
            response("[null]"), response("[{}]"),
            response("""[{"pic_id":"x","pic_url":7}]"""),
            response("""[{"pic_id":"x"}]"""),
        ).forEach { raw ->
            assertTrue(raw, runCatching { CloudMemeRepository.parse(raw) }.exceptionOrNull() is CloudMemeProtocolException)
        }
        val error = runCatching { CloudMemeRepository.parse("""{"error_code":110001}""") }.exceptionOrNull()
        assertEquals(110001, (error as CloudMemeServiceException).code)
    }

    @Test
    fun sendsHttpsPostAndSignsCommandWithSnapshotCredential() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(CommonParamInterceptor("BDUSS" to { "other-account" }, "_client_version" to { "12.0" }))
            .addInterceptor(SortAndSignInterceptor("test-secret"))
            .addInterceptor { chain ->
                captured = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(response().toResponseBody()).build()
            }.build()
        val api = Retrofit.Builder().baseUrl("http://example.com/").client(client)
            .build().create(OfficialTiebaApi::class.java)
        api.getCloudMemes("snapshot-account").close()
        val request = captured!!
        assertEquals("POST", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("tiebac.baidu.com", request.url.host)
        assertEquals("/c/e/meme/getAllMeme", request.url.encodedPath)
        assertEquals("1003351", request.url.queryParameter("cmd"))
        val form = request.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("snapshot-account", fields["BDUSS"])
        assertEquals("1003351", fields["cmd"])
        val signed = fields.filterKeys { it != "sign" }.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" }
        val expected = MessageDigest.getInstance("MD5").digest((signed + "test-secret").toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected.lowercase(), fields["sign"]!!.lowercase())
    }
}
