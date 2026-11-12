package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.widgets.compose.EmoticonPickerPanel
import com.huanchengfly.tieba.post.utils.Emoticon
import kotlinx.coroutines.flow.first

/**
 * 聊天输入栏，群聊与私信共用。
 *
 * 结构参考 JetChat：输入框 + 表情 + 发送按钮一行，表情面板放在下方。
 * imePadding 只作用于输入栏，避免与 Scaffold 的 content padding 叠加成双倍留白。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    showEmoticonPanel: Boolean,
    onToggleEmoticonPanel: () -> Unit,
    onEmoticonClick: (Emoticon) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onMentionClick: (() -> Unit)? = null,
    onRobotClick: (() -> Unit)? = null,
    onDismissEmoticonPanel: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputInteractionSource = remember { MutableInteractionSource() }
    val imeVisible by rememberUpdatedState(WindowInsets.isImeVisible)
    val emoticonPanelVisible by rememberUpdatedState(showEmoticonPanel)
    val dismissEmoticonPanel by rememberUpdatedState(onDismissEmoticonPanel)

    LaunchedEffect(inputInteractionSource) {
        inputInteractionSource.interactions.collect { interaction ->
            if (emoticonPanelVisible &&
                (interaction is PressInteraction.Press || interaction is FocusInteraction.Focus)
            ) {
                dismissEmoticonPanel()
            }
        }
    }
    LaunchedEffect(showEmoticonPanel) {
        if (showEmoticonPanel) {
            focusManager.clearFocus()
            keyboardController?.hide()
            // 先观察 IME 隐藏，避免残留的可见状态关闭刚打开的面板。
            snapshotFlow { imeVisible }.first { !it }
            snapshotFlow { imeVisible }.first { it }
            dismissEmoticonPanel()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ExtendedTheme.colors.bottomBar)
            .navigationBarsPadding()
            .then(if (showEmoticonPanel) Modifier else Modifier.imePadding())
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
                singleLine = false,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                interactionSource = inputInteractionSource,
                shape = MaterialTheme.shapes.medium,
                colors = TextFieldDefaults.textFieldColors(
                    textColor = ExtendedTheme.colors.text,
                    disabledTextColor = ExtendedTheme.colors.textDisabled,
                    backgroundColor = ExtendedTheme.colors.bottomBarSurface,
                    cursorColor = ExtendedTheme.colors.primary,
                    placeholderColor = ExtendedTheme.colors.textSecondary,
                    disabledPlaceholderColor = ExtendedTheme.colors.textDisabled,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            IconButton(onClick = onToggleEmoticonPanel, enabled = enabled) {
                Icon(
                    imageVector = Icons.Outlined.EmojiEmotions,
                    contentDescription = if (showEmoticonPanel) "关闭表情面板" else "打开表情面板",
                )
            }
            IconButton(
                onClick = onSend,
                enabled = enabled && value.text.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = stringResource(id = R.string.btn_send),
                )
            }
        }
        if (onMentionClick != null || onRobotClick != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onMentionClick != null) {
                    IconButton(onClick = onMentionClick, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Outlined.AlternateEmail,
                            contentDescription = "提及成员",
                        )
                    }
                }
                if (onRobotClick != null) {
                    IconButton(onClick = onRobotClick, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Outlined.SmartToy,
                            contentDescription = "选择机器人",
                        )
                    }
                }
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
