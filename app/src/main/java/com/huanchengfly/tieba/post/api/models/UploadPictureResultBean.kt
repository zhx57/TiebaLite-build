package com.huanchengfly.tieba.post.api.models

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable(with = UploadPictureResultSerializer::class)
data class UploadPictureResultBean(
    @SerializedName(value = "error_code", alternate = ["errorCode"])
    val errorCode: String = "",
    @SerializedName(value = "error_msg", alternate = ["errorMsg"])
    val errorMsg: String = "",
    @SerializedName(value = "resourceId", alternate = ["resource_id"])
    val resourceId: String? = null,
    @SerializedName(value = "chunkNo", alternate = ["chunk_no"])
    val chunkNo: String? = null,
    @SerializedName(value = "pic_id", alternate = ["picId"])
    val picId: String? = null,
    @SerializedName(value = "pic_info", alternate = ["picInfo"])
    val picInfo: PicInfo? = null,
    @SerializedName("info")
    val info: JsonElement? = null,
) {
    val uploadedPicture: UploadedPicture?
        get() {
            val candidates = mutableListOf<UploadedPicture>()
            if (picInfo != null || !picId.isNullOrBlank()) {
                candidates += UploadedPicture(picId?.takeIf { it.isNotBlank() }, picInfo)
            }
            val entries = when (val value = info) {
                null, JsonNull -> emptyList()
                is JsonObject -> listOf(value)
                is JsonArray -> value.map {
                    it as? JsonObject ?: throw SerializationException("上传 info 数组包含非资源对象")
                }
                else -> throw SerializationException("上传 info 不是对象或数组")
            }
            entries.mapNotNullTo(candidates) { entry ->
                val id = entry.stringValue("pic_id", "picId")
                val nested = entry.value("pic_info", "picInfo").asPicInfo()
                val flat = entry.stringValue("pic_url", "picUrl")?.let { url ->
                    UploadInfo(id, entry.stringValue("width").orEmpty(), entry.stringValue("height").orEmpty(), url).toPicInfo()
                }
                if (id == null && nested == null && flat == null) null
                else UploadedPicture(id, nested ?: flat)
            }
            val unique = candidates.distinct()
            if (unique.size > 1) throw SerializationException("上传响应包含多个图片资源，无法确定 URL 与 ID 的对应关系")
            return unique.singleOrNull()
        }

    val resolvedPicInfo: PicInfo? get() = uploadedPicture?.picInfo
    val resolvedPicId: String? get() = uploadedPicture?.picId
}

data class UploadedPicture(val picId: String?, val picInfo: PicInfo?) {
    val imageUrl: String?
        get() = picInfo?.let { info ->
            listOf(info.bigPic.picUrl, info.originPic.picUrl, info.smallPic.picUrl)
                .firstOrNull { it.isNotBlank() }
        }
    val thumbnailUrl: String? get() = picInfo?.smallPic?.picUrl?.takeIf { it.isNotBlank() } ?: imageUrl
}

object UploadPictureResultSerializer : KSerializer<UploadPictureResultBean> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): UploadPictureResultBean {
        val input = decoder as? JsonDecoder ?: throw SerializationException("上传响应必须为 JSON")
        val element = input.decodeJsonElement()
        if (element is JsonArray) return UploadPictureResultBean(info = element)
        val root = element as? JsonObject ?: throw SerializationException("上传响应必须为对象或数组")
        return UploadPictureResultBean(
            errorCode = root.stringValue("error_code", "errorCode").orEmpty(),
            errorMsg = root.stringValue("error_msg", "errorMsg").orEmpty(),
            resourceId = root.stringValue("resource_id", "resourceId"),
            chunkNo = root.stringValue("chunk_no", "chunkNo"),
            picId = root.stringValue("pic_id", "picId"),
            picInfo = root.value("pic_info", "picInfo").asPicInfo()
                ?: root.stringValue("pic_url", "picUrl")?.let { url ->
                    UploadInfo(picUrl = url, width = root.stringValue("width").orEmpty(),
                        height = root.stringValue("height").orEmpty()).toPicInfo()
                },
            info = root["info"],
        )
    }

    override fun serialize(encoder: Encoder, value: UploadPictureResultBean) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("上传响应必须为 JSON")
        output.encodeJsonElement(buildJsonObject {
            put("error_code", value.errorCode)
            put("error_msg", value.errorMsg)
            value.resourceId?.let { put("resource_id", it) }
            value.chunkNo?.let { put("chunk_no", it) }
            value.picId?.let { put("pic_id", it) }
            value.picInfo?.let { put("pic_info", output.json.encodeToJsonElement(PicInfo.serializer(), it)) }
            value.info?.let { put("info", it) }
        })
    }
}

private fun JsonObject.value(vararg names: String): JsonElement? = names.asSequence()
    .mapNotNull { get(it)?.takeUnless { value -> value is JsonNull } }.firstOrNull()

private fun JsonObject.stringValue(vararg names: String): String? = names.asSequence()
    .mapNotNull { (get(it) as? JsonPrimitive)?.contentOrNull }
    .firstOrNull { it.isNotBlank() }

private fun JsonElement?.asPicInfo(): PicInfo? {
    if (this == null || this is JsonNull || this is JsonArray && isEmpty()) return null
    val obj = this as? JsonObject ?: throw SerializationException("pic_info 不是图片对象")
    fun item(vararg names: String): PicInfoItem? {
        val value = obj.value(*names) ?: return null
        val fields = value as? JsonObject ?: throw SerializationException("图片尺寸信息不是对象")
        return PicInfoItem(fields.stringValue("width").orEmpty(), fields.stringValue("height").orEmpty(),
            fields.stringValue("type").orEmpty(), fields.stringValue("pic_url", "picUrl").orEmpty())
    }
    val origin = item("origin_pic", "originPic")
    val big = item("big_pic", "bigPic")
    val small = item("small_pic", "smallPic")
    val fallback = origin ?: big ?: small ?: return null
    return PicInfo(origin ?: fallback, big ?: fallback, small ?: fallback)
}

@Serializable
data class UploadInfo(
    @SerialName("pic_id")
    @SerializedName(value = "pic_id", alternate = ["picId"])
    val picId: String? = null,
    val width: String = "",
    val height: String = "",
    @SerialName("pic_url")
    @SerializedName(value = "pic_url", alternate = ["picUrl"])
    val picUrl: String = "",
) {
    fun toPicInfoOrNull(): PicInfo? = picUrl.takeIf { it.isNotBlank() }?.let { toPicInfo() }
    fun toPicInfo() = PicInfo(
        originPic = PicInfoItem(width, height, "", picUrl),
        bigPic = PicInfoItem(width, height, "", picUrl),
        smallPic = PicInfoItem(width, height, "", picUrl),
    )
}

@Serializable
data class PicInfo(
    @SerialName("origin_pic")
    @SerializedName(value = "origin_pic", alternate = ["originPic"])
    val originPic: PicInfoItem,
    @SerialName("big_pic")
    @SerializedName(value = "big_pic", alternate = ["bigPic"])
    val bigPic: PicInfoItem,
    @SerialName("small_pic")
    @SerializedName(value = "small_pic", alternate = ["smallPic"])
    val smallPic: PicInfoItem,
)

@Serializable
data class PicInfoItem(
    val width: String,
    val height: String,
    val type: String,
    @SerialName("pic_url")
    @SerializedName(value = "pic_url", alternate = ["picUrl"])
    val picUrl: String
)
