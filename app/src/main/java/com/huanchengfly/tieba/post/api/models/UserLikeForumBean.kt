package com.huanchengfly.tieba.post.api.models

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName
import com.huanchengfly.tieba.post.models.BaseBean
import kotlinx.collections.immutable.persistentListOf

/**
 * 关注吧列表响应。
 *
 * 只使用 Gson 注解解析：该接口返回的 has_more / error_code 等字段类型不稳定
 * （数字与字符串混用），走 kotlinx 序列化会因类型不匹配直接失败。
 */
data class UserLikeForumBean(
    @SerializedName("error_code")
    val errorCode: String = "-1",

    @SerializedName("error_msg")
    val errorMsg: String = "unknown error",

    @JvmField
    @SerializedName("has_more")
    val hasMore: String = "0",

    @JvmField
    @SerializedName("forum_list")
    val forumList: ForumListBean = ForumListBean(),

    @SerializedName("common_forum_list")
    val commonForumList: ForumListBean = ForumListBean(),
) : BaseBean() {

    data class ForumListBean(
        // 普通关注的吧
        @JvmField
        @SerializedName("non-gconforum")
        val forumList: List<ForumBean> = persistentListOf(),

        // 官方吧 / 游戏吧等分组，部分账号的关注数据只出现在这里
        @JvmField
        @SerializedName("gconforum")
        val gconForumList: List<ForumBean> = persistentListOf(),
    )

    @Immutable
    data class ForumBean(
        val id: String = "",

        @JvmField
        val name: String? = null,

        @SerializedName("level_id")
        val levelId: String? = null,

        @SerializedName("favo_type")
        val favoType: String? = null,

        @SerializedName("level_name")
        val levelName: String? = null,

        @SerializedName("cur_score")
        val curScore: String? = null,

        @SerializedName("levelup_score")
        val levelUpScore: String? = null,

        @JvmField
        val avatar: String? = null,

        @JvmField
        val slogan: String? = null,
    )
}
