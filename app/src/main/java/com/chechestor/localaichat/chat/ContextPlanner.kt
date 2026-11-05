package com.chechestor.localaichat.chat

import com.chechestor.localaichat.data.MessageEntity
import com.chechestor.localaichat.llm.ChatMessage
import com.chechestor.localaichat.llm.ChatRole
import com.chechestor.localaichat.llm.QwenChatTemplate

/**
 * Бюджет контекста 2048 токенов (F006): системный промпт + сводка старой истории + последние сообщения
 * + запас под ответ. Если история не помещается, старые сообщения уходят в сводку, которую пишет сама Qwen.
 */
class ContextPlanner(
    private val countTokens: suspend (String) -> Int,
    private val contextSize: Int,
    private val systemPrompt: String,
) {
    /** Что делать перед генерацией. */
    sealed interface Plan {
        /** Всё помещается: отправляем [history] как есть. */
        data class Ready(val history: List<ChatMessage>) : Plan
        /** Нужно сжать: [toSummarize] — старые сообщения, которые войдут в новую сводку. */
        data class Compress(val toSummarize: List<MessageEntity>) : Plan
        /** Новое сообщение само не помещается в свою долю контекста. */
        data object MessageTooLong : Plan
    }

    /** Системное сообщение: промпт и, если есть, сводка начала диалога. */
    fun systemMessage(summary: String): ChatMessage = ChatMessage(
        ChatRole.SYSTEM,
        if (summary.isBlank()) systemPrompt
        else "$systemPrompt\n\nКраткое содержание начала диалога:\n${summary.trim()}",
    )

    fun history(summary: String, recent: List<MessageEntity>): List<ChatMessage> =
        listOf(systemMessage(summary)) + ModelHistory.build(recent).drop(1)

    /** Токены промпта целиком, с разметкой шаблона Qwen. */
    suspend fun promptTokens(history: List<ChatMessage>, thinking: Boolean): Int =
        countTokens(QwenChatTemplate.build(history, thinking))

    /** Доля контекста под последние сообщения и новое сообщение. */
    suspend fun recentShare(answerReserve: Int): Int {
        val system = countTokens(QwenChatTemplate.build(listOf(ChatMessage(ChatRole.SYSTEM, systemPrompt)), false))
        return contextSize - answerReserve - system - SUMMARY_MAX_TOKENS
    }

    /** Проверка нового сообщения до сохранения (F006 п. 6). */
    suspend fun isMessageTooLong(text: String, answerReserve: Int): Boolean =
        countTokens(text) + MESSAGE_OVERHEAD > recentShare(answerReserve)

    /**
     * [recent] — сообщения после сводки, последнее — новое сообщение пользователя.
     * Сводка меняется только когда история перестала помещаться.
     */
    suspend fun plan(summary: String, recent: List<MessageEntity>, thinking: Boolean, answerReserve: Int): Plan {
        val messages = recent.filter { it.text.isNotBlank() }
        val history = history(summary, messages)
        if (promptTokens(history, thinking) + answerReserve <= contextSize) return Plan.Ready(history)

        val share = recentShare(answerReserve)
        val last = messages.lastOrNull() ?: return Plan.Ready(history)
        if (countTokens(last.text) + MESSAGE_OVERHEAD > share) return Plan.MessageTooLong

        // Оставляем самые новые сообщения, пока они занимают не больше ~70% своей доли.
        val keepBudget = (share * KEEP_RATIO).toInt()
        var used = 0
        var keepFrom = messages.size
        for (i in messages.indices.reversed()) {
            val t = countTokens(messages[i].text) + MESSAGE_OVERHEAD
            if (i < messages.size - 1 && used + t > keepBudget) break
            used += t
            keepFrom = i
        }
        val toSummarize = messages.subList(0, keepFrom)
        if (toSummarize.isEmpty()) return Plan.MessageTooLong
        return Plan.Compress(toSummarize)
    }

    /**
     * Делит сообщения для сводки на части, каждая из которых помещается в один запрос
     * вместе с прежней сводкой и ответом-сводкой (F006 п. 3, «сжимать частями»).
     */
    suspend fun summaryChunks(messages: List<MessageEntity>): List<List<MessageEntity>> {
        val budget = contextSize - SUMMARY_MAX_TOKENS * 2 - SUMMARY_PROMPT_OVERHEAD
        val chunks = mutableListOf<MutableList<MessageEntity>>()
        var current = mutableListOf<MessageEntity>()
        var used = 0
        for (m in messages) {
            val t = countTokens(m.text) + MESSAGE_OVERHEAD
            if (current.isNotEmpty() && used + t > budget) {
                chunks += current
                current = mutableListOf()
                used = 0
            }
            current += m
            used += t
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    companion object {
        const val SUMMARY_MAX_TOKENS = 300
        const val KEEP_RATIO = 0.7
        /** Разметка одного сообщения в шаблоне: `<|im_start|>role\n … <|im_end|>\n`. */
        const val MESSAGE_OVERHEAD = 6
        private const val SUMMARY_PROMPT_OVERHEAD = 120

        /** Запрос сводки — фиксированный текст (F006 п. 3). */
        const val SUMMARY_INSTRUCTION =
            "Кратко перескажи по-русски суть диалога: факты, договорённости, вопросы пользователя. " +
                "Сохрани имена, числа и важные детали. Не больше 200 слов. Пиши только пересказ."

        /** Сообщения для запроса сводки: прежняя сводка + отделённые сообщения. */
        fun summaryRequest(previousSummary: String, messages: List<MessageEntity>): List<ChatMessage> {
            val text = buildString {
                if (previousSummary.isNotBlank()) {
                    append("Прежний пересказ начала диалога:\n").append(previousSummary.trim()).append("\n\n")
                }
                append("Продолжение диалога:\n")
                for (m in messages) {
                    append(if (m.role == MessageEntity.ROLE_USER) "Пользователь: " else "Ассистент: ")
                    append(m.text.trim()).append('\n')
                }
                append('\n').append(SUMMARY_INSTRUCTION)
            }
            return listOf(
                ChatMessage(ChatRole.SYSTEM, "Ты составляешь краткий пересказ диалога."),
                ChatMessage(ChatRole.USER, text),
            )
        }
    }
}
