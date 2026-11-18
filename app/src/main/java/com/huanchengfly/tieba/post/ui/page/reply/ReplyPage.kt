package com.huanchengfly.tieba.post.ui.page.reply

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.text.Editable
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ReplacementSpan
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Badge
import androidx.compose.material.BadgedBox
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.InsertPhoto
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.TextViewCompat
import androidx.core.widget.addTextChangedListener
import com.github.panpf.sketch.compose.AsyncImage
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.GlobalEvent
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.emitGlobalEvent
import com.huanchengfly.tieba.post.arch.onEvent
import com.huanchengfly.tieba.post.arch.onGlobalEvent
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.components.spans.EmoticonSpanV2
import com.huanchengfly.tieba.post.pxToDpFloat
import com.huanchengfly.tieba.post.toMD5
import com.huanchengfly.tieba.post.toastShort
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.destinations.ReplyPageDestination
import com.huanchengfly.tieba.post.ui.page.reply.ReplyPanelType.EMOJI
import com.huanchengfly.tieba.post.ui.page.reply.ReplyPanelType.IMAGE
import com.huanchengfly.tieba.post.ui.page.reply.ReplyPanelType.NONE
import com.huanchengfly.tieba.post.ui.utils.imeNestedScroll
import com.huanchengfly.tieba.post.ui.widgets.compose.BaseDialog
import com.huanchengfly.tieba.post.ui.widgets.compose.BaseTextField
import com.huanchengfly.tieba.post.ui.widgets.compose.Dialog
import com.huanchengfly.tieba.post.ui.widgets.compose.DialogNegativeButton
import com.huanchengfly.tieba.post.ui.widgets.compose.DialogPositiveButton
import com.huanchengfly.tieba.post.ui.widgets.compose.DialogState
import com.huanchengfly.tieba.post.ui.widgets.compose.ReplyEmoticonPickerPanel
import com.huanchengfly.tieba.post.ui.widgets.compose.MyBackHandler
import com.huanchengfly.tieba.post.ui.widgets.compose.VerticalDivider
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState
import com.huanchengfly.tieba.post.ui.widgets.edittext.widget.UndoableEditText
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.DatabaseUtil
import com.huanchengfly.tieba.post.utils.PickMediasRequest
import com.huanchengfly.tieba.post.utils.StringUtil
import com.huanchengfly.tieba.post.utils.appPreferences
import com.huanchengfly.tieba.post.utils.hideKeyboard
import com.huanchengfly.tieba.post.utils.showKeyboard
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.spec.DestinationStyleBottomSheet
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max

data class ReplyArgs(
    val forumId: Long,
    val forumName: String,
    val threadId: Long,
    val postId: Long? = null,
    val subPostId: Long? = null,
    val replyUserId: Long? = null,
    val replyUserName: String? = null,
    val replyUserPortrait: String? = null,
    val tbs: String? = null,
)

@Composable
fun ReplyDialog(
    args: ReplyArgs,
    state: DialogState = rememberDialogState(),
) {
    BaseDialog(
        dialogState = state,
        imePadding = false,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            color = ExtendedTheme.colors.windowBackground,
            elevation = 0.dp,
        ) {
            ReplyPageContent(
                viewModel = pageViewModel(),
                onBack = { dismiss() },
                forumId = args.forumId,
                forumName = args.forumName,
                threadId = args.threadId,
                postId = args.postId,
                subPostId = args.subPostId,
                replyUserId = args.replyUserId,
                replyUserName = args.replyUserName,
                replyUserPortrait = args.replyUserPortrait,
                tbs = args.tbs,
                isDialog = true
            )
        }
    }
}

