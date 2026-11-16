package com.huanchengfly.tieba.post.ui.page.reply

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.models.UploadPictureResultBean
import com.huanchengfly.tieba.post.api.models.chat.ChatCloudMeme
import com.huanchengfly.tieba.post.api.models.protos.addPost.AddPostResponse
import com.huanchengfly.tieba.post.api.models.protos.addThread.AddThreadResponse
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaUnknownException
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorCode
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.arch.BaseViewModel
import com.huanchengfly.tieba.post.arch.PartialChange
import com.huanchengfly.tieba.post.arch.PartialChangeProducer
import com.huanchengfly.tieba.post.arch.UiEvent
import com.huanchengfly.tieba.post.arch.UiIntent
import com.huanchengfly.tieba.post.arch.UiState
import com.huanchengfly.tieba.post.components.ImageUploader
import com.huanchengfly.tieba.post.components.UploadPictureFailedException
import com.huanchengfly.tieba.post.repository.AddPostRepository
import com.huanchengfly.tieba.post.utils.FileUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

enum class ReplyPanelType {
    NONE,
    EMOJI,
    IMAGE,
    VOICE
}

enum class ReplyType {
    NONE, //回贴
    TOPIC_THREAD, //发主题贴
}


@Stable
@HiltViewModel
class ReplyViewModel @Inject constructor() :
    BaseViewModel<ReplyUiIntent, ReplyPartialChange, ReplyUiState, ReplyUiEvent>() {
    override fun createInitialState() = ReplyUiState()

    override fun createPartialChangeProducer(): PartialChangeProducer<ReplyUiIntent, ReplyPartialChange, ReplyUiState> =
        ReplyPartialChangeProducer

    override fun dispatchEvent(partialChange: ReplyPartialChange): UiEvent? = when (partialChange) {
        is ReplyPartialChange.Send.Success  -> ReplyUiEvent.ReplySuccess(
            partialChange.threadId,
            partialChange.postId,
            partialChange.expInc
        )

        is ReplyPartialChange.UploadAttachments.Success -> ReplyUiEvent.AttachmentsReady(
            partialChange.localPictures,
            partialChange.cloudPictures
        )

        is ReplyPartialChange.Send.Failure -> ReplyUiEvent.SendFailed(
            partialChange.errorCode,
            partialChange.errorMessage
        )

        is ReplyPartialChange.UploadAttachments.Failure -> ReplyUiEvent.AttachmentsFailed(
            partialChange.errorMessage
        )

        else -> null
    }

    private object ReplyPartialChangeProducer :
        PartialChangeProducer<ReplyUiIntent, ReplyPartialChange, ReplyUiState> {
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun toPartialChangeFlow(intentFlow: Flow<ReplyUiIntent>): Flow<ReplyPartialChange> =
            merge(
                intentFlow.filterIsInstance<ReplyUiIntent.UploadAttachments>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ReplyUiIntent.Send>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ReplyUiIntent.SwitchPanel>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ReplyUiIntent.AddImage>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ReplyUiIntent.RemoveImage>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ReplyUiIntent.AddCloudMeme>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ReplyUiIntent.RemoveCloudMeme>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ReplyUiIntent.ToggleIsOriginImage>()
                    .flatMapConcat { it.producePartialChange() },
                intentFlow.filterIsInstance<ReplyUiIntent.SwitchReplyType>()
                    .flatMapConcat { it.producePartialChange() },
            )

        private fun ReplyUiIntent.Send.producePartialChange(): Flow<ReplyPartialChange.Send> {
            if (forumId != 0L && threadId == 0L ) {
                val threadTitle = title.orEmpty()
                return AddPostRepository
                    .addThread(
                        content,
                        forumId,
                        forumName,
                        title = threadTitle,
                        isHide = 1,
                        isTitle = if (threadTitle.isBlank()) 1 else 0,
                    )
                    .map<AddThreadResponse, ReplyPartialChange.Send> {
                        if (it.data_ == null) throw TiebaUnknownException
                        ReplyPartialChange.Send.Success(
                            threadId = it.data_.tid,
                            postId = it.data_.pid,
                            expInc = it.data_.exp?.inc.orEmpty()
                        )
                    }
                    .onStart { emit(ReplyPartialChange.Send.Start) }
                    .catch {
                        Log.i("ReplyViewModel", "failure: ${it.message}")
                        it.printStackTrace()
                        emit(
                            ReplyPartialChange.Send.Failure(
                                it.getErrorCode(),
                                it.getErrorMessage()
                            )
                        )
                    }
            }
            return AddPostRepository
                .addPost(
                    content,
                    forumId,
                    forumName,
                    threadId,
                    tbs,
                    postId = postId,
                    subPostId = subPostId,
                    replyUserId = replyUserId
                )
                .map<AddPostResponse, ReplyPartialChange.Send> {
                    if (it.data_ == null) throw TiebaUnknownException
                    ReplyPartialChange.Send.Success(
                        threadId = it.data_.tid,
                        postId = it.data_.pid,
                        expInc = it.data_.exp?.inc.orEmpty()
                    )
                }
                .onStart { emit(ReplyPartialChange.Send.Start) }
                .catch {
                    Log.i("ReplyViewModel", "failure: ${it.message}")
                    it.printStackTrace()
                    emit(ReplyPartialChange.Send.Failure(it.getErrorCode(), it.getErrorMessage()))
                }
        }

        private fun ReplyUiIntent.UploadAttachments.producePartialChange(): Flow<ReplyPartialChange.UploadAttachments> =
            flow<ReplyPartialChange.UploadAttachments> {
                val uploader = ImageUploader(forumName)
                val localResults: List<UploadPictureResultBean> = if (imageUris.isEmpty()) {
                    emptyList()
                } else {
                    uploader.uploadImages(
                        imageUris.map {
                            FileUtil.getRealPathFromUri(
                                App.INSTANCE,
                                Uri.parse(it)
                            )
                        },
                        isOriginImage
                    ).last()
                }
                val uploadedCloudMemes = cloudMemes.map { uploader.uploadCloudMeme(it) }
                val localPictures = localResults.mapIndexed { index, result ->
                    result.toReplyPictureAttachmentOrNull()
                        ?: throw UploadPictureFailedException(
                            message = "第 ${index + 1} 张图片上传结果缺少官方 pic_id 或尺寸"
                        )
                }
                val cloudPictures = uploadedCloudMemes.map { meme ->
                    meme.toReplyPictureAttachmentOrNull()
                        ?: throw UploadPictureFailedException(
                            message = "云表情上传结果缺少官方 pic_id 或尺寸"
                        )
                }
                emit(
                    ReplyPartialChange.UploadAttachments.Success(
                        localPictures = localPictures,
                        cloudPictures = cloudPictures
                    )
                )
            }
                .onStart { emit(ReplyPartialChange.UploadAttachments.Start) }
                .catch {
                    if (it is CancellationException) throw it
                    it.printStackTrace()
                    emit(
                        ReplyPartialChange.UploadAttachments.Failure(
                            it.getErrorCode(),
                            it.getErrorMessage()
                        )
                    )
                }

        private fun ReplyUiIntent.SwitchPanel.producePartialChange() =
            flowOf(ReplyPartialChange.SwitchPanel(panelType))


        private fun ReplyUiIntent.SwitchReplyType.producePartialChange() =
            flowOf(ReplyPartialChange.SwitchReplyType(replyType))

        private fun ReplyUiIntent.AddImage.producePartialChange() =
            flowOf(ReplyPartialChange.AddImage(imageUris))

        private fun ReplyUiIntent.RemoveImage.producePartialChange() =
            flowOf(ReplyPartialChange.RemoveImage(imageIndex))

        private fun ReplyUiIntent.AddCloudMeme.producePartialChange() =
            flowOf(ReplyPartialChange.AddCloudMeme(cloudMeme))

        private fun ReplyUiIntent.RemoveCloudMeme.producePartialChange() =
            flowOf(ReplyPartialChange.RemoveCloudMeme(cloudMemeId))

        private fun ReplyUiIntent.ToggleIsOriginImage.producePartialChange() =
            flowOf(ReplyPartialChange.ToggleIsOriginImage(isOriginImage))
    }
}

