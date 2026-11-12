package com.huanchengfly.tieba.post.ui.widgets.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.utils.Emoticon
import com.huanchengfly.tieba.post.utils.EmoticonManager

/**
 * 表情选择面板。
 *
 * 与回帖页共用同一份表情数据，点击回调返回表情对象，
 * 调用方可把 [Emoticon.name] 以 `#(name)` 的形式插入输入框。
 */
@Composable
fun EmoticonPickerPanel(
    modifier: Modifier = Modifier,
    onEmoticonClick: (Emoticon) -> Unit,
) {
    val context = LocalContext.current
    val emoticons = remember {
        EmoticonManager.getAllEmoticon()
            .filter { it.name.isNotEmpty() }
    }

    Column(modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(48.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            items(emoticons) { emoticon ->
                Image(
                    painter = rememberDrawablePainter(
                        drawable = EmoticonManager.getEmoticonDrawable(context, emoticon.id)
                    ),
                    contentDescription = stringResource(id = R.string.emoticon, emoticon.name),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(48.dp)
                        .padding(8.dp)
                        .clickable { onEmoticonClick(emoticon) },
                )
            }
        }
    }
}
