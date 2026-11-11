package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.models.chat.SendState
import com.huanchengfly.tieba.post.ui.common.PbContentText
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.utils.DateTimeUtils
import com.huanchengfly.tieba.post.utils.StringUtil

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
    onAvatarClick: () -> Unit = {},
) {
    val bubbleColor = if (isSelf) ExtendedTheme.colors.primary else ExtendedTheme.colors.chip
    val textColor = if (isSelf) ExtendedTheme.colors.onPrimary else ExtendedTheme.colors.onChip
    val displayText = text.ifBlank { stringResource(id = R.string.text_chat_unsupported_message) }

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
