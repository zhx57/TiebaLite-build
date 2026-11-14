package com.huanchengfly.tieba.post.ui.page.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import android.view.inputmethod.BaseInputConnection
import android.text.InputType
import android.view.Gravity
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.widget.addTextChangedListener
import androidx.compose.ui.unit.Velocity
import android.widget.TextView.BufferType
import java.util.concurrent.atomic.AtomicBoolean
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.models.chat.ChatCloudMeme
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.common.theme.compose.menuBackground
import com.huanchengfly.tieba.post.ui.widgets.compose.EmoticonPickerPanel
import com.huanchengfly.tieba.post.ui.widgets.edittext.widget.UndoableEditText
import com.huanchengfly.tieba.post.utils.Emoticon
import com.huanchengfly.tieba.post.utils.StringUtil

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
    onCloudMemeClick: ((ChatCloudMeme) -> Unit)? = null,
    onPhotoClick: (() -> Unit)? = null,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.isImeVisible
    val imeHeight = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val availableHeight = (configuration.screenHeightDp.dp -
        if (imeVisible && !showEmoticonPanel) imeHeight else 0.dp).coerceAtLeast(0.dp)
    val maxInputLines = when {
        availableHeight < 360.dp -> 2
        availableHeight < 600.dp || showEmoticonPanel -> 4
        else -> 6
    }
    var inputBarHeight by remember { mutableStateOf(56.dp) }
    val navigationHeight = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val maxPanelHeight = (availableHeight - inputBarHeight - navigationHeight)
        .coerceAtLeast(0.dp)
    val emoticonPanelHeight = if (maxPanelHeight > 0.dp) {
        (availableHeight * 0.58f)
            .coerceIn(200.dp, 360.dp)
            .coerceAtMost(maxPanelHeight)
    } else {
        0.dp
    }
    var panelExpanded by remember { mutableStateOf(false) }
    val dragThreshold = with(density) { 24.dp.toPx() }
    val panelScrollConnection = remember(dragThreshold) {
        object : NestedScrollConnection {
            var downwardDrag = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0 && !panelExpanded) {
                    panelExpanded = true
                    return available
                }
                if (available.y < 0) downwardDrag = 0f
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0 && panelExpanded) {
                    downwardDrag += available.y
                    if (downwardDrag >= dragThreshold) {
                        panelExpanded = false
                        downwardDrag = 0f
                    }
                    return available
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                downwardDrag = 0f
                return Velocity.Zero
            }
        }
    }
    val menuMaxHeight = (availableHeight * 0.5f).coerceIn(48.dp, 280.dp)
    var menuExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val currentOnValueChange = rememberUpdatedState(onValueChange)
    val currentShowEmoticonPanel = rememberUpdatedState(showEmoticonPanel)
    val currentDismissEmoticonPanel = rememberUpdatedState(onDismissEmoticonPanel)
    val textColor = ExtendedTheme.colors.text.toArgb()
    val disabledTextColor = ExtendedTheme.colors.textDisabled.toArgb()
    val surfaceColor = ExtendedTheme.colors.bottomBarSurface.toArgb()
    val placeholderColor = ExtendedTheme.colors.textSecondary.toArgb()

    LaunchedEffect(enabled) {
        if (!enabled) menuExpanded = false
    }
    LaunchedEffect(showEmoticonPanel) {
        if (!showEmoticonPanel) panelExpanded = false
        if (showEmoticonPanel) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            // IME 动画会反复上报可见状态，只由显式输入操作关闭面板。
        }
    }

    BackHandler(enabled = showEmoticonPanel) {
        if (panelExpanded) panelExpanded = false else onDismissEmoticonPanel()
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
                .onSizeChanged { inputBarHeight = with(density) { it.height.toDp() } }
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
            val syncingText = remember { AtomicBoolean(false) }
            AndroidView(
                factory = { context ->
                    object : UndoableEditText(context) {
                        var selectionReady = false

                        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
                            super.onSelectionChanged(selStart, selEnd)
                            if (selectionReady && !syncingText.get() && selStart >= 0 && selEnd >= 0) {
                                currentOnValueChange.value(chatInputValue())
                            }
                        }
                    }.apply {
                        setTextColor(textColor)
                        setHintTextColor(placeholderColor)
                        setBackgroundColor(surfaceColor)
                        setHint(context.getString(R.string.tip_input_message))
                        gravity = Gravity.TOP or Gravity.START
                        inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        setSingleLine(false)
                        maxLines = maxInputLines
                        minLines = 1
                        textSize = 16f
                        setPadding(16, 8, 16, 8)
                        setOnClickListener {
                            if (currentShowEmoticonPanel.value) {
                                currentDismissEmoticonPanel.value()
                                post { keyboardController?.show() }
                            }
                        }
                        isEnabled = enabled
                        setText(StringUtil.getEmoticonContent(this, value.text), BufferType.SPANNABLE)
                        setSelection(
                            value.selection.start.coerceIn(0, length()),
                            value.selection.end.coerceIn(0, length()),
                        )
                        addTextChangedListener(
                            beforeTextChanged = { _, _, _, _ -> selectionReady = false },
                            afterTextChanged = {
                                selectionReady = true
                                if (!syncingText.get()) {
                                    currentOnValueChange.value(chatInputValue())
                                }
                            },
                        )
                        selectionReady = true
                    }
                },
                update = { editText ->
                    editText.isEnabled = enabled
                    editText.maxLines = maxInputLines
                    if (editText.text?.toString() != value.text) {
                        val selectionStart = value.selection.start.coerceIn(0, value.text.length)
                        val selectionEnd = value.selection.end.coerceIn(0, value.text.length)
                        syncingText.set(true)
                        try {
                            editText.editableText.replace(
                                0,
                                editText.length(),
                                StringUtil.getEmoticonContent(editText, value.text),
                            )
                            editText.setSelection(selectionStart, selectionEnd)
                        } finally {
                            syncingText.set(false)
                        }
                    } else if (
                        editText.selectionStart != value.selection.start ||
                        editText.selectionEnd != value.selection.end
                    ) {
                        syncingText.set(true)
                        try {
                            editText.setSelection(
                                value.selection.start.coerceIn(0, editText.length()),
                                value.selection.end.coerceIn(0, editText.length()),
                            )
                        } finally {
                            syncingText.set(false)
                        }
                    }
                    editText.setTextColor(if (enabled) textColor else disabledTextColor)
                    editText.setHintTextColor(if (enabled) placeholderColor else disabledTextColor)
                    editText.setBackgroundColor(surfaceColor)
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
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
                    if (onPhotoClick != null) {
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onPhotoClick()
                        }) {
                            Icon(Icons.Outlined.Image, contentDescription = null, tint = ExtendedTheme.colors.text)
                            Text("图片", modifier = Modifier.padding(start = 12.dp), color = ExtendedTheme.colors.text)
                        }
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
            val panelHeight = if (panelExpanded) {
                maxPanelHeight
            } else {
                emoticonPanelHeight
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeight)
                    .background(ExtendedTheme.colors.bottomBar)
                    .nestedScroll(panelScrollConnection),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                        .pointerInput(dragThreshold) {
                            var distance = 0f
                            detectVerticalDragGestures(
                                onDragStart = { distance = 0f },
                                onVerticalDrag = { change, delta ->
                                    change.consume()
                                    distance += delta
                                    if (distance <= -dragThreshold) panelExpanded = true
                                    if (distance >= dragThreshold) panelExpanded = false
                                },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (panelExpanded) "下滑收起" else "上滑展开",
                        color = ExtendedTheme.colors.primary,
                        modifier = Modifier.weight(1f).clickable { panelExpanded = !panelExpanded }
                            .padding(horizontal = 12.dp),
                    )
                    TextButton(onClick = onDismissEmoticonPanel) {
                        Text("关闭", color = ExtendedTheme.colors.text)
                    }
                }
                EmoticonPickerPanel(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onEmoticonClick = onEmoticonClick,
                    onCloudMemeClick = onCloudMemeClick?.takeIf { enabled }?.let { send ->
                        { meme ->
                            send(meme)
                            if (!panelExpanded) onDismissEmoticonPanel()
                        }
                    },
                )
            }
        }
    }
}

private fun UndoableEditText.chatInputValue(): TextFieldValue {
    val editable = editableText
    val composingStart = BaseInputConnection.getComposingSpanStart(editable)
    val composingEnd = BaseInputConnection.getComposingSpanEnd(editable)
    return TextFieldValue(
        text = editable.toString(),
        selection = TextRange(
            selectionStart.coerceIn(0, editable.length),
            selectionEnd.coerceIn(0, editable.length),
        ),
        composition = if (composingStart >= 0 && composingEnd > composingStart) {
            TextRange(composingStart, composingEnd)
        } else null,
    )
}

/** 消息列表统一的内容边距。 */
val ChatListContentPadding = PaddingValues(vertical = 8.dp)
