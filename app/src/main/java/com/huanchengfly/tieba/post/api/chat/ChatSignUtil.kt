package com.huanchengfly.tieba.post.api.chat

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 聊天相关接口的签名工具。
 *
 * 贴吧聊天接口（pim.baidu.com）与普通业务接口的签名规则不同：
 * 普通接口会在末尾拼接 salt `tiebaclient!!!`，聊天接口的 salt 为空串。
 *
 * 签名算法为 md5(按 (key, value) 排序拼接的 "k=v" 串 + salt)，
 * 排序先比较 key，key 相同再比较 value，与 aiotieba 的 sorted(data) 一致。
 *
 * 注意：摘要必须是 32 位小写十六进制。服务端按小写比对，
 * 大写会被判定为签名错误并返回 110001。
 */
object ChatSignUtil {

    fun sign(params: Map<String, String>, salt: String = ""): String {
        val raw = params.entries
            .sortedWith(compareBy({ it.key }, { it.value }))
            .joinToString(separator = "") { "${it.key}=${it.value}" } + salt
        return md5Hex(raw)
    }

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
}
