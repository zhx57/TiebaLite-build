package com.huanchengfly.tieba.post.api.chat.blcp

/**
 * BLCP 协议常量。
 */
object BlcpConstants {

    /** LCP 帧前缀，末字节为协议版本。 */
    val FRAME_PREFIX = byteArrayOf(0x6C, 0x63, 0x70, 0x01)

    /** BLCP 网关。 */
    const val HOST = "common.lcs.baidu.com"
    const val PORT = 443

    /** 聊天 appid。 */
    const val CHAT_APP_ID = 10773430

    /** IM SDK 版本。 */
    const val CHAT_SDK_VERSION = 11250036

    /** LCP SDK 版本。 */
    const val LCP_SDK_VERSION = "3460016"

    /** 聊天客户端版本，过旧会被服务端判定为 110001。 */
    const val CHAT_VERSION = "22.13.1.1"

    /** 服务号。 */
    const val SERVICE_LCM = 1
    const val SERVICE_IM = 2
    const val SERVICE_MCAST = 3
    const val SERVICE_CLOUD_CONTROL = 4

    /** 方法号。 */
    const val METHOD_LOGIN = 1
    const val METHOD_HEARTBEAT = 3
    const val METHOD_LOGIN_BY_TOKEN = 50
    const val METHOD_SEND_MCAST_MSG = 185
    const val METHOD_JOIN_CAST = 201
}
