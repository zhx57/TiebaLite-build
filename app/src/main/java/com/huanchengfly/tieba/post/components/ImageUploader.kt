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
import com.huanchengfly.tieba.post.utils.ImageUtil
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
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
        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile("temp", ".tmp")
        }
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
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun uploadCloudMeme(meme: ChatCloudMeme): ChatCloudMeme {
        if (meme.uploadedImageUrl != null) return meme

        val cacheKey = "${meme.id}|${meme.imageUrl}"
        cloudMemeCache[cacheKey]?.let { return it.copy(
            uploadedImageUrl = it.uploadedImageUrl,
            uploadedThumbnailUrl = it.uploadedThumbnailUrl,
        ) }

        val pending = cloudMemeUploadLock.withLock {
            cloudMemeCache[cacheKey]?.let { return@withLock null }
                ?: cloudMemeUploads[cacheKey]
                ?: cloudMemeUploadScope.async(start = CoroutineStart.LAZY) {
                    uploadCloudMemeUncached(meme)
                }.also { cloudMemeUploads[cacheKey] = it }
        }
        if (pending == null) return cloudMemeCache.getValue(cacheKey)

        pending.start()
        return try {
            pending.await().also { cloudMemeCache[cacheKey] = it }
        } catch (error: Throwable) {
            cloudMemeUploads.remove(cacheKey, pending)
            throw error
        } finally {
            cloudMemeUploads.remove(cacheKey, pending)
        }
    }

    private suspend fun uploadCloudMemeUncached(meme: ChatCloudMeme): ChatCloudMeme = withContext(Dispatchers.IO) {
        val file = File.createTempFile("cloud_meme_", ".image", App.INSTANCE.cacheDir)
        try {
            val request = Request.Builder().url(meme.imageUrl).get().build()
            httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "下载云表情失败：HTTP ${response.code}" }
                val body = response.body ?: error("下载云表情失败：响应为空")
                body.byteStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            }
            val result = uploadSinglePicture(file.absolutePath, isOriginImage = true)
            val info = result.picInfo ?: error("上传云表情失败：响应缺少图片地址")
            val bigUrl = info.bigPic.picUrl.takeIf { it.isNotBlank() }
                ?: info.originPic.picUrl.takeIf { it.isNotBlank() }
                ?: error("上传云表情失败：响应缺少原图地址")
            val smallUrl = info.smallPic.picUrl.takeIf { it.isNotBlank() } ?: bigUrl
            meme.copy(uploadedImageUrl = bigUrl, uploadedThumbnailUrl = smallUrl)
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
        val file = compressImage(filePath, isOriginImage)
        val fileLength = file.length()
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
                    it.read(chunkBytes)
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
        return requestBodies.asFlow()
            .flatMapConcat { RetrofitTiebaApi.OFFICIAL_TIEBA_API.uploadPicture(it) }
            .catch {
                throw UploadPictureFailedException(it.getErrorCode(), it.getErrorMessage())
            }
            .onCompletion {
                withContext(Dispatchers.IO) {
                    file.delete()
                }
            }
            .last()
    }
}

suspend fun uploadChatPhoto(uri: Uri): ChatCloudMeme = withContext(Dispatchers.IO) {
    val file = File.createTempFile("chat_photo_", ".image", App.INSTANCE.cacheDir)
    try {
        App.INSTANCE.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取相册图片")
        val result = ImageUploader("").uploadSinglePicture(file.absolutePath, isOriginImage = true)
        val info = result.picInfo ?: error("上传图片失败：响应缺少图片地址")
        val bigUrl = info.bigPic.picUrl.takeIf { it.isNotBlank() }
            ?: info.originPic.picUrl.takeIf { it.isNotBlank() }
            ?: error("上传图片失败：响应缺少大图地址")
        val smallUrl = info.smallPic.picUrl.takeIf { it.isNotBlank() } ?: bigUrl
        ChatCloudMeme(
            id = "local_photo_${uri}",
            imageUrl = bigUrl,
            uploadedImageUrl = bigUrl,
            uploadedThumbnailUrl = smallUrl,
        )
    } finally {
        file.delete()
    }
}

class UploadPictureFailedException(
    override val code: Int = -1,
    override val message: String = "上传图片失败",
) : TiebaException(message)