package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.models.chat.ChatRoomMessage
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.ProvideNavigator
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.ConfirmDialog
import com.huanchengfly.tieba.post.ui.widgets.compose.LazyLoad
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState
import com.huanchengfly.tieba.post.utils.AccountUtil.LocalAccount
import com.huanchengfly.tieba.post.utils.CuidUtils
import com.huanchengfly.tieba.post.utils.Emoticon
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/** 触发加载更早消息所需的全部状态快照。 */
private data class LoadMoreSignal(
    val lastVisible: Int,
    val total: Int,
    val hasMore: Boolean,
    val loading: Boolean,
    val oldestMsgId: Long,
)

/**
 * 群聊消息页。
 *
 * UI 只做渲染，所有合并、去重、状态流转都在 ViewModel 完成。
 * 列表按 JetChat 的标准做法：reverseLayout + 倒序数据，index 0 贴屏幕底部。
 */
@Destination
@Composable
fun ChatRoomPage(
    navigator: DestinationsNavigator,
    roomId: Long,
    roomName: String = "",
    fid: Long = 0L,
    viewModel: ChatRoomViewModel = pageViewModel(),
) {
    LazyLoad(loaded = viewModel.initialized) {
        viewModel.send(ChatRoomUiIntent.Load(roomId))
        viewModel.initialized = true
    }

    val isLoading by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomUiState::isLoading,
        initial = true,
    )
    val isLoadingMore by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomUiState::isLoadingMore,
        initial = false,
    )
    val hasMore by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomUiState::hasMore,
        initial = true,
    )
    val messages by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomUiState::messages,
        initial = persistentListOf(),
    )

    val account = LocalAccount.current
    var input by remember { mutableStateOf("") }
    var showEmoticonPanel by remember { mutableStateOf(false) }
    var resendTarget by remember { mutableStateOf<ChatRoomMessage?>(null) }

    val listState = rememberLazyListState()
    // 倒序数据：index 0 是最新消息，贴在底部
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

    // 定时增量拉取：进入页面立刻刷一次，之后每 3 秒一次
    LaunchedEffect(roomId) {
        viewModel.send(ChatRoomUiIntent.Poll(roomId))
        while (true) {
            delay(3_000)
            viewModel.send(ChatRoomUiIntent.Poll(roomId))
        }
    }

    // 滑到视觉顶部时加载更早消息。
    // 把 hasMore / isLoadingMore / 消息数都纳入 snapshotFlow 的输入，
    // 这样加载完成后若仍停在顶部会自动继续取下一页，不会出现"划到顶不加载"。
    LaunchedEffect(Unit) {
        snapshotFlow {
            val info = listState.layoutInfo
            LoadMoreSignal(
                lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0,
                total = info.totalItemsCount,
                hasMore = hasMore,
                loading = isLoadingMore,
                oldestMsgId = messages.firstOrNull()?.msgId ?: 0L,
            )
        }
            .distinctUntilChanged()
            .collect { signal ->
                if (!signal.hasMore || signal.loading) return@collect
                if (signal.total > 0 && signal.lastVisible >= signal.total - 2 &&
                    signal.oldestMsgId != 0L
                ) {
                    viewModel.send(ChatRoomUiIntent.LoadMore(roomId, signal.oldestMsgId))
                }
            }
    }

    val resendDialogState = rememberDialogState()
    ConfirmDialog(
        dialogState = resendDialogState,
        onConfirm = {
            val target = resendTarget
            val acc = account
            if (target != null && acc != null) {
                viewModel.send(
                    ChatRoomUiIntent.Resend(
                        roomId = roomId,
                        failedMsgId = target.msgId,
                        text = target.text,
                        forumId = fid,
                        bduss = acc.bduss,
                        selfName = acc.nameShow ?: acc.name,
                        selfPortrait = acc.portrait,
                        selfUid = acc.uid.toLongOrNull() ?: 0L,
                        cuidGalaxy2 = CuidUtils.getNewCuid(),
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
                        Text(text = roomName.ifBlank { stringResource(id = R.string.title_chat_rooms) })
                    },
                    navigationIcon = { BackNavigationIcon { navigator.navigateUp() } },
                )
            },
            bottomBar = {
                if (account != null) {
                    ChatComposer(
                        value = input,
                        onValueChange = { input = it },
                        onSend = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                viewModel.send(
                                    ChatRoomUiIntent.Send(
                                        roomId = roomId,
                                        text = text,
                                        forumId = fid,
                                        bduss = account.bduss,
                                        selfName = account.nameShow ?: account.name,
                                        selfPortrait = account.portrait,
                                        selfUid = account.uid.toLongOrNull() ?: 0L,
                                        cuidGalaxy2 = CuidUtils.getNewCuid(),
                                    )
                                )
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
                }
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
                        key = { "${it.msgId}_${it.senderUid}" },
                    ) { message ->
                        ChatMessageRow(
                            text = message.text,
                            isSelf = message.isSelf,
                            senderName = message.senderName,
                            avatarUrl = message.senderPortrait,
                            msgTime = message.msgTime,
                            sendState = message.sendState,
                            onRetryClick = {
                                resendTarget = message
                                resendDialogState.show()
                            },
                        )
                    }
                }

                if (isLoadingMore) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .size(24.dp),
                        color = ExtendedTheme.colors.primary,
                    )
                }

                when {
                    isLoading && messages.isEmpty() -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = ExtendedTheme.colors.primary,
                    )

                    !isLoading && messages.isEmpty() -> Text(
                        text = stringResource(id = R.string.tip_chat_room_empty),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.body2,
                        color = ExtendedTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}
