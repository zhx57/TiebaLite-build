package com.huanchengfly.tieba.post.ui.page.chat

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.AlertDialog
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.models.chat.ChatMention
import com.huanchengfly.tieba.post.api.models.chat.ChatRobot
import com.huanchengfly.tieba.post.api.models.chat.ChatRoomMessage
import com.huanchengfly.tieba.post.arch.collectPartialAsState
import com.huanchengfly.tieba.post.arch.pageViewModel
import com.huanchengfly.tieba.post.ui.common.theme.compose.ExtendedTheme
import com.huanchengfly.tieba.post.ui.page.ProvideNavigator
import com.huanchengfly.tieba.post.ui.page.destinations.UserProfilePageDestination
import com.huanchengfly.tieba.post.ui.widgets.compose.BackNavigationIcon
import com.huanchengfly.tieba.post.ui.widgets.compose.ConfirmDialog
import com.huanchengfly.tieba.post.ui.widgets.compose.LazyLoad
import com.huanchengfly.tieba.post.ui.widgets.compose.MyLazyColumn
import com.huanchengfly.tieba.post.ui.widgets.compose.MyScaffold
import com.huanchengfly.tieba.post.ui.widgets.compose.TitleCentredToolbar
import com.huanchengfly.tieba.post.ui.widgets.compose.rememberDialogState
import com.huanchengfly.tieba.post.utils.AccountUtil.LocalAccount
import com.huanchengfly.tieba.post.utils.CuidUtils
import com.huanchengfly.tieba.post.utils.Emoticon
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/** 触发加载更早消息所需的全部状态快照。 */
private data class LoadMoreSignal(
    val lastVisible: Int,
    val total: Int,
    val hasMore: Boolean,
    val loading: Boolean,
    val oldestMsgId: Long,
)

/**
 * 群聊消息页。
 *
 * UI 只做渲染，所有合并、去重、状态流转都在 ViewModel 完成。
 * 列表按 JetChat 的标准做法：reverseLayout + 倒序数据，index 0 贴屏幕底部。
 */
