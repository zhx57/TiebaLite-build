package com.huanchengfly.tieba.post.ui.page.chat

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.services.NotifyJobService
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.LocalNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.PrivateChatPageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.Container
import com.huanchengfly.tieba.post.ui.widgets.compose.LocalShouldLoad
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.PullToRefreshLayout
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
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val state by viewModel.uiState.collectAsState()
    val accountUid = AccountUtil.LocalAccount.current?.uid?.toLongOrNull() ?: 0L
    val sessions = if (state.accountUid == accountUid) state.sessions else persistentListOf()
    val isLoading = state.accountUid == accountUid && state.isLoading
    val error = state.error.takeIf { state.accountUid == accountUid }
    val lazyListState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val shouldLoad = LocalShouldLoad.current

    LaunchedEffect(accountUid) {
        viewModel.send(PrivateChatListUiIntent.AccountChanged(accountUid))
    }
    LaunchedEffect(lifecycleOwner, shouldLoad, accountUid) {
        if (!shouldLoad) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            context.sendBroadcast(
                Intent(NotifyJobService.ACTION_CLEAR_PRIVATE).setPackage(context.packageName),
            )
            while (true) {
                viewModel.send(PrivateChatListUiIntent.Load)
                delay(5_000L)
            }
        }
    }

    PullToRefreshLayout(
        refreshing = isLoading,
        onRefresh = { viewModel.send(PrivateChatListUiIntent.Load) },
    ) {
        MyLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
            state = lazyListState,
        ) {
            if (isLoading) {
                item(key = "loading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            if (error != null || (sessions.isEmpty() && !isLoading)) {
                item(key = "status") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(error ?: "暂无私信", color = ExtendedTheme.colors.textSecondary)
                        TextButton(onClick = { viewModel.send(PrivateChatListUiIntent.Load) }) {
                            Text(if (error != null) "重试" else "刷新")
                        }
                    }
                }
            }
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
}