@OptIn(
    ExperimentalLayoutApi::class,
    FlowPreview::class
)
@Composable
internal fun ReplyPageContent(
    viewModel: ReplyViewModel,
    onBack: () -> Unit,
    forumId: Long,
    forumName: String,
    threadId: Long,
    postId: Long? = null,
    subPostId: Long? = null,
    replyUserId: Long? = null,
    replyUserName: String? = null,
    replyUserPortrait: String? = null,
    tbs: String? = null,
    isDialog: Boolean = false,
) {
    val hash = remember(forumId, threadId, postId, subPostId) {
        "${threadId}_${postId}_${subPostId}".toMD5()
    }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val curTbs = remember(tbs) { tbs ?: AccountUtil.getAccountInfo { this.tbs }.orEmpty() }

    val isUploading by viewModel.uiState.collectPartialAsState(
        prop1 = ReplyUiState::isUploading,
        initial = false
    )
    val isSending by viewModel.uiState.collectPartialAsState(
        prop1 = ReplyUiState::isSending,
        initial = false
    )
    val isReplying by remember { derivedStateOf { isUploading || isSending } }
    val replySuccess by viewModel.uiState.collectPartialAsState(
        prop1 = ReplyUiState::replySuccess,
        initial = false
    )
    val curKeyboardType by viewModel.uiState.collectPartialAsState(
        prop1 = ReplyUiState::replyPanelType,
        initial = NONE
    )
    val selectedImageList by viewModel.uiState.collectPartialAsState(
        prop1 = ReplyUiState::selectedImageList,
        initial = persistentListOf<String>()
    )
    val isOriginImage by viewModel.uiState.collectPartialAsState(
        prop1 = ReplyUiState::isOriginImage,
        initial = false
    )

    val replyType by viewModel.uiState.collectPartialAsState(
        prop1 = ReplyUiState::replyType,
        initial = NONE
    )
    val isTopicThread = replyType == ReplyType.TOPIC_THREAD
    val supportsNativeMeme = threadId != 0L
    //threadId为0时切换为发主题帖
    if (forumId != 0L && threadId == 0L) viewModel.send(ReplyUiIntent.SwitchReplyType(ReplyType.TOPIC_THREAD))
    val keyboardController = LocalSoftwareKeyboardController.current
    var initialText by remember { mutableStateOf("") }
    var threadTitle by remember { mutableStateOf("") }
    var waitEditTextToSet by remember { mutableStateOf(false) }
    var editTextView by remember { mutableStateOf<UndoableEditText?>(null) }
    fun getText(): String {
        return editTextView?.text?.toString().orEmpty()
    }

    fun setText(text: String) {
        val editor = editTextView
        if (editor != null) {
            editor.restoreReplyDraft(text)
        } else {
            initialText = text
            waitEditTextToSet = true
        }
    }

    fun insertToken(token: String) {
        val editor = editTextView ?: return
        val body = editor.text ?: return
        val first = editor.selectionStart.takeIf { it >= 0 } ?: body.length
        val last = editor.selectionEnd.takeIf { it >= 0 } ?: first
        var start = minOf(first, last)
        var end = maxOf(first, last)
        for (span in body.getSpans(start, end, ReplyMemeSpan::class.java)) {
            val spanStart = body.getSpanStart(span)
            val spanEnd = body.getSpanEnd(span)
            if (spanStart < start && start < spanEnd) start = spanStart
            if (spanStart < end && end < spanEnd) end = spanEnd
        }
        body.replace(start, end, token)
        editor.setSelection(start + token.length)
    }

    val curTextFlow = remember { MutableStateFlow("") }
    val curText by curTextFlow.collectAsState()
    LaunchedEffect(Unit) {
        val draft = DatabaseUtil.getDraft(hash)
        if (draft != null) {
            setText(draft.content)
        }
        curTextFlow
            .sample(500)
            .distinctUntilChanged()
            .collect {
                Log.i("ReplyPage", "collect: $it")
                if (!replySuccess) {
                    DatabaseUtil.saveDraft(hash, it)
                }
            }
    }
    val textLength by remember { derivedStateOf { curText.length } }
    val isTextEmpty by remember { derivedStateOf { curText.isEmpty() } }
    var topTitle = when (replyType) {
        ReplyType.TOPIC_THREAD -> context.getString(R.string.title_thread)
        else -> context.getString(R.string.title_reply)
    }
    LaunchedEffect(replyType) {
        topTitle = when (replyType) {
            ReplyType.TOPIC_THREAD -> context.getString(R.string.title_thread)
            else -> context.getString(R.string.title_reply)
        }
    }
    LaunchedEffect(replyType, editTextView) {
        editTextView?.hint = when {
            replyType == ReplyType.TOPIC_THREAD -> context.getString(R.string.tip_thread_content)
            subPostId != null && subPostId != 0L && replyUserName != null ->
                context.getString(R.string.hint_reply, replyUserName)
            else -> context.getString(R.string.tip_reply)
        }
    }
    viewModel.onEvent<ReplyUiEvent.ReplySuccess> {
        if (it.expInc.isEmpty()) {
            context.toastShort(R.string.toast_add_thread_success_default)
        } else {
            context.toastShort(R.string.toast_reply_success, it.expInc)
        }
        coroutineScope.launch {
            DatabaseUtil.deleteDraft(hash)
            onBack()
        }
    }

    var submitLocked by remember { mutableStateOf(false) }
    fun validatedReplyText(): String? {
        val text = getText()
        if (supportsNativeMeme && text.hasInvalidReplyMemeMarker()) {
            submitLocked = false
            context.toastShort("贴纸标记已损坏，请撤销修改或删除后重新插入")
            return null
        }
        return text
    }

    // 附件上传成功即发送；submitLocked 在上传与发送全过程保持按钮禁用。
    viewModel.onEvent<ReplyUiEvent.AttachmentsReady> { event ->
        // 附件来自点击发送时的快照；正文取上传完成时的最新可编辑内容。
        val text = validatedReplyText() ?: return@onEvent
        viewModel.send(
            ReplyUiIntent.Send(
                buildReplySendContent(text, event.localPictures, subPostId, replyUserPortrait, replyUserName),
                forumId,
                forumName,
                threadId,
                curTbs,
                title = threadTitle.takeIf { isTopicThread },
                postId,
                subPostId,
                replyUserId,
            )
        )
    }

    viewModel.onEvent<ReplyUiEvent.AttachmentsFailed> {
        submitLocked = false
        context.toastShort(R.string.toast_upload_image_failed, it.errorMessage)
    }

    viewModel.onEvent<ReplyUiEvent.SendFailed> {
        submitLocked = false
        context.toastShort(R.string.toast_reply_failed, it.errorCode, it.errorMessage)
    }

    var closingPanel by remember { mutableStateOf(false) }
    var startClosingAnimation by remember { mutableStateOf(false) }

    fun showKeyboard() {
        editTextView?.post {
            editTextView?.requestFocus()
            showKeyboard(context, editTextView!!)
        }
        keyboardController?.show()
    }

    fun hideKeyboard() {
        editTextView?.apply {
            hideKeyboard(context, this)
            clearFocus()
        }
        keyboardController?.hide()
    }

    fun switchToPanel(type: ReplyPanelType, showKeyboardAfterClose: Boolean = false) {
        if (curKeyboardType == type || type == NONE) {
            if (curKeyboardType != NONE) {
                if (showKeyboardAfterClose) {
                    showKeyboard()
                    closingPanel = true
                    startClosingAnimation = false
                } else {
                    closingPanel = false
                    startClosingAnimation = false
                    hideKeyboard()
                }
            }
            viewModel.send(ReplyUiIntent.SwitchPanel(NONE))
        } else {
            hideKeyboard()
            viewModel.send(ReplyUiIntent.SwitchPanel(type))
        }
    }


    MyBackHandler(
        enabled = curKeyboardType != NONE,
        currentScreen = ReplyPageDestination.takeUnless { isDialog }
    ) {
        switchToPanel(NONE)
    }

    val density = LocalDensity.current
    val imeInset = WindowInsets.ime
    val imeAnimationTargetInset = WindowInsets.imeAnimationTarget

    val imeCurrentHeight by produceState(initialValue = 0, imeInset, density) {
        snapshotFlow { imeInset.getBottom(density) }
            .distinctUntilChanged()
            .collect { value = it }
    }
    val imeAnimationTargetHeight by produceState(
        initialValue = 0,
        imeAnimationTargetInset,
        density
    ) {
        snapshotFlow { imeAnimationTargetInset.getBottom(density) }
            .distinctUntilChanged()
            .collect { value = it }
    }
    val imeAnimationEnd by remember { derivedStateOf { imeCurrentHeight == imeAnimationTargetHeight } }
    val imeVisibleHeightPx by produceState(
        initialValue = remember { context.appPreferences.imeHeight },
        imeAnimationTargetInset,
        density
    ) {
        snapshotFlow { imeAnimationTargetInset.getBottom(density) }
            .filter { it > 0 }
            .distinctUntilChanged()
            .collect {
                context.appPreferences.imeHeight = it
                value = it
            }
    }

    val panelHeight by remember {
        derivedStateOf {
            max(
                if (curKeyboardType != NONE) imeVisibleHeightPx.pxToDpFloat() else 0f,
                150f
            ).dp
        }
    }

    val textMeasurer = rememberTextMeasurer()

    val minResult = textMeasurer.measure(
        AnnotatedString("\n\n"),
        style = LocalTextStyle.current
    ).size.height.pxToDpFloat().dp

    val maxResult = textMeasurer.measure(
        AnnotatedString("\n\n\n\n\n"),
        style = LocalTextStyle.current
    ).size.height.pxToDpFloat().dp

    LaunchedEffect(closingPanel, imeAnimationEnd) {
        if (closingPanel) {
            if (!startClosingAnimation && !imeAnimationEnd) {
                startClosingAnimation = true
            } else if (startClosingAnimation && imeAnimationEnd) {
                closingPanel = false
                startClosingAnimation = false
            }
        }
    }

    val totalAttachmentCount = selectedImageList.size
    val canSend = !isReplying && !submitLocked &&
        (!isTextEmpty || totalAttachmentCount > 0)

    val textFieldScrollState = rememberScrollState()

    val parentModifier = if (curKeyboardType == NONE && !closingPanel) {
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .consumeWindowInsets(WindowInsets.ime)
    } else {
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .consumeWindowInsets(WindowInsets.ime)
    }

    Column(
        modifier = parentModifier
//            .onFirstVisible() {
//                if (editTextView != null) {
//                    showKeyboard()
//                }
//            }
            .onVisibilityChanged { visibility ->
                if (
                    curKeyboardType == NONE &&
                    !closingPanel &&
                    editTextView?.hasFocus() == true &&
                    imeAnimationEnd &&
                    visibility &&
                    imeAnimationTargetHeight == 0
                ) {
                    showKeyboard()
                }
                if (
                    curKeyboardType == NONE &&
                    !closingPanel &&
                    !visibility &&
                    imeAnimationTargetHeight > 10
                ) {
                    hideKeyboard()
                }
            }
            .padding(bottom = with(density) {
                if (curKeyboardType == NONE && !closingPanel) {
                    imeAnimationTargetHeight.toDp()
                } else {
                    0.dp
                }
            }),
        verticalArrangement = Arrangement.Bottom
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$topTitle",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$textLength",
                style = MaterialTheme.typography.caption,
                color = ExtendedTheme.colors.textSecondary
            )
        }
        VerticalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        if (isTopicThread) {
            BaseTextField(
                value = threadTitle,
                onValueChange = { if (it.length <= 31) threadTitle = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.subtitle1.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                placeholder = {
                    Text(
                        text = stringResource(id = R.string.hint_thread_title),
                        style = MaterialTheme.typography.subtitle1.copy(fontSize = 15.sp),
                        color = ExtendedTheme.colors.textSecondary,
                    )
                },
            )
            VerticalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
        Box(
            modifier = Modifier
                .wrapContentHeight()
                .fillMaxWidth()
                .imeNestedScroll(textFieldScrollState),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .requiredHeightIn(min = minResult, max = maxResult)
                    .verticalScroll(textFieldScrollState)
            ) {
                AndroidView(
                    factory = { ctx ->
                        (View.inflate(
                            ctx,
                            R.layout.layout_reply_edit_text,
                            null
                        ) as UndoableEditText).apply {
                            editTextView = this
                            hint = when {
                                isTopicThread -> ctx.getString(R.string.tip_thread_content)
                                subPostId != null && subPostId != 0L && replyUserName != null ->
                                    ctx.getString(R.string.hint_reply, replyUserName)
                                else -> ctx.getString(R.string.tip_reply)
                            }
                            setOnFocusChangeListener { _, hasFocus ->
                                if (hasFocus) {
                                    switchToPanel(NONE, showKeyboardAfterClose = true)
                                }
                            }
                            installReplyUndoActions()
                            addTextChangedListener(afterTextChanged = { body ->
                                if (body != null) renderReplyTokenSpans(body, supportsNativeMeme)
                                curTextFlow.value = body?.toString().orEmpty()
                            })
                            if (waitEditTextToSet) {
                                waitEditTextToSet = false
                                restoreReplyDraft(initialText)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Top)
                )
            }
//            BaseTextField(
//                value = text,
//                onValueChange = { text = it },
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .padding(16.dp)
//                    .requiredHeightIn(min = minResult, max = maxResult)
//                    .focusRequester(focusRequester)
//                    .verticalScroll(textFieldScrollState)
//                    .onFocusChanged {
//                        Log.i("ReplyPage", "onFocusChanged: $it")
//                        if (it.hasFocus) {
//                            switchToKeyboard(NONE)
//                        }
//                    },
//                placeholder = { Text(text = stringResource(id = R.string.tip_reply)) },
//            )
        }
        if (totalAttachmentCount > 0) {
            ReplyAttachmentStrip(
                imageUris = selectedImageList,
                onRemoveImage = { index ->
                    if (!isReplying) viewModel.send(ReplyUiIntent.RemoveImage(index))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            IconButton(
                onClick = { switchToPanel(EMOJI) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.EmojiEmotions,
                    contentDescription = stringResource(id = R.string.insert_emotions),
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(
                onClick = { switchToPanel(IMAGE) },
                modifier = Modifier.size(24.dp)
            ) {
                BadgedBox(
                    badge = {
                        if (totalAttachmentCount > 0) {
                            Badge(
                                backgroundColor = ExtendedTheme.colors.accent,
                                contentColor = ExtendedTheme.colors.background,
                            ) {
                                Text(
                                    text = "$totalAttachmentCount",
                                    style = MaterialTheme.typography.caption
                                )
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.InsertPhoto,
                        contentDescription = stringResource(id = R.string.insert_photo),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
//            IconButton(
//                onClick = { switchToPanel(VOICE) },
//                modifier = Modifier.size(24.dp)
//            ) {
//                Icon(
//                    imageVector = Icons.Outlined.KeyboardVoice,
//                    contentDescription = stringResource(id = R.string.insert_voice),
//                    modifier = Modifier.size(24.dp)
//                )
//            }
            Spacer(modifier = Modifier.weight(1f))
            if (isReplying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = ExtendedTheme.colors.primary
                )
            } else {
                IconButton(
                    onClick = {
                        if (isReplying || submitLocked) return@IconButton
                        val text = validatedReplyText() ?: return@IconButton
                        submitLocked = true
                        // 快照附件，避免上传期间删除或新增导致附件与计数错乱。
                        val imageSnapshot = selectedImageList.toList()
                        if (imageSnapshot.isEmpty()) {
                            viewModel.send(
                                ReplyUiIntent.Send(
                                    content = buildReplySendContent(
                                        text, subPostId = subPostId,
                                        replyUserPortrait = replyUserPortrait, replyUserName = replyUserName,
                                    ),
                                    forumId = forumId,
                                    forumName = forumName,
                                    threadId = threadId,
                                    tbs = curTbs,
                                    title = threadTitle.takeIf { isTopicThread },
                                    postId = postId,
                                    subPostId = subPostId,
                                    replyUserId = replyUserId
                                )
                            )
                        } else {
                            viewModel.send(
                                ReplyUiIntent.UploadAttachments(
                                    forumName = forumName,
                                    imageUris = imageSnapshot,
                                    isOriginImage = isOriginImage,
                                )
                            )
                        }
                    },
                    enabled = canSend,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = stringResource(id = R.string.send_reply),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
//        Spacer(
//            modifier = Modifier
//                .fillMaxWidth()
//                .windowInsetsPadding(WindowInsets.ime)
//        )
        if (curKeyboardType != NONE) {
            Column(modifier = Modifier.height(panelHeight)) {
                when (curKeyboardType) {
                    EMOJI -> {
                        ReplyEmoticonPickerPanel(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp),
                            onEmoticonClick = { emoticon ->
                                insertToken("#(${emoticon.name})")
                            },
                            onCloudMemeClick = if (supportsNativeMeme) { meme ->
                                val marker = meme.toReplyMarkerOrNull()
                                if (marker == null) context.toastShort("此贴纸缺少发送信息，请刷新后重试")
                                else insertToken(marker)
                            } else null,
                        )
                    }

                    IMAGE -> {
                        ImagePanel(
                            selectedImages = selectedImageList,
                            remainingCount = (REPLY_MAX_ATTACHMENT_COUNT - totalAttachmentCount)
                                .coerceAtLeast(0),
                            onNewImageSelected = { uris ->
                                if (!isReplying) {
                                    viewModel.send(ReplyUiIntent.AddImage(uris.map { it.toString() }))
                                }
                            },
                            onRemoveImage = {
                                if (!isReplying) viewModel.send(ReplyUiIntent.RemoveImage(it))
                            },
                            isOriginImage = isOriginImage,
                            onIsOriginImageChange = {
                                viewModel.send(ReplyUiIntent.ToggleIsOriginImage(it))
                            },
                            modifier = Modifier
                                .padding(16.dp, 8.dp)
                                .fillMaxSize(),
                        )
                    }

                    else -> {}
                }
            }
        } else if (closingPanel) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(panelHeight)
            )
        }
    }

//    DisposableEffect(Unit) {
//        if (editTextView != null) {
//            showKeyboard()
//        }
//
//        onDispose {
//            if (editTextView != null) {
//                hideKeyboard()
//            }
//        }
//    }

    fun getDispatchUri(): Uri {
        return if (postId != null) {
            Uri.parse("com.baidu.tieba://unidispatch/pb?obj_locate=comment_lzl_cut_guide&obj_source=wise&obj_name=index&obj_param2=chrome&has_token=0&qd=scheme&refer=tieba.baidu.com&wise_sample_id=3000232_2&hightlight_anchor_pid=${postId}&is_anchor_to_comment=1&comment_sort_type=0&fr=bpush&tid=${threadId}")
        } else {
            Uri.parse("com.baidu.tieba://unidispatch/pb?obj_locate=pb_reply&obj_source=wise&obj_name=index&obj_param2=chrome&has_token=0&qd=scheme&refer=tieba.baidu.com&wise_sample_id=3000232_2-99999_9&fr=bpush&tid=${threadId}")
        }
    }

    fun launchOfficialApp() {
        val intent = Intent(Intent.ACTION_VIEW).setData(getDispatchUri())
        val resolveInfos =
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            ).filter { it.activityInfo.packageName != context.packageName }
        try {
            if (resolveInfos.isNotEmpty()) {
                context.startActivity(intent)
            } else {
                context.toastShort(R.string.toast_official_client_not_install)
            }
        } catch (e: ActivityNotFoundException) {
            context.toastShort(R.string.toast_official_client_not_install)
        }
    }

    val warningDialogState = rememberDialogState()
    Dialog(
        dialogState = warningDialogState,
        title = { Text(text = stringResource(id = R.string.title_dialog_reply_warning)) },
        buttons = {
            DialogPositiveButton(
                text = stringResource(id = R.string.button_official_client_reply),
                onClick = { launchOfficialApp() }
            )
            DialogNegativeButton(text = stringResource(id = R.string.btn_continue_reply))
            DialogNegativeButton(
                text = stringResource(id = R.string.btn_cancel_reply),
                onClick = { onBack() }
            )
        },
    ) {
        Text(
            text = stringResource(id = R.string.message_dialog_reply_warning),
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }

    LaunchedEffect(Unit) {
        if (context.appPreferences.postOrReplyWarning) {
            warningDialogState.show()
        }
    }
}

// TODO: 将软键盘状态相关逻辑抽离出来
@Destination(style = DestinationStyleBottomSheet::class)
@Composable
fun ReplyPage(
    navigator: DestinationsNavigator,
    forumId: Long,
    forumName: String,
    threadId: Long,
    postId: Long? = null,
    subPostId: Long? = null,
    replyUserId: Long? = null,
    replyUserName: String? = null,
    replyUserPortrait: String? = null,
    tbs: String? = null,
    viewModel: ReplyViewModel = pageViewModel(),
) {
    ReplyPageContent(
        viewModel = viewModel,
        onBack = { navigator.navigateUp() },
        forumId = forumId,
        forumName = forumName,
        threadId = threadId,
        postId = postId,
        subPostId = subPostId,
        replyUserId = replyUserId,
        replyUserName = replyUserName,
        replyUserPortrait = replyUserPortrait,
        tbs = tbs
    )
}

@Composable
private fun ReplyAttachmentStrip(
    imageUris: ImmutableList<String>,
    onRemoveImage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(imageUris, key = { index, uri -> "local_${index}_$uri" }) { index, imageUri ->
            Box(modifier = Modifier.size(64.dp)) {
                AsyncImage(
                    imageUri = imageUri,
                    contentDescription = stringResource(id = R.string.desc_image),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(
                    onClick = { onRemoveImage(index) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(id = R.string.desc_remove_image),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

private fun UndoableEditText.restoreReplyDraft(draft: String) {
    mgr.disable()
    try {
        setText(draft)
        setSelection(draft.length)
    } finally {
        mgr.enable()
    }
}

private fun UndoableEditText.installReplyUndoActions() {
    TextViewCompat.setCustomSelectionActionModeCallback(this, object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_undoable_edit_text, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.findItem(R.id.action_undo)?.isEnabled = canUndo()
            menu.findItem(R.id.action_redo)?.isEnabled = canRedo()
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val handled = when (item.itemId) {
                R.id.action_undo -> undo()
                R.id.action_redo -> redo()
                else -> return false
            }
            if (handled) mode.finish()
            return handled
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
    })
}

// 只修改显示 Span。恢复草稿、编辑、撤销和重做都从原始正文重建。
private fun UndoableEditText.renderReplyTokenSpans(body: Editable, supportsNativeMeme: Boolean) {
    body.getSpans(0, body.length, EmoticonSpanV2::class.java).forEach { body.removeSpan(it) }
    body.getSpans(0, body.length, ReplyMemeSpan::class.java).forEach { body.removeSpan(it) }
    val source = body.toString()
    var offset = 0
    fun renderClassicUntil(end: Int) {
        if (end > offset) {
            val classic = StringUtil.getEmoticonContent(this, source.substring(offset, end))
            TextUtils.copySpansFrom(classic, 0, classic.length, EmoticonSpanV2::class.java, body, offset)
        }
    }
    if (supportsNativeMeme) {
        replyMemeMarker.findAll(source).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            renderClassicUntil(start)
            body.setSpan(ReplyMemeSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            offset = end
        }
    }
    renderClassicUntil(source.length)
}

private class ReplyMemeSpan : ReplacementSpan() {
    private val label = "[贴纸]"

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        kotlin.math.ceil(paint.measureText(label)).toInt()

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float,
        top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        canvas.drawText(label, x, y.toFloat(), paint)
    }
}

@Composable
private fun ImagePanel(
    selectedImages: ImmutableList<String>,
    remainingCount: Int,
    onNewImageSelected: (List<Uri>) -> Unit,
    onRemoveImage: (Int) -> Unit,
    isOriginImage: Boolean,
    onIsOriginImageChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val id = remember { UUID.randomUUID().toString() }
    val coroutineScope = rememberCoroutineScope()
    onGlobalEvent<GlobalEvent.SelectedImages>(
        coroutineScope,
        filter = { it.id == id }
    ) {
        onNewImageSelected(it.images)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Top
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxHeight(0.5f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
//            item {
//                Spacer(modifier = Modifier.width(16.dp))
//            }
            itemsIndexed(selectedImages) { index, imageUri ->
                Box {
                    AsyncImage(
                        imageUri = imageUri,
                        contentDescription = stringResource(id = R.string.desc_image),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                    )
                    IconButton(
                        onClick = { onRemoveImage(index) },
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(id = R.string.desc_remove_image)
                        )
                    }
                }
            }
            if (remainingCount > 0) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .background(ExtendedTheme.colors.chip)
                            .clickable {
                                coroutineScope.emitGlobalEvent(
                                    GlobalEvent.StartSelectImages(
                                        id,
                                        remainingCount,
                                        PickMediasRequest.ImageOnly
                                    )
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(id = R.string.desc_add_image),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .padding(vertical = 16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onIsOriginImageChange(!isOriginImage)
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = isOriginImage, onCheckedChange = onIsOriginImageChange)
            Text(text = stringResource(id = R.string.origin_image))
        }
    }
}