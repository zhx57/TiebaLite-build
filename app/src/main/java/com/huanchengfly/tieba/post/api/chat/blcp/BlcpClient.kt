package com.huanchengfly.tieba.post.api.chat.blcp

import com.huanchengfly.tieba.post.api.models.protos.blcp.Common
import com.huanchengfly.tieba.post.api.models.protos.blcp.LcmRequest
import com.huanchengfly.tieba.post.api.models.protos.blcp.RpcData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * BLCP 客户端：TLS 长连接 + LCP 帧收发 + 请求响应关联。
 *
 * 只在群聊发送场景中使用，每个实例只建立一次连接。
 */
class BlcpClient {

    private val random = SecureRandom()

    private val stateLock = Any()

    private val writeLock = Any()

    private var socket: SSLSocket? = null

    private var readerThread: Thread? = null

    @Volatile
    private var closed = false

    private val pending = HashMap<Long, CompletableDeferred<BlcpFrame.Decoded>>()

    private var readBuffer = ByteArray(0)

    private var started = false

    private var connected = false

    val isConnected: Boolean
        get() = synchronized(stateLock) {
            connected && !closed && socket?.let {
                it.isConnected && !it.isClosed && !it.isInputShutdown && !it.isOutputShutdown
            } == true
        }

    /**
     * 建立 TLS 连接，每个实例只允许调用一次。
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        synchronized(stateLock) {
            check(!started && !closed) { "BLCP client cannot be reconnected" }
            started = true
        }
        var connectingSocket: SSLSocket? = null
        try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = factory.createSocket() as SSLSocket
            connectingSocket = sslSocket
            synchronized(stateLock) {
                check(!closed) { "BLCP connection is closed" }
                socket = sslSocket
            }
            sslSocket.connect(
                InetSocketAddress(BlcpConstants.HOST, BlcpConstants.PORT),
                10_000,
            )
            sslSocket.soTimeout = 10_000
            sslSocket.startHandshake()
            sslSocket.soTimeout = 0
            synchronized(stateLock) {
                check(!closed) { "BLCP connection is closed" }
                connected = true
                readerThread = Thread({ readLoop(sslSocket) }, "blcp-reader").apply {
                    isDaemon = true
                    start()
                }
            }
        } catch (e: Exception) {
            closeConnection(e)
            runCatching { connectingSocket?.close() }
            throw e
        }
    }

    /**
     * 关闭连接。
     */
    fun close() {
        closeConnection(IOException("BLCP connection is closed"))
    }

    private fun closeConnection(cause: Exception) {
        val resources = synchronized(stateLock) {
            if (closed) return
            closed = true
            connected = false
            val resources = Triple(socket, readerThread, pending.values.toList())
            socket = null
            readerThread = null
            pending.clear()
            resources
        }
        resources.third.forEach { it.completeExceptionally(cause) }
        runCatching { resources.first?.close() }
        resources.second?.takeIf { it != Thread.currentThread() }?.interrupt()
    }

    /**
     * 发送 LCM 登录请求，返回服务端要求的心跳间隔（毫秒）。
     */
    suspend fun loginLcm(token: String, cuid: String): Long {
        val logId = nextCorrelationId()
        val request = RpcData(
            lcm_request = LcmRequest(
                log_id = logId,
                token = token,
                common = com.huanchengfly.tieba.post.api.models.protos.blcp.Common(
                    cuid = cuid,
                    device = "android",
                    app_id = BlcpConstants.CHAT_APP_ID.toString(),
                    app_version = BlcpConstants.CHAT_VERSION,
                    sdk_version = BlcpConstants.LCP_SDK_VERSION,
                    network = "wifi",
                ),
                timestamp = System.currentTimeMillis(),
                start_type = -1,
                conn_type = 1,
            )
        )
        val response = send(
            serviceId = BlcpConstants.SERVICE_LCM,
            methodId = BlcpConstants.METHOD_LOGIN,
            body = request.encode(),
            correlationId = logId,
        )
        response.meta.response?.let { rpcResponse ->
            if (rpcResponse.error_code != 0) {
                throw IllegalStateException(
                    "LCM login failed: RPC ${rpcResponse.error_code} ${rpcResponse.error_text}"
                )
            }
        }
        val rpcData = RpcData.ADAPTER.decode(response.body)
        val lcmResponse = rpcData.lcm_response
            ?: throw IllegalStateException("LCM login failed: response missing")
        if (lcmResponse.error_code != 0) {
            throw IllegalStateException(
                "LCM login failed: ${lcmResponse.error_code} ${lcmResponse.error_msg}"
            )
        }
        return lcmResponse.next_interval_ms
    }

