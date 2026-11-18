package com.huanchengfly.tieba.post.ui.page.reply

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Stable
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.models.UploadPictureResultBean
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
import com.huanchengfly.tieba.post.repository.CloudMeme
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
import java.security.MessageDigest
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
                val localResults = ImageUploader(forumName).uploadImages(
                    imageUris.map { FileUtil.getRealPathFromUri(App.INSTANCE, Uri.parse(it)) },
                    isOriginImage,
                ).last()
                val localPictures = localResults.mapIndexed { index, result ->
                    result.toReplyPictureAttachmentOrNull()
                        ?: throw UploadPictureFailedException(
                            message = "第 ${index + 1} 张图片上传结果缺少官方 pic_id 或尺寸"
                        )
                }
                emit(ReplyPartialChange.UploadAttachments.Success(localPictures))
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

internal const val REPLY_MAX_ATTACHMENT_COUNT = 9

internal val replyMemeMarker = Regex("""#\(meme,[0-9]+(?:_[0-9]+)?,[0-9]+,[0-9]+,[a-f0-9]{32}\)""")

internal fun CloudMeme.toReplyMarkerOrNull(): String? {
    val idPart = replyIdPart ?: return null
    val body = "meme,$idPart,$width,$height"
    return "#($body,${signReplyMemeBody(body)})"
}

private fun signReplyMemeBody(body: String): String = MessageDigest.getInstance("MD5")
    .digest("$body,7S6wbXjEKL9N".toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

// 保留普通文本和基础表情；对仍能识别为贴纸的残缺或被修改标记拒绝发送。
private val replyMemePrefix = Regex("""#\(meme(?=[,\s]|$)|#\(meme[0-9]+(?:_[0-9]+)?,""")

internal fun String.hasInvalidReplyMemeMarker(): Boolean = replyMemePrefix.findAll(this).any { prefix ->
    val marker = replyMemeMarker.matchAt(this, prefix.range.first)?.value ?: return@any true
    val body = marker.substring(2, marker.lastIndexOf(','))
    !marker.endsWith(",${signReplyMemeBody(body)})")
}

/** 贴纸已在正文中，只有本地图片需要在正文后附加上传结果。 */
internal fun buildReplySendContent(
    text: String,
    localImages: List<ReplyPictureAttachment> = emptyList(),
    subPostId: Long? = null,
    replyUserPortrait: String? = null,
    replyUserName: String? = null,
): String {
    val body = if (localImages.isEmpty()) text else
        (listOf(text) + localImages.map { it.toContent() }).filter { it.isNotBlank() }.joinToString("\n")
    return if (subPostId == null || subPostId == 0L) body else
        "回复 #(reply, $replyUserPortrait, $replyUserName) :$body"
}

sealed interface ReplyUiIntent : UiIntent {
    data class UploadAttachments(
        val forumName: String,
        val imageUris: List<String>,
        val isOriginImage: Boolean,
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
            return oldState.copy(
                selectedImageList = (oldState.selectedImageList + imageUris)
                    .distinct().take(REPLY_MAX_ATTACHMENT_COUNT).toImmutableList(),
            )
        }
    }

    data class RemoveImage(val imageIndex: Int) : ReplyPartialChange {
        override fun reduce(oldState: ReplyUiState): ReplyUiState =
            oldState.copy(
                selectedImageList = (oldState.selectedImageList - oldState.selectedImageList[imageIndex]).toImmutableList()
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
) : UiState

sealed interface ReplyUiEvent : UiEvent {
    data class AttachmentsReady(
        val localPictures: List<ReplyPictureAttachment>,
    ) : ReplyUiEvent

    data class AttachmentsFailed(val errorMessage: String) : ReplyUiEvent

    data class SendFailed(val errorCode: Int, val errorMessage: String) : ReplyUiEvent

    data class ReplySuccess(
        val threadId: String,
        val postId: String,
        val expInc: String
    ) : ReplyUiEvent
}