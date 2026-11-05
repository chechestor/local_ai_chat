package com.chechestor.localaichat.chat

import com.chechestor.localaichat.data.MessageEntity
import com.chechestor.localaichat.llm.ChatMessage
import com.chechestor.localaichat.llm.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelHistoryTest {
    private fun msg(role: String, text: String, thinking: String = "") =
        MessageEntity(sessionId = 1, role = role, text = text, createdAt = 0, thinking = thinking)

    @Test
    fun systemPromptFirstThinkingExcludedEmptySkipped() {
        val history = ModelHistory.build(
            listOf(
                msg(MessageEntity.ROLE_USER, "Привет"),
                msg(MessageEntity.ROLE_ASSISTANT, "Здравствуйте", thinking = "длинные размышления"),
                msg(MessageEntity.ROLE_USER, "Как дела?"),
                msg(MessageEntity.ROLE_ASSISTANT, ""),
            ),
            systemPrompt = "SYS",
        )
        assertEquals(
            listOf(
                ChatMessage(ChatRole.SYSTEM, "SYS"),
                ChatMessage(ChatRole.USER, "Привет"),
                ChatMessage(ChatRole.ASSISTANT, "Здравствуйте"),
                ChatMessage(ChatRole.USER, "Как дела?"),
            ),
            history,
        )
    }
}
