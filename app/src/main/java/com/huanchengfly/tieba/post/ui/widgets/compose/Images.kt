package com.huanchengfly.tieba.post.ui.widgets.compose

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import com.github.panpf.sketch.compose.AsyncImage
import com.github.panpf.sketch.compose.rememberAsyncImageState
import com.github.panpf.sketch.request.Depth
import com.github.panpf.sketch.request.DisplayRequest
import com.github.panpf.sketch.request.DisplayResult
import com.github.panpf.sketch.stateimage.ThumbnailMemoryCacheStateImage
import com.github.panpf.sketch.transform.MaskTransformation
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.arch.BaseComposeActivity.Companion.LocalWindowSizeClass
import com.huanchengfly.tieba.post.goToActivity
import com.huanchengfly.tieba.post.api.models.chat.ChatCloudMeme
import com.huanchengfly.tieba.post.components.ImageUploader
import com.huanchengfly.tieba.post.models.PhotoViewData
import com.huanchengfly.tieba.post.repository.CloudMemeCache
import com.huanchengfly.tieba.post.repository.CloudMemeRepository
import com.huanchengfly.tieba.post.repository.collectMeme
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.common.windowsizeclass.WindowWidthSizeClass
import com.huanchengfly.tieba.post.ui.page.photoview.PhotoViewActivity
import com.huanchengfly.tieba.post.ui.page.photoview.PhotoViewActivity.Companion.EXTRA_PHOTO_VIEW_DATA
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.ImageUtil
import com.huanchengfly.tieba.post.utils.NetworkUtil
import com.huanchengfly.tieba.post.utils.appPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

fun shouldLoadImage(context: Context, skipNetworkCheck: Boolean): Boolean {
    val imageLoadSettings =
        context.appPreferences.imageLoadType?.toIntOrNull() ?: ImageUtil.SETTINGS_SMART_ORIGIN
    return skipNetworkCheck
            || imageLoadSettings == ImageUtil.SETTINGS_SMART_ORIGIN
            || imageLoadSettings == ImageUtil.SETTINGS_ALL_ORIGIN
            || (imageLoadSettings == ImageUtil.SETTINGS_SMART_LOAD && NetworkUtil.isWifiConnected(
        context
    ))
}

/** 收藏结果只区分服务端已确认的三种终态，调用方据此选择提示文案。 */
internal sealed interface CloudMemeCollectOutcome {
    data object Collected : CloudMemeCollectOutcome
    data object AlreadyCollected : CloudMemeCollectOutcome
    data object AccountChanged : CloudMemeCollectOutcome
    data class Failed(val message: String) : CloudMemeCollectOutcome
}

/**
 * 只接受可下载的完整 http(s) 图片地址；协议相对的 //host 会补全为 https。
 * 不从这里推导任何 pic_id，URL 只作为上传素材来源。
 */
internal fun collectableImageUrlOrNull(imageUrl: String): String? {
    val trimmed = imageUrl.trim()
    if (trimmed.isEmpty()) return null
    val normalized = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
    val url = normalized.toHttpUrlOrNull() ?: return null
    if (url.scheme !in setOf("http", "https") || url.host.isBlank()) return null
    return url.toString()
}

/**
 * 校验可信来源给出的官方 pic_id，拒绝空值、URL、路径片段和明显异常字符。
 * 该函数不会从 URL 反推 ID。
 */
internal fun trustedOfficialPicIdOrNull(candidate: String?): String? {
    val id = candidate?.trim().orEmpty()
    if (id.length !in 3..256) return null
    if (id.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) return null
    return id
}

/** 跨列表项去重：同一账号下同一 URL 或同一 pic_id 同时只允许一个收藏任务。 */
internal object CloudMemeCollectRegistry {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun tryAcquire(key: String): Boolean = inFlight.add(key)

    fun release(key: String) {
        inFlight.remove(key)
    }

    fun key(uid: String, imageUrl: String, picId: String?): String {
        val identity = trustedOfficialPicIdOrNull(picId)?.let { "id:$it" }
            ?: "url:${imageUrl.trim()}"
        return "$uid|$identity"
    }
}

