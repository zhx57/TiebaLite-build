package com.huanchengfly.tieba.post.ui.page.reply

import com.huanchengfly.tieba.post.api.models.UploadInfo
import com.huanchengfly.tieba.post.api.models.UploadPictureResultBean
import com.huanchengfly.tieba.post.api.models.chat.ChatCloudMeme
import com.huanchengfly.tieba.post.ui.widgets.compose.trustedOfficialPicIdOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyAttachmentTest {

    private fun cloudMeme(id: String) = ChatCloudMeme(
        id = id,
        imageUrl = "https://example.com/$id.png",
        officialPicId = "pic_$id",
        width = 120,
        height = 80,
    )

    @Test
    fun mergeKeepsExistingAttachmentsAndDropsDuplicates() {
        val merged = mergeReplyAttachments(
            existingLocalImageUris = listOf("content://one", "content://two"),
            existingCloudMemes = listOf(cloudMeme("a")),
            newLocalImageUris = listOf("content://two", "content://three"),
            newCloudMemes = listOf(cloudMeme("a"), cloudMeme("b")),
        )
        assertEquals(listOf("content://one", "content://two", "content://three"), merged.localImageUris)
        assertEquals(listOf("a", "b"), merged.cloudMemes.map { it.id })
        assertEquals(5, merged.totalCount)
    }

    @Test
    fun mergeNeverExceedsAttachmentLimit() {
        val local = (1..REPLY_MAX_ATTACHMENT_COUNT).map { "content://$it" }
        val merged = mergeReplyAttachments(
            existingLocalImageUris = local,
            existingCloudMemes = emptyList(),
            newLocalImageUris = listOf("content://extra"),
            newCloudMemes = listOf(cloudMeme("extra")),
        )
        assertEquals(REPLY_MAX_ATTACHMENT_COUNT, merged.totalCount)
        assertTrue(merged.isFull)
        assertEquals(REPLY_MAX_ATTACHMENT_COUNT, merged.localImageUris.size)
        assertTrue(merged.cloudMemes.isEmpty())
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

        assertNull(cloudMeme("x").copy(officialPicId = null).toReplyPictureAttachmentOrNull())
        assertNull(cloudMeme("y").copy(width = 0, height = 0).toReplyPictureAttachmentOrNull())
    }

    @Test
    fun sendContentKeepsTextFirstThenLocalThenCloud() {
        val local = ReplyPictureAttachment("pic_local", 100, 80)
        val cloud = ReplyPictureAttachment("pic_cloud", 120, 90)
        assertEquals(
            "正文\n#(pic,pic_local,100,80)\n#(pic,pic_cloud,120,90)",
            buildReplySendContent("正文", listOf(local), listOf(cloud)),
        )
        assertEquals(
            "#(pic,pic_cloud,120,90)",
            buildReplySendContent("  ", emptyList(), listOf(cloud)),
        )
    }

    @Test
    fun onlyStrictResourceIdsAreTrustedForCollection() {
        assertEquals("pic123", trustedOfficialPicIdOrNull(" pic123 "))
        assertNull(trustedOfficialPicIdOrNull(null))
        assertNull(trustedOfficialPicIdOrNull(""))
        assertNull(trustedOfficialPicIdOrNull("https://example.com/a.png"))
        assertNull(trustedOfficialPicIdOrNull("/forum/pic/item/abc.jpg"))
        assertNull(trustedOfficialPicIdOrNull("a b"))
    }
}
