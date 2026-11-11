package com.huanchengfly.tieba.post.api.chat.blcp

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * BLCP 聊天用到的加密工具。
 *
 * bduk 用于把 uid 编码成消息体里的 baidu_uk 字段，算法为
 * AES-128-CBC（固定 key/iv）后做 URL-safe Base64 去掉填充。
 */
object BlcpCrypto {

    private const val AES_KEY = "AFD311832EDEEAEF"
    private const val AES_IV = "2011121211143000"

    private val random = SecureRandom()

    /**
     * uid 转 bduk。
     */
    fun userId2Bduk(userId: Long): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec(AES_IV.toByteArray(Charsets.US_ASCII)),
        )
        val encrypted = cipher.doFinal(userId.toString().toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)
    }

    /**
     * bduk 转 uid。
     */
    fun bduk2UserId(bduk: String): Long {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec(AES_IV.toByteArray(Charsets.US_ASCII)),
        )
        val encrypted = Base64.getUrlDecoder().decode(bduk)
        val plain = cipher.doFinal(encrypted)
        return String(plain, Charsets.UTF_8).toLong()
    }

    /**
     * 消息去重 key：bduk + 微秒时间戳 + 随机长整型。
     */
    fun computeMsgKey(bduk: String): String {
        val timestamp = System.currentTimeMillis() * 1000
        val randomLong = random.nextLong()
        return "$bduk$timestamp$randomLong"
    }
}
