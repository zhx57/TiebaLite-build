package com.huanchengfly.tieba.post.api.chat.ws

import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.api.models.chat.SendState
import com.huanchengfly.tieba.post.api.models.protos.chat.CommitPersonalMsgReqIdl
import com.huanchengfly.tieba.post.api.models.protos.chat.CommitPersonalMsgResIdl
import com.huanchengfly.tieba.post.api.models.protos.chat.CommitReceivedPmsgReqIdl
import com.huanchengfly.tieba.post.api.models.protos.chat.GetGroupMsgReqIdl
import com.huanchengfly.tieba.post.api.models.protos.chat.GetGroupMsgResIdl
import com.huanchengfly.tieba.post.api.models.protos.chat.PushNotifyResIdl
import com.huanchengfly.tieba.post.api.models.protos.chat.UpdateClientInfoReqIdl
import com.huanchengfly.tieba.post.api.models.protos.chat.UpdateClientInfoResIdl
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.CuidUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 贴吧私信 WebSocket 客户端。
 *
 * 生命周期：只在私信相关页面存活，页面退出即断开，不做后台常驻。
 *
 * 使用流程：
 * 1. [connect] 建立连接并完成 CMD 1001 握手，拿到私信消息组 id 与各组的 lastMsgId
 * 2. [getGroupMessages] 拉取新消息
 * 3. [sendMessage] 发送私信
 * 4. [setReaded] 上报已读
 */
class ChatWebSocketClient {

    private val wsSecKey: ByteArray = ByteArray(31).also {
        SecureRandom().nextBytes(it)
    }

    private val reqIdGen = AtomicInteger((System.currentTimeMillis() / 1000).toInt())

    private var webSocket: RawWebSocket? = null

    /** 等待响应的请求，key 为 reqId。 */
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()

    /**
     * 收到服务端推送（新私信）时的回调。
     *
     * 推送帧只带 groupId / msgId，不包含消息体，收到后需要主动拉一次增量。
     */
    @Volatile
    var onPushNotify: ((groupId: Long) -> Unit)? = null

    /** 消息组游标：groupId -> lastMsgId。 */
    private val groupMsgIds = ConcurrentHashMap<Long, Long>()

    /** 私信消息组 id。 */
    var privateGroupId: Long = 0L
        private set

    /** 当前登录用户 uid，用于判断消息是否为自己发出。 */
    var selfUid: Long = 0L
        private set

    @Volatile
    private var connected = false

    /**
     * 建立连接并完成握手。
     */
    suspend fun connect(cookie: String) = withContext(Dispatchers.IO) {
        if (connected) return@withContext
        // 使用原始 Socket 实现的 WebSocket：
        // 贴吧服务端要求 Sec-WebSocket-Extensions 为 im_version=2.3 且不支持
        // permessage-deflate，OkHttp 无法满足这两点，会在握手后被服务端断开。
        val socket = RawWebSocket(
            host = ChatWsConstants.WS_HOST,
            port = ChatWsConstants.WS_PORT,
            path = ChatWsConstants.WS_PATH,
            headers = linkedMapOf(
                "Sec-WebSocket-Extensions" to ChatWsConstants.WS_EXTENSIONS,
                "Accept-Encoding" to "gzip",
                "User-Agent" to "tieba/${ChatWsConstants.CHAT_VERSION}",
                "Cookie" to cookie,
            ),
        )
        socket.onBinary = { bytes -> handleFrame(bytes) }
        socket.onClosed = { err ->
            connected = false
            pending.values.forEach {
                it.completeExceptionally(err ?: IllegalStateException("websocket closed"))
            }
            pending.clear()
        }
        socket.connect()
        webSocket = socket
        handshake()
        connected = true
    }

