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
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.LocalNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.ChatRoomListPageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.Container
import com.huanchengfly.tieba.post.ui.widgets.compose.LazyLoad
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.debounceClickable
import com.huanchengfly.tieba.post.utils.StringUtil
import kotlinx.collections.immutable.persistentListOf

/**
 * 消息页的群聊 Tab：列出当前账号关注的贴吧，点击进入该吧的群聊列表。
 *
 * 群列表接口需要 fid，所以必须先选贴吧，这里以关注吧作为入口。
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
    val forums by viewModel.uiState.collectPartialAsState(
        prop1 = ChatEntryUiState::forums,
        initial = persistentListOf(),
    )
    val lazyListState = rememberLazyListState()

    MyLazyColumn(
        contentPadding = PaddingValues(vertical = 4.dp),
        state = lazyListState,
    ) {
        items(items = forums, key = { it.forumId }) { forum ->
            Container {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .debounceClickable(onClick = {
                            val fid = forum.forumId.toLongOrNull() ?: 0L
                            if (fid != 0L) {
                                navigator.navigate(
                                    ChatRoomListPageDestination(
                                        fid = fid,
                                        forumName = forum.forumName,
                                    )
                                )
                            }
                        })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(
                        data = StringUtil.getAvatarUrl(forum.avatar),
                        size = Sizes.Medium,
                        contentDescription = null,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = forum.forumName,
                            style = MaterialTheme.typography.subtitle1,
                            color = ExtendedTheme.colors.text,
                        )
                        Text(
                            text = "Lv.${forum.userLevel}",
                            style = MaterialTheme.typography.caption,
                            color = ExtendedTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}
