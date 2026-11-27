package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.huanchengfly.tieba.post.arch.hiltViewModel
import com.huanchengfly.tieba.post.models.database.ChatConversation
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.LocalNavigator
import com.huanchengfly.tieba.post.ui.page.ProvideNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.ChatEntryPageDestination
import com.huanchengfly.tieba.post.ui.page.destinations.ChatRoomPageDestination
import com.huanchengfly.tieba.post.ui.page.destinations.PrivateChatPageDestination
import com.huanchengfly.tieba.post.ui.page.destinations.SearchPageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.Container
import com.huanchengfly.tieba.post.ui.widgets.compose.LocalShouldLoad
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.PullToRefreshLayout
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.Toolbar
import com.huanchengfly.tieba.post.ui.widgets.compose.accountNavIconIfCompact
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.DateTimeUtils
import com.huanchengfly.tieba.post.utils.StringUtil
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterialApi::class)
@Destination
@Composable
fun ConversationsPage(navigator: DestinationsNavigator) {
    ProvideNavigator(navigator = navigator) {
        ConversationsPage(showBack = true)
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun ConversationsPage(
    viewModel: ConversationsViewModel = hiltViewModel(),
    showBack: Boolean = false,
) {
    val navigator = LocalNavigator.current
    val accountUid = AccountUtil.LocalAccount.current?.uid?.toLongOrNull() ?: 0L
    val state by viewModel.state.collectAsState()
    val conversations = if (state.accountUid == accountUid) state.conversations else emptyList()
    val loading = accountUid > 0L && (state.accountUid != accountUid || state.loading)
    val error = state.error.takeIf { state.accountUid == accountUid }
    val lifecycleOwner = LocalLifecycleOwner.current
    val shouldLoad = LocalShouldLoad.current
    LaunchedEffect(accountUid) { viewModel.bindAccount(accountUid) }
    LaunchedEffect(accountUid, lifecycleOwner, shouldLoad) {
        if (!shouldLoad || accountUid <= 0L) return@LaunchedEffect
        viewModel.bindAccount(accountUid)
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refresh(accountUid)
                delay(30_000L)
            }
        }
    }
    MyScaffold(
        topBar = {
            Toolbar(
                title = "聊天",
                navigationIcon = if (showBack) {
                    { BackNavigationIcon { navigator.navigateUp() } }
                } else accountNavIconIfCompact(),
                actions = {
                    TextButton(onClick = { navigator.navigate(ChatEntryPageDestination) }) { Text("发现群聊") }
                    TextButton(onClick = { navigator.navigate(SearchPageDestination) }) { Text("找人") }
                },
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        PullToRefreshLayout(
            refreshing = state.accountUid == accountUid && state.refreshing,
            onRefresh = { viewModel.retry(accountUid) },
        ) {
            MyLazyColumn(contentPadding = PaddingValues(vertical = 4.dp), modifier = Modifier.fillMaxSize().padding(padding)) {
                if (loading && conversations.isEmpty()) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                if (error != null || (!loading && conversations.isEmpty())) {
                    item(key = "status") {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                error ?: if (accountUid <= 0L) "请先登录后查看聊天" else "暂无最近聊天，可发现群聊或搜索用户开始聊天",
                                color = ExtendedTheme.colors.textSecondary,
                            )
                            if (accountUid > 0L) {
                                TextButton(onClick = { viewModel.retry(accountUid) }) { Text("刷新") }
                            }
                        }
                    }
                }
                items(conversations, key = { "${it.kind}:${it.conversationId}" }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        onOpen = {
                            when (conversation.kind) {
                                "group" -> navigator.navigate(ChatRoomPageDestination(
                                    roomId = conversation.conversationId,
                                    roomName = conversation.name,
                                    fid = conversation.forumId,
                                ))
                                "private" -> navigator.navigate(PrivateChatPageDestination(
                                    peerUid = conversation.conversationId,
                                    peerName = conversation.name,
                                    peerPortrait = conversation.avatar,
                                ))
                            }
                        },
                        onPin = { viewModel.togglePinned(conversation) },
                        onHide = { viewModel.hide(conversation) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: ChatConversation,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onHide: () -> Unit,
) {
    var menuOpen by remember(conversation.accountUid, conversation.kind, conversation.conversationId) { mutableStateOf(false) }
    Container {
        Box {
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = onOpen, onLongClickLabel = "管理会话", onLongClick = { menuOpen = true })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(
                    data = if (conversation.avatar.startsWith("http")) conversation.avatar else StringUtil.getAvatarUrl(conversation.avatar),
                    size = Sizes.Medium,
                    contentDescription = null,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        conversation.name.ifBlank { if (conversation.kind == "group") "群聊 ${conversation.conversationId}" else conversation.conversationId.toString() },
                        style = MaterialTheme.typography.subtitle1,
                        color = ExtendedTheme.colors.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        conversation.summary,
                        style = MaterialTheme.typography.caption,
                        color = ExtendedTheme.colors.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (conversation.pinned) Text("已置顶", style = MaterialTheme.typography.caption, color = ExtendedTheme.colors.textSecondary)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (conversation.lastMessageTime > 0L) Text(
                        DateTimeUtils.getRelativeTimeString(LocalContext.current, conversation.lastMessageTime),
                        style = MaterialTheme.typography.caption, color = ExtendedTheme.colors.textSecondary,
                    )
                    if (conversation.unreadCount > 0) Text(
                        "${conversation.unreadCount}条未读",
                        style = MaterialTheme.typography.caption, color = MaterialTheme.colors.secondary,
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(onClick = { menuOpen = false; onPin() }) { Text(if (conversation.pinned) "取消置顶" else "置顶") }
                DropdownMenuItem(onClick = { menuOpen = false; onHide() }) { Text("从最近移除（保留聊天记录）") }
            }
        }
    }
}
