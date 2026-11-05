package com.chechestor.localaichat.chat

import com.chechestor.localaichat.LocalAiChatApp
import com.chechestor.localaichat.data.MessageEntity
import com.chechestor.localaichat.llm.ChatMessage
import com.chechestor.localaichat.llm.ChatRole

/** История, которая уходит в модель. */
object ModelHistory {
    /**
     * Системный промпт (F004) + сообщения сессии. Пустые сообщения пропускаются.
     * Размышления прошлых ответов в историю не входят (F007): берётся только текст ответа.
     */
    fun build(messages: List<MessageEntity>, systemPrompt: String = LocalAiChatApp.SYSTEM_PROMPT): List<ChatMessage> = buildList {
        add(ChatMessage(ChatRole.SYSTEM, systemPrompt))
        for (m in messages) {
            if (m.text.isBlank()) continue
            val role = if (m.role == MessageEntity.ROLE_USER) ChatRole.USER else ChatRole.ASSISTANT
            add(ChatMessage(role, m.text))
        }
    }
}
