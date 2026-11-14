package com.huanchengfly.tieba.post.api.chat.ws

/**
 * 聊天 WebSocket 用到的命令号与协议常量。
 */
object ChatWsConstants {

    /** 握手：上报 ws_sec_key 与账号信息。 */
    const val CMD_UPDATE_CLIENT_INFO = 1001

    /** 拉取消息组新消息。 */
    const val CMD_GET_GROUP_MSG = 202003

    /** 发送私信。 */
    const val CMD_SEND_MSG = 205001

    /** 上报已读。 */
    const val CMD_SET_MSG_READED = 205006

    /** 服务端推送（无请求侧）。 */
    const val CMD_PUSH_NOTIFY = 202006

    /** 私信消息组类型。 */
    const val GROUP_TYPE_PRIVATE_MSG = 6

    /** 消息类型：私信。 */
    const val MSG_TYPE_PRIVATE = 1

    /** 消息类型：已读回执。 */
    const val MSG_TYPE_READED = 22

    /** 消息类型：私信图片。 */
    const val MSG_TYPE_PRIVATE_IMAGE = 2

    /** 私信渠道接入地址。 */
    const val WS_HOST = "im.tieba.baidu.com"
    const val WS_PORT = 8000
    const val WS_PATH = "/"

    /** 握手时声明的自定义扩展，服务端会校验该值。 */
    const val WS_EXTENSIONS = "im_version=2.3"

    /** 聊天接口使用的客户端版本，过旧会被服务端判定为 110001。 */
    const val CHAT_VERSION = "22.13.1.1"
}
