package com.nmtuong.telegramdrive.feature.preview

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextPreviewTest {
    @Test
    fun `text preview reads utf8 content`() {
        val file = Files.createTempFile("telegram-drive-text", ".txt").toFile()
        file.writeText("hello\n世界")
        try {
            assertEquals("hello\n世界", readTextPreview(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `text preview refuses content beyond byte limit`() {
        val file = Files.createTempFile("telegram-drive-text", ".txt").toFile()
        file.writeText("12345")
        try {
            assertThrows(IllegalArgumentException::class.java) { readTextPreview(file, maxBytes = 4) }
        } finally {
            file.delete()
        }
    }
}