/**
 * 单张图片收藏流程。
 *
 * 只有传入的可信 [trustedPicId] 才直接使用；否则必须下载原图上传换取官方 pic_id。
 * 每次挂起返回后都重新核对账号快照，切号时返回 [CloudMemeCollectOutcome.AccountChanged]。
 */
internal suspend fun collectImageAsCloudMeme(
    imageUrl: String,
    trustedPicId: String?,
    expectedAccount: () -> Pair<String, String>,
    cache: CloudMemeCache,
    uploadImage: suspend (ChatCloudMeme) -> ChatCloudMeme,
): CloudMemeCollectOutcome {
    val image = collectableImageUrlOrNull(imageUrl)
        ?: return CloudMemeCollectOutcome.Failed("图片地址无效，无法收藏")
    val snapshot = expectedAccount()
    val uid = snapshot.first
    val bduss = snapshot.second
    if (uid.isBlank() || bduss.isBlank()) {
        return CloudMemeCollectOutcome.Failed("请先登录后再收藏")
    }
    fun accountChanged() = expectedAccount() != snapshot

    val directPicId = trustedOfficialPicIdOrNull(trustedPicId)
    // 直接使用可信 pic_id 时强制刷新，才能准确区分已收藏与新收藏。
    // 上传路径拿到的 pic_id 必然是新资源，只需读取常规缓存做一次 URL 预判。
    val collectedBefore = try {
        cache.load(uid, bduss, force = directPicId != null).collected
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        emptyList()
    }
    if (accountChanged()) return CloudMemeCollectOutcome.AccountChanged
    if (directPicId != null && collectedBefore.any { it.id == directPicId }) {
        return CloudMemeCollectOutcome.AlreadyCollected
    }
    // 没有可信 pic_id 时，先用服务端列表里的图片地址做一次只读预判，避免重复上传。
    if (directPicId == null && collectedBefore.any { meme ->
            collectableImageUrlOrNull(meme.imageUrl) == image ||
                collectableImageUrlOrNull(meme.thumbnail.orEmpty()) == image
        }
    ) {
        return CloudMemeCollectOutcome.AlreadyCollected
    }

    val picId: String = directPicId ?: run {
        val uploaded = try {
            uploadImage(
                ChatCloudMeme(
                    id = "collect-image-${image.hashCode()}",
                    imageUrl = image,
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (accountChanged()) return CloudMemeCollectOutcome.AccountChanged
            return CloudMemeCollectOutcome.Failed(uploadFailureMessage(error))
        }
        if (accountChanged()) return CloudMemeCollectOutcome.AccountChanged
        trustedOfficialPicIdOrNull(uploaded.officialPicId)
            ?: return CloudMemeCollectOutcome.Failed("上传后未获得官方图片 ID，无法收藏")
    }

    if (collectedBefore.any { it.id == picId }) {
        return CloudMemeCollectOutcome.AlreadyCollected
    }
    if (accountChanged()) return CloudMemeCollectOutcome.AccountChanged

    return try {
        cache.collectMeme(uid, bduss, picId, expectedAccount)
        if (accountChanged()) CloudMemeCollectOutcome.AccountChanged
        else CloudMemeCollectOutcome.Collected
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        if (accountChanged()) return CloudMemeCollectOutcome.AccountChanged
        // 服务端拒绝时再拉一次全量列表：已包含说明是重复收藏，而不是失败。
        val confirmed = try {
            cache.load(uid, bduss, force = true)
        } catch (loadError: CancellationException) {
            throw loadError
        } catch (loadError: Throwable) {
            null
        }
        if (confirmed?.collected?.any { it.id == picId } == true) {
            CloudMemeCollectOutcome.AlreadyCollected
        } else {
            CloudMemeCollectOutcome.Failed(collectFailureMessage(error))
        }
    }
}

private fun uploadFailureMessage(error: Throwable): String {
    val detail = error.message?.takeIf { it.isNotBlank() }
    return if (detail != null) "上传图片失败：$detail" else "上传图片失败，请检查网络后重试"
}

private fun collectFailureMessage(error: Throwable): String {
    val detail = error.message?.takeIf { it.isNotBlank() }
    return if (detail != null) "收藏失败：$detail" else "收藏失败，请检查网络后重试"
}

/** 图片长按后弹出的轻量收藏菜单。 */
@Composable
internal fun CloudMemeImageMenu(
    expanded: Boolean,
    isCollecting: Boolean,
    onDismiss: () -> Unit,
    onCollect: () -> Unit,
) {
    androidx.compose.material.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        androidx.compose.material.DropdownMenuItem(
            enabled = !isCollecting,
            onClick = {
                onDismiss()
                onCollect()
            },
        ) {
            androidx.compose.material.Text(if (isCollecting) "收藏中…" else "收藏为云表情")
        }
    }
}

/**
 * 收藏协程持有者：同一个组合内复用，dispose 时取消任务。
 * 仅当账号快照仍一致时才展示结果提示。
 */
@Stable
internal class CloudMemeImageCollector internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val cache: CloudMemeCache,
    private val expectedAccount: () -> Pair<String, String>,
    private val uploadImage: suspend (ChatCloudMeme) -> ChatCloudMeme,
) {
    /** 正在收藏的图片地址集合；仅用于 UI 禁用与反馈，真正去重由 [CloudMemeCollectRegistry] 保证。 */
    private val activeUrls = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private val jobs = mutableMapOf<String, Job>()

    fun isCollecting(imageUrl: String): Boolean = activeUrls.containsKey(imageUrl.trim())

    fun collect(imageUrl: String, trustedPicId: String? = null) {
        val normalizedUrl = imageUrl.trim()
        if (activeUrls.containsKey(normalizedUrl)) return
        val snapshot = expectedAccount()
        if (snapshot.first.isBlank() || snapshot.second.isBlank()) {
            toast("请先登录后再收藏")
            return
        }
        val collectKey = CloudMemeCollectRegistry.key(snapshot.first, normalizedUrl, trustedPicId)
        if (!CloudMemeCollectRegistry.tryAcquire(collectKey)) {
            toast("该图片正在收藏中")
            return
        }
        activeUrls[normalizedUrl] = collectKey
        toast("正在收藏…")
        // LAZY 确保 job 先登记再执行，finally 中才能正确复位状态。
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                when (val outcome = collectImageAsCloudMeme(
                    imageUrl = normalizedUrl,
                    trustedPicId = trustedPicId,
                    expectedAccount = expectedAccount,
                    cache = cache,
                    uploadImage = uploadImage,
                )) {
                    CloudMemeCollectOutcome.Collected -> toast("已收藏到云表情")
                    CloudMemeCollectOutcome.AlreadyCollected -> toast("已在云表情收藏中")
                    CloudMemeCollectOutcome.AccountChanged -> Unit
                    is CloudMemeCollectOutcome.Failed -> toast(outcome.message)
                }
            } finally {
                // 只有仍是当前登记的同一任务时才释放锁，避免误释放后续任务。
                if (jobs[normalizedUrl] === coroutineContext[Job]) {
                    CloudMemeCollectRegistry.release(collectKey)
                    activeUrls.remove(normalizedUrl)
                    jobs.remove(normalizedUrl)
                }
            }
        }
        jobs[normalizedUrl] = job
        job.start()
    }

    fun cancelInFlight() {
        jobs.values.forEach { it.cancel() }
        activeUrls.values.toList().forEach(CloudMemeCollectRegistry::release)
        jobs.clear()
        activeUrls.clear()
    }

    fun dispose() = cancelInFlight()

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun rememberCloudMemeImageCollector(): CloudMemeImageCollector {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = AccountUtil.LocalAccount.current
    // 快照始终读当前登录账号，避免某些页面未提供 LocalAccount 时无法收藏。
    val observedAccount by rememberUpdatedState(account?.uid.orEmpty() to account?.bduss.orEmpty())
    val cache = remember(context) { CloudMemeRepository.cache(App.INSTANCE.filesDir) }
    val uploader = remember { ImageUploader("") }
    val collector = remember {
        CloudMemeImageCollector(
            context = context,
            scope = scope,
            cache = cache,
            expectedAccount = {
                // 单次读取当前账号对象，避免两次 getter 之间切号产生混合快照。
                AccountUtil.currentAccount?.let { it.uid to it.bduss } ?: ("" to "")
            },
            uploadImage = { uploader.uploadCloudMeme(it) },
        )
    }
    // 切号时立即取消在途收藏，旧账号的结果不会再回写到新账号界面。
    LaunchedEffect(observedAccount) {
        collector.cancelInFlight()
    }
    DisposableEffect(collector) {
        onDispose { collector.dispose() }
    }
    return collector
}