/** 回帖可发送的图片附件；picId 与宽高均来自上传结果，缺失时禁止发送。 */
data class ReplyPictureAttachment(
    val picId: String,
    val width: Int,
    val height: Int,
) {
    fun toContent(): String = "#(pic,$picId,$width,$height)"
}

internal fun UploadPictureResultBean.toReplyPictureAttachmentOrNull(): ReplyPictureAttachment? {
    val picture = uploadedPicture ?: return null
    val picId = picture.picId?.takeIf { it.isNotBlank() } ?: return null
    val origin = picture.picInfo?.originPic ?: return null
    val width = origin.width.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val height = origin.height.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return ReplyPictureAttachment(picId, width, height)
}

internal fun ChatCloudMeme.toReplyPictureAttachmentOrNull(): ReplyPictureAttachment? {
    val picId = officialPicId?.takeIf { it.isNotBlank() } ?: return null
    if (width <= 0 || height <= 0) return null
    return ReplyPictureAttachment(picId, width, height)
}

/** 本地图与云表情统一计数的上限，与图片面板添加按钮保持一致。 */
internal const val REPLY_MAX_ATTACHMENT_COUNT = 9

/** 按 本地图 -> 云表情 的顺序拼接补内容，正文在前，楼中楼引用由调用方最外层包装。 */
internal fun buildReplySendContent(
    text: String,
    localImages: List<ReplyPictureAttachment>,
    cloudMemes: List<ReplyPictureAttachment>,
): String {
    val pictures = (localImages + cloudMemes).map { it.toContent() }
    return (listOf(text) + pictures)
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

/**
 * 合并附件：保留已有项并去重，新项仅在未达 [maxCount] 时追加，超出部分丢弃。
 * 现有附件不会因为新增图片而被挤掉；发送顺序仍是本地图在前、云表情在后。
 */
internal fun mergeReplyAttachments(
    existingLocalImageUris: List<String>,
    existingCloudMemes: List<ChatCloudMeme>,
    newLocalImageUris: List<String> = emptyList(),
    newCloudMemes: List<ChatCloudMeme> = emptyList(),
    maxCount: Int = REPLY_MAX_ATTACHMENT_COUNT,
): ReplyAttachmentSelection {
    val max = maxCount.coerceAtLeast(0)
    val local = existingLocalImageUris.distinct().take(max).toMutableList()
    val cloud = existingCloudMemes.distinctBy { it.id }
        .take((max - local.size).coerceAtLeast(0))
        .toMutableList()
    for (uri in newLocalImageUris) {
        if (local.size + cloud.size >= max) break
        if (uri !in local) local.add(uri)
    }
    for (meme in newCloudMemes) {
        if (local.size + cloud.size >= max) break
        if (cloud.none { it.id == meme.id }) cloud.add(meme)
    }
    return ReplyAttachmentSelection(local, cloud)
}

internal data class ReplyAttachmentSelection(
    val localImageUris: List<String>,
    val cloudMemes: List<ChatCloudMeme>,
) {
    val totalCount: Int get() = localImageUris.size + cloudMemes.size
    val isFull: Boolean get() = totalCount >= REPLY_MAX_ATTACHMENT_COUNT
}

sealed interface ReplyUiIntent : UiIntent {
    data class UploadAttachments(
        val forumName: String,
        val imageUris: List<String>,
        val isOriginImage: Boolean,
        val cloudMemes: List<ChatCloudMeme> = emptyList(),
    ) : ReplyUiIntent

    data class Send(
        val content: String,
        val forumId: Long,
        val forumName: String,
        val threadId: Long,
        val tbs: String,
        val title: String? = null,
        val postId: Long? = null,
        val subPostId: Long? = null,
        val replyUserId: Long? = null,
    ) : ReplyUiIntent


    data class SwitchPanel(val panelType: ReplyPanelType) : ReplyUiIntent

    data class SwitchReplyType(val replyType: ReplyType) : ReplyUiIntent

    data class AddImage(val imageUris: List<String>) : ReplyUiIntent

    data class RemoveImage(val imageIndex: Int) : ReplyUiIntent

    data class AddCloudMeme(val cloudMeme: ChatCloudMeme) : ReplyUiIntent

    data class RemoveCloudMeme(val cloudMemeId: String) : ReplyUiIntent

    data class ToggleIsOriginImage(val isOriginImage: Boolean) : ReplyUiIntent
}

sealed interface ReplyPartialChange : PartialChange<ReplyUiState> {
    sealed class UploadAttachments : ReplyPartialChange {
        override fun reduce(oldState: ReplyUiState): ReplyUiState = when (this) {
            is Start -> oldState.copy(isUploading = true)
            is Success -> oldState.copy(isUploading = false)
            is Failure -> oldState.copy(isUploading = false)
        }

        object Start : UploadAttachments()

        data class Success(
            val localPictures: List<ReplyPictureAttachment>,
            val cloudPictures: List<ReplyPictureAttachment>,
        ) : UploadAttachments()

        data class Failure(
            val errorCode: Int,
            val errorMessage: String
        ) : UploadAttachments()
    }

    sealed class Send : ReplyPartialChange {
        override fun reduce(oldState: ReplyUiState): ReplyUiState {
            return when (this) {
                is Start -> oldState.copy(isSending = true)
                is Success -> oldState.copy(isSending = false, replySuccess = true)
                is Failure -> oldState.copy(isSending = false, replySuccess = false)
            }
        }

        object Start : Send()

        data class Success(
            val threadId: String,
            val postId: String,
            val expInc: String
        ) : Send()

        data class Failure(
            val errorCode: Int,
            val errorMessage: String
        ) : Send()
    }

    data class SwitchPanel(val panelType: ReplyPanelType) : ReplyPartialChange {
        override fun reduce(oldState: ReplyUiState): ReplyUiState =
            oldState.copy(replyPanelType = panelType)
    }

    data class SwitchReplyType(val replyType: ReplyType) : ReplyPartialChange {
        override fun reduce(oldState: ReplyUiState): ReplyUiState =
            oldState.copy(replyType = replyType)
    }

    data class AddImage(val imageUris: List<String>) : ReplyPartialChange {
        override fun reduce(oldState: ReplyUiState): ReplyUiState {
            val merged = mergeReplyAttachments(
                existingLocalImageUris = oldState.selectedImageList,
                existingCloudMemes = oldState.selectedCloudMemeList,
                newLocalImageUris = imageUris,
            )
            return oldState.copy(
                selectedImageList = merged.localImageUris.toImmutableList(),
                selectedCloudMemeList = merged.cloudMemes.toImmutableList(),
            )
        }
    }

    data class RemoveImage(val imageIndex: Int) : ReplyPartialChange {
        override fun reduce(oldState: ReplyUiState): ReplyUiState =
            oldState.copy(
                selectedImageList = (oldState.selectedImageList - oldState.selectedImageList[imageIndex]).toImmutableList()
            )
    }

    data class AddCloudMeme(val cloudMeme: ChatCloudMeme) : ReplyPartialChange {
        override fun reduce(oldState: ReplyUiState): ReplyUiState {
            val merged = mergeReplyAttachments(
                existingLocalImageUris = oldState.selectedImageList,
                existingCloudMemes = oldState.selectedCloudMemeList,
                newCloudMemes = listOf(cloudMeme),
            )
            return oldState.copy(
                selectedImageList = merged.localImageUris.toImmutableList(),
                selectedCloudMemeList = merged.cloudMemes.toImmutableList(),
            )
        }
    }

    data class RemoveCloudMeme(val cloudMemeId: String) : ReplyPartialChange {
        override fun reduce(oldState: ReplyUiState): ReplyUiState =
            oldState.copy(
                selectedCloudMemeList = oldState.selectedCloudMemeList
                    .filterNot { it.id == cloudMemeId }
                    .toImmutableList()
            )
    }

    data class ToggleIsOriginImage(val isOriginImage: Boolean) : ReplyPartialChange {
        override fun reduce(oldState: ReplyUiState): ReplyUiState =
            oldState.copy(isOriginImage = isOriginImage)
    }
}

data class ReplyUiState(
    val isSending: Boolean = false,
    val replySuccess: Boolean = false,
    val replyPanelType: ReplyPanelType = ReplyPanelType.NONE,
    val replyType: ReplyType = ReplyType.NONE,
    val isUploading: Boolean = false,
    val isOriginImage: Boolean = false,
    val selectedImageList: ImmutableList<String> = persistentListOf(),
    val selectedCloudMemeList: ImmutableList<ChatCloudMeme> = persistentListOf(),
) : UiState

sealed interface ReplyUiEvent : UiEvent {
    data class AttachmentsReady(
        val localPictures: List<ReplyPictureAttachment>,
        val cloudPictures: List<ReplyPictureAttachment>,
    ) : ReplyUiEvent

    data class AttachmentsFailed(val errorMessage: String) : ReplyUiEvent

    data class SendFailed(val errorCode: Int, val errorMessage: String) : ReplyUiEvent

    data class ReplySuccess(
        val threadId: String,
        val postId: String,
        val expInc: String
    ) : ReplyUiEvent
}