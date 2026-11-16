package com.huanchengfly.tieba.post.ui.page.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.huanchengfly.tieba.post.goToActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageKind
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageSemantics
import com.huanchengfly.tieba.post.api.models.chat.SendState
import com.huanchengfly.tieba.post.models.PhotoViewData
import com.huanchengfly.tieba.post.models.PicItem
import com.huanchengfly.tieba.post.ui.common.PbContentText
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.destinations.UserProfilePageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.CloudMemeImageMenu
import com.huanchengfly.tieba.post.ui.widgets.compose.LongClickMenu
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberCloudMemeImageCollector
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberMenuState
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.VoicePlayer
import com.huanchengfly.tieba.post.utils.DateTimeUtils
import com.huanchengfly.tieba.post.utils.StringUtil
import com.huanchengfly.tieba.post.utils.appPreferences
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 聊天消息行，群聊与私信共用。
 *
 * 结构参考 JetChat 与 compose_chat：整行按收发方靠左/靠右，自己消息的状态指示器
 * 放在气泡左侧同一个内层 Row 里，固定宽度不参与 weight，避免气泡宽度抖动。
 */
private val NON_IMAGE_MESSAGE_KINDS = setOf(
    ChatMessageKind.VOICE,
    ChatMessageKind.TEXT_TO_SPEECH,
    ChatMessageKind.SYSTEM_EVENT,
    ChatMessageKind.DELETE_EVENT,
    ChatMessageKind.EMOTION_REACTION,
    ChatMessageKind.CONTROL,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageRow(
    text: String,
    isSelf: Boolean,
    senderName: String,
    avatarUrl: String,
    msgTime: Long,
    sendState: SendState,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
    images: List<String> = emptyList(),
    transportAccepted: Boolean = false,
    semantics: ChatMessageSemantics = ChatMessageSemantics(),
    /** 语音消息播放地址；非空时渲染与回帖一致的语音气泡。 */
    voiceUrl: String? = null,
    /** 语音时长，单位毫秒。 */
    voiceDurationMs: Int = 0,
    onAvatarClick: () -> Unit = {},
    onAvatarLongClick: (() -> Unit)? = null,
) {
    val bubbleColor = if (isSelf) ExtendedTheme.colors.primary else ExtendedTheme.colors.chip
    val textColor = if (isSelf) ExtendedTheme.colors.onPrimary else ExtendedTheme.colors.onChip
    val hasImages = images.isNotEmpty() && semantics.kind !in NON_IMAGE_MESSAGE_KINDS
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
    val compact = maxWidth < 360.dp
    val maxBubbleWidth = (maxWidth - if (compact) 68.dp else 96.dp).coerceAtMost(360.dp)
    val context = LocalContext.current
    val copyMessage = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(context, R.string.chat_message_copied, Toast.LENGTH_SHORT).show()
    }
    val semanticLabel = when (semantics.kind) {
        ChatMessageKind.VOICE -> "语音消息"
        ChatMessageKind.LARGE_EMOTICON -> "大表情"
        ChatMessageKind.IMAGE_TO_TEXT -> "图生文"
        ChatMessageKind.SINGLE_TEXT_IMAGE -> "图文消息"
        ChatMessageKind.TEXT_TO_SPEECH -> "文字转语音"
        ChatMessageKind.AI_INTRODUCTION -> "AI 介绍"
        ChatMessageKind.WEB_CHAT -> "WebChat 消息"
        ChatMessageKind.POST_SHARE -> "帖子分享"
        ChatMessageKind.DELETE_EVENT -> "消息已删除"
        ChatMessageKind.EMOTION_REACTION -> "表情回应"
        ChatMessageKind.SYSTEM_EVENT -> "系统消息"
        ChatMessageKind.CONTROL -> "控制消息"
        ChatMessageKind.UNKNOWN -> semantics.summary.takeIf { it.isNotBlank() } ?: "未知消息"
        else -> null
    }
    val displayText = when {
        text.isNotBlank() -> text
        hasImages -> ""
        semanticLabel != null -> semanticLabel
        else -> stringResource(id = R.string.text_chat_unsupported_message)
    }

    val annotatedText = remember(displayText) { annotateChatLinks(displayText) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 6.dp else 16.dp, vertical = 6.dp),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isSelf) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAvatarClick,
                        onLongClick = onAvatarLongClick,
                        onLongClickLabel = if (onAvatarLongClick != null) "提及此成员" else null,
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
                Avatar(
                    data = StringUtil.getAvatarUrl(avatarUrl),
                    size = Sizes.Small,
                    contentDescription = "$senderName 的头像",
                )
            }
        }

        Column(
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .padding(horizontal = if (compact) 4.dp else 8.dp),
            horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (isSelf) stringResource(id = R.string.text_chat_me) else senderName,
                style = MaterialTheme.typography.caption,
                color = ExtendedTheme.colors.textSecondary,
                fontSize = 12.sp,
            )
            val menuState = rememberMenuState()
            LongClickMenu(
                enabled = text.isNotBlank(),
                menuState = menuState,
                menuContent = {
                    if (text.isNotBlank()) {
                        DropdownMenuItem(onClick = {
                            copyMessage()
                            menuState.expanded = false
                        }) {
                            Text(stringResource(R.string.chat_message_copy))
                        }
                    }
                },
                content = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isSelf) {
                            ChatSendStateIndicator(
                                state = sendState,
                                transportAccepted = transportAccepted,
                                onRetryClick = onRetryClick,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                        if (hasImages) {
                            ChatMessageImages(
                                images = images,
                                isSelf = isSelf,
                            )
                        }
                        if (voiceUrl != null) {
                            // 与回帖语音共用同一套播放器与进度动画，避免出现两个语音实现。
                            VoicePlayer(
                                url = voiceUrl,
                                duration = voiceDurationMs,
                            )
                        } else if (!hasImages || text.isNotBlank()) {
                            PbContentText(
                                text = annotatedText,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(bubbleColor)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                color = textColor,
                                style = MaterialTheme.typography.body2,
                            )
                        }
                        }
                    }
                },
            )
            if (msgTime > 0) {
                Text(
                    text = DateTimeUtils.getRelativeTimeString(LocalContext.current, msgTime),
                    style = MaterialTheme.typography.caption,
                    color = ExtendedTheme.colors.textSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
    }
}

internal fun annotateChatLinks(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    Regex("https?://[^\\s<>\\\"，。！？；、（）【】]+", RegexOption.IGNORE_CASE)
        .findAll(text).forEach { match ->
            val url = match.value.trimEnd('.', ',', ';', ')', ']', '}')
            if (url.toHttpUrlOrNull() != null) {
                val end = match.range.first + url.length
                addStringAnnotation("url", url, match.range.first, end)
                addStyle(SpanStyle(textDecoration = TextDecoration.Underline), match.range.first, end)
            }
        }
}

/**
 * 图片消息内容。
 *
 * 群聊图片地址是完整的 CDN 链接，直接交给 NetworkImage 加载；
 * 传入 photoViewData 后点击即可全屏查看大图。
 *
 * 长按弹收藏菜单，与单击打开大图共用 combinedClickable，
 * 因此两个动作不会同时触发，也不影响 PhotoView 自身的翻页手势。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessageImages(
    images: List<String>,
    isSelf: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val radius = context.appPreferences.radius.dp
    val collector = rememberCloudMemeImageCollector()
    val picItems = remember(images) {
        images.mapIndexed { index, url ->
            PicItem(
                picId = index.toString(),
                picIndex = index,
                url = url,
                originUrl = url,
                showOriginBtn = false,
                originSize = 0,
            )
        }
    }
    val single = images.size == 1
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start,
    ) {
        images.forEachIndexed { index, url ->
            androidx.compose.runtime.key(index, url) {
                androidx.compose.foundation.layout.BoxWithConstraints {
                    // 与 AsyncImage 同处子组合，避免图片状态先于 contentScale 初始化而启动。
                    val state = com.github.panpf.sketch.compose.rememberAsyncImageState()
                    val result = state.result as? com.github.panpf.sketch.request.DisplayResult.Success
                    val ratio = result?.imageInfo?.let {
                        if (it.width > 0 && it.height > 0) it.width.toFloat() / it.height else 1f
                    } ?: 1f
                    val imageWidth = minOf(maxWidth, if (single) 240.dp else 144.dp,
                        (320.dp * ratio).coerceAtLeast(44.dp))
                    val imageHeight = (imageWidth / ratio).coerceIn(44.dp, 320.dp)
                    var shouldLoad by remember(url) {
                        androidx.compose.runtime.mutableStateOf(
                            com.huanchengfly.tieba.post.ui.widgets.compose.shouldLoadImage(context, false)
                        )
                    }
                    var menuExpanded by remember(url) { mutableStateOf(false) }
                    val darken = ExtendedTheme.colors.isNightMode && context.appPreferences.imageDarkenWhenNightMode
                    val request = remember(url, shouldLoad, darken) {
                        com.github.panpf.sketch.request.DisplayRequest(context, url) {
                            if (!shouldLoad) depth(com.github.panpf.sketch.request.Depth.LOCAL)
                            if (darken) {
                                transformations(com.github.panpf.sketch.transform.MaskTransformation(android.graphics.Color.parseColor("#99000000")))
                            }
                        }
                    }
                    com.github.panpf.sketch.compose.AsyncImage(
                        request = request,
                        state = state,
                        contentDescription = stringResource(id = R.string.desc_image),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(width = imageWidth, height = imageHeight)
                            .clip(RoundedCornerShape(radius))
                            .combinedClickable(
                                onLongClick = { menuExpanded = true },
                                onClick = {
                                    if (!shouldLoad) shouldLoad = true
                                    else context.goToActivity<com.huanchengfly.tieba.post.ui.page.photoview.PhotoViewActivity> {
                                        putExtra(
                                            com.huanchengfly.tieba.post.ui.page.photoview.PhotoViewActivity.EXTRA_PHOTO_VIEW_DATA,
                                            PhotoViewData(picItems = picItems, index = index),
                                        )
                                    }
                                },
                            ),
                    )
                    CloudMemeImageMenu(
                        expanded = menuExpanded,
                        isCollecting = collector.isCollecting(url),
                        onDismiss = { menuExpanded = false },
                        onCollect = { collector.collect(url) },
                    )
                }
            }
        }
    }
}

/**
 * 打开用户资料卡。
 *
 * UserProfilePage 的目标地带有 ExperimentalMaterialApi 注解，
 * 统一在这里 OptIn，避免每个调用点各写一遍。
 */
@OptIn(ExperimentalMaterialApi::class)
fun navigateToUserProfile(navigator: DestinationsNavigator, uid: Long) {
    navigator.navigate(UserProfilePageDestination(uid))
}

/**
 * 传输阶段转圈，ACK 后静默核对回显；失败显示感叹号并可点击重发。
 *
 * 固定 44dp 触控区、20dp 图标，三种状态位置一致，切换时不会引起布局跳动。
 */
@Composable
private fun ChatSendStateIndicator(
    state: SendState,
    transportAccepted: Boolean,
    onRetryClick: () -> Unit,
) {
    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        when (state) {
            SendState.SENDING -> {
                if (!transportAccepted) CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = ExtendedTheme.colors.textSecondary,
                )
            }

            SendState.FAILED -> IconButton(
                onClick = onRetryClick,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = stringResource(id = R.string.btn_resend),
                    tint = MaterialTheme.colors.error,
                    modifier = Modifier.size(20.dp),
                )
            }

            SendState.SENT -> Unit
        }
    }
}
