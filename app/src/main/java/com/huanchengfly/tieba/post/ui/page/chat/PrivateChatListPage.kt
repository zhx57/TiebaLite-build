package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.LocalNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.PrivateChatPageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.Container
import com.huanchengfly.tieba.post.ui.widgets.compose.LazyLoad
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.debounceClickable
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.DateTimeUtils
import com.huanchengfly.tieba.post.utils.StringUtil
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay

/**
 * 私信会话列表页。
 */
@Composable
fun PrivateChatListPage(
    viewModel: PrivateChatListViewModel = pageViewModel(),
) {
    val cookie = remember { AccountUtil.getCookie().orEmpty() }
    LazyLoad(loaded = viewModel.initialized) {
        viewModel.send(PrivateChatListUiIntent.Load(cookie))
        viewModel.initialized = true
    }
    val navigator = LocalNavigator.current
    val sessions by viewModel.uiState.collectPartialAsState(
        prop1 = PrivateChatListUiState::sessions,
        initial = persistentListOf(),
    )
    val lazyListState = rememberLazyListState()

    // 页面存活期间定时静默刷新会话列表，收到新私信时也会即时刷新
    LaunchedEffect(cookie) {
        while (true) {
            delay(5_000)
            viewModel.send(PrivateChatListUiIntent.Poll(cookie))
        }
    }

    MyLazyColumn(
        contentPadding = PaddingValues(vertical = 4.dp),
        state = lazyListState,
    ) {
        items(items = sessions, key = { it.peerUid }) { session ->
            Container {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .debounceClickable(onClick = {
                            navigator.navigate(
                                PrivateChatPageDestination(
                                    peerUid = session.peerUid,
                                    peerName = session.peerName,
                                    peerPortrait = session.peerPortrait,
                                )
                            )
                        })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(
                        data = StringUtil.getAvatarUrl(session.peerPortrait),
                        size = Sizes.Medium,
                        contentDescription = null,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = session.peerName,
                            style = MaterialTheme.typography.subtitle1,
                            color = ExtendedTheme.colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = session.lastMessage,
                            style = MaterialTheme.typography.caption,
                            color = ExtendedTheme.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (session.lastTime > 0) {
                        Text(
                            text = DateTimeUtils.getRelativeTimeString(
                                LocalContext.current,
                                session.lastTime
                            ),
                            style = MaterialTheme.typography.caption,
                            color = ExtendedTheme.colors.textSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}
