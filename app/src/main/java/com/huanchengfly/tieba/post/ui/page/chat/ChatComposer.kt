package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.common.theme.compose.menuBackground
import com.huanchengfly.tieba.post.ui.widgets.compose.EmoticonPickerPanel
import com.huanchengfly.tieba.post.utils.Emoticon

/**
 * 聊天输入栏，群聊与私信共用。
 *
 * 输入框、更多操作与发送按钮放在同一行，表情面板放在下方。
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
    robotCommandName: String? = null,
    onCancelRobotCommand: (() -> Unit)? = null,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.isImeVisible
    val imeHeight = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val availableHeight = (configuration.screenHeightDp.dp -
        if (imeVisible) imeHeight else 0.dp).coerceAtLeast(0.dp)
    val maxInputLines = when {
        availableHeight < 360.dp -> 2
        availableHeight < 600.dp || showEmoticonPanel -> 4
        else -> 6
    }
    val emoticonPanelHeight = (availableHeight * 0.35f).coerceIn(64.dp, 240.dp)
    val menuMaxHeight = (availableHeight * 0.5f).coerceIn(48.dp, 280.dp)
    var menuExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputInteractionSource = remember { MutableInteractionSource() }
    val emoticonPanelVisible by rememberUpdatedState(showEmoticonPanel)
    val dismissEmoticonPanel by rememberUpdatedState(onDismissEmoticonPanel)

    LaunchedEffect(inputInteractionSource) {
        inputInteractionSource.interactions.collect { interaction ->
            if (emoticonPanelVisible &&
                interaction is PressInteraction.Press
            ) {
                dismissEmoticonPanel()
            }
        }
    }
    LaunchedEffect(enabled) {
        if (!enabled) menuExpanded = false
    }
    LaunchedEffect(showEmoticonPanel) {
        if (showEmoticonPanel) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            // IME 动画会反复上报可见状态，只由显式输入操作关闭面板。
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ExtendedTheme.colors.bottomBar)
            .padding(bottom = if (configuration.isScreenRound) 12.dp else 0.dp)
            .navigationBarsPadding()
            .then(if (showEmoticonPanel) Modifier else Modifier.imePadding())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = when {
                        configuration.isScreenRound -> 16.dp
                        configuration.screenWidthDp < 360 -> 4.dp
                        else -> 12.dp
                    },
                    vertical = 4.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (configuration.screenWidthDp < 360) 0.dp else 8.dp),
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = { Text(text = stringResource(id = R.string.tip_input_message)) },
                singleLine = false,
                maxLines = maxInputLines,
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
            Box {
                IconButton(
                    onClick = { menuExpanded = !menuExpanded },
                    modifier = Modifier.size(48.dp),
                    enabled = enabled,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "更多聊天操作",
                        tint = if (enabled) ExtendedTheme.colors.text else ExtendedTheme.colors.textDisabled,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded && enabled,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier
                        .heightIn(max = menuMaxHeight)
                        .background(ExtendedTheme.colors.menuBackground),
                ) {
                    DropdownMenuItem(onClick = {
                        menuExpanded = false
                        onToggleEmoticonPanel()
                    }) {
                        Icon(Icons.Outlined.EmojiEmotions, contentDescription = null, tint = ExtendedTheme.colors.text)
                        Text(
                            text = if (showEmoticonPanel) "关闭表情面板" else "打开表情面板",
                            modifier = Modifier.padding(start = 12.dp),
                            color = ExtendedTheme.colors.text,
                        )
                    }
                    if (onMentionClick != null) {
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onMentionClick()
                        }) {
                            Icon(Icons.Outlined.AlternateEmail, contentDescription = null, tint = ExtendedTheme.colors.text)
                            Text("提及成员", modifier = Modifier.padding(start = 12.dp), color = ExtendedTheme.colors.text)
                        }
                    }
                    if (onRobotClick != null) {
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onRobotClick()
                        }) {
                            Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = ExtendedTheme.colors.text)
                            Text("选择机器人", modifier = Modifier.padding(start = 12.dp), color = ExtendedTheme.colors.text)
                        }
                    }
                    if (robotCommandName != null && onCancelRobotCommand != null) {
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onCancelRobotCommand()
                        }) {
                            Icon(Icons.Outlined.Close, contentDescription = null, tint = ExtendedTheme.colors.text)
                            Text(
                                text = "取消机器人命令：$robotCommandName",
                                modifier = Modifier.padding(start = 12.dp),
                                color = ExtendedTheme.colors.text,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = onSend,
                modifier = Modifier.size(48.dp),
                enabled = enabled && value.text.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = stringResource(id = R.string.btn_send),
                    tint = if (enabled && value.text.isNotBlank()) {
                        ExtendedTheme.colors.primary
                    } else ExtendedTheme.colors.textDisabled,
                )
            }
        }
        if (showEmoticonPanel) {
            EmoticonPickerPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(emoticonPanelHeight),
                onEmoticonClick = onEmoticonClick,
            )
        }
    }
}

/** 消息列表统一的内容边距。 */
val ChatListContentPadding = PaddingValues(vertical = 8.dp)
