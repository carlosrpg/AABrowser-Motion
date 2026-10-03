package com.kododake.aabrowser.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectedTextBufferTest {
    @Test
    fun acceptsSequentialCharactersAtTheCaret() {
        val buffer = ProjectedTextBuffer()

        "youtube".forEach { buffer.insertText(it.toString()) }

        assertEquals("youtube", buffer.text)
        assertEquals(7, buffer.selectionStart)
        assertEquals(7, buffer.selectionEnd)
    }

    @Test
    fun replacesSelectionAndDeletesCompleteUnicodeCodePoints() {
        val buffer = ProjectedTextBuffer()
        buffer.setSnapshot("a😀bc", 1, 3)

        buffer.insertText("X")
        assertEquals("aXbc", buffer.text)
        assertEquals(2, buffer.selectionStart)

        buffer.setSnapshot("a😀bc", 3, 3)
        assertTrue(buffer.deleteBackward())
        assertEquals("abc", buffer.text)
        assertEquals(1, buffer.selectionStart)
    }

    @Test
    fun backspaceAtStartDoesNotChangeText() {
        val buffer = ProjectedTextBuffer()
        buffer.setSnapshot("abc", 0, 0)

        assertFalse(buffer.deleteBackward())
        assertEquals("abc", buffer.text)
        assertEquals(0, buffer.selectionStart)
    }
}
