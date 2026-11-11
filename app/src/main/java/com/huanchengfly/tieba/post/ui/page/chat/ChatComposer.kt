package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.widgets.compose.EmoticonPickerPanel
import com.huanchengfly.tieba.post.utils.Emoticon

/**
 * 聊天输入栏，群聊与私信共用。
 *
 * 结构参考 JetChat：输入框 + 表情 + 发送按钮一行，表情面板放在下方。
 * imePadding 只作用于输入栏，避免与 Scaffold 的 content padding 叠加成双倍留白。
 */
@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    showEmoticonPanel: Boolean,
    onToggleEmoticonPanel: () -> Unit,
    onEmoticonClick: (Emoticon) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .background(ExtendedTheme.colors.bottomBar)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = { Text(text = stringResource(id = R.string.tip_input_message)) },
                maxLines = 4,
            )
            IconButton(onClick = onToggleEmoticonPanel, enabled = enabled) {
                Icon(
                    imageVector = Icons.Outlined.EmojiEmotions,
                    contentDescription = stringResource(id = R.string.emoticon),
                )
            }
            IconButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = stringResource(id = R.string.btn_send),
                )
            }
        }
        if (showEmoticonPanel) {
            EmoticonPickerPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                onEmoticonClick = onEmoticonClick,
            )
        }
    }
}

/** 消息列表统一的内容边距。 */
val ChatListContentPadding = PaddingValues(vertical = 8.dp)