    /**
     * IM 登录，返回 uk / bduk / triggerId。
     */
    suspend fun loginIm(bduss: String, cuidGalaxy2: String): BlcpImIdentity {
        val logId = nextCorrelationId()
        val body = JSONObject().apply {
            put("method", BlcpConstants.METHOD_LOGIN_BY_TOKEN)
            put("appid", BlcpConstants.CHAT_APP_ID)
            put("device_id", "android_$cuidGalaxy2")
            put("account_type", 1)
            put("token", bduss)
            put("version", 4)
            put("sdk_version", BlcpConstants.CHAT_SDK_VERSION)
            put("app_version", BlcpConstants.CHAT_VERSION)
            put("app_open_type", 0)
            // 官方客户端会带这两个字段，缺失时 IM 登录可能返回异常
            put("client_identifier", JSONObject().put("zid", "").put("version_code", "").toString())
            put("device_info", JSONObject().apply {
                put("app_version", BlcpConstants.CHAT_VERSION)
                put("os_version", "32")
                put("platform", "android")
                put("appid", BlcpConstants.CHAT_APP_ID.toString())
                put("from", "1008550l")
                put("cfrom", "1008550l")
            })
            put("tail", 0)
            put("timeout", 10)
            put("cookie", "")
            put("rpc", JSONObject().put("rpc_retry_time", 0).toString())
            put("user_type", 0)
            put("client_logid", System.currentTimeMillis() * 1000)
        }
        val response = send(
            serviceId = BlcpConstants.SERVICE_IM,
            methodId = BlcpConstants.METHOD_LOGIN_BY_TOKEN,
            body = body.toString().toByteArray(Charsets.UTF_8),
            correlationId = logId,
        )
        val rpcError = response.meta.response?.error_code ?: 0
        if (rpcError != 0) {
            throw IllegalStateException(
                "IM login failed: RPC $rpcError ${response.meta.response?.error_text.orEmpty()}"
            )
        }
        val payload = parseJsonBody(response.body, "IM login")
        val errCode = payload.optInt("err_code", 0)
        if (errCode != 0) {
            throw IllegalStateException("IM login failed: $errCode ${payload.optString("msg")}")
        }
        // trigger_id 在协议里是数组，取第一个元素；
        // bd_uid 才是 bduk，uk 为数字
        val triggerRaw = payload.opt("trigger_id")
        val triggerId = when (triggerRaw) {
            is JSONArray -> if (triggerRaw.length() > 0) triggerRaw.optLong(0) else 0L
            is Number -> triggerRaw.toLong()
            else -> 0L
        }
        val uk = payload.optLong("uk")
        if (uk == 0L) {
            throw IllegalStateException("IM login failed: uk missing")
        }
        return BlcpImIdentity(
            uk = uk,
            bduk = payload.optString("bd_uid"),
            triggerId = triggerId,
        )
    }

    /**
     * 发送群消息。
     */
    suspend fun sendMcastMsg(mcastId: Long, requestData: JSONObject): JSONObject {
        val logId = nextCorrelationId()
        requestData.put("client_logid", System.currentTimeMillis() * 1000)
        requestData.put("rpc", JSONObject().put("rpc_retry_time", 0).toString())
        val response = send(
            serviceId = BlcpConstants.SERVICE_MCAST,
            methodId = BlcpConstants.METHOD_SEND_MCAST_MSG,
            body = requestData.toString().toByteArray(Charsets.UTF_8),
            correlationId = logId,
        )
        // 先看 RPC 层的错误码；业务层 body 可能为空，此时按 RPC 结果判定
        val rpcError = response.meta.response?.error_code ?: 0
        if (rpcError != 0) {
            throw IllegalStateException(
                "send group message failed: RPC $rpcError ${response.meta.response?.error_text.orEmpty()}"
            )
        }
        if (response.body.isEmpty()) return JSONObject()
        val payload = parseJsonBody(response.body, "send group message")
        val errCode = payload.optInt("err_code", 0)
        if (errCode != 0) {
            throw IllegalStateException("send group message failed: $errCode ${payload.optString("msg")}")
        }
        return payload
    }

    /**
     * 加入群聊频道。
     */
    suspend fun joinChatroom(mcastId: Long, uk: Long) {
        val logId = nextCorrelationId()
        val body = JSONObject().apply {
            put("method", BlcpConstants.METHOD_JOIN_CAST)
            put("mcast_id", mcastId)
            put("appid", BlcpConstants.CHAT_APP_ID)
            put("uk", uk)
        }
        // 加入失败不阻断后续发送，但把错误暴露出来便于排查
        val response = send(
            serviceId = BlcpConstants.SERVICE_MCAST,
            methodId = BlcpConstants.METHOD_JOIN_CAST,
            body = body.toString().toByteArray(Charsets.UTF_8),
            correlationId = logId,
        )
        val rpcError = response.meta.response?.error_code ?: 0
        if (rpcError != 0) {
            throw IllegalStateException(
                "join chatroom failed: RPC $rpcError ${response.meta.response?.error_text.orEmpty()}"
            )
        }
        if (response.body.isEmpty()) return
        val payload = runCatching { JSONObject(String(response.body, Charsets.UTF_8)) }.getOrNull()
            ?: return
        val errCode = payload.optInt("err_code", 0)
        if (errCode != 0) {
            throw IllegalStateException("join chatroom failed: $errCode ${payload.optString("msg")}")
        }
    }

    private suspend fun send(
        serviceId: Int,
        methodId: Int,
        body: ByteArray,
        correlationId: Long,
    ): BlcpFrame.Decoded {
        val deferred = CompletableDeferred<BlcpFrame.Decoded>()
        val sslSocket = synchronized(stateLock) {
            check(connected && !closed) { "BLCP connection is not open" }
            val currentSocket = socket ?: error("BLCP connection is not open")
            pending[correlationId] = deferred
            currentSocket
        }
        val frame = BlcpFrame.buildRequest(
            serviceId = serviceId,
            methodId = methodId,
            correlationId = correlationId,
            body = body,
        )
        try {
            synchronized(writeLock) {
                sslSocket.outputStream.write(frame)
                sslSocket.outputStream.flush()
            }
            return withTimeout(15_000) { deferred.await() }
        } catch (e: Exception) {
            closeConnection(e)
            throw e
        } finally {
            synchronized(stateLock) { pending.remove(correlationId) }
        }
    }

    private fun readLoop(sslSocket: SSLSocket) {
        val buffer = ByteArray(8192)
        try {
            while (!closed) {
                val read = sslSocket.inputStream.read(buffer)
                if (read < 0) throw EOFException("BLCP connection reached EOF")
                if (read == 0) continue
                readBuffer += buffer.copyOfRange(0, read)
                val (frames, rest) = BlcpFrame.splitFrames(readBuffer)
                readBuffer = rest
                frames.forEach { frame ->
                    val decoded = runCatching { BlcpFrame.decode(frame) }.getOrNull() ?: return@forEach
                    val correlationId = decoded.meta.correlation_id ?: 0L
                    synchronized(stateLock) {
                        pending.remove(correlationId)?.complete(decoded)
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed) closeConnection(e)
        }
    }

    private fun nextCorrelationId(): Long {
        val high = random.nextInt(1 shl 30).toLong()
        val low = random.nextInt(1 shl 30).toLong()
        return (high shl 32) or (low shl 2) or random.nextInt(4).toLong()
    }

    /**
     * 解析 BLCP 响应体。服务端异常时可能返回空包，这里给出可读错误而不是 JSON 解析异常。
     */
    private fun parseJsonBody(body: ByteArray, scene: String): JSONObject {
        val text = String(body, Charsets.UTF_8)
        return runCatching { JSONObject(text) }.getOrElse {
            throw IllegalStateException("$scene failed: empty or invalid response")
        }
    }
}

/**
 * IM 登录后拿到的聊天身份。
 */
data class BlcpImIdentity(
    val uk: Long,
    val bduk: String,
    val triggerId: Long,
)
