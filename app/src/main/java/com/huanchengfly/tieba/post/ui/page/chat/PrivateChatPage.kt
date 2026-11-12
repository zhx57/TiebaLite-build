package com.huanchengfly.tieba.post.ui.page.chat

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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.ProvideNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.UserProfilePageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.ConfirmDialog
import com.huanchengfly.tieba.post.ui.widgets.compose.LazyLoad
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState
import com.huanchengfly.tieba.post.utils.Emoticon
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
    LazyLoad(loaded = viewModel.initialized) {
        viewModel.send(PrivateChatUiIntent.Load(peerUid))
        viewModel.initialized = true
    }

    val isLoading by viewModel.uiState.collectPartialAsState(
        prop1 = PrivateChatUiState::isLoading,
        initial = true,
    )
    val messages by viewModel.uiState.collectPartialAsState(
        prop1 = PrivateChatUiState::messages,
        initial = persistentListOf(),
    )

    var input by remember { mutableStateOf("") }
    var showEmoticonPanel by remember { mutableStateOf(false) }
    var resendTarget by remember { mutableStateOf<ChatWsMessage?>(null) }

    val listState = rememberLazyListState()
    val reversedMessages = remember(messages) { messages.asReversed() }

    // 用户是否停留在最新消息处。只在用户滑动结束时采样，
    // 避免新消息插入导致索引变化被误判为"用户上滑"。
    var followBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    followBottom = listState.firstVisibleItemIndex == 0 &&
                        listState.firstVisibleItemScrollOffset == 0
                }
            }
    }

    // 新消息到达：贴底时跟随到底部；用户正在翻历史时保持原地不跳。
    // 自己发出的消息无论当前位置都滚过去，避免发完看不到。
    val newestMsgId = messages.lastOrNull()?.msgId
    LaunchedEffect(newestMsgId) {
        val newest = messages.lastOrNull() ?: return@LaunchedEffect
        if (!followBottom && !newest.isSelf) return@LaunchedEffect
        // 用户正在拖动时不抢滚动，等下一次消息再跟随
        if (listState.isScrollInProgress) return@LaunchedEffect
        listState.animateScrollToItem(0)
    }

    // 绑定会话并注册推送：收到服务端推送立即刷新，5 秒轮询只作兜底
    LaunchedEffect(peerUid) {
        viewModel.bindPeer(peerUid)
        while (true) {
            delay(5_000)
            viewModel.send(PrivateChatUiIntent.Poll(peerUid))
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
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            viewModel.send(PrivateChatUiIntent.Send(peerUid, text))
                            input = ""
                            showEmoticonPanel = false
                        }
                    },
                    showEmoticonPanel = showEmoticonPanel,
                    onToggleEmoticonPanel = { showEmoticonPanel = !showEmoticonPanel },
                    onEmoticonClick = { emoticon: Emoticon ->
                        input += "#(${emoticon.name})"
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
                            isSelf = message.isSelf,
                            senderName = message.peerName.ifBlank { peerName },
                            avatarUrl = message.peerPortrait.ifBlank { peerPortrait },
                            msgTime = message.msgTime,
                            sendState = message.sendState,
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
