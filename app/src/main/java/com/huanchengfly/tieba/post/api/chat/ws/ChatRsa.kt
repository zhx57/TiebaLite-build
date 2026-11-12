package com.huanchengfly.tieba.post.api.chat.ws

import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/**
 * 握手阶段用百度公钥加密 ws_sec_key 的工具。
 *
 * 官方客户端内置的是一把 2048 位 RSA 公钥，这里只需要模数与指数，
 * 采用 PKCS#1 v1.5 填充。
 */
object ChatRsa {

    private const val MODULUS_HEX =
        "c10a70059c57255fc955117fb8d7f231276eed85b044d2ccf3edb16e7886a7" +
            "68884073a290fa536108e5420322d6ebead64669277dab1e8de19308f2da5d25" +
            "170af7bb81e4d9693bbe7b2a6108c095894ad130b38e99a223e3a23ce50d56d6" +
            "d61d088b895a85b73be9be2e0053b0b588a1af73fe084b8e568c7c5e49c1e19d9" +
            "67b9e34b84b552034ffc67e54486f381b061c99f64116e7f38ee587c6d5fcbaa" +
            "f8292943270e28fe6c395e17edc88a4bfb96415410ba13510888ffdd77d01b2" +
            "bad955753c1c9f3cc1cc8b1a7123718cdaac1c985b17702720bd45c905af64e" +
            "b33fc74343772c08cd90353f824003b1b618eda7f4c559a0fb33339db0861bdd" +
            "31f"

    private const val EXPONENT = 65537

    /**
     * 用 PKCS#1 v1.5 填充加密 [wsSecKey]。
     */
    fun encryptWsSecKey(wsSecKey: ByteArray): ByteArray {
        val modulus = BigInteger(MODULUS_HEX, 16)
        val spec = RSAPublicKeySpec(modulus, BigInteger.valueOf(EXPONENT.toLong()))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(wsSecKey)
    }
}
