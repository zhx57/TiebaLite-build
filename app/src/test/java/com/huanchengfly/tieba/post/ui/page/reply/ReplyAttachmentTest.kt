package com.huanchengfly.tieba.post.ui.page.reply

import com.huanchengfly.tieba.post.api.models.UploadInfo
import com.huanchengfly.tieba.post.api.models.UploadPictureResultBean
import com.huanchengfly.tieba.post.repository.CloudMeme
import com.huanchengfly.tieba.post.ui.widgets.compose.trustedOfficialPicIdOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyAttachmentTest {
    private val meme = CloudMeme("18446744073709551615", "https://example.com/m.gif", null, "12", 120, 80)
    private val packagedMarker = "#(meme,12_18446744073709551615,120,80,de1418bd657f4af8a67b8e8d8931cc75)"
    private val unpackagedMarker = "#(meme,18446744073709551615,120,80,a0c11e4cc0f2d229cc477558642a4c69)"

    @Test
    fun signsNativeMarkersWithAndWithoutAPackage() {
        assertEquals(packagedMarker, meme.toReplyMarkerOrNull())
        for (packageId in listOf(null, "", "0")) {
            assertEquals(unpackagedMarker, meme.copy(packageId = packageId).toReplyMarkerOrNull())
        }
    }

    @Test
    fun rejectsTruncatedOrEditedStickerWithoutBlockingOrdinaryText() {
        listOf("#(meme", "#(meme,", packagedMarker.dropLast(1),
            packagedMarker.replace("meme,", "meme"),
            packagedMarker.replace(",120,", ",121,"),
            "$unpackagedMarker 前缀后${packagedMarker.dropLast(2)}后缀",
        ).forEach { assertTrue(it, it.hasInvalidReplyMemeMarker()) }
        assertFalse("文字 #(滑稽) $packagedMarker$unpackagedMarker".hasInvalidReplyMemeMarker())
    }

    @Test
    fun missingOrInvalidMetadataCannotProduceAMarker() {
        assertNull(meme.copy(width = null).toReplyMarkerOrNull())
        assertNull(meme.copy(height = 0).toReplyMarkerOrNull())
        assertNull(meme.copy(id = "0").toReplyMarkerOrNull())
        assertNull(meme.copy(id = "collect_123").toReplyMarkerOrNull())
        assertNull(meme.copy(packageId = "diy_123").toReplyMarkerOrNull())
    }

    @Test
    fun imageQuotaStillKeepsExistingPhotosAndDropsDuplicates() {
        val existing = (1..8).map { "content://$it" }
        val original = ReplyPartialChange.AddImage(existing).reduce(ReplyUiState())
        val result = ReplyPartialChange.AddImage(listOf("content://8", "content://9", "content://10"))
            .reduce(original)
        assertEquals(REPLY_MAX_ATTACHMENT_COUNT, result.selectedImageList.size)
        assertEquals(existing + "content://9", result.selectedImageList)
    }

    @Test
    fun incompleteUploadResultsAreRejectedInsteadOfSendingPlaceholders() {
        val missingId = UploadPictureResultBean(
            picInfo = UploadInfo(width = "100", height = "80", picUrl = "https://example.com/p.png").toPicInfo(),
        )
        assertNull(missingId.toReplyPictureAttachmentOrNull())

        val missingSize = UploadPictureResultBean(
            picId = "pic_1",
            picInfo = UploadInfo(width = "0", height = "80", picUrl = "https://example.com/p.png").toPicInfo(),
        )
        assertNull(missingSize.toReplyPictureAttachmentOrNull())
    }

    @Test
    fun pureAndMixedStickerContentKeepsItsPositionAndAddsOneReplyQuote() {
        val marker = checkNotNull(meme.toReplyMarkerOrNull())
        val local = ReplyPictureAttachment("pic_local", 100, 80)
        val mixed = "左${marker}右"
        assertTrue(replyMemeMarker.matches(marker))
        assertEquals(listOf(marker, marker), replyMemeMarker.findAll("$mixed$marker").map { it.value }.toList())
        assertEquals(marker, buildReplySendContent(marker))
        assertEquals("#(pic,pic_local,100,80)", buildReplySendContent("  ", listOf(local)))
        assertEquals("$mixed\n#(pic,pic_local,100,80)", buildReplySendContent(mixed, listOf(local)))
        assertEquals(
            "回复 #(reply, portrait, 用户) :$mixed\n#(pic,pic_local,100,80)",
            buildReplySendContent(mixed, listOf(local), 1L, "portrait", "用户"),
        )
        assertEquals("回复 #(reply, portrait, 用户) :$marker", buildReplySendContent(marker,
            subPostId = 1L, replyUserPortrait = "portrait", replyUserName = "用户"))
    }

    @Test
    fun chatCollectionResourceIdValidationRemainsUnchanged() {
        assertEquals("pic123", trustedOfficialPicIdOrNull(" pic123 "))
        assertNull(trustedOfficialPicIdOrNull(null))
        assertNull(trustedOfficialPicIdOrNull(""))
        assertNull(trustedOfficialPicIdOrNull("https://example.com/a.png"))
        assertNull(trustedOfficialPicIdOrNull("/forum/pic/item/abc.jpg"))
        assertNull(trustedOfficialPicIdOrNull("a b"))
    }
}