    /**
     * CMD 1001：上报 ws_sec_key 与账号信息，注册消息组。
     */
    private suspend fun handshake() {
        val bduss = AccountUtil.getBduss().orEmpty()
        val device = JSONObject().apply {
            put("_client_type", "2")
            put("_client_version", ChatWsConstants.CHAT_VERSION)
        }
        val request = UpdateClientInfoReqIdl(
            cuid = "-|com.baidu.tieba${ChatWsConstants.CHAT_VERSION}",
            data_ = UpdateClientInfoReqIdl.DataReq(
                bduss = bduss,
                device = device.toString(),
                secretKey = ChatRsa.encryptWsSecKey(wsSecKey).toByteString(),
                stoken = AccountUtil.getSToken().orEmpty(),
            )
        )
        val payload = sendRaw(
            data = request.encode(),
            cmd = ChatWsConstants.CMD_UPDATE_CLIENT_INFO,
            compress = false,
            encrypt = false,
        )
        val response = UpdateClientInfoResIdl.ADAPTER.decode(payload)
        val errCode = response.error?.error_code ?: 0
        if (errCode != 0) {
            throw IllegalStateException("chat handshake failed: $errCode ${response.error?.error_msg}")
        }
        val data = response.data_ ?: throw IllegalStateException("chat handshake returned no data")
        val userId = data.userInfo?.userId ?: 0L
        val available = data.isUserAvailable ?: 0L
        if (userId == 0L || available != userId) {
            throw IllegalStateException("chat account unavailable: userId=$userId available=$available")
        }
        selfUid = userId
        data.groupInfo.orEmpty().forEach { group ->
            val gid = group.groupId ?: 0L
            if (group.groupType == ChatWsConstants.GROUP_TYPE_PRIVATE_MSG) {
                privateGroupId = gid
            }
            groupMsgIds[gid] = group.lastMsgId ?: 0L
        }
        // 兜底：部分账号的私信组 groupType 不是 6（或字段缺失），
        // 若没拿到私信组则取第一个非 0 的组，避免私信列表永远为空。
        if (privateGroupId == 0L) {
            privateGroupId = data.groupInfo.orEmpty()
                .mapNotNull { it.groupId }
                .firstOrNull { it != 0L } ?: 0L
        }
    }

    /**
     * 拉取私信消息组的新消息。
     *
     * @param fromStart 为 true 时游标置 0，用于首次进入拉取历史；
     *                  握手返回的 lastMsgId 已是最新一条，直接用它只会拉到空列表。
     */
    suspend fun fetchPrivateMessages(fromStart: Boolean = false): List<ChatWsMessage> {
        if (privateGroupId == 0L) return emptyList()
        val lastMsgId = if (fromStart) 0L else (groupMsgIds[privateGroupId] ?: 0L)
        val result = fetchPrivateMessages(privateGroupId, lastMsgId)
        return result.sortedBy { it.msgTime }
    }

    /**
     * 从最早一条开始按批拉取历史，直到追上握手时记录的最新游标。
     *
     * 单次请求只返回一小批（约 60 条）且是从 lastMsgId 往后取，
     * 只请求一次 lastMsgId=0 拿到的是很久以前的消息，会话列表里看不到最近联系人。
     * 这里逐批推进游标，最多 [maxPages] 批，兼顾完整性与耗时。
     */
    suspend fun fetchPrivateHistory(maxPages: Int = 10): List<ChatWsMessage> {
        if (privateGroupId == 0L) return emptyList()
        val target = groupMsgIds[privateGroupId] ?: 0L
        val merged = LinkedHashMap<Long, ChatWsMessage>()
        var cursor = 0L
        repeat(maxPages.coerceAtLeast(1)) {
            val batch = fetchPrivateMessages(privateGroupId, cursor)
            if (batch.isEmpty()) return@repeat
            var maxMsgId = cursor
            batch.forEach { message ->
                merged[message.msgId] = message
                if (message.msgId > maxMsgId) maxMsgId = message.msgId
            }
            // 没有推进说明服务端反复返回同一批，避免死循环
            if (maxMsgId <= cursor) return@repeat
            cursor = maxMsgId
            if (target > 0L && cursor >= target) return@repeat
        }
        return merged.values.sortedBy { it.msgTime }
    }

    /**
     * 从指定游标拉取私信增量，供后台通知统计未读使用。
     *
     * 与 [fetchPrivateMessages] 不同，这里不回退历史，只返回 [cursor] 之后的新消息；
     * cursor 为 0 时调用方应先通过 [privateLastMsgId] 取当前最新游标建基线。
     */
    suspend fun fetchPrivateMessagesSince(cursor: Long): List<ChatWsMessage> {
        if (privateGroupId == 0L) return emptyList()
        return fetchPrivateMessages(privateGroupId, cursor)
    }

