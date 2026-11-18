package com.huanchengfly.tieba.post.repository

import com.huanchengfly.tieba.post.api.retrofit.converter.kotlinx.serialization.asConverterFactory
import com.huanchengfly.tieba.post.api.retrofit.converter.gson.GsonConverterFactory
import retrofit2.Converter
import okhttp3.ResponseBody
import com.huanchengfly.tieba.post.api.models.UploadPictureResultBean
import com.huanchengfly.tieba.post.api.models.protos.MemeInfo
import com.huanchengfly.tieba.post.api.models.protos.PbContent
import com.huanchengfly.tieba.post.api.models.protos.renders
import com.huanchengfly.tieba.post.ui.common.MemeContentRender
import kotlinx.serialization.json.Json
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

    private val uploadConverter: Converter<ResponseBody, UploadPictureResultBean> = Retrofit.Builder()
        .baseUrl("https://example.com/")
        .addConverterFactory(Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }.asConverterFactory())
        .addConverterFactory(GsonConverterFactory.create())
        .build().responseBodyConverter(UploadPictureResultBean::class.java, emptyArray())

    private fun decodeUpload(raw: String): UploadPictureResultBean =
        uploadConverter.convert(raw.toResponseBody())!!

    @Test
    fun uploadPictureAllowsMissingChunkNumberInFinalResponse() {
        val result = decodeUpload(
            """{"error_code":"0","error_msg":"ok","pic_id":"p1","pic_info":{"origin_pic":{"width":"1","height":"1","type":"gif","pic_url":"https://example.com/o"},"big_pic":{"width":"1","height":"1","type":"gif","pic_url":"https://example.com/b"},"small_pic":{"width":"1","height":"1","type":"gif","pic_url":"https://example.com/s"}}}""",
        )
        assertEquals("p1", result.picId)
        assertEquals(null, result.chunkNo)
        assertEquals("https://example.com/b", result.picInfo!!.bigPic.picUrl)
    }

    @Test
    fun uploadPictureAcceptsLegacyTopLevelInfoResponse() {
        val result = decodeUpload(
            """{"error_code":"0","error_msg":"ok","info":{"pic_id":"legacy-pic","width":"100","height":"100","pic_url":"https://example.com/legacy.jpg"}}""",
        )
        assertEquals("legacy-pic", result.resolvedPicId)
        assertEquals("https://example.com/legacy.jpg", result.resolvedPicInfo!!.bigPic.picUrl)
    }

    @Test
    fun uploadPictureAcceptsSnakeAndCamelCaseResponseFields() {
        val snake = decodeUpload(
            """{"error_code":"0","error_msg":"ok","chunk_no":"1","pic_id":"p1","pic_info":{"origin_pic":{"width":"1","height":"1","type":"gif","pic_url":"https://example.com/o"},"big_pic":{"width":"1","height":"1","type":"gif","pic_url":"https://example.com/b"},"small_pic":{"width":"1","height":"1","type":"gif","pic_url":"https://example.com/s"}}}""",
        )
        val camel = decodeUpload(
            """{"errorCode":"0","errorMsg":"ok","chunkNo":"1","picId":"p2","picInfo":{"originPic":{"width":"1","height":"1","type":"gif","picUrl":"https://example.com/o"},"bigPic":{"width":"1","height":"1","type":"gif","picUrl":"https://example.com/b"},"smallPic":{"width":"1","height":"1","type":"gif","picUrl":"https://example.com/s"}}}""",
        )
        assertEquals("p1", snake.picId)
        assertEquals("https://example.com/b", snake.picInfo!!.bigPic.picUrl)
        assertEquals("p2", camel.picId)
        assertEquals("https://example.com/s", camel.picInfo!!.smallPic.picUrl)
    }

    @Test
    fun uploadBoundaryAcceptsObjectsArraysAndAcknowledgements() {
        val resource = """{"pic_id":123,"pic_url":"https://example.com/a","width":100,"height":200}"""
        listOf(resource, """{"error_code":0,"info":$resource}""",
            """{"error_code":"0","info":[$resource]}""", "[$resource]").forEach { raw ->
            val picture = decodeUpload(raw).uploadedPicture!!
            assertEquals("123", picture.picId)
            assertEquals("https://example.com/a", picture.imageUrl)
        }
        listOf("[]", "{}", """{"error_code":0,"chunkNo":1}""",
            """{"error_code":"0","info":[]}""", """{"info":null}""").forEach { raw ->
            assertNull(raw, decodeUpload(raw).uploadedPicture)
        }
    }

    @Test
    fun uploadBoundaryDoesNotTurnJsonNullIntoAnIdOrUrl() {
        val picture = decodeUpload("""{"info":{"pic_id":null,"pic_url":"https://example.com/a"}}""").uploadedPicture!!
        assertNull(picture.picId)
        assertEquals("https://example.com/a", picture.imageUrl)
        assertNull(decodeUpload("""{"info":{"pic_id":null,"pic_url":null}}""").uploadedPicture)
        assertNull(decodeUpload("""{"pic_id":"id-only"}""").uploadedPicture!!.imageUrl)
    }

    @Test
    fun uploadBoundaryRejectsAmbiguousResourcesRatherThanMixingFirstIdAndUrl() {
        listOf(
            """{"info":[{"pic_id":"first"},{"pic_id":"second","pic_url":"https://example.com/b"}]}""",
            """{"info":[{"pic_url":"https://example.com/a"},{"pic_id":"second"}]}""",
            """{"pic_id":"outer","info":{"pic_id":"inner","pic_url":"https://example.com/b"}}""",
            """{"info":[{"pic_id":"a","pic_url":"https://example.com/a"},{"pic_id":"b","pic_url":"https://example.com/b"}]}""",
        ).forEach { raw ->
            assertTrue(raw, runCatching { decodeUpload(raw).uploadedPicture }.isFailure)
        }
    }

    @Test
    fun uploadBoundaryAcceptsNestedCamelInfoAndNumericBusinessErrors() {
        val result = decodeUpload("""{"errorCode":0,"resourceId":"r","chunkNo":2,"info":{"picId":"p","picInfo":{"bigPic":{"picUrl":"https://example.com/b","width":2,"height":3}}}}""")
        assertEquals("0", result.errorCode)
        assertEquals(String::class.java, UploadPictureResultBean::class.java.getDeclaredField("errorCode").type)
        assertEquals("r", result.resourceId)
        assertEquals("2", result.chunkNo)
        assertEquals("p", result.uploadedPicture!!.picId)
        assertEquals("https://example.com/b", result.uploadedPicture!!.imageUrl)
        listOf("110", "\"110\"").forEach { code ->
            val error = decodeUpload("""{"error_code":$code,"error_msg":"denied","info":[]}""")
            assertEquals("110", error.errorCode)
            assertEquals("denied", error.errorMsg)
        }
    }

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
    fun preservesReplyMetadataAndStillReadsLegacyEntries() {
        val modern = """{"pic_id":123,"pck_id":5,"width":"120","height":80,"pic_url":"https://example.com/m.gif"}"""
        val result = CloudMemeRepository.parse(response("[$modern]", "[$pic]"))
        val meme = result.collected.single()
        assertEquals("5_123", meme.replyIdPart)
        assertEquals(120, meme.width)
        assertEquals(80, meme.height)
        assertNull(result.diy.single().replyIdPart)
    }

    @Test
    fun malformedOptionalPackageDoesNotPoisonEitherListOrBecomeAnUnpackagedSticker() {
        val valid = """{"pic_id":123,"pck_id":5,"width":120,"height":80,"pic_url":"https://example.com/m.gif"}"""
        for (invalid in listOf("false", "[]", "{}", "1.5", "\"diy_5\"")) {
            val broken = valid.replace("\"pic_id\":123", "\"pic_id\":456").replace("\"pck_id\":5", "\"pck_id\":$invalid")
            val lists = CloudMemeRepository.parse(response("[$broken,$valid]", "[$broken]"))
            assertEquals(listOf("456", "123"), lists.collected.map { it.id })
            assertEquals("https://example.com/m.gif", lists.diy.single().displayUrl)
            assertNull(lists.diy.single().replyIdPart)
            assertNull(lists.collected.first().replyIdPart)
            assertEquals("5_123", lists.collected.last().replyIdPart)
        }
    }

    @Test
    fun pic2IdPrefersTopLevelAndRejectsUnconfirmedIds() {
        assertEquals("18446744073709551615", CloudMemeRepository.parsePicId(
            """{"error_code":0,"pics":[{"pic_id":18446744073709551615}],"data":{"pics":[{"pic_id":2}]}}""",
        ))
        assertEquals("42", CloudMemeRepository.parsePicId(
            """{"error_code":"0","data":{"pics":[{"pic_id":"42"}]}}""",
        ))
        assertEquals("42", CloudMemeRepository.parsePicId(
            """{"error_code":0,"pics":[],"data":{"pics":[{"pic_id":42}]}}""",
        ))
        listOf(
            """{"pics":[{"pic_id":1}]}""",
            """{"error_code":0,"pics":{}}""",
            """{"error_code":0,"pics":[]}""",
            """{"error_code":0,"pics":[{}]}""",
            """{"error_code":0,"pics":[{"pic_id":0}]}""",
            """{"error_code":0,"pics":[{"pic_id":"http://example.com"}]}""",
            """{"error_code":0,"pics":[{"pic_id":1},{"pic_id":2}]}""",
        ).forEach { raw ->
            assertTrue(raw, runCatching { CloudMemeRepository.parsePicId(raw) }.exceptionOrNull() is CloudMemeProtocolException)
        }
        val serviceError = runCatching {
            CloudMemeRepository.parsePicId("""{"error_code":110001,"pics":[{"pic_id":1}]}""")
        }.exceptionOrNull() as CloudMemeServiceException
        assertEquals(110001, serviceError.code)
    }

    @Test
    fun type20UsesStickerAndItsUnsignedIdBeforeOrdinaryImageParsing() {
        val sticker = PbContent(
            type = 20, src = "https://example.com/photo", bsize = "broken",
            memeInfo = MemeInfo(picId = -1L, picUrl = "https://example.com/sticker", width = 64, height = 48),
        )
        val render = listOf(sticker).renders.single() as MemeContentRender
        assertEquals("18446744073709551615", render.picId)
        assertEquals("https://example.com/sticker", render.imageUrl)
        assertEquals(64, render.width)
        assertEquals(48, render.height)

        val thumbnailOnly = sticker.copy(memeInfo = MemeInfo(picId = 42L, thumbnail = "https://example.com/thumb"))
        val thumbnailRender = listOf(thumbnailOnly).renders.single() as MemeContentRender
        assertEquals("42", thumbnailRender.picId)
        assertEquals("https://example.com/thumb", thumbnailRender.imageUrl)
    }

    @Test
    fun pic2IdSendsSignedFormWithSnapshotCredentialAndArrayOfUrls() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(CommonParamInterceptor("BDUSS" to { "other-account" }, "_client_version" to { "12.0" }))
            .addInterceptor(SortAndSignInterceptor("test-secret"))
            .addInterceptor { chain ->
                captured = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("""{"error_code":0,"pics":[{"pic_id":42}]}""".toResponseBody()).build()
            }.build()
        val api = Retrofit.Builder().baseUrl("http://example.com/").client(client)
            .build().create(OfficialTiebaApi::class.java)
        val urls = """["https://example.com/origin.jpg?x=1&y=2"]"""
        assertEquals("42", api.resolveCloudMemeId("snapshot-account", urls).use {
            CloudMemeRepository.parsePicId(it.string())
        })
        val request = captured!!
        assertEquals("POST", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("tiebac.baidu.com", request.url.host)
        assertEquals("/c/e/meme/pic2idJson", request.url.encodedPath)
        assertEquals("1003353", request.url.queryParameter("cmd"))
        val form = request.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("snapshot-account", fields["BDUSS"])
        assertEquals("1003353", fields["cmd"])
        assertEquals(urls, fields["pic_urls"])
        val signed = fields.filterKeys { it != "sign" }.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" }
        val expected = MessageDigest.getInstance("MD5").digest((signed + "test-secret").toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, fields["sign"]!!.lowercase())
    }

    @Test
    fun explicitEmptySnapshotUsesAllDeleteSentinel() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                captured = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(response().toResponseBody()).build()
            }.build()
        val api = Retrofit.Builder().baseUrl("http://example.com/").client(client)
            .build().create(OfficialTiebaApi::class.java)
        api.updateCloudMemes("snapshot-account", pics = "all_delete", picUpdateTime = "1234").close()
        val form = captured!!.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("all_delete", fields["pics"])
        assertEquals("1003339", fields["cmd"])
    }

    @Test
    fun sendsFullCollectedSnapshotWithPicIdsAndOfficialTimestamp() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(CommonParamInterceptor("BDUSS" to { "other-account" }, "_client_version" to { "12.0" }))
            .addInterceptor { chain ->
                captured = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(response().toResponseBody()).build()
            }.build()
        val api = Retrofit.Builder().baseUrl("http://example.com/").client(client)
            .build().create(OfficialTiebaApi::class.java)
        api.updateCloudMemes(
            bduss = "snapshot-account",
            pics = "[{\"pic_id\":\"old\"},{\"pic_id\":\"new\"}]",
            picUpdateTime = "1234",
        ).close()
        val request = captured!!
        assertEquals("POST", request.method)
        assertEquals("https", request.url.scheme)
        assertEquals("tiebac.baidu.com", request.url.host)
        assertEquals("/c/e/meme/collect", request.url.encodedPath)
        val form = request.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("snapshot-account", fields["BDUSS"])
        assertEquals("1003339", fields["cmd"])
        assertEquals("[{\"pic_id\":\"old\"},{\"pic_id\":\"new\"}]", fields["pics"])
        assertEquals("1234", fields["pic_update_time"])
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
