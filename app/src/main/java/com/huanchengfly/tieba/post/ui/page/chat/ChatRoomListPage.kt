package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.common.theme.compose.pullRefreshIndicator
import com.huanchengfly.tieba.post.ui.page.ProvideNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.ChatRoomPageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.Container
import com.huanchengfly.tieba.post.ui.widgets.compose.LazyLoad
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.ui.widgets.compose.debounceClickable
import com.huanchengfly.tieba.post.utils.StringUtil
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.collections.immutable.persistentListOf

/**
 * 吧群列表页：展示指定贴吧下的所有群聊房间。
 */
@OptIn(ExperimentalMaterialApi::class)
@Destination
@Composable
fun ChatRoomListPage(
    navigator: DestinationsNavigator,
    fid: Long,
    forumName: String = "",
    viewModel: ChatRoomListViewModel = pageViewModel(),
) {
    LazyLoad(loaded = viewModel.initialized) {
        viewModel.send(ChatRoomListUiIntent.Load(fid))
        viewModel.initialized = true
    }
    val isLoading by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomListUiState::isLoading,
        initial = true,
    )
    val rooms by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomListUiState::rooms,
        initial = persistentListOf(),
    )
    val error by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomListUiState::error,
        initial = null,
    )
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isLoading,
        onRefresh = { viewModel.send(ChatRoomListUiIntent.Load(fid)) },
    )
    val lazyListState = rememberLazyListState()

    ProvideNavigator(navigator = navigator) {
        MyScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TitleCentredToolbar(
                title = {
                    Text(text = stringResource(id = R.string.title_chat_rooms))
                },
                navigationIcon = {
                    BackNavigationIcon { navigator.navigateUp() }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pullRefresh(pullRefreshState)
        ) {
            MyLazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                state = lazyListState,
            ) {
                items(items = rooms, key = { it.roomId }) { room ->
                    Container {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .debounceClickable(onClick = {
                                    navigator.navigate(
                                        ChatRoomPageDestination(
                                            roomId = room.roomId,
                                            roomName = room.name.ifBlank { forumName },
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
                                )
                                if (room.announce.isNotBlank()) {
                                    Text(
                                        text = room.announce,
                                        style = MaterialTheme.typography.caption,
                                        color = ExtendedTheme.colors.textSecondary,
                                        maxLines = 1,
                                    )
                                }
                            }
                            if (room.discussNum > 0) {
                                Text(
                                    text = stringResource(
                                        id = R.string.text_chat_room_member_count,
                                        room.discussNum
                                    ),
                                    style = MaterialTheme.typography.caption,
                                    color = ExtendedTheme.colors.textSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }

            PullRefreshIndicator(
                refreshing = isLoading,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = ExtendedTheme.colors.pullRefreshIndicator,
                contentColor = ExtendedTheme.colors.primary,
            )

            if (rooms.isEmpty() && !isLoading) {
                Text(
                    // 加载失败时直接展示错误信息，便于定位是哪个吧、什么错误码
                    text = error?.message ?: stringResource(id = R.string.tip_chat_room_list_empty),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.body2,
                    color = ExtendedTheme.colors.textSecondary,
                )
            }
        }
    }
    }
}