@Composable
private fun PreviewImage(
    imageUri: String,
    show: Boolean,
    layoutSizeProvider: () -> IntSize,
    layoutOffsetProvider: () -> Offset,
    imageAspectRatioProvider: () -> Float,
    originImageUri: String? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    var showFullScreenLayout by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }

    LaunchedEffect(show) {
        if (show) {
            showFullScreenLayout = true
            showPreview = true
        } else {
            showPreview = false
        }
    }

    val windowWidthSizeClass = LocalWindowSizeClass.current.widthSizeClass
    val widthFraction = remember(windowWidthSizeClass) {
        when (windowWidthSizeClass) {
            WindowWidthSizeClass.Compact -> 0.8f
            WindowWidthSizeClass.Medium -> 0.6f
            else -> 0.4f
        }
    }
    val imageAspectRatio = imageAspectRatioProvider()
    val previewImageWidthPx = remember(widthFraction) {
        App.ScreenInfo.EXACT_SCREEN_WIDTH * widthFraction
    }
    val previewImageHeightPx = remember(imageAspectRatio, previewImageWidthPx) {
        previewImageWidthPx * imageAspectRatio
    }
    val previewImageWidthDp = remember(previewImageWidthPx, density) {
        with(density) { previewImageWidthPx.toDp() }
    }
    val previewImageHeightDp = remember(previewImageHeightPx, density) {
        with(density) { previewImageHeightPx.toDp() }
    }

    val screenWidth = App.ScreenInfo.EXACT_SCREEN_WIDTH
    val screenCenterX = screenWidth / 2
    val screenHeight = App.ScreenInfo.EXACT_SCREEN_HEIGHT
    val screenCenterY = screenHeight / 2
    val statusBarHeight = WindowInsets.statusBars.getTop(density)

    if (showFullScreenLayout) {
        val animProgress = remember { Animatable(0f) }

        LaunchedEffect(showPreview) {
            animProgress.animateTo(
                if (showPreview) 1f else 0f,
                animationSpec = spring(
                    stiffness = Spring.StiffnessMedium,
                )
            )
            if (!showPreview) {
                showFullScreenLayout = false
            }
        }

        FullScreen {
            val (layoutWidthPx, layoutHeightPx) = layoutSizeProvider()
            val layoutWidthDp = remember(layoutWidthPx, density) {
                with(density) { layoutWidthPx.toDp() }
            }
            val layoutHeightDp = remember(layoutHeightPx, density) {
                with(density) { layoutHeightPx.toDp() }
            }

            val request = remember(imageUri) {
                DisplayRequest(context, imageUri)
            }

            val originRequest = remember(imageUri, originImageUri) {
                DisplayRequest(context, originImageUri ?: imageUri) {
                    placeholder(ThumbnailMemoryCacheStateImage(imageUri))
                    crossfade(fadeStart = false)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(
                            lerp(layoutWidthDp, previewImageWidthDp, animProgress.value)
                        )
                        .height(
                            lerp(layoutHeightDp, previewImageHeightDp, animProgress.value)
                        )
                        .absoluteOffset {
                            val layoutOffset = layoutOffsetProvider()
                            val currentLayoutWidthPx = lerp(
                                layoutWidthPx.toFloat(),
                                previewImageWidthPx,
                                animProgress.value
                            )
                            val currentLayoutHeightPx = lerp(
                                layoutHeightPx.toFloat(),
                                previewImageHeightPx,
                                animProgress.value
                            )
                            IntOffset(
                                lerp(
                                    layoutOffset.x - (screenCenterX - currentLayoutWidthPx / 2),
                                    0f,
                                    animProgress.value
                                ).toInt(),
                                lerp(
                                    layoutOffset.y - (screenCenterY - currentLayoutHeightPx / 2 + statusBarHeight / 2),
                                    0f,
                                    animProgress.value
                                ).toInt()
                            )
                        }
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    AsyncImage(
                        request = request,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    if (originImageUri != null && animProgress.value >= 1f) {
                        AsyncImage(
                            request = originRequest,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkImage(
    imageUri: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    photoViewData: PhotoViewData? = null,
    contentScale: ContentScale = ContentScale.Fit,
    skipNetworkCheck: Boolean = false,
    enablePreview: Boolean = false,
    onLongPress: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    var shouldLoad by remember { mutableStateOf(shouldLoadImage(context, skipNetworkCheck)) }
    val enableClick = remember(photoViewData, shouldLoad) { photoViewData != null || !shouldLoad }
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    // 有收藏菜单时长按只弹菜单，避免与内置预览争抢同一手势。
    val longPressShowsMenu = onLongPress != null
    val enableGesture = enableClick || longPressShowsMenu

    val colorMask =
        if (ExtendedTheme.colors.isNightMode && context.appPreferences.imageDarkenWhenNightMode) {
            MaskTransformation(Color.parseColor("#99000000"))
        } else null

    var layoutSize by remember { mutableStateOf(IntSize.Zero) }

    var layoutOffset by remember { mutableStateOf(Offset.Zero) }

    val request = remember(imageUri, shouldLoad, colorMask) {
        DisplayRequest(context, imageUri) {
            placeholder(ThumbnailMemoryCacheStateImage())
            crossfade(fadeStart = false)
            if (!shouldLoad) {
                depth(Depth.LOCAL)
            }
            if (colorMask != null) {
                transformations(colorMask)
            }
        }
    }

    val state = rememberAsyncImageState()
    val imageAspectRatio by remember {
        derivedStateOf {
            with(state.result) {
                if (this is DisplayResult.Success) imageInfo.height.toFloat() / imageInfo.width
                else 0f
            }
        }
    }

    var isLongPressing by remember { mutableStateOf(false) }

    if (enablePreview) {
        PreviewImage(
            imageUri = imageUri,
            show = isLongPressing,
            layoutSizeProvider = { layoutSize },
            layoutOffsetProvider = { layoutOffset },
            imageAspectRatioProvider = { imageAspectRatio },
            originImageUri = photoViewData?.data?.originUrl
        )
    }

    Box(
        modifier = Modifier
            .pointerInput(enableGesture, enableClick, enablePreview, longPressShowsMenu) {
                if (enableGesture) {
                    detectTapGestures(
                        onLongPress = {
                            val action = currentOnLongPress
                            if (action != null) {
                                // 长按只弹菜单，不进入全屏预览。
                                action()
                            } else if (enablePreview) {
                                isLongPressing = true
                            }
                        },
                        onPress = {
                            tryAwaitRelease()
                            isLongPressing = false
                        },
                        onTap = {
                            if (!shouldLoad) {
                                shouldLoad = true
                            } else if (photoViewData != null) {
                                context.goToActivity<PhotoViewActivity> {
                                    putExtra(
                                        EXTRA_PHOTO_VIEW_DATA,
                                        photoViewData
                                    )
                                }
                            }
                        }
                    )
                }
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress { change, dragAmount ->
                    Log.i("NetworkImage", "dragAmount: $dragAmount")
                }
            }
            .then(modifier)
    ) {
        AsyncImage(
            request = request,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    layoutSize = it
                }
                .onGloballyPositioned {
                    layoutOffset = it.positionInWindow()
                },
            contentDescription = contentDescription,
            contentScale = contentScale,
            state = state,
        )
    }
}

@Composable
fun NetworkImage(
    imageUriProvider: () -> String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    photoViewDataProvider: (() -> PhotoViewData)? = null,
    contentScale: ContentScale = ContentScale.Fit,
    skipNetworkCheck: Boolean = false,
    enablePreview: Boolean = false,
    onLongPress: (() -> Unit)? = null,
) {
    val imageUri by rememberUpdatedState(newValue = imageUriProvider())
    val photoViewData by rememberUpdatedState(newValue = photoViewDataProvider?.invoke())

    NetworkImage(
        imageUri = imageUri,
        contentDescription = contentDescription,
        modifier = modifier,
        photoViewData = photoViewData,
        contentScale = contentScale,
        skipNetworkCheck = skipNetworkCheck,
        enablePreview = enablePreview,
        onLongPress = onLongPress,
    )
}