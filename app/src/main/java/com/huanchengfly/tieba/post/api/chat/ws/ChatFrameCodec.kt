package com.huanchengfly.tieba.post.api.chat.ws

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 贴吧聊天 WebSocket 的二进制帧编解码。
 *
 * 帧格式（大端）：
 * ```
 * flag (1B) | cmd (4B) | reqId (4B) | data
 * ```
 * flag 位含义：
 * - 0x08：protobuf 负载，固定置位
 * - 0x40：负载经 gzip 压缩
 * - 0x80：负载经 AES-256-ECB 加密
 */
object ChatFrameCodec {

    private const val FLAG_PROTOBUF = 0x08
    private const val FLAG_COMPRESSED = 0x40
    private const val FLAG_ENCRYPTED = 0x80

    /** 派生 AES 密钥用的 salt。 */
    private val KEY_SALT = byteArrayOf(
        0xA4.toByte(), 0x0B, 0xC8.toByte(), 0x34,
        0xD6.toByte(), 0x95.toByte(), 0xF3.toByte(), 0x13,
    )

    /** PBKDF2 迭代次数。 */
    private const val KEY_ITERATIONS = 5

    /** 派生出的 AES 密钥长度（位）。 */
    private const val KEY_LENGTH_BITS = 256

    data class Frame(
        val data: ByteArray,
        val cmd: Int,
        val reqId: Int,
    )

    /**
     * 由 ws_sec_key 派生 32 字节 AES 密钥：PBKDF2-HMAC-SHA1。
     *
     * 这里手写实现，因为 PBEKeySpec 以 char[] 传密码会按 UTF-8 重新编码，
     * 与参考实现按原始字节计算的结果不一致。
     */
    fun deriveAesKey(wsSecKey: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(wsSecKey, "HmacSHA1"))

        val output = ByteArray(KEY_LENGTH_BITS / 8)
        var offset = 0
        var blockIndex = 1
        while (offset < output.size) {
            val block = pbkdf2Block(mac, KEY_SALT, blockIndex)
            val length = minOf(block.size, output.size - offset)
            block.copyInto(output, offset, 0, length)
            offset += length
            blockIndex++
        }
        return output
    }

    private fun pbkdf2Block(mac: Mac, salt: ByteArray, blockIndex: Int): ByteArray {
        // U1 = PRF(password, salt || INT_32_BE(blockIndex))
        val firstInput = ByteArray(salt.size + 4)
        salt.copyInto(firstInput)
        writeInt(firstInput, salt.size, blockIndex)
        var u = mac.doFinal(firstInput)
        val result = u.copyOf()

        repeat(KEY_ITERATIONS - 1) {
            u = mac.doFinal(u)
            for (i in result.indices) {
                result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
            }
        }
        return result
    }

    /**
     * 打包一帧。
     */
    fun pack(
        wsSecKey: ByteArray,
        data: ByteArray,
        cmd: Int,
        reqId: Int,
        compress: Boolean = false,
        encrypt: Boolean = true,
    ): ByteArray {
        var flag = FLAG_PROTOBUF
        var payload = data

        if (compress) {
            flag = flag or FLAG_COMPRESSED
            payload = gzip(payload)
        }
        if (encrypt) {
            flag = flag or FLAG_ENCRYPTED
            payload = aesEcbEncrypt(deriveAesKey(wsSecKey), payload)
        }

        val frame = ByteArray(9 + payload.size)
        frame[0] = flag.toByte()
        writeInt(frame, 1, cmd)
        writeInt(frame, 5, reqId)
        payload.copyInto(frame, 9)
        return frame
    }

    /**
     * 解析一帧。
     */
    fun parse(wsSecKey: ByteArray, raw: ByteArray): Frame {
        require(raw.size >= 9) { "websocket frame is too short" }
        val flag = raw[0].toInt() and 0xFF
        val cmd = readInt(raw, 1)
        val reqId = readInt(raw, 5)
        var payload = raw.copyOfRange(9, raw.size)

        if (flag and FLAG_ENCRYPTED != 0) {
            payload = aesEcbDecrypt(deriveAesKey(wsSecKey), payload)
        }
        if (flag and FLAG_COMPRESSED != 0) {
            payload = ungzip(payload)
        }
        return Frame(payload, cmd, reqId)
    }

    private fun aesEcbEncrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(plain)
    }

    private fun aesEcbDecrypt(key: ByteArray, encrypted: ByteArray): ByteArray {
        require(encrypted.isNotEmpty() && encrypted.size % 16 == 0) {
            "invalid AES-ECB payload length"
        }
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(encrypted)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun ungzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            input.copyTo(out)
        }
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