    /** 私信消息组当前已知的最新游标，未握手时为 0。 */
    val privateLastMsgId: Long
        get() = groupMsgIds[privateGroupId] ?: 0L

    /**
     * 用指定游标拉一批私信消息，返回结果不做时间排序。
     *
     * gettype 固定为 1（增量游标模式），游标为 0 时服务端从最早一条开始返回。
     */
    private suspend fun fetchPrivateMessages(groupId: Long, lastMsgId: Long): List<ChatWsMessage> {
        val request = GetGroupMsgReqIdl(
            cuid = "${CuidUtils.getNewCuid()}|com.baidu.tieba_mini${ChatWsConstants.CHAT_VERSION}",
            data_ = GetGroupMsgReqIdl.DataReq(
                groupMids = listOf(
                    GetGroupMsgReqIdl.DataReq.GroupLastId(
                        groupId = groupId,
                        lastMsgId = lastMsgId,
                    )
                ),
                gettype = "1",
            )
        )
        val payload = sendRaw(
            data = request.encode(),
            cmd = ChatWsConstants.CMD_GET_GROUP_MSG,
        )
        val response = GetGroupMsgResIdl.ADAPTER.decode(payload)
        val errCode = response.error?.error_code ?: 0
        if (errCode != 0) {
            throw IllegalStateException("fetch messages failed: $errCode ${response.error?.error_msg}")
        }
        val result = mutableListOf<ChatWsMessage>()
        response.data_?.groupInfo.orEmpty().forEach { group ->
            group.msgList.orEmpty().forEach { msg ->
                val msgId = msg.msgId ?: 0L
                val fromUid = msg.userInfo?.userId ?: 0L
                val msgType = msg.msgType ?: 0
                // 已读回执等系统消息不是聊天内容，留在列表里会显示成 JSON
                if (msgType == ChatWsConstants.MSG_TYPE_READED || msgType == ChatWsConstants.MSG_TYPE_MISC) {
                    return@forEach
                }
                result += ChatWsMessage(
                    msgId = msgId,
                    msgType = msgType,
                    peerUid = fromUid,
                    peerName = msg.userInfo?.userName.orEmpty(),
                    peerPortrait = msg.userInfo?.portrait.orEmpty(),
                    content = normalizePrivateContent(msg.content.orEmpty(), msgType),
                    msgTime = (msg.createTime ?: 0) * 1000L,
                    isSelf = fromUid == selfUid,
                )
                if (msgId > (groupMsgIds[groupId] ?: 0L)) {
                    groupMsgIds[groupId] = msgId
                }
            }
        }
        return result
    }

