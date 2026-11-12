package com.huanchengfly.tieba.post.ui.page.user.likeforum

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.models.UserLikeForumBean
import com.huanchengfly.tieba.post.arch.GlobalEvent
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.getOrNull
import com.huanchengfly.tieba.post.arch.onGlobalEvent
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.common.theme.compose.pullRefreshIndicator
import com.huanchengfly.tieba.post.ui.page.LocalNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.ForumPageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.Avatar
import com.huanchengfly.tieba.post.ui.widgets.compose.Container
import com.huanchengfly.tieba.post.ui.widgets.compose.ErrorScreen
import com.huanchengfly.tieba.post.ui.widgets.compose.LazyLoad
import com.huanchengfly.tieba.post.ui.widgets.compose.LoadMoreLayout
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.Sizes
import com.huanchengfly.tieba.post.ui.widgets.compose.states.StateScreen
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun UserLikeForumPage(
    uid: Long,
    fluid: Boolean = false,
    enablePullRefresh: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    tabs: (@Composable () -> Unit)? = null,
    listState: LazyListState? = null,
    topSpacerHeight: Dp = 0.dp,
    viewModel: UserLikeForumViewModel = pageViewModel(),
) {
    val navigator = LocalNavigator.current

    LazyLoad(loaded = viewModel.initialized) {
        viewModel.send(UserLikeForumUiIntent.Refresh(uid))
        viewModel.initialized = true
    }

    val isRefreshing by viewModel.uiState.collectPartialAsState(
        prop1 = UserLikeForumUiState::isRefreshing,
        initial = true
    )
    val isLoadingMore by viewModel.uiState.collectPartialAsState(
        prop1 = UserLikeForumUiState::isLoadingMore,
        initial = false
    )
    val error by viewModel.uiState.collectPartialAsState(
        prop1 = UserLikeForumUiState::error,
        initial = null
    )
    val currentPage by viewModel.uiState.collectPartialAsState(
        prop1 = UserLikeForumUiState::currentPage,
        initial = 1
    )
    val hasMore by viewModel.uiState.collectPartialAsState(
        prop1 = UserLikeForumUiState::hasMore,
        initial = false
    )
    val forums by viewModel.uiState.collectPartialAsState(
        prop1 = UserLikeForumUiState::forums,
        initial = persistentListOf()
    )

    val isEmpty by remember {
        derivedStateOf { forums.isEmpty() }
    }
    val isError by remember {
        derivedStateOf { error != null }
    }

    onGlobalEvent<GlobalEvent.Refresh>(
        filter = { it.key == "user_profile" }
    ) {
        viewModel.send(UserLikeForumUiIntent.Refresh(uid))
    }

    StateScreen(
        isEmpty = isEmpty,
        isError = isError,
        isLoading = isRefreshing,
        onReload = {
            viewModel.send(UserLikeForumUiIntent.Refresh(uid))
        },
        loadingScreen = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topSpacerHeight)
            ) {
                header?.invoke()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        },
        emptyScreen = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topSpacerHeight)
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                header?.invoke()
                Text(
                    text = stringResource(id = R.string.title_empty),
                    style = MaterialTheme.typography.body2,
                    color = ExtendedTheme.colors.textSecondary,
                )
            }
        },
        errorScreen = { ErrorScreen(error = error.getOrNull()) },
    ) {
        val pullRefreshState = rememberPullRefreshState(
            refreshing = isRefreshing,
            onRefresh = ::reload
        )

        val lazyListState = listState ?: rememberLazyListState()

        val pullRefreshModifier =
            if (enablePullRefresh) Modifier.pullRefresh(pullRefreshState) else Modifier

        Box(modifier = pullRefreshModifier) {
            LoadMoreLayout(
                isLoading = isLoadingMore,
                onLoadMore = {
                    viewModel.send(UserLikeForumUiIntent.LoadMore(uid, currentPage))
                },
                loadEnd = !hasMore,
                lazyListState = lazyListState
            ) {
                UserLikeForumList(
                    data = forums,
                    fluid = fluid,
                    topSpacerHeight = topSpacerHeight,
                    onClickForum = { forumBean ->
                        forumBean.name?.let {
                            navigator.navigate(ForumPageDestination(it))
                        }
                    },
                    header = header,
                    tabs = tabs,
                    lazyListState = lazyListState
                )
            }

            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = ExtendedTheme.colors.pullRefreshIndicator,
                contentColor = ExtendedTheme.colors.primary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserLikeForumList(
    data: ImmutableList<UserLikeForumBean.ForumBean>,
    onClickForum: (UserLikeForumBean.ForumBean) -> Unit,
    fluid: Boolean = false,
    topSpacerHeight: Dp = 0.dp,
    header: (@Composable () -> Unit)? = null,
    tabs: (@Composable () -> Unit)? = null,
    lazyListState: LazyListState = rememberLazyListState(),
) {
    MyLazyColumn(state = lazyListState) {
        // 首项占位：为覆盖在列表之上的资料头部与标签栏腾出空间
        if (topSpacerHeight > 0.dp) {
            item(key = "profile_top_spacer") {
                Spacer(modifier = Modifier.height(topSpacerHeight))
            }
        }
        // 与帖子列表一致：头部和标签栏作为前两项，整页连续滚动
        if (header != null) {
            item(key = "profile_header") { header() }
        }
        if (tabs != null) {
            item(key = "profile_tabs") { tabs() }
        }
        items(
            items = data,
            key = { it.id }
        ) {
            Container(fluid = fluid) {
                UserLikeForumItem(
                    item = it,
                    onClick = {
                        onClickForum(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun UserLikeForumItem(
    item: UserLikeForumBean.ForumBean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .then(modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Avatar(
            data = item.avatar,
            size = Sizes.Medium,
            contentDescription = null
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = item.name.orEmpty(), style = MaterialTheme.typography.subtitle1)
            item.slogan.takeUnless { it.isNullOrEmpty() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.body2,
                    color = ExtendedTheme.colors.textSecondary
                )
            }
        }
    }
}