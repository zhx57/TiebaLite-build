package com.huanchengfly.tieba.post.api.chat.blcp

import com.huanchengfly.tieba.post.api.models.protos.blcp.EventTimestamp
import com.huanchengfly.tieba.post.api.models.protos.blcp.RpcMeta
import com.huanchengfly.tieba.post.api.models.protos.blcp.RpcNotifyMeta
import com.huanchengfly.tieba.post.api.models.protos.blcp.RpcRequestMeta
import com.huanchengfly.tieba.post.api.models.protos.blcp.RpcResponseMeta
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * LCP 帧编解码。
 *
 * 帧格式（大端）：
 * ```
 * "lcp" 0x01 | totalLength (4B) | headerLength (4B) | header | body
 * ```
 * header 为序列化后的 RpcMeta，body 在 LCM 控制面是 RpcData protobuf，
 * 其余服务是 UTF-8 JSON，两者都可能按 header 声明做 gzip 压缩。
 */
object BlcpFrame {

    private const val MAX_FRAME_SIZE = 16 * 1024 * 1024

    data class Decoded(
        val meta: RpcMeta,
        val body: ByteArray,
    )

    /**
     * 构造一帧请求。
     */
    fun buildRequest(
        serviceId: Int,
        methodId: Int,
        correlationId: Long,
        body: ByteArray,
        compress: Boolean = false,
    ): ByteArray {
        val meta = RpcMeta(
            correlation_id = correlationId,
            request = RpcRequestMeta(
                service_id = serviceId.toLong(),
                method_id = methodId.toLong(),
                log_id = correlationId,
                need_common = 1,
                event_list = listOf(
                    EventTimestamp(
                        event = "CLCPReqBegin",
                        timestamp_ms = System.currentTimeMillis(),
                    )
                ),
            ),
            compress_type = if (compress) 1 else 0,
            accept_compress_type = 1,
        )
        val header = meta.encode()
        val payload = if (compress) gzip(body) else body
        return frame(header, payload)
    }

    /**
     * 解出一帧。
     */
    fun decode(bytes: ByteArray): Decoded {
        require(bytes.size >= 12) { "LCP frame is too short" }
        require(
            bytes[0] == BlcpConstants.FRAME_PREFIX[0] &&
                bytes[1] == BlcpConstants.FRAME_PREFIX[1] &&
                bytes[2] == BlcpConstants.FRAME_PREFIX[2] &&
                bytes[3] == BlcpConstants.FRAME_PREFIX[3]
        ) { "missing LCP frame prefix" }
        val totalLength = readInt(bytes, 4)
        val headerLength = readInt(bytes, 8)
        require(headerLength <= totalLength) { "invalid LCP frame lengths" }
        val header = bytes.copyOfRange(12, 12 + headerLength)
        val rawBody = bytes.copyOfRange(12 + headerLength, 12 + totalLength)
        val meta = RpcMeta.ADAPTER.decode(header)
        val body = if (meta.compress_type == 1) ungzip(rawBody) else rawBody
        return Decoded(meta, body)
    }

    /**
     * 从字节流中切出所有完整帧，返回剩余未消费的字节。
     *
     * 用于 TLS 流式读取时增量解析。
     */
    fun splitFrames(buffer: ByteArray): Pair<List<ByteArray>, ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (true) {
            // 跳过前导垃圾数据，找到帧前缀
            while (offset + 4 <= buffer.size && !isPrefixAt(buffer, offset)) {
                offset++
            }
            if (offset + 12 > buffer.size) break
            val totalLength = readInt(buffer, offset + 4)
            if (totalLength <= 0 || totalLength > MAX_FRAME_SIZE) {
                offset += 4
                continue
            }
            val frameLength = 12 + totalLength
            if (offset + frameLength > buffer.size) break
            frames += buffer.copyOfRange(offset, offset + frameLength)
            offset += frameLength
        }
        return frames to buffer.copyOfRange(offset, buffer.size)
    }

    private fun isPrefixAt(buffer: ByteArray, offset: Int): Boolean {
        val prefix = BlcpConstants.FRAME_PREFIX
        return buffer[offset] == prefix[0] &&
            buffer[offset + 1] == prefix[1] &&
            buffer[offset + 2] == prefix[2] &&
            buffer[offset + 3] == prefix[3]
    }

    private fun frame(header: ByteArray, body: ByteArray): ByteArray {
        val totalLength = header.size + body.size
        val out = ByteArray(12 + totalLength)
        BlcpConstants.FRAME_PREFIX.copyInto(out, 0)
        writeInt(out, 4, totalLength)
        writeInt(out, 8, header.size)
        header.copyInto(out, 12)
        body.copyInto(out, 12 + header.size)
        return out
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun ungzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(data)).use { it.copyTo(out) }
        return out.toByteArray()
    }

    private fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 24) and 0xFF).toByte()
        data[offset + 1] = ((value shr 16) and 0xFF).toByte()
        data[offset + 2] = ((value shr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }
}
