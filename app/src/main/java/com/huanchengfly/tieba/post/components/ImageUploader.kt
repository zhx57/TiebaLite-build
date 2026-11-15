package com.huanchengfly.tieba.post.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.BOUNDARY
import com.huanchengfly.tieba.post.api.booleanToString
import com.huanchengfly.tieba.post.api.models.UploadPictureResultBean
import com.huanchengfly.tieba.post.api.models.chat.ChatCloudMeme
import com.huanchengfly.tieba.post.api.retrofit.RetrofitTiebaApi
import com.huanchengfly.tieba.post.api.retrofit.body.MyMultipartBody
import com.huanchengfly.tieba.post.api.retrofit.body.buildMultipartBody
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaException
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorCode
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.ImageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import com.huanchengfly.tieba.post.utils.MD5Util
import com.huanchengfly.tieba.post.utils.appPreferences
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.io.RandomAccessFile

class ImageUploader(
    private val forumName: String,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE
) {
    companion object {
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        private val cloudMemeCache = ConcurrentHashMap<String, ChatCloudMeme>()
        private val cloudMemeUploads = ConcurrentHashMap<String, Deferred<ChatCloudMeme>>()
        private val cloudMemeUploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val cloudMemeUploadLock = Mutex()
        const val DEFAULT_CHUNK_SIZE = 512000

        const val IMAGE_MAX_SIZE = 5242880
        const val ORIGIN_IMAGE_MAX_SIZE = 10485760

        const val PIC_WATER_TYPE_NO = "0"
        const val PIC_WATER_TYPE_USER_NAME = "1"
        const val PIC_WATER_TYPE_FORUM_NAME = "2"
    }

    fun uploadImages(
        filePaths: List<String>,
        isOriginImage: Boolean = false,
    ): Flow<List<UploadPictureResultBean>> {
        return filePaths.asFlow()
            .map { filePath ->
                uploadSinglePicture(filePath, isOriginImage)
            }
            .runningFold<UploadPictureResultBean, MutableList<UploadPictureResultBean>>(initial = mutableListOf()) { list, result ->
                list.add(result)
                list
            }
            .filter { it.size == filePaths.size }
    }

    private suspend fun compressImage(
        filePath: String,
        isOriginImage: Boolean
    ): File {
        val originFile = File(filePath)
        val fileLength = originFile.length()
        val maxSize = if (isOriginImage) ORIGIN_IMAGE_MAX_SIZE else IMAGE_MAX_SIZE
        val tempFile = File.createTempFile("temp", ".tmp")
        try {
            withContext<Unit>(Dispatchers.IO) {
                if (isOriginImage && fileLength <= maxSize) {
                    originFile.copyTo(tempFile, true)
                } else {
                    val bitmap = BitmapFactory.decodeFile(filePath)
                    val firstCompressResult = ImageUtil.compressImage(bitmap, quality = 95)
                    tempFile.writeBytes(firstCompressResult)
                    if (firstCompressResult.size > maxSize) {
                        // 压缩尺寸至 1080P
                        val width = bitmap.width
                        val height = bitmap.height
                        val scale = if (width > height) {
                            1080f / width
                        } else {
                            1080f / height
                        }
                        if (scale < 1) {
                            val newWidth = (width * scale).toInt()
                            val newHeight = (height * scale).toInt()
                            val newBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                            tempFile.writeBytes(ImageUtil.compressImage(newBitmap, quality = 95))
                        }
                    }
                }
            }
            return tempFile
        } catch (error: Throwable) {
            tempFile.delete()
            throw error
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun uploadCloudMeme(meme: ChatCloudMeme): ChatCloudMeme {
        val accountKey = currentUploadAccountKey()
        val cacheKey = "$accountKey|${meme.id}|${meme.imageUrl}"
        cloudMemeCache[cacheKey]?.let { return it }

        val pending = cloudMemeUploadLock.withLock {
            cloudMemeCache[cacheKey]?.let { return@withLock null }
                ?: cloudMemeUploads[cacheKey]
                ?: cloudMemeUploadScope.async(start = CoroutineStart.LAZY) {
                    uploadCloudMemeUncached(meme, accountKey)
                }.also { deferred ->
                    cloudMemeUploads[cacheKey] = deferred
                    deferred.invokeOnCompletion { cloudMemeUploads.remove(cacheKey, deferred) }
                }
        }
        if (pending == null) return cloudMemeCache.getValue(cacheKey)

        pending.start()
        return pending.await().also {
            check(currentUploadAccountKey() == accountKey) { "上传期间账号已切换" }
            cloudMemeCache[cacheKey] = it
        }
    }

    private suspend fun uploadCloudMemeUncached(meme: ChatCloudMeme, accountKey: String): ChatCloudMeme = withContext(Dispatchers.IO) {
        val file = File.createTempFile("cloud_meme_", ".image", App.INSTANCE.cacheDir)
        try {
            val request = Request.Builder().url(meme.imageUrl).get().build()
            httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "下载云表情失败：HTTP ${response.code}" }
                val body = response.body ?: error("下载云表情失败：响应为空")
                body.byteStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            }
            coroutineContext.ensureActive()
            check(currentUploadAccountKey() == accountKey) { "上传期间账号已切换" }
            val picture = uploadSinglePicture(file.absolutePath, isOriginImage = true).uploadedPicture
                ?: error("上传云表情失败：响应缺少图片资源")
            check(currentUploadAccountKey() == accountKey) { "上传期间账号已切换" }
            meme.copy(uploadedImageUrl = picture.imageUrl, uploadedThumbnailUrl = picture.thumbnailUrl,
                officialPicId = picture.picId)
        } finally {
            file.delete()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun uploadSinglePicture(
        filePath: String,
        isOriginImage: Boolean = false,
    ): UploadPictureResultBean {
        val option = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, option)
        val width = option.outWidth
        val height = option.outHeight
        check(width > 0 && height > 0) { "图片宽高不正确" }
        require(chunkSize > 0) { "分片大小必须大于零" }
        val accountKey = currentUploadAccountKey()
        val file = compressImage(filePath, isOriginImage)
        try {
            val fileLength = file.length()
            check(fileLength > 0) { "图片文件为空" }
            val maxSize = if (isOriginImage) ORIGIN_IMAGE_MAX_SIZE else IMAGE_MAX_SIZE
            check(fileLength <= maxSize) { "图片大小超过限制" }
            val fileMd5 = MD5Util.toMd5(file)
            val isMultipleChunkSize = fileLength % chunkSize == 0L
            val totalChunkNum = fileLength / chunkSize + if (isMultipleChunkSize) 0 else 1
            val picWatermarkType =
                App.INSTANCE.appPreferences.picWatermarkType ?: PIC_WATER_TYPE_FORUM_NAME
            val requestBodies = (0 until totalChunkNum).map { chunk ->
                val isFinish = chunk == totalChunkNum - 1
                val curChunkSize = if (isFinish) {
                    if (isMultipleChunkSize) {
                        chunkSize
                    } else {
                        fileLength % chunkSize
                    }
                } else {
                    chunkSize
                }.toInt()
                val chunkBytes = ByteArray(curChunkSize)
                withContext(Dispatchers.IO) {
                    RandomAccessFile(file, "r").use {
                        it.seek(chunk * chunkSize.toLong())
                        it.readFully(chunkBytes)
                    }
                }
                buildMultipartBody(BOUNDARY) {
                    setType(MyMultipartBody.FORM)
                    addFormDataPart("alt", "json")
                    addFormDataPart("chunkNo", "${chunk + 1}")
                    if (forumName.isNotEmpty()) addFormDataPart("forum_name", forumName)
                    addFormDataPart("groupId", "1")
                    addFormDataPart("height", "$height")
                    addFormDataPart("isFinish", isFinish.booleanToString())
                    addFormDataPart("is_bjh", "0")
                    addFormDataPart("pic_water_type", picWatermarkType)
                    addFormDataPart("resourceId", "$fileMd5$chunkSize")
                    addFormDataPart("saveOrigin", isOriginImage.booleanToString())
                    addFormDataPart("size", "$fileLength")
                    if (forumName.isNotEmpty()) addFormDataPart("small_flow_fname", forumName)
                    addFormDataPart("width", "$width")
                    addFormDataPart("chunk", "file", chunkBytes.toRequestBody())
                }
            }
            var finalResponse: UploadPictureResultBean? = null
            for (body in requestBodies) {
                coroutineContext.ensureActive()
                check(currentUploadAccountKey() == accountKey) { "上传期间账号已切换" }
                finalResponse = RetrofitTiebaApi.OFFICIAL_TIEBA_API.uploadPicture(body)
                    .map { response ->
                        val code = response.errorCode.toIntOrNull()
                        if (code != null && code != 0) {
                            throw UploadPictureFailedException(code, response.errorMsg.ifBlank { "上传图片失败" })
                        }
                        if (response.errorCode.isNotBlank() && code == null) {
                            throw UploadPictureFailedException(message = "上传响应错误码无效")
                        }
                        response.uploadedPicture
                        response
                    }.last()
            }
            check(currentUploadAccountKey() == accountKey) { "上传期间账号已切换" }
            val response = finalResponse ?: throw UploadPictureFailedException(message = "上传完成响应为空")
            val picture = response.uploadedPicture
            if (picture?.imageUrl == null) throw UploadPictureFailedException(message = "上传完成响应缺少图片地址")
            return response.copy(picId = picture.picId, picInfo = picture.picInfo, info = null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: UploadPictureFailedException) {
            throw error
        } catch (error: Throwable) {
            throw UploadPictureFailedException(error.getErrorCode(), error.getErrorMessage())
        } finally {
            file.delete()
        }
    }
}

suspend fun uploadChatPhoto(uri: Uri): ChatCloudMeme = withContext(Dispatchers.IO) {
    val file = File.createTempFile("chat_photo_", ".image", App.INSTANCE.cacheDir)
    try {
        App.INSTANCE.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取相册图片")
        val result = ImageUploader("").uploadSinglePicture(file.absolutePath, isOriginImage = true)
        val picture = result.uploadedPicture ?: error("上传图片失败：响应缺少图片资源")
        val bigUrl = picture.imageUrl ?: error("上传图片失败：响应缺少图片地址")
        val smallUrl = picture.thumbnailUrl ?: bigUrl
        ChatCloudMeme(
            id = "uploaded_photo_${System.currentTimeMillis()}",
            imageUrl = bigUrl,
            uploadedImageUrl = bigUrl,
            uploadedThumbnailUrl = smallUrl,
            officialPicId = picture.picId,
        )
    } finally {
        file.delete()
    }
}

private fun currentUploadAccountKey(): String {
    val account = AccountUtil.currentAccount ?: error("请先登录后上传图片")
    return "${account.id}:${account.uid}:${MD5Util.toMd5(account.bduss)}"
}

class UploadPictureFailedException(
    override val code: Int = -1,
    override val message: String = "上传图片失败",
) : TiebaException(message)