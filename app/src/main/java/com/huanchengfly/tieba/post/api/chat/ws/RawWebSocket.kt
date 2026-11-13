package com.huanchengfly.tieba.post.api.chat.ws

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 贴吧聊天用的极简 WebSocket 客户端。
 *
 * 不能直接用 OkHttp 的 WebSocket：
 * 1. OkHttp 会强制协商 `permessage-deflate`，且禁止手动设置
 *    `Sec-WebSocket-Extensions`，而贴吧服务端要求该头为 `im_version=2.3`；
 * 2. 服务端不认 OkHttp 的扩展协商，握手后会立刻断开，表现为
 *    `unexpected end of stream`。
 *
 * 因此这里用原始 Socket 完成 HTTP Upgrade 与帧编解码，只实现本项目需要的
 * 二进制帧、ping/pong 与 close。
 */
class RawWebSocket(
    private val host: String,
    private val port: Int,
    private val path: String = "/",
    private val headers: Map<String, String> = emptyMap(),
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 0,
) {

    private companion object {
        const val OP_CONTINUATION = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
    }

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val random = SecureRandom()

    @Volatile
    private var closed = false

    /** 收到完整二进制消息时回调。 */
    @Volatile
    var onBinary: ((ByteArray) -> Unit)? = null

    /** 连接断开时回调。 */
    @Volatile
    var onClosed: ((Throwable?) -> Unit)? = null

    /**
     * 建立 TCP 连接并完成 WebSocket 握手。
     */
    fun connect() {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = readTimeoutMs
        socket = s
        input = BufferedInputStream(s.getInputStream())
        output = s.getOutputStream()
        closed = false
        handshake()
        startReader()
    }

    /**
     * 发送二进制消息。
     */
    @Synchronized
    fun sendBinary(payload: ByteArray): Boolean {
        if (closed) return false
        return runCatching {
            writeFrame(OP_BINARY, payload)
            true
        }.getOrDefault(false)
    }

    /**
     * 关闭连接。
     */
    fun close() {
        val wasClosed = closed
        closed = true
        if (!wasClosed) runCatching { writeFrame(OP_CLOSE, ByteArray(0)) }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun handshake() {
        val key = ByteArray(16).also { random.nextBytes(it) }
        val keyBase64 = Base64.getEncoder().encodeToString(key)
        val builder = StringBuilder()
        builder.append("GET ").append(path).append(" HTTP/1.1\r\n")
        builder.append("Host: ").append(host).append(':').append(port).append("\r\n")
        builder.append("Upgrade: websocket\r\n")
        builder.append("Connection: Upgrade\r\n")
        builder.append("Sec-WebSocket-Key: ").append(keyBase64).append("\r\n")
        builder.append("Sec-WebSocket-Version: 13\r\n")
        headers.forEach { (k, v) ->
            if (v.isNotEmpty()) builder.append(k).append(": ").append(v).append("\r\n")
        }
        builder.append("\r\n")
        output?.write(builder.toString().toByteArray(Charsets.UTF_8))
        output?.flush()

        // 读取响应头，直到空行
        val headerBytes = readHttpHeader()
        val headerText = String(headerBytes, Charsets.ISO_8859_1)
        val statusLine = headerText.lineSequence().firstOrNull().orEmpty()
        if (!statusLine.contains("101")) {
            throw IllegalStateException("websocket handshake failed: $statusLine")
        }
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((keyBase64 + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.UTF_8))
        )
        val accept = headerText.lineSequence()
            .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        if (accept != null && accept != expected) {
            throw IllegalStateException("websocket accept key mismatch")
        }
    }

    private fun readHttpHeader(): ByteArray {
        val stream = input ?: throw IllegalStateException("websocket input is null")
        val buffer = java.io.ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = stream.read()
            if (b == -1) throw IllegalStateException("websocket closed during handshake")
            buffer.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (buffer.size() > 8192) throw IllegalStateException("websocket header too large")
        }
        return buffer.toByteArray()
    }

    private fun startReader() {
        Thread({
            var fragmented = java.io.ByteArrayOutputStream()
            var failure: Throwable? = null
            try {
                while (!closed) {
                    val first = input?.read() ?: break
                    if (first == -1) break
                    val b0 = first
                    val b1 = input!!.read()
                    if (b1 == -1) break
                    val opcode = b0 and 0x0F
                    val fin = (b0 and 0x80) != 0
                    val masked = (b1 and 0x80) != 0
                    var length = (b1 and 0x7F).toLong()
                    if (length == 126L) {
                        length = ((input!!.read() shl 8) or input!!.read()).toLong()
                    } else if (length == 127L) {
                        var l = 0L
                        repeat(8) { l = (l shl 8) or input!!.read().toLong() }
                        length = l
                    }
                    val maskKey = if (masked) ByteArray(4).also { input!!.readFully(it) } else null
                    val payload = ByteArray(length.toInt())
                    input!!.readFully(payload)
                    if (maskKey != null) {
                        for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                    }

                    when (opcode) {
                        OP_BINARY, OP_TEXT, OP_CONTINUATION -> {
                            fragmented.write(payload)
                            if (fin) {
                                onBinary?.invoke(fragmented.toByteArray())
                                fragmented = java.io.ByteArrayOutputStream()
                            }
                        }

                        OP_PING -> runCatching { writeFrame(OP_PONG, payload) }
                        OP_CLOSE -> break

                        OP_PONG -> Unit
                    }
                }
            } catch (e: Exception) {
                failure = e
            } finally {
                val notifyClosed = !closed
                closed = true
                runCatching { socket?.close() }
                if (notifyClosed) onClosed?.invoke(failure)
            }
        }, "raw-ws-reader").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val out = output ?: throw IllegalStateException("websocket output is null")
        val b0 = (0x80 or opcode).toByte()
        val maskKey = ByteArray(4).also { random.nextBytes(it) }
        val header = java.io.ByteArrayOutputStream()
        header.write(b0.toInt() and 0xFF)
        val len = payload.size
        when {
            len < 126 -> header.write(0x80 or len)
            len <= 0xFFFF -> {
                header.write(0x80 or 126)
                header.write((len shr 8) and 0xFF)
                header.write(len and 0xFF)
            }

            else -> {
                header.write(0x80 or 127)
                for (i in 7 downTo 0) header.write(((len.toLong() shr (i * 8)) and 0xFF).toInt())
            }
        }
        header.write(maskKey)
        val masked = ByteArray(len) { i -> (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte() }
        out.write(header.toByteArray())
        out.write(masked)
        out.flush()
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read <= 0) throw IllegalStateException("websocket stream ended")
            offset += read
        }
    }
}