@Destination
@Composable
fun ChatRoomPage(
    navigator: DestinationsNavigator,
    roomId: Long,
    roomName: String = "",
    fid: Long = 0L,
    viewModel: ChatRoomViewModel = pageViewModel(),
) {
    val context = LocalContext.current
    DisposableEffect(viewModel, roomId) {
        onDispose { viewModel.closeSendSession() }
    }
    LazyLoad(loaded = viewModel.initialized) {
        viewModel.send(ChatRoomUiIntent.Load(roomId))
        viewModel.initialized = true
    }

    val isLoading by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomUiState::isLoading,
        initial = true,
    )
    val isLoadingMore by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomUiState::isLoadingMore,
        initial = false,
    )
    val hasMore by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomUiState::hasMore,
        initial = true,
    )
    val messages by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomUiState::messages,
        initial = persistentListOf(),
    )

    val robots by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomUiState::robots, initial = persistentListOf(),
    )
    val isLoadingRobots by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomUiState::isLoadingRobots, initial = false,
    )
    val robotError by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomUiState::robotError, initial = null,
    )
    val sendRequestId by viewModel.uiState.collectPartialAsState(
        prop1 = ChatRoomUiState::sendRequestId, initial = 0L,
    )
    val account = LocalAccount.current
    var input by rememberSaveable(roomId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var mentions by rememberSaveable(roomId, stateSaver = ChatMentionsSaver) {
        mutableStateOf(emptyList<ChatMention>())
    }
    var robotSkill by rememberSaveable(roomId) { mutableStateOf<Int?>(null) }
    var robotTargetUk by rememberSaveable(roomId) { mutableStateOf<String?>(null) }
    var robotCommandName by rememberSaveable(roomId) { mutableStateOf("") }
    var picker by remember { mutableStateOf<String?>(null) }
    var showEmoticonPanel by remember { mutableStateOf(false) }
    var resendTarget by remember { mutableStateOf<ChatRoomMessage?>(null) }
    val mentionCandidates = remember(messages, robots) {
        (robots.map { it.user } + messages.asReversed().mapNotNull { message ->
            message.senderBaiduUk.takeIf { it.isNotBlank() && message.senderName.isNotBlank() }
                ?.let { ChatMention(message.senderUid, it, message.senderName, message.senderPortrait) }
        } + messages.flatMap { it.mentions }).distinctBy { it.baiduUk }
    }
    fun updateInput(value: TextFieldValue) {
        input = value
        mentions = mentions.filter {
            Regex("@${Regex.escape(it.name)}(?=\\s|$)").containsMatchIn(value.text)
        }
        if (mentions.none { it.baiduUk == robotTargetUk }) {
            robotSkill = null
            robotTargetUk = null
            robotCommandName = ""
        }
    }
    fun insertMention(user: ChatMention, command: String = "") {
        val token = "@${user.name} "
        val addition = token + command
        val start = if (input.selection.collapsed && input.selection.start > 0 &&
            input.text[input.selection.start - 1] == '@'
        ) input.selection.start - 1 else input.selection.min
        val end = input.selection.max
        updateInput(TextFieldValue(
            input.text.replaceRange(start, end, addition),
            TextRange(start + addition.length),
        ))
        mentions = (mentions + user).distinctBy { it.baiduUk }
    }

    LaunchedEffect(roomId) { viewModel.send(ChatRoomUiIntent.LoadRobots(roomId)) }

    val listState = rememberLazyListState()
    // 倒序数据：index 0 是最新消息，贴在底部
    val reversedMessages = remember(messages) { messages.asReversed() }

    FollowLatestChatMessage(listState, roomId, sendRequestId)

    // 首次 Load 已经负责当前页，仅在页面可见时按固定间隔触发后续轮询。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(roomId, viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(2_000)
                viewModel.send(ChatRoomUiIntent.Poll(roomId))
            }
        }
    }

    // 滑到视觉顶部时加载更早消息。
    // 把 hasMore / isLoadingMore / 消息数都纳入 snapshotFlow 的输入，
    // 这样加载完成后若仍停在顶部会自动继续取下一页，不会出现"划到顶不加载"。
    LaunchedEffect(Unit) {
        snapshotFlow {
            val info = listState.layoutInfo
            LoadMoreSignal(
                lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0,
                total = info.totalItemsCount,
                hasMore = hasMore,
                loading = isLoadingMore,
                oldestMsgId = messages.firstOrNull()?.msgId ?: 0L,
            )
        }
            .distinctUntilChanged()
            .collect { signal ->
                if (!signal.hasMore || signal.loading) return@collect
                if (signal.total > 0 && signal.lastVisible >= signal.total - 2 &&
                    signal.oldestMsgId != 0L
                ) {
                    viewModel.send(ChatRoomUiIntent.LoadMore(roomId, signal.oldestMsgId))
                }
            }
    }

    val resendDialogState = rememberDialogState()
    ConfirmDialog(
        dialogState = resendDialogState,
        onConfirm = {
            val target = resendTarget
            val acc = account
            if (target != null && acc != null) {
                viewModel.send(
                    ChatRoomUiIntent.Resend(
                        roomId = roomId,
                        failedMsgId = target.msgId,
                        text = target.text,
                        forumId = fid,
                        bduss = acc.bduss,
                        selfName = acc.nameShow ?: acc.name,
                        selfPortrait = acc.portrait,
                        selfUid = acc.uid.toLongOrNull() ?: 0L,
                        cuidGalaxy2 = CuidUtils.getNewCuid(),
                        mentions = target.mentions,
                        robotSkill = target.robotSkill,
                        cloudMeme = target.cloudMeme,
                    )
                )
            }
            resendTarget = null
        },
        onCancel = { resendTarget = null },
        confirmText = stringResource(id = R.string.btn_resend),
        cancelText = stringResource(id = R.string.button_cancel),
    ) {
        Column {
            resendTarget?.sendError?.let { Text(it, color = MaterialTheme.colors.error) }
            Text(text = stringResource(id = R.string.tip_resend_message))
        }
    }

    if (picker != null) {
        ChatRecipientPicker(
            robotMode = picker == "robot",
            candidates = mentionCandidates,
            robots = robots,
            loading = isLoadingRobots,
            error = robotError,
            onReload = { viewModel.send(ChatRoomUiIntent.LoadRobots(roomId)) },
            onDismiss = { picker = null },
            onMention = { user ->
                insertMention(user)
                picker = null
            },
            onCommand = { robot, type, name, alias ->
                insertMention(robot.user, alias.ifBlank { name } + " ")
                robotSkill = type
                robotTargetUk = robot.user.baiduUk
                robotCommandName = name
                picker = null
            },
        )
    }

    ProvideNavigator(navigator = navigator) {
        MyScaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TitleCentredToolbar(
                    title = {
                        Text(text = roomName.ifBlank { stringResource(id = R.string.title_chat_rooms) })
                    },
                    navigationIcon = { BackNavigationIcon { navigator.navigateUp() } },
                )
            },
            bottomBar = {
                if (account != null) {
                    Column {
                        ChatComposer(
                            value = input,
                            onValueChange = { value ->
                                val insertedAt = value.composition == null &&
                                    value.text.length > input.text.length &&
                                    value.selection.collapsed && value.selection.start > 0 &&
                                    value.text[value.selection.start - 1] == '@'
                                updateInput(value)
                                if (insertedAt) picker = "mention"
                            },
                            onSend = {
                                val text = input.text.trim()
                                if (text.isNotEmpty()) {
                                    viewModel.send(
                                        ChatRoomUiIntent.Send(
                                            roomId = roomId,
                                            text = text,
                                            forumId = fid,
                                            bduss = account.bduss,
                                            selfName = account.nameShow ?: account.name,
                                            selfPortrait = account.portrait,
                                            selfUid = account.uid.toLongOrNull() ?: 0L,
                                            cuidGalaxy2 = CuidUtils.getNewCuid(),
                                            mentions = if (robotSkill != null) {
                                                mentions.sortedBy { it.baiduUk != robotTargetUk }
                                            } else mentions,
                                            robotSkill = robotSkill,
                                        )
                                    )
                                    input = TextFieldValue()
                                    mentions = emptyList()
                                    robotSkill = null
                                    robotTargetUk = null
                                    robotCommandName = ""
                                }
                            },
                            showEmoticonPanel = showEmoticonPanel,
                            onToggleEmoticonPanel = { showEmoticonPanel = !showEmoticonPanel },
                            onDismissEmoticonPanel = { showEmoticonPanel = false },
                            onMentionClick = { picker = "mention" },
                            onRobotClick = { picker = "robot" },
                            onCloudMemeClick = { meme ->
                                viewModel.send(ChatRoomUiIntent.Send(
                                    roomId = roomId,
                                    text = "",
                                    forumId = fid,
                                    bduss = account.bduss,
                                    selfName = account.nameShow ?: account.name,
                                    selfPortrait = account.portrait,
                                    selfUid = account.uid.toLongOrNull() ?: 0L,
                                    cuidGalaxy2 = CuidUtils.getNewCuid(),
                                    cloudMeme = meme,
                                ))
                            },
                            robotCommandName = robotCommandName.takeIf { robotSkill != null },
                            onCancelRobotCommand = {
                                robotSkill = null
                                robotTargetUk = null
                                robotCommandName = ""
                            },
                            onEmoticonClick = { emoticon: Emoticon ->
                                val insertion = "#(${emoticon.name})"
                                val start = input.selection.min
                                updateInput(TextFieldValue(
                                    input.text.replaceRange(start, input.selection.max, insertion),
                                    TextRange(start + insertion.length),
                                ))
                            },
                        )
                    }
                }
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                MyLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = ChatListContentPadding,
                    state = listState,
                    reverseLayout = true,
                ) {
                    items(
                        items = reversedMessages,
                        key = { "${it.msgId}_${it.senderUid}" },
                    ) { message ->
                        ChatMessageRow(
                            text = message.text,
                            isSelf = message.isSelf,
                            senderName = message.senderName,
                            avatarUrl = message.senderPortrait,
                            msgTime = message.msgTime,
                            sendState = message.sendState,
                            transportAccepted = message.transportAccepted,
                            images = message.images,
                            onRetryClick = {
                                resendTarget = message
                                resendDialogState.show()
                            },
                            onAvatarClick = {
                                if (message.senderUid > 0L) {
                                    navigateToUserProfile(navigator, message.senderUid)
                                }
                            },
                            onAvatarLongClick = {
                                if (message.senderBaiduUk.isNotBlank() && message.senderName.isNotBlank()) {
                                    insertMention(ChatMention(
                                        message.senderUid, message.senderBaiduUk,
                                        message.senderName, message.senderPortrait,
                                    ))
                                } else {
                                    Toast.makeText(context, "缺少成员身份信息，暂时无法艾特", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }

                if (isLoadingMore) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .size(24.dp),
                        color = ExtendedTheme.colors.primary,
                    )
                }

                when {
                    isLoading && messages.isEmpty() -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = ExtendedTheme.colors.primary,
                    )

                    !isLoading && messages.isEmpty() -> Text(
                        text = stringResource(id = R.string.tip_chat_room_empty),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.body2,
                        color = ExtendedTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

private val ChatMentionsSaver = listSaver<List<ChatMention>, String>(
    save = { mentions ->
        mentions.flatMap { listOf(it.uid.toString(), it.baiduUk, it.name, it.portrait) }
    },
    restore = { values ->
        values.chunked(4).map { ChatMention(it[0].toLong(), it[1], it[2], it[3]) }
    },
)

@Composable
private fun ChatRecipientPicker(
    robotMode: Boolean,
    candidates: List<ChatMention>,
    robots: List<ChatRobot>,
    loading: Boolean,
    error: String?,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    onMention: (ChatMention) -> Unit,
    onCommand: (ChatRobot, Int, String, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (robotMode) "机器人命令" else "选择艾特对象") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(if (robotMode) "搜索机器人或技能" else "搜索昵称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在加载机器人…", Modifier.padding(start = 8.dp))
                    }
                }
                if (error != null) {
                    Text("机器人加载失败：$error", color = MaterialTheme.colors.error)
                    TextButton(onClick = onReload) { Text("重试") }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    if (robotMode) {
                        if (robots.isEmpty() && !loading && error == null) {
                            item { Text("该群未提供机器人技能") }
                        }
                        robots.forEach { robot ->
                            val skills = robot.skills.filter {
                                robot.user.name.contains(query, true) || it.name.contains(query, true) ||
                                    it.alias.contains(query, true)
                            }
                            if (skills.isNotEmpty()) {
                                item { Text(robot.user.name, style = MaterialTheme.typography.subtitle2) }
                                items(skills, key = { "${robot.user.baiduUk}_${it.type}" }) { skill ->
                                    Column(
                                        Modifier.fillMaxWidth().clickable {
                                            onCommand(robot, skill.type, skill.name, skill.alias)
                                        }.padding(vertical = 10.dp),
                                    ) {
                                        Text(skill.name.ifBlank { skill.alias })
                                        if (skill.description.isNotBlank()) {
                                            Text(skill.description, style = MaterialTheme.typography.caption)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val users = candidates.filter { it.name.contains(query, true) }
                        if (users.isEmpty()) item { Text("未找到对象，请先加载相关聊天记录") }
                        items(users, key = { it.baiduUk }) { user ->
                            Text(
                                "@${user.name}",
                                Modifier.fillMaxWidth().clickable { onMention(user) }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
                if (!robotMode) {
                    Text("候选来自已加载消息和群机器人", style = MaterialTheme.typography.caption)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
