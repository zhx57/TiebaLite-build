package com.huanchengfly.tieba.post.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.huanchengfly.tieba.post.api.chat.ws.ChatWsMessage
import com.huanchengfly.tieba.post.api.models.chat.ChatCloudMeme
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageKind
import com.huanchengfly.tieba.post.api.models.chat.ChatMessageSemantics
import com.huanchengfly.tieba.post.api.models.chat.ChatRoomMessage
import com.huanchengfly.tieba.post.api.models.chat.SendState

/** 群模型由现有 R8 规则保留；私信显式指定字段名，避免混淆影响持久格式。 */
object ChatMessageCodec {
    private val gson = Gson()

    fun encodeGroup(message: ChatRoomMessage): String = gson.toJson(message)
    fun decodeGroup(payload: String): ChatRoomMessage = gson.fromJson(payload, ChatRoomMessage::class.java)

    fun encodePrivate(message: ChatWsMessage): String = JsonObject().apply {
        addProperty("msgId", message.msgId)
        addProperty("peerUid", message.peerUid)
        addProperty("peerName", message.peerName)
        addProperty("peerPortrait", message.peerPortrait)
        addProperty("content", message.content)
        addProperty("msgTime", message.msgTime)
        addProperty("isSelf", message.isSelf)
        addProperty("sendState", message.sendState.name)
        addProperty("isLocal", message.isLocal)
        addProperty("msgType", message.msgType)
        add("images", gson.toJsonTree(message.images))
        add("cloudMeme", gson.toJsonTree(message.cloudMeme))
        addProperty("localPhotoUri", message.localPhotoUri)
        add("semantics", gson.toJsonTree(message.semantics))
        addProperty("conversationUid", message.conversationUid)
        addProperty("senderUid", message.senderUid)
        addProperty("deliveryConfirmed", message.deliveryConfirmed)
        addProperty("localId", message.localId)
        addProperty("msgKey", message.msgKey)
        addProperty("sendError", message.sendError)
        addProperty("transmissionStarted", message.transmissionStarted)
    }.toString()

    fun decodePrivate(payload: String): ChatWsMessage {
        val json = gson.fromJson(payload, JsonObject::class.java)
        fun string(key: String) = json.get(key)?.takeUnless { it.isJsonNull }?.asString
        fun long(key: String) = json.get(key)?.asLong ?: 0L
        fun bool(key: String) = json.get(key)?.asBoolean ?: false
        return ChatWsMessage(
            msgId = long("msgId"), peerUid = long("peerUid"),
            peerName = string("peerName").orEmpty(), peerPortrait = string("peerPortrait").orEmpty(),
            content = string("content").orEmpty(), msgTime = long("msgTime"), isSelf = bool("isSelf"),
            sendState = string("sendState")?.let(SendState::valueOf) ?: SendState.SENT,
            isLocal = bool("isLocal"), msgType = long("msgType").toInt(),
            images = json.getAsJsonArray("images")?.map { it.asString }.orEmpty(),
            cloudMeme = gson.fromJson(json.get("cloudMeme"), ChatCloudMeme::class.java),
            localPhotoUri = string("localPhotoUri"),
            semantics = gson.fromJson(json.get("semantics"), ChatMessageSemantics::class.java) ?: ChatMessageSemantics(),
            conversationUid = long("conversationUid"), senderUid = long("senderUid"),
            deliveryConfirmed = bool("deliveryConfirmed"), localId = string("localId").orEmpty(),
            msgKey = string("msgKey").orEmpty(), sendError = string("sendError"),
            transmissionStarted = bool("transmissionStarted"),
        )
    }

    private fun semantics(old: ChatMessageSemantics, new: ChatMessageSemantics): ChatMessageSemantics =
        if (new.kind == ChatMessageKind.UNKNOWN && old.kind != ChatMessageKind.UNKNOWN) old
        else new.copy(rawPayload = new.rawPayload ?: old.rawPayload)

    fun mergeGroup(old: ChatRoomMessage, incoming: ChatRoomMessage): ChatRoomMessage {
        val keepSent = old.sendState == SendState.SENT && incoming.sendState != SendState.SENT
        val new = if (keepSent) old else incoming
        return new.copy(
            localId = old.localId,
            msgKey = incoming.msgKey.ifBlank { old.msgKey },
            msgId = if (keepSent) old.msgId else incoming.msgId,
            displayTime = old.displayTime,
            images = (new.images + old.images).distinct(),
            localPhotoUri = old.localPhotoUri ?: new.localPhotoUri,
            cloudMeme = new.cloudMeme ?: old.cloudMeme,
            text = new.text.ifBlank { old.text },
            senderName = new.senderName.ifBlank { old.senderName },
            senderPortrait = new.senderPortrait.ifBlank { old.senderPortrait },
            senderBaiduUk = new.senderBaiduUk.ifBlank { old.senderBaiduUk },
            mentions = new.mentions.ifEmpty { old.mentions },
            robotSkill = new.robotSkill ?: old.robotSkill,
            knownServerIdsAtSend = old.knownServerIdsAtSend + new.knownServerIdsAtSend,
            echoForPendingId = new.echoForPendingId ?: old.echoForPendingId,
            transportAccepted = if (new.sendState == SendState.SENDING && old.sendState != SendState.SENDING) {
                new.transportAccepted
            } else old.transportAccepted || new.transportAccepted,
            transmissionStarted = if (new.sendState == SendState.SENDING && old.sendState != SendState.SENDING) {
                new.transmissionStarted
            } else old.transmissionStarted || new.transmissionStarted,
            sendError = if (new.sendState == SendState.SENT) null else new.sendError,
            semantics = semantics(old.semantics, new.semantics),
            voiceUrl = new.voiceUrl ?: old.voiceUrl,
            voiceDurationMs = new.voiceDurationMs.takeIf { it > 0 } ?: old.voiceDurationMs,
        )
    }

    fun mergePrivate(old: ChatWsMessage, incoming: ChatWsMessage): ChatWsMessage {
        val new = if (old.sendState == SendState.SENT && incoming.sendState != SendState.SENT) old else incoming
        return new.copy(
            localId = old.localId, msgKey = incoming.msgKey.ifBlank { old.msgKey },
            images = (new.images + old.images).distinct(),
            localPhotoUri = old.localPhotoUri ?: new.localPhotoUri,
            cloudMeme = new.cloudMeme ?: old.cloudMeme,
            content = new.content.ifBlank { old.content },
            peerName = new.peerName.ifBlank { old.peerName },
            peerPortrait = new.peerPortrait.ifBlank { old.peerPortrait },
            senderUid = new.senderUid.takeIf { it > 0 } ?: old.senderUid,
            conversationUid = new.conversationUid.takeIf { it > 0 } ?: old.conversationUid,
            deliveryConfirmed = old.deliveryConfirmed || new.deliveryConfirmed,
            transmissionStarted = if (new.sendState == SendState.SENDING && old.sendState != SendState.SENDING) {
                new.transmissionStarted
            } else old.transmissionStarted || new.transmissionStarted,
            sendError = if (new.sendState == SendState.SENT) null else new.sendError,
            semantics = semantics(old.semantics, new.semantics),
        )
    }
}
