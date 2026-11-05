package com.chechestor.localaichat.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTextTest {
    @Test
    fun atLeastFortyCharsPerSecond() {
        // Маленький буфер: темп 40 символов/с.
        assertEquals(4.0, StreamingText.step(0.0, 10, 0.1), 1e-9)
    }

    @Test
    fun bigBacklogIsCaughtUpWithinHalfSecond() {
        // 200 символов в буфере → 400 символов/с, за 0,1 с показывается 40.
        assertEquals(40.0, StreamingText.step(0.0, 200, 0.1), 1e-9)
        var shown = 0.0
        repeat(60) { shown = StreamingText.step(shown, 200, 1.0 / 60) }
        assertTrue(shown > 200 * 0.8)
    }

    @Test
    fun neverOvershootsOrJumpsOnLongPause() {
        assertEquals(10.0, StreamingText.step(9.5, 10, 0.1), 1e-9)
        // Пауза кадра в 2 с не даёт прыжка: шаг ограничен 0,1 с.
        assertEquals(200.0, StreamingText.step(0.0, 1000, 2.0), 1e-9)
    }

    @Test
    fun safeTakeKeepsSurrogatePairs() {
        val s = "a😀b"
        assertEquals("a", StreamingText.safeTake(s, 2))
        assertEquals("a😀", StreamingText.safeTake(s, 3))
        assertEquals(s, StreamingText.safeTake(s, 10))
    }

    @Test
    fun onlyCompleteLinesGoToMarkdown() {
        assertEquals("", StreamingText.completeLines("**Ингре"))
        assertEquals("Рецепт:\n", StreamingText.completeLines("Рецепт:\n- 2 яй"))
    }

    @Test
    fun tailLooksLikeRenderedMarkdown() {
        assertEquals("• 2 яйца", StreamingText.plainTail("- 2 яйца"))
        assertEquals("Ингредиенты:", StreamingText.plainTail("**Ингредиенты:**"))
        assertEquals("Шаги", StreamingText.plainTail("## Шаги"))
    }
}
