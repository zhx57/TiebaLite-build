package com.huanchengfly.tieba.post.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions.getExtensionVersion
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.R
import com.zhihu.matisse.Matisse
import com.zhihu.matisse.MimeType
import com.zhihu.matisse.engine.impl.GlideEngine
import com.zhihu.matisse.ui.MatisseActivity

fun AppCompatActivity.registerPickMediasLauncher(callback: (PickMediasResult) -> Unit): ActivityResultLauncher<PickMediasRequest> {
    return registerForActivityResult(
        PickMediasContract
    ) {
        callback(it)
    }
}

@Composable
fun registerPickMediasLauncher(callback: (PickMediasResult) -> Unit): ActivityResultLauncher<PickMediasRequest> =
    rememberLauncherForActivityResult(PickMediasContract, callback)

// 聊天单图入口不使用旧图库，也不申请全库媒体权限。
internal data class ChatImageResult(val uri: Uri?, val flags: Int = 0)

internal object ChatImageContract : ActivityResultContract<Unit, ChatImageResult>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        return if (!App.INSTANCE.appPreferences.doNotUsePhotoPicker &&
            ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)
        ) {
            ActivityResultContracts.PickVisualMedia().createIntent(
                context,
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        } else {
            ActivityResultContracts.OpenDocument().createIntent(context, arrayOf("image/*"))
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): ChatImageResult =
        if (resultCode == Activity.RESULT_OK) {
            ChatImageResult(intent?.data, intent?.flags ?: 0)
        } else {
            ChatImageResult(null)
        }
}

/** 防止重复启动；取消和启动失败均以空结果结束。 */
@Composable
fun rememberChatImagePicker(onResult: (Uri?) -> Unit): () -> Unit {
    val context = LocalContext.current
    var picking by rememberSaveable { mutableStateOf(false) }
    var pickerAccount by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ChatImageContract) { result ->
        picking = false
        val accountMatches = pickerAccount == AccountUtil.getUid()
        pickerAccount = null
        if (!accountMatches) {
            Toast.makeText(context, "账号已切换，请重新选图", Toast.LENGTH_SHORT).show()
            onResult(null)
            return@rememberLauncherForActivityResult
        }
        result.uri?.let { uri ->
            if (result.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 &&
                result.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
            ) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                    // 部分提供者只授予临时读取权限，仍可立即上传。
                }
            }
        }
        onResult(result.uri)
    }
    return {
        if (!picking) {
            picking = true
            pickerAccount = AccountUtil.getUid()
            try {
                launcher.launch(Unit)
            } catch (_: RuntimeException) {
                picking = false
                pickerAccount = null
                Toast.makeText(context, "无法打开系统图片选择器，请稍后重新选图", Toast.LENGTH_SHORT).show()
                onResult(null)
            }
        }
    }
}

fun isPhotoPickerAvailable(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        true
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        getExtensionVersion(Build.VERSION_CODES.R) >= 2
    } else {
        false
    }
}

fun shouldUsePhotoPicker(): Boolean {
    return !App.INSTANCE.appPreferences.doNotUsePhotoPicker && isPhotoPickerAvailable()
}

fun Intent.getClipDataUris(): List<Uri> {
    // Use a LinkedHashSet to maintain any ordering that may be
    // present in the ClipData
    val resultSet = LinkedHashSet<Uri>()
    data?.let { data ->
        resultSet.add(data)
    }
    val clipData = clipData
    if (clipData == null && resultSet.isEmpty()) {
        return emptyList()
    } else if (clipData != null) {
        for (i in 0 until clipData.itemCount) {
            val uri = clipData.getItemAt(i).uri
            if (uri != null) {
                resultSet.add(uri)
            }
        }
    }
    return resultSet.toList()
}

@SuppressLint("NewApi")
private fun getMaxItems() = if (shouldUsePhotoPicker()) {
    MediaStore.getPickImagesMaxLimit()
} else {
    Integer.MAX_VALUE
}

data class PickMediasRequest(
    val id: String = "",
    val maxItems: Int = 1,
    val mediaType: MediaType = ImageAndVideo
) {
    sealed interface MediaType

    data object ImageOnly : MediaType

    data object VideoOnly : MediaType

    data object ImageAndVideo : MediaType

    data class SingleMimeType(val mimeType: String) : MediaType

    companion object {
        internal fun getMimeType(input: MediaType): String? {
            return when (input) {
                is ImageOnly -> "image/*"
                is VideoOnly -> "video/*"
                is SingleMimeType -> input.mimeType
                is ImageAndVideo -> null
            }
        }
    }
}

data class PickMediasResult(
    val id: String,
    val uris: List<Uri>
)

object PickMediasContract : ActivityResultContract<PickMediasRequest, PickMediasResult>() {
    private var curRequestId: String? = null

    val hasCurrentRequest: Boolean
        get() = curRequestId != null

    override fun createIntent(context: Context, input: PickMediasRequest): Intent {
        curRequestId = input.id
        if (shouldUsePhotoPicker()) {
            return Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = PickMediasRequest.getMimeType(input.mediaType)
                if (input.maxItems > 1) {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, input.maxItems)
                }
            }
        }
        val mimeType: Set<MimeType> = when (val mediaType = input.mediaType) {
            is PickMediasRequest.ImageOnly -> MimeType.ofImage()
            is PickMediasRequest.VideoOnly -> MimeType.ofVideo()
            is PickMediasRequest.SingleMimeType -> setOf(MimeType.valueOf(mediaType.mimeType))
            is PickMediasRequest.ImageAndVideo -> MimeType.ofAll()
        }
        Matisse.from(context as Activity)
            .choose(mimeType)
            .theme(if (ThemeUtil.isNightMode()) R.style.Matisse_Dracula else R.style.Matisse_Zhihu)
            .countable(input.maxItems > 1)
            .maxSelectable(input.maxItems)
            .imageEngine(GlideEngine())
        return Intent(context, MatisseActivity::class.java)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): PickMediasResult {
        val id = curRequestId + ""
        curRequestId = null
        if (resultCode != Activity.RESULT_OK || intent == null) {
            return PickMediasResult(id, emptyList())
        }
        if (shouldUsePhotoPicker()) {
            return PickMediasResult(id, intent.getClipDataUris())
        }
        return PickMediasResult(id, Matisse.obtainResult(intent))
    }
}