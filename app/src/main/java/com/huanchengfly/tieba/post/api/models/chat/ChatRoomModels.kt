package com.huanchengfly.tieba.post.api.models.chat

/**
 * 吧群（群聊）相关数据模型。
 *
 * 群列表与群详情接口返回 JSON，字段名与官方客户端一致，使用南向解析工具处理，
 * 不做完整强类型建模，避免服务端字段变动导致解析失败。
 */
data class ChatRoom(
    val roomId: Long,
    val forumId: Long,
    val forumName: String,
    val name: String,
    val avatar: String,
    val discussNum: Int,
    val announce: String,
)

data class ChatRoomMessage(
    val msgId: Long,
    val senderUid: Long,
    val senderName: String,
    val senderPortrait: String,
    val contentType: Int,
    val text: String,
    val msgTime: Long,
    val isSelf: Boolean,
    /** 图片消息的图片地址，多图时有多项；非图片消息为空。 */
    val images: List<String> = emptyList(),
    /** 本地发送状态；服务端返回的消息恒为 [SendState.SENT]。 */
    val sendState: SendState = SendState.SENT,
    /** 是否是本地插入的占位消息；服务端返回的消息恒为 false。 */
    val isLocal: Boolean = false,
)

/**
 * 本地消息的发送状态。
 */
enum class SendState {
    /** 发送中，界面显示转圈。 */
    SENDING,

    /** 发送成功，与服务端消息一致。 */
    SENT,

    /** 发送失败，界面显示感叹号，可点击重发。 */
    FAILED,
}
