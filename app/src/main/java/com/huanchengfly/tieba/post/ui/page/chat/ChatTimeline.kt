package com.huanchengfly.tieba.post.ui.page.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * 页面内容区负责 IME 和导航栏，输入栏预算来自扣除工具栏后的真实约束。
 */
@Composable
internal fun ChatTimeline(
    modifier: Modifier = Modifier,
    composer: @Composable (availableHeight: Dp) -> Unit,
    messages: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.imePadding().navigationBarsPadding()) {
        val availableHeight = maxHeight
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxSize()) { messages() }
            composer(availableHeight)
        }
    }
}
