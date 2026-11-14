package com.huanchengfly.tieba.post.ui.page.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.components.uploadChatPhoto
import com.huanchengfly.tieba.post.utils.PickMediasRequest
import com.huanchengfly.tieba.post.utils.registerPickMediasLauncher
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
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
    val coroutineScope = rememberCoroutineScope()
    val photoLauncher = registerPickMediasLauncher { result ->
        result.uris.firstOrNull()?.let { uri ->
            coroutineScope.launch {
                try {
                    val cloudMeme = uploadChatPhoto(uri)
                    viewModel.send(PrivateChatUiIntent.Send(peerUid, "", cloudMeme = cloudMeme))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Toast.makeText(context, error.message ?: "图片上传失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val isLoading by viewModel.uiState.collectPartialAsState(
        prop1 = PrivateChatUiState::isLoading,
        initial = true,
    )
    val messages by viewModel.uiState.collectPartialAsState(
        prop1 = PrivateChatUiState::messages,
        initial = persistentListOf(),
    )

    var input by rememberSaveable(peerUid, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var showEmoticonPanel by remember { mutableStateOf(false) }
    var resendTarget by remember { mutableStateOf<ChatWsMessage?>(null) }

    val listState = rememberLazyListState()
    val reversedMessages = remember(messages) { messages.asReversed() }

    val sendRequestId by viewModel.uiState.collectPartialAsState(
        prop1 = PrivateChatUiState::sendRequestId, initial = 0L,
    )
    FollowLatestChatMessage(listState, peerUid, sendRequestId)

    // 可见时接收推送，回到页面立即补拉，5 秒轮询只作兜底。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(peerUid, viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.bindPeer(peerUid)
            try {
                if (!viewModel.initialized) {
                    viewModel.send(PrivateChatUiIntent.Load(peerUid))
                    viewModel.initialized = true
                } else {
                    viewModel.send(PrivateChatUiIntent.Poll(peerUid))
                }
                while (true) {
                    delay(5_000)
                    viewModel.send(PrivateChatUiIntent.Poll(peerUid))
                }
            } finally {
                viewModel.unbindPeer(peerUid)
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
                    )
                )
            }
            resendTarget = null
        },
        onCancel = { resendTarget = null },
        confirmText = stringResource(id = R.string.btn_resend),
        cancelText = stringResource(id = R.string.button_cancel),
    ) {
        Text(text = stringResource(id = R.string.tip_resend_message))
    }

    ProvideNavigator(navigator = navigator) {
        MyScaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TitleCentredToolbar(
                    title = {
                        Text(text = peerName.ifBlank { stringResource(id = R.string.title_private_chat) })
                    },
                    navigationIcon = { BackNavigationIcon { navigator.navigateUp() } },
                )
            },
            bottomBar = {
                ChatComposer(
                    value = input,
                    onValueChange = { input = it },
                    onSend = {
                        val text = input.text.trim()
                        if (text.isNotEmpty()) {
                            viewModel.send(PrivateChatUiIntent.Send(peerUid, text))
                            input = TextFieldValue()
                        }
                    },
                    showEmoticonPanel = showEmoticonPanel,
                    onToggleEmoticonPanel = { showEmoticonPanel = !showEmoticonPanel },
                    onEmoticonClick = { emoticon: Emoticon ->
                        val token = "#(${emoticon.name})"
                        val selection = input.selection
                        input = TextFieldValue(
                            text = input.text.replaceRange(selection.min, selection.max, token),
                            selection = TextRange(selection.min + token.length),
                            composition = null,
                        )
                    },
                    onDismissEmoticonPanel = { showEmoticonPanel = false },
                    onPhotoClick = {
                        photoLauncher.launch(PickMediasRequest(maxItems = 1, mediaType = PickMediasRequest.ImageOnly))
                    },
                    onCloudMemeClick = { meme ->
                        viewModel.send(PrivateChatUiIntent.Send(peerUid, "", cloudMeme = meme))
                    },
                )
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                MyLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = ChatListContentPadding,
                    state = listState,
                    reverseLayout = true,
                ) {
                    items(
                        items = reversedMessages,
                        key = { "${it.msgId}_${it.peerUid}" },
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
