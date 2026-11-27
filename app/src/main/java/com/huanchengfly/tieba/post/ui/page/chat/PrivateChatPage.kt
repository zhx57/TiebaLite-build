package com.huanchengfly.tieba.post.ui.page.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.clickable
import com.huanchengfly.tieba.post.api.models.chat.SendState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.utils.rememberChatImagePicker
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.ProvideNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.UserProfilePageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.ConfirmDialog
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState
import com.huanchengfly.tieba.post.utils.Emoticon
import com.huanchengfly.tieba.post.utils.AccountUtil.LocalAccount
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay

/**
 * 私信聊天页。
 *
 * 与群聊页共用消息行和输入栏组件，列表同样使用 reverseLayout + 倒序数据。
 */
@Destination
@Composable
fun PrivateChatPage(
    navigator: DestinationsNavigator,
    peerUid: Long,
    peerName: String = "",
    peerPortrait: String = "",
    viewModel: PrivateChatViewModel = pageViewModel(),
) {
    val context = LocalContext.current
    val accountUid = LocalAccount.current?.uid?.toLongOrNull() ?: 0L
    val stateAccountUid by viewModel.uiState.collectPartialAsState(
        prop1 = PrivateChatUiState::accountUid, initial = 0L,
    )
    var photoAccountUid by remember { mutableStateOf(0L) }
    val pickPhoto = rememberChatImagePicker { result ->
        result?.let { uri ->
            if (photoAccountUid == accountUid && accountUid > 0L &&
                com.huanchengfly.tieba.post.utils.AccountUtil.getUid()?.toLongOrNull() == accountUid
            ) viewModel.send(PrivateChatUiIntent.Send(peerUid, "", photoUri = uri, accountUid = accountUid))
        }
    }
    val isLoading by viewModel.uiState.collectPartialAsState(
        prop1 = PrivateChatUiState::isLoading,
        initial = true,
    )
    val storedMessages by viewModel.uiState.collectPartialAsState(
        prop1 = PrivateChatUiState::messages,
        initial = persistentListOf(),
    )

    val messages = if (stateAccountUid == accountUid && accountUid > 0L) storedMessages else persistentListOf()

    var drafts by rememberSaveable { mutableStateOf(hashMapOf<String, String>()) }
    val draftKey = "$accountUid:$peerUid"
    var input by rememberSaveable(peerUid, accountUid, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(drafts[draftKey].orEmpty()))
    }
    var draftId by rememberSaveable(peerUid, accountUid) { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    fun updateInput(value: TextFieldValue) {
        input = value
        drafts = HashMap(drafts).apply { put(draftKey, value.text) }
        draftId = java.util.UUID.randomUUID().toString()
    }
    val acceptedDraftId by viewModel.uiState.collectPartialAsState(
        prop1 = PrivateChatUiState::acceptedDraftId, initial = null,
    )
    LaunchedEffect(acceptedDraftId, accountUid, stateAccountUid) {
        if (accountUid > 0L && stateAccountUid == accountUid && acceptedDraftId == draftId) {
            updateInput(TextFieldValue())
        }
    }
    var showEmoticonPanel by remember { mutableStateOf(false) }
    var resendTarget by remember(peerUid, accountUid) { mutableStateOf<ChatWsMessage?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, peerUid, accountUid) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                showEmoticonPanel = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            showEmoticonPanel = false
            viewModel.send(PrivateChatUiIntent.Stop(peerUid, accountUid))
        }
    }

    val listState = rememberLazyListState()
    val reversedMessages = remember(messages) { messages.asReversed() }
    val historyLimit by viewModel.uiState.collectPartialAsState(
        prop1 = PrivateChatUiState::historyLimit, initial = 60,
    )
    LaunchedEffect(listState, peerUid, historyLimit, messages.size) {
        snapshotFlow {
            listState.isScrollInProgress && messages.size >= historyLimit &&
                listState.layoutInfo.visibleItemsInfo.any { it.index >= messages.lastIndex }
        }.distinctUntilChanged().filter { it }.collect {
            viewModel.send(PrivateChatUiIntent.LoadMore(peerUid, historyLimit, accountUid = accountUid))
        }
    }

    val sendRequestId by viewModel.uiState.collectPartialAsState(
        prop1 = PrivateChatUiState::sendRequestId, initial = 0L,
    )
    FollowLatestChatMessage(listState, peerUid, sendRequestId)

    // 可见时通过 HTTP 每 5 秒轮询，回到页面立即补拉。
    LaunchedEffect(peerUid, accountUid, viewModel, lifecycleOwner) {
        if (accountUid <= 0L) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                viewModel.send(PrivateChatUiIntent.Load(peerUid, peerName, peerPortrait, accountUid))
                while (true) {
                    delay(5_000)
                    viewModel.send(PrivateChatUiIntent.Poll(peerUid, accountUid = accountUid))
                }
            } finally {
                viewModel.send(PrivateChatUiIntent.Stop(peerUid, accountUid))
            }
        }
    }

    val resendDialogState = rememberDialogState()
    ConfirmDialog(
        dialogState = resendDialogState,
        onConfirm = {
            resendTarget?.let { target ->
                viewModel.send(
                    PrivateChatUiIntent.Resend(
                        peerUid = peerUid,
                        failedMsgId = target.msgId,
                        content = target.content,
                        cloudMeme = target.cloudMeme,
                        photoUri = target.localPhotoUri?.takeIf { target.cloudMeme?.uploadedImageUrl == null }
                            ?.let(android.net.Uri::parse),
                        accountUid = accountUid,
                    )
                )
            }
            resendTarget = null
        },
        onCancel = { resendTarget = null },
        confirmText = stringResource(id = R.string.btn_resend),
        cancelText = stringResource(id = R.string.button_cancel),
    ) {
        Column {
            if (resendTarget?.sendState == SendState.UNKNOWN) {
                Text("发送结果未确认，对方可能已收到。继续重试可能产生重复消息。")
            }
            resendTarget?.sendError?.takeIf { it.isNotBlank() }?.let { Text(it) }
            if (resendTarget?.localPhotoUri != null && resendTarget?.cloudMeme?.uploadedImageUrl == null) {
                Text("若图片授权已失效，请取消重发并重新选图")
            }
            Text(text = stringResource(id = R.string.tip_resend_message))
        }
    }

    ProvideNavigator(navigator = navigator) {
        MyScaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TitleCentredToolbar(
                    title = {
                        Text(text = peerName.ifBlank { stringResource(id = R.string.title_private_chat) }, modifier = Modifier.padding(horizontal = 56.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    },
                    navigationIcon = { BackNavigationIcon { navigator.navigateUp() } },
                )
            },
            bottomBar = {
                ChatComposer(
                    value = input,
                    onValueChange = { updateInput(it) },
                    onSend = {
                        val text = input.text.trim()
                        if (text.isNotEmpty()) {
                            viewModel.send(PrivateChatUiIntent.Send(peerUid, text, draftId = draftId, accountUid = accountUid))
                        }
                    },
                    showEmoticonPanel = showEmoticonPanel,
                    onToggleEmoticonPanel = { showEmoticonPanel = !showEmoticonPanel },
                    onEmoticonClick = { emoticon: Emoticon ->
                        val token = "#(${emoticon.name})"
                        val selection = input.selection
                        updateInput(TextFieldValue(
                            text = input.text.replaceRange(selection.min, selection.max, token),
                            selection = TextRange(selection.min + token.length),
                            composition = null,
                        ))
                    },
                    onDismissEmoticonPanel = { showEmoticonPanel = false },
                    onPhotoClick = {
                        photoAccountUid = accountUid
                        pickPhoto()
                    },
                    onCloudMemeClick = { meme ->
                        viewModel.send(PrivateChatUiIntent.Send(peerUid, "", cloudMeme = meme, accountUid = accountUid))
                    },
                )
            },
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                MyLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = ChatListContentPadding,
                    state = listState,
                    reverseLayout = true,
                ) {
                    items(
                        items = reversedMessages,
                        key = { it.localId.ifBlank { "${it.msgId}_${it.peerUid}" } },
                    ) { message ->
                        ChatMessageRow(
                            text = message.content,
                            images = message.images,
                            isSelf = message.isSelf,
                            senderName = message.peerName.ifBlank { peerName },
                            avatarUrl = message.peerPortrait.ifBlank { peerPortrait },
                            msgTime = message.msgTime,
                            sendState = message.sendState,
                            semantics = message.semantics,
                            onRetryClick = {
                                resendTarget = message
                                resendDialogState.show()
                            },
                            onAvatarClick = {
                                if (message.peerUid > 0L) {
                                    navigateToUserProfile(navigator, message.peerUid)
                                }
                            },
                        )
                    }
                    item(key = "private_history_more") {
                        Text(
                            text = "加载更早消息",
                            modifier = Modifier.padding(16.dp).clickable {
                                viewModel.send(PrivateChatUiIntent.LoadMore(peerUid, historyLimit, accountUid = accountUid))
                            },
                        )
                    }
                }

                if (isLoading && messages.isEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = ExtendedTheme.colors.primary,
                    )
                }
            }
        }
    }
}
