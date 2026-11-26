package com.huanchengfly.tieba.post.api.chat.http

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 官方私信接口的对方标识编解码。
 *
 * 官方 com/baidu/android/imsdk/utils/Utility 的 transBDUID 用 AES-128-CBC + PKCS5Padding
 * 把贴吧 uid 加密成 Base64 串放进请求的 contacter 字段，transBDUK 是它的逆运算。
 * 这不是字符表置换，必须原样实现才能通过服务端校验。
 */
object ImBduid {

    /**
     * 官方内置的固定密钥。
     *
     * 这里用逐字节十六进制字面量构造，避免字符串常量在传输过程中被截断。
     */
    internal val KEY: ByteArray = byteArrayOf(0x41, 0x46, 0x44, 0x33, 0x31, 0x31, 0x38, 0x33, 0x32, 0x45, 0x44, 0x45, 0x45, 0x41, 0x45, 0x46)

    /** 官方内置的固定初始向量，16 字节。 */
    internal const val IV = "2011121211143000"

    private fun cipher(mode: Int): Cipher =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV.toByteArray()))
        }

    /**
     * 贴吧 uid 加密为请求里的 contacter。
     */
    fun transBDUID(uid: String): String {
        if (uid.isEmpty()) return ""
        return runCatching {
            val encrypted = cipher(Cipher.ENCRYPT_MODE).doFinal(uid.toByteArray(Charsets.UTF_8))
            // 官方用 Android 的 Base64.NO_WRAP，保留 = 填充且不换行。
            Base64.getEncoder().encodeToString(encrypted)
        }.getOrDefault("")
    }

    /**
     * 请求里的 passer 标识解密回贴吧 uid。
     */
    fun transBDUK(bduk: String): String {
        if (bduk.isEmpty()) return ""
        return runCatching {
            val raw = Base64.getDecoder().decode(bduk)
            String(cipher(Cipher.DECRYPT_MODE).doFinal(raw), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
