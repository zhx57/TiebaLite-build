package com.huanchengfly.tieba.post

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLoggingPolicyTest {
    @Test
    fun baseViewModelLogsOnlyEventTypesNotMessageBodiesOrCredentials() {
        val app = sequenceOf(File("."), File("app")).first { File(it, "src/main/java").isDirectory }
        val source = File(app, "src/main/java/com/huanchengfly/tieba/post/arch/BaseViewModel.kt").readText()
        assertFalse(source.contains("send \$intent"))
        assertFalse(source.contains("partialChange \$it"))
        assertFalse(source.contains("event \$event"))
        assertTrue(source.contains("intent.javaClass.simpleName"))
        assertTrue(source.contains("event.javaClass.simpleName"))
    }
}
