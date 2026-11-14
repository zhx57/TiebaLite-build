package com.huanchengfly.tieba.post.ui.widgets.compose

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.models.chat.ChatCloudMeme
import com.huanchengfly.tieba.post.repository.CloudMemeRepository
import com.huanchengfly.tieba.post.repository.CloudMemeSession
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.utils.AccountUtil.LocalAccount
import com.huanchengfly.tieba.post.utils.Emoticon
import com.huanchengfly.tieba.post.utils.EmoticonManager

/** 基础表情插入输入框，云表情由聊天页通过图片消息发送。 */
@Composable
fun EmoticonPickerPanel(
    modifier: Modifier = Modifier,
    onEmoticonClick: (Emoticon) -> Unit,
    onCloudMemeClick: ((ChatCloudMeme) -> Unit)? = null,
) {
    val context = LocalContext.current
    val emoticons = remember {
        EmoticonManager.getAllEmoticon().filter { it.name.isNotEmpty() }
    }
    var category by remember { mutableStateOf(0) }
    val account = LocalAccount.current
    val uid = account?.uid.orEmpty()
    val bduss = account?.bduss.orEmpty()

    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("基础", "云收藏", "云 DIY").forEachIndexed { index, title ->
                TextButton(onClick = { category = index }, modifier = Modifier.weight(1f)) {
                    Text(title, color = if (category == index) ExtendedTheme.colors.primary else ExtendedTheme.colors.text)
                }
            }
        }
        key(uid, bduss) {
            val scope = rememberCoroutineScope()
            val cache = remember { CloudMemeRepository.cache(context.applicationContext.filesDir) }
            val session = remember {
                CloudMemeSession(scope, cached = { cache.cached(uid) }) {
                    cache.load(uid, bduss, force = true)
                }
            }
            DisposableEffect(session) { onDispose { session.close() } }
            val state by session.state.collectAsState()
            LaunchedEffect(category) {
                if (category != 0 && uid.isNotBlank() && bduss.isNotBlank() &&
                    state.lists == null && state.error == null && !state.loading
                ) {
                    session.open()
                }
            }
            if (category == 0) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(48.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(emoticons) { emoticon ->
                        Image(
                            painter = rememberDrawablePainter(
                                drawable = EmoticonManager.getEmoticonDrawable(context, emoticon.id)
                            ),
                            contentDescription = stringResource(id = R.string.emoticon, emoticon.name),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(48.dp).padding(8.dp)
                                .clickable { onEmoticonClick(emoticon) },
                        )
                    }
                }
            } else if (uid.isBlank() || bduss.isBlank()) {
                Text("登录后可查看当前账号的云表情", color = ExtendedTheme.colors.textSecondary,
                    modifier = Modifier.padding(12.dp))
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(if (onCloudMemeClick != null) "点击表情立即发送" else "当前会话暂不支持云表情发送", color = ExtendedTheme.colors.textSecondary,
                        fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = session::refresh, enabled = !state.loading) {
                        Text(if (state.loading) "加载中…" else "刷新", color = ExtendedTheme.colors.primary)
                    }
                }
                state.error?.let { error ->
                    Text(
                        text = if (state.lists != null) "未更新，保留上次内容。$error" else error,
                        color = ExtendedTheme.colors.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                val memes = state.lists?.let { if (category == 1) it.collected else it.diy }
                if (memes != null && memes.isEmpty()) {
                    Text(if (category == 1) "当前账号暂无云收藏" else "当前账号暂无云 DIY 表情",
                        color = ExtendedTheme.colors.textSecondary, modifier = Modifier.padding(12.dp))
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(72.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    items(memes.orEmpty(), key = { it.id }) { meme ->
                        NetworkImage(
                            imageUri = meme.displayUrl,
                            contentDescription = "云表情 ${meme.id}",
                            contentScale = ContentScale.Fit,
                            skipNetworkCheck = true,
                            modifier = Modifier.aspectRatio(1f).padding(8.dp)
                                .background(ExtendedTheme.colors.background)
                                .clickable(enabled = onCloudMemeClick != null) {
                                    val selected = runCatching { ChatCloudMeme(meme.id, meme.imageUrl) }.getOrNull()
                                    if (selected == null) {
                                        Toast.makeText(context, "此表情缺少有效原图地址，请刷新后重试", Toast.LENGTH_SHORT).show()
                                    } else {
                                        onCloudMemeClick?.invoke(selected)
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}
