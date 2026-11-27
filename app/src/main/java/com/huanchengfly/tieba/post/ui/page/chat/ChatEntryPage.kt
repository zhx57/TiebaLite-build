package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.collectAsState
import com.huanchengfly.tieba.post.ui.common.theme.compose.pullRefreshIndicator
import com.huanchengfly.tieba.post.ui.widgets.compose.TextButton
import com.huanchengfly.tieba.post.utils.AccountUtil
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.huanchengfly.tieba.post.ui.page.ProvideNavigator
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.Toolbar
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.LocalNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.ChatRoomPageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.Container
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.debounceClickable
import com.huanchengfly.tieba.post.utils.StringUtil
import kotlinx.collections.immutable.persistentListOf

@OptIn(ExperimentalMaterialApi::class)
@Destination
@Composable
fun ChatEntryPage(navigator: DestinationsNavigator) {
    ProvideNavigator(navigator) {
        MyScaffold(topBar = {
            Toolbar(title = "发现群聊", navigationIcon = { BackNavigationIcon { navigator.navigateUp() } })
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) { ChatEntryPage() }
        }
    }
}

/** 优先展示订阅群，再渐进补充全部关注贴吧的公开群。 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun ChatEntryPage(
    viewModel: ChatEntryViewModel = pageViewModel(),
) {
    val accountUid = AccountUtil.LocalAccount.current?.uid
    LaunchedEffect(accountUid) {
        viewModel.send(ChatEntryUiIntent.Load)
        viewModel.initialized = true
    }
    val navigator = LocalNavigator.current
    val state by viewModel.uiState.collectAsState()
    // Compose 账号状态变化时立即隐藏旧列表，不等待旧请求返回。
    val accountMatches = state.accountUid == accountUid
    val rooms = if (accountMatches) state.rooms else persistentListOf()
    val loading = !accountMatches || state.isLoading || !state.hasLoaded
    val refresh = { viewModel.send(ChatEntryUiIntent.Load) }
    val pullRefreshState = rememberPullRefreshState(state.isLoading, refresh)
    val lazyListState = rememberLazyListState()

    Box(Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
    MyLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        state = lazyListState,
    ) {
        if (rooms.isNotEmpty() && loading) {
            item { Text("正在加载更多关注吧群…", Modifier.padding(16.dp), color = ExtendedTheme.colors.textSecondary) }
        }
        if (accountMatches && state.error != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = "部分群列表加载失败（${state.failedCount} 项）。" +
                            (if (rooms.isNotEmpty()) "已保留可用群。" else "") + state.error?.message.orEmpty(),
                        color = ExtendedTheme.colors.textSecondary,
                    )
                    TextButton(onClick = refresh) { Text("重试") }
                }
            }
        }
        if (rooms.isEmpty() && !loading) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (state.error != null) "加载群聊列表失败，请重试"
                            else "当前服务端未返回可展示群，可下拉刷新重试",
                        color = ExtendedTheme.colors.textSecondary,
                    )
                    TextButton(onClick = refresh) { Text("重新加载") }
                }
            }
        }
        items(items = rooms, key = { it.roomId }) { room ->
            Container {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .debounceClickable(onClick = {
                            navigator.navigate(
                                ChatRoomPageDestination(
                                    roomId = room.roomId,
                                    roomName = room.name.ifBlank { room.forumName },
                                    fid = room.forumId,
                                )
                            )
                        })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(
                        data = StringUtil.getAvatarUrl(room.avatar),
                        size = Sizes.Medium,
                        contentDescription = null,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = room.name.ifBlank { room.forumName },
                            style = MaterialTheme.typography.subtitle1,
                            color = ExtendedTheme.colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (room.forumName.isNotBlank()) {
                            Text(
                                text = room.forumName,
                                style = MaterialTheme.typography.caption,
                                color = ExtendedTheme.colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
    if (rooms.isEmpty() && loading) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = ExtendedTheme.colors.primary)
            Text("正在加载群聊…", Modifier.padding(16.dp), color = ExtendedTheme.colors.textSecondary)
        }
    }
    PullRefreshIndicator(
        refreshing = state.isLoading && rooms.isNotEmpty(),
        state = pullRefreshState,
        modifier = Modifier.align(Alignment.TopCenter),
        backgroundColor = ExtendedTheme.colors.pullRefreshIndicator,
        contentColor = ExtendedTheme.colors.primary,
    )
    }
}
