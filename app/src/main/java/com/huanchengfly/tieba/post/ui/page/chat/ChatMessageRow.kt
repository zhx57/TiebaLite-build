package com.huanchengfly.tieba.post.ui.page.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.models.chat.SendState
import com.huanchengfly.tieba.post.models.PhotoViewData
import com.huanchengfly.tieba.post.models.PicItem
import com.huanchengfly.tieba.post.ui.common.PbContentText
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.destinations.UserProfilePageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.LongClickMenu
import com.huanchengfly.tieba.post.ui.widgets.compose.NetworkImage
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberMenuState
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.utils.DateTimeUtils
import com.huanchengfly.tieba.post.utils.StringUtil
import com.huanchengfly.tieba.post.utils.appPreferences
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/**
 * 聊天消息行，群聊与私信共用。
 *
 * 结构参考 JetChat 与 compose_chat：整行按收发方靠左/靠右，自己消息的状态指示器
 * 放在气泡左侧同一个内层 Row 里，固定宽度不参与 weight，避免气泡宽度抖动。
 */
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
    onAvatarClick: () -> Unit = {},
) {
    val bubbleColor = if (isSelf) ExtendedTheme.colors.primary else ExtendedTheme.colors.chip
    val textColor = if (isSelf) ExtendedTheme.colors.onPrimary else ExtendedTheme.colors.onChip
    val hasImages = images.isNotEmpty()
    val context = LocalContext.current
    val copyMessage = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(context, R.string.chat_message_copied, Toast.LENGTH_SHORT).show()
    }
    val displayText = when {
        hasImages -> text
        else -> text.ifBlank { stringResource(id = R.string.text_chat_unsupported_message) }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isSelf) {
            Avatar(
                data = StringUtil.getAvatarUrl(avatarUrl),
                size = Sizes.Small,
                contentDescription = null,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAvatarClick,
                    ),
            )
        }

        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .padding(horizontal = 8.dp),
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
                                onRetryClick = onRetryClick,
                            )
                        }
                        if (hasImages) {
                            ChatMessageImages(
                                images = images,
                                isSelf = isSelf,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (!hasImages || text.isNotBlank()) {
                            PbContentText(
                                text = displayText,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(bubbleColor)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                color = textColor,
                                style = MaterialTheme.typography.body2,
                            )
                        }
                    }
                },
            )
            if (msgTime > 0 && sendState == SendState.SENT) {
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

/**
 * 图片消息内容。
 *
 * 群聊图片地址是完整的 CDN 链接，直接交给 NetworkImage 加载；
 * 传入 photoViewData 后点击即可全屏查看大图。
 */
@Composable
private fun ChatMessageImages(
    images: List<String>,
    isSelf: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val radius = context.appPreferences.radius.dp
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
            NetworkImage(
                imageUri = url,
                contentDescription = stringResource(id = R.string.desc_image),
                modifier = Modifier
                    .focusable()
                    .clip(RoundedCornerShape(radius))
                    .size(if (single) 180.dp else 96.dp),
                photoViewData = PhotoViewData(
                    picItems = picItems,
                    index = index,
                ),
                contentScale = ContentScale.Crop,
            )
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
 * 自己消息的发送状态：发送中转圈，失败显示圆形感叹号并可点击重发。
 *
 * 固定 20dp，三种状态位置一致，切换时不会引起布局跳动。
 */
@Composable
private fun ChatSendStateIndicator(
    state: SendState,
    onRetryClick: () -> Unit,
) {
    Box(modifier = Modifier.size(20.dp)) {
        when (state) {
            SendState.SENDING -> CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 2.dp,
                color = ExtendedTheme.colors.textSecondary,
            )

            SendState.FAILED -> IconButton(
                onClick = onRetryClick,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = stringResource(id = R.string.btn_resend),
                    tint = MaterialTheme.colors.error,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            SendState.SENT -> Unit
        }
    }
}
