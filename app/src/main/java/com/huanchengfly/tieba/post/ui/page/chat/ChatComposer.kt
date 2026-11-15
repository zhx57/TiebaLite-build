package com.huanchengfly.tieba.post.ui.page.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.models.chat.ChatCloudMeme
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.common.theme.compose.menuBackground
import com.huanchengfly.tieba.post.ui.widgets.compose.EmoticonPickerPanel
import com.huanchengfly.tieba.post.utils.Emoticon

/** 输入与扩展面板共用页面内容区预算，不依赖 Scaffold bottomBar 的松约束。 */
@Composable
fun ChatComposer(
    value: TextFieldValue,
    availableHeight: Dp,
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
    onCloudMemeClick: ((ChatCloudMeme) -> Unit)? = null,
    onPhotoClick: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(showEmoticonPanel, enabled) {
        if (!showEmoticonPanel) expanded = false
        if (!enabled) menuExpanded = false
        if (showEmoticonPanel) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    BackHandler(enabled = showEmoticonPanel) {
        if (expanded) expanded = false else onDismissEmoticonPanel()
    }
    BoxWithConstraints(modifier.fillMaxWidth().background(ExtendedTheme.colors.bottomBar)) {
        val compact = maxWidth < 400.dp
        val inputMaxHeight = (availableHeight * 0.3f).coerceIn(48.dp, 144.dp)
        val panelMaxHeight = (availableHeight * 0.65f - inputMaxHeight).coerceIn(0.dp, 480.dp)
        val panelHeight = if (expanded) panelMaxHeight else minOf(280.dp, panelMaxHeight)
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (compact) 4.dp else 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    maxLines = if (availableHeight < 360.dp || showEmoticonPanel) 2 else 6,
                    textStyle = MaterialTheme.typography.body1.copy(
                        color = if (enabled) ExtendedTheme.colors.text else ExtendedTheme.colors.textDisabled,
                    ),
                    cursorBrush = SolidColor(ExtendedTheme.colors.primary),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = inputMaxHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ExtendedTheme.colors.bottomBarSurface)
                        .onFocusChanged {
                            if (it.isFocused && showEmoticonPanel) onDismissEmoticonPanel()
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    decorationBox = { innerTextField ->
                        Box {
                            if (value.text.isEmpty()) Text(
                                stringResource(R.string.tip_input_message),
                                color = ExtendedTheme.colors.textSecondary,
                            )
                            innerTextField()
                        }
                    },
                )
                Box {
                    IconButton(onClick = { menuExpanded = !menuExpanded }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Add, "更多聊天操作", tint = ExtendedTheme.colors.text)
                    }
                    DropdownMenu(
                        expanded = menuExpanded && enabled,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.heightIn(max = (availableHeight * 0.7f).coerceIn(48.dp, 320.dp))
                            .background(ExtendedTheme.colors.menuBackground),
                    ) {
                        DropdownMenuItem(onClick = { menuExpanded = false; onToggleEmoticonPanel() }) {
                            Icon(Icons.Outlined.EmojiEmotions, null, tint = ExtendedTheme.colors.text)
                            Text(if (showEmoticonPanel) "关闭表情面板" else "打开表情面板", Modifier.padding(start = 12.dp), color = ExtendedTheme.colors.text)
                        }
                        if (onPhotoClick != null) DropdownMenuItem(onClick = { menuExpanded = false; onPhotoClick() }) {
                            Icon(Icons.Outlined.Image, null, tint = ExtendedTheme.colors.text)
                            Text("图片", Modifier.padding(start = 12.dp), color = ExtendedTheme.colors.text)
                        }
                        if (onMentionClick != null) DropdownMenuItem(onClick = { menuExpanded = false; onMentionClick() }) {
                            Icon(Icons.Outlined.AlternateEmail, null, tint = ExtendedTheme.colors.text)
                            Text("提及成员", Modifier.padding(start = 12.dp), color = ExtendedTheme.colors.text)
                        }
                        if (onRobotClick != null) DropdownMenuItem(onClick = { menuExpanded = false; onRobotClick() }) {
                            Icon(Icons.Outlined.SmartToy, null, tint = ExtendedTheme.colors.text)
                            Text("选择机器人", Modifier.padding(start = 12.dp), color = ExtendedTheme.colors.text)
                        }
                        if (robotCommandName != null && onCancelRobotCommand != null) DropdownMenuItem(onClick = { menuExpanded = false; onCancelRobotCommand() }) {
                            Icon(Icons.Outlined.Close, null, tint = ExtendedTheme.colors.text)
                            Text("取消机器人命令：$robotCommandName", Modifier.padding(start = 12.dp), color = ExtendedTheme.colors.text, maxLines = 2)
                        }
                    }
                }
                IconButton(onClick = onSend, enabled = enabled && value.text.isNotBlank(), modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        stringResource(R.string.btn_send),
                        tint = if (enabled && value.text.isNotBlank()) ExtendedTheme.colors.primary else ExtendedTheme.colors.textDisabled,
                    )
                }
            }
            if (showEmoticonPanel && panelHeight >= 44.dp) {
                Column(Modifier.fillMaxWidth().height(panelHeight)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (expanded) "收起面板" else "展开面板",
                            Modifier.weight(1f).heightIn(min = 44.dp).clickable { expanded = !expanded }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            color = ExtendedTheme.colors.primary,
                        )
                        TextButton(onClick = onDismissEmoticonPanel, modifier = Modifier.heightIn(min = 44.dp)) {
                            Text("关闭", color = ExtendedTheme.colors.text)
                        }
                    }
                    EmoticonPickerPanel(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onEmoticonClick = onEmoticonClick,
                        onCloudMemeClick = onCloudMemeClick?.takeIf { enabled }?.let { send ->
                            { meme -> send(meme); if (!expanded) onDismissEmoticonPanel() }
                        },
                    )
                }
            }
        }
    }
}

val ChatListContentPadding = PaddingValues(vertical = 8.dp)
