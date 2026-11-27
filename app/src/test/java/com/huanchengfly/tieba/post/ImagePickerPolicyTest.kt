package com.huanchengfly.tieba.post

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 这里只检查源码约束；系统选择器的实际行为仍需设备验证。 */
class ImagePickerPolicyTest {
    private fun source(path: String): String {
        val app = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main/java").isDirectory }
        return File(app, "src/main/java/com/huanchengfly/tieba/post/$path").readText()
    }

    @Test
    fun chatContractUsesOnlyPermissionFreeSingleImagePickers() {
        val picker = source("utils/ImagePicker.kt")
            .substringAfter("internal object ChatImageContract")
            .substringBefore("fun isPhotoPickerAvailable()")
        assertTrue(picker.contains("doNotUsePhotoPicker"))
        assertTrue(picker.contains("PickVisualMedia.isPhotoPickerAvailable(context)"))
        assertTrue(picker.contains("PickVisualMedia.ImageOnly"))
        assertTrue(picker.contains("OpenDocument().createIntent(context, arrayOf(\"image/*\"))"))
        assertFalse(picker.contains("Matisse"))
        assertFalse(picker.contains("requestPermission"))
        assertTrue(picker.contains("resultCode == Activity.RESULT_OK"))
        assertTrue(picker.contains("ChatImageResult(null)"))
    }

    @Test
    fun chatEntrypointsCannotLaunchSharedMediaContract() {
        listOf(
            "ui/page/chat/ChatRoomPage.kt",
            "ui/page/chat/PrivateChatPage.kt",
            "ui/widgets/compose/EmoticonPickerPanel.kt",
        ).forEach { path ->
            val page = source(path)
            assertTrue(path, page.contains("rememberChatImagePicker"))
            assertFalse(path, page.contains("registerPickMediasLauncher"))
            assertFalse(path, page.contains("PickMediasRequest"))
        }
    }

    @Test
    fun guardedLaunchAndGrantHandlingRemainInPlace() {
        val picker = source("utils/ImagePicker.kt")
            .substringAfter("fun rememberChatImagePicker")
            .substringBefore("fun isPhotoPickerAvailable()")
        assertTrue(picker.contains("if (!picking)"))
        assertTrue(picker.contains("catch (_: RuntimeException)"))
        assertTrue(picker.contains("onResult(null)"))
        assertTrue(picker.contains("pickerAccount = AccountUtil.getUid()"))
        assertTrue(picker.contains("pickerAccount == AccountUtil.getUid()"))
        assertTrue(picker.contains("if (!accountMatches)"))
        assertTrue(picker.contains("FLAG_GRANT_PERSISTABLE_URI_PERMISSION"))
        assertTrue(picker.contains("FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(picker.contains("catch (_: SecurityException)"))
        val panel = source("ui/widgets/compose/EmoticonPickerPanel.kt")
        assertTrue(panel.contains("pickingUpload = false"))
        assertTrue(panel.contains("pickerAccount = null"))
        assertTrue(panel.contains("请重新选图"))
    }
}
