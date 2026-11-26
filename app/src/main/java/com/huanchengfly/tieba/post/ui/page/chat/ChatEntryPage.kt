package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.LocalNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.ChatRoomPageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.Container
import com.huanchengfly.tieba.post.ui.widgets.compose.LazyLoad
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.debounceClickable
import com.huanchengfly.tieba.post.utils.StringUtil
import kotlinx.collections.immutable.persistentListOf

/**
 * 消息页的群聊 Tab：直接列出用户加入的全部吧群。
 *
 * 官方消息中心就是这个形态，用 c/u/chat/getChatSubscriptionList 一次拉全量，
 * 不需要先选贴吧再按 fid 拉一层。
 */
@Composable
fun ChatEntryPage(
    viewModel: ChatEntryViewModel = pageViewModel(),
) {
    LazyLoad(loaded = viewModel.initialized) {
        viewModel.send(ChatEntryUiIntent.Load)
        viewModel.initialized = true
    }
    val navigator = LocalNavigator.current
    val rooms by viewModel.uiState.collectPartialAsState(
        prop1 = ChatEntryUiState::rooms,
        initial = persistentListOf(),
    )
    val lazyListState = rememberLazyListState()

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
}