    /**
     * 清洗私信正文。
     *
     * 服务端把部分非文本消息（撤回提示、系统提示等）也塞进 content，
     * 内容是 JSON。这里只保留可读文本，避免会话列表出现大段 JSON。
     */
    private fun normalizePrivateContent(raw: String, msgType: Int): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return trimmed
        return runCatching {
            val node = JSONTokener(trimmed).nextValue()
            extractPrivateText(node).ifBlank { "" }
        }.getOrDefault("")
    }

    private fun extractPrivateText(node: Any?): String = when (node) {
        null -> ""
        is String -> node.trim()
        is JSONObject -> listOf("text", "content", "msg", "toast")
            .asSequence()
            .map { extractPrivateText(node.opt(it)) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        is JSONArray -> buildString {
            for (i in 0 until node.length()) append(extractPrivateText(node.opt(i)))
        }
        else -> ""
    }

    /**
     * 发送私信。
     */
    suspend fun sendMessage(toUid: Long, content: String): Long {
        val recordId = System.currentTimeMillis()
        val request = CommitPersonalMsgReqIdl(
            data_ = CommitPersonalMsgReqIdl.DataReq(
                toUid = toUid,
                msgType = ChatWsConstants.MSG_TYPE_PRIVATE,
                content = content,
                recordId = recordId,
            )
        )
        val payload = sendRaw(
            data = request.encode(),
            cmd = ChatWsConstants.CMD_SEND_MSG,
        )
        val response = CommitPersonalMsgResIdl.ADAPTER.decode(payload)
        val errCode = response.error?.error_code ?: 0
        if (errCode != 0) {
            throw IllegalStateException("send message failed: $errCode ${response.error?.error_msg}")
        }
        val blockErrno = response.data_?.blockInfo?.blockErrno ?: 0
        if (blockErrno != 0) {
            throw IllegalStateException(
                "send message blocked: $blockErrno ${response.data_?.blockInfo?.blockErrmsg}"
            )
        }
        return response.data_?.msgId ?: 0L
    }

    /**
     * 上报已读。
     */
    suspend fun setReaded(peerUid: Long, msgId: Long) {
        if (privateGroupId == 0L) return
        val request = CommitReceivedPmsgReqIdl(
            data_ = CommitReceivedPmsgReqIdl.DataReq(
                groupId = privateGroupId,
                toUid = peerUid,
                msgType = ChatWsConstants.MSG_TYPE_READED,
                msgId = msgId,
            )
        )
        runCatching {
            sendRaw(
                data = request.encode(),
                cmd = ChatWsConstants.CMD_SET_MSG_READED,
            )
        }
    }

    /**
     * 关闭连接。
     */
    fun close() {
        connected = false
        pending.values.forEach { it.cancel() }
        pending.clear()
        runCatching { webSocket?.close() }
        webSocket = null
    }

    /**
     * 发送一帧并等待同 reqId 的响应。
     */
    private suspend fun sendRaw(
        data: ByteArray,
        cmd: Int,
        compress: Boolean = false,
        encrypt: Boolean = true,
    ): ByteArray {
        val socket = webSocket ?: throw IllegalStateException("websocket is not connected")
        val reqId = reqIdGen.incrementAndGet()
        val deferred = CompletableDeferred<ByteArray>()
        pending[reqId] = deferred
        val frame = ChatFrameCodec.pack(
            wsSecKey = wsSecKey,
            data = data,
            cmd = cmd,
            reqId = reqId,
            compress = compress,
            encrypt = encrypt,
        )
        if (!socket.sendBinary(frame)) {
            pending.remove(reqId)
            throw IllegalStateException("failed to send websocket frame")
        }
        return try {
            withTimeout(15_000) { deferred.await() }
        } finally {
            pending.remove(reqId)
        }
    }

    /**
     * 处理收到的帧。
     *
     * 匹配等待中的请求，同时处理服务端推送：
     * 推送帧不含消息体，只通知哪个消息组有新消息，由上层触发增量拉取。
     */
    private fun handleFrame(bytes: ByteArray) {
        val frame = runCatching { ChatFrameCodec.parse(wsSecKey, bytes) }.getOrNull() ?: return
        if (frame.cmd == ChatWsConstants.CMD_PUSH_NOTIFY) {
            handlePushNotify(frame.data)
            return
        }
        pending.remove(frame.reqId)?.complete(frame.data)
    }

    private fun handlePushNotify(payload: ByteArray) {
        if (payload.isEmpty()) return
        val notify = runCatching { PushNotifyResIdl.ADAPTER.decode(payload) }.getOrNull() ?: return
        notify.multiMsg.orEmpty().forEach { pusherMsg ->
            val data = pusherMsg.data_ ?: return@forEach
            val groupId = data.groupId ?: 0L
            if (groupId != 0L) {
                onPushNotify?.invoke(groupId)
            }
        }
    }
}

/**
 * 一条私信消息。
 */
data class ChatWsMessage(
    val msgId: Long,
    val peerUid: Long,
    val peerName: String,
    val peerPortrait: String,
    val content: String,
    val msgTime: Long,
    val isSelf: Boolean,
    /** 本地发送状态；服务端返回的消息恒为 [SendState.SENT]。 */
    val sendState: SendState = SendState.SENT,
    /** 是否是本地插入的占位消息；服务端返回的消息恒为 false。 */
    val isLocal: Boolean = false,
    /** 消息类型，见 [ChatWsConstants]；0 表示旧数据/未知。 */
    val msgType: Int = 0,
)
