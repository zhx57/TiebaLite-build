package com.huanchengfly.tieba.post.api.models.chat

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

data class ChatMention(
    val uid: Long,
    val baiduUk: String,
    val name: String,
    val portrait: String,
)

data class ChatRobotSkill(
    val type: Int,
    val name: String,
    val alias: String,
    val description: String,
)

data class ChatRobot(val user: ChatMention, val skills: List<ChatRobotSkill>)

enum class ChatMessageKind {
    TEXT,
    IMAGE,
    VOICE,
    LARGE_EMOTICON,
    IMAGE_TO_TEXT,
    SINGLE_TEXT_IMAGE,
    TEXT_TO_SPEECH,
    AI_INTRODUCTION,
    WEB_CHAT,
    POST_SHARE,
    DELETE_EVENT,
    EMOTION_REACTION,
    SYSTEM_EVENT,
    CONTROL,
    UNKNOWN,
}

data class ChatMessageSemantics(
    val kind: ChatMessageKind = ChatMessageKind.UNKNOWN,
    val protocolSource: String = "legacy",
    val transportType: Int = 0,
    val businessType: Int? = null,
    val targetId: Long? = null,
    val summary: String = "未知消息",
    val rawPayload: String? = null,
) {
    companion object {
        const val MAX_RAW_PAYLOAD_LENGTH = 4096

        fun classify(
            transportType: Int,
            businessType: Int?,
            system: Boolean = false,
        ): ChatMessageKind = when {
            system && businessType == 7025 -> ChatMessageKind.DELETE_EVENT
            system && businessType == 20000 -> ChatMessageKind.EMOTION_REACTION
            system && businessType == 7010 -> ChatMessageKind.POST_SHARE
            system -> ChatMessageKind.SYSTEM_EVENT
            businessType == 1 -> ChatMessageKind.TEXT
            businessType == 2 -> ChatMessageKind.IMAGE
            businessType == 3 -> ChatMessageKind.VOICE
            businessType == 4 -> ChatMessageKind.LARGE_EMOTICON
            businessType == 101 -> ChatMessageKind.IMAGE_TO_TEXT
            businessType == 102 -> ChatMessageKind.SINGLE_TEXT_IMAGE
            businessType == 103 -> ChatMessageKind.TEXT_TO_SPEECH
            businessType == 104 -> ChatMessageKind.AI_INTRODUCTION
            businessType == 1001 || businessType == 1002 -> ChatMessageKind.WEB_CHAT
            transportType == 1 && businessType == null -> ChatMessageKind.TEXT
            else -> ChatMessageKind.UNKNOWN
        }

        fun limitedRawPayload(raw: String?): String? = raw
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_RAW_PAYLOAD_LENGTH)
    }
}

data class ChatCloudMeme(
    val id: String,
    val imageUrl: String,
    val uploadedImageUrl: String? = null,
    val uploadedThumbnailUrl: String? = null,
) {
    init {
        require(id.isNotBlank()) { "云表情 id 不能为空" }
        require(imageUrl.toHttpUrlOrNull()?.let { it.host.isNotBlank() && it.scheme in setOf("http", "https") } == true) {
            "云表情图片地址必须是完整的 http(s) URL"
        }
        listOf(uploadedImageUrl, uploadedThumbnailUrl).filterNotNull().forEach { url ->
            require(url.toHttpUrlOrNull()?.let { it.host.isNotBlank() && it.scheme in setOf("http", "https") } == true) {
                "上传后的聊天图片地址必须是完整的 http(s) URL"
            }
        }
    }

    val sendImageUrl: String
        get() = uploadedImageUrl ?: imageUrl

    val sendThumbnailUrl: String
        get() = uploadedThumbnailUrl ?: sendImageUrl
}

data class ChatRoomMessage(
    val msgId: Long,
    val senderUid: Long,
    val senderName: String,
    val senderPortrait: String,
    val contentType: Int,
    val text: String,
    val msgTime: Long,
    val isSelf: Boolean,
    /** 本地消息的展示时间；回显替换时保留本地插入位置。 */
    val displayTime: Long = msgTime,
    /** 图片消息的图片地址，多图时有多项；非图片消息为空。 */
    val images: List<String> = emptyList(),
    /** 本地发送状态；服务端返回的消息恒为 [SendState.SENT]。 */
    val sendState: SendState = SendState.SENT,
    /** 是否是本地插入的占位消息；服务端返回的消息恒为 false。 */
    val isLocal: Boolean = false,
    val mentions: List<ChatMention> = emptyList(),
    val robotSkill: Int? = null,
    val senderBaiduUk: String = "",
    /** 回显早于发送确认时，保留一对一对应关系。 */
    val echoForPendingId: Long? = null,
    val transportAccepted: Boolean = false,
    val sendError: String? = null,
    val cloudMeme: ChatCloudMeme? = null,
    /** 保留群聊外层类型、内层业务类型和未知消息摘要，不覆盖旧展示字段。 */
    val semantics: ChatMessageSemantics = ChatMessageSemantics(),
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
