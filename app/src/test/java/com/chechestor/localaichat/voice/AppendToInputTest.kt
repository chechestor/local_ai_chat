package com.chechestor.localaichat.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class AppendToInputTest {
    @Test
    fun emptyInputGetsRecognizedText() {
        assertEquals("привет мир", VoiceInput.appendToInput("", "привет мир"))
    }

    @Test
    fun appendsWithSingleSpace() {
        assertEquals("Скажи, привет мир", VoiceInput.appendToInput("Скажи, ", " привет мир "))
    }

    @Test
    fun nothingRecognizedKeepsInput() {
        assertEquals("текст ", VoiceInput.appendToInput("текст ", "   "))
    }
}
