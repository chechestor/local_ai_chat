package com.chechestor.localaichat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTitleTest {
    @Test
    fun shortMessageIsTitleAsIs() {
        assertEquals("Как приготовить омлет?", ChatRepository.autoTitle("  Как приготовить омлет?  "))
    }

    @Test
    fun whitespaceIsCollapsed() {
        assertEquals("Привет мир", ChatRepository.autoTitle("Привет\n\n   мир"))
    }

    @Test
    fun longMessageIsCutAtWordWithEllipsis() {
        val title = ChatRepository.autoTitle("Расскажи подробно, как устроен двигатель внутреннего сгорания в автомобиле")
        assertTrue(title.endsWith("…"))
        assertTrue(title.length <= ChatRepository.AUTO_TITLE_LENGTH + 1)
        assertEquals("Расскажи подробно, как устроен двигатель…", title)
    }

    @Test
    fun longWordIsCutHard() {
        val title = ChatRepository.autoTitle("а".repeat(100))
        assertEquals("а".repeat(40) + "…", title)
    }
}
