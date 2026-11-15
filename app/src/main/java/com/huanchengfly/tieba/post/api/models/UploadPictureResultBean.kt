package com.huanchengfly.tieba.post.api.models

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UploadPictureResultBean(
    @SerialName("error_code")
    @SerializedName("error_code")
    val errorCode: String,
    @SerialName("error_msg")
    @SerializedName("error_msg")
    val errorMsg: String,
    @SerialName("resource_id")
    @SerializedName(value = "resourceId", alternate = ["resource_id"])
    val resourceId: String? = null,
    @SerialName("chunk_no")
    @SerializedName(value = "chunkNo", alternate = ["chunk_no"])
    val chunkNo: String,
    @SerialName("pic_id")
    @SerializedName(value = "pic_id", alternate = ["picId"])
    val picId: String? = null,
    @SerialName("pic_info")
    @SerializedName(value = "pic_info", alternate = ["picInfo"])
    val picInfo: PicInfo? = null
)

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
