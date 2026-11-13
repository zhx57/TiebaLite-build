package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/** 只由真实拖动改变跟随意愿，消息插入和程序滚动不能关闭跟随。 */
@Composable
internal fun FollowLatestChatMessage(
    listState: LazyListState,
    conversationId: Long,
    sendRequestId: Long,
) {
    var following by rememberSaveable(conversationId) { mutableStateOf(true) }
    var userScrolling by remember(listState) { mutableStateOf(false) }
    var handledSendRequest by rememberSaveable(conversationId) { mutableStateOf(sendRequestId) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    userScrolling = true
                    following = false
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    // 等待释放手势的帧完成，避免在惯性滚动启动前采样短暂空闲。
                    withFrameNanos { }
                    withFrameNanos { }
                    snapshotFlow { listState.isScrollInProgress }.first { !it }
                    if (userScrolling) {
                        following = listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset == 0
                        userScrolling = false
                    }
                }
            }
        }
    }
    LaunchedEffect(sendRequestId) {
        if (sendRequestId > handledSendRequest) {
            handledSendRequest = sendRequestId
            following = true
            userScrolling = false
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            // 布局快照包含消息插入、图片尺寸变化、键盘和面板改变后的视口。
            Triple(listState.layoutInfo, listState.isScrollInProgress, following && !userScrolling)
        }.collect { (_, scrolling, shouldFollow) ->
            val atBottom = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
            if (shouldFollow && !scrolling && !atBottom) {
                // 不排队播放动画；每次布局完成直接定位最新项。
                listState.scrollToItem(0)
            }
        }
    }
}
