package com.chechestor.localaichat.llm

import com.chechestor.localaichat.models.ChatFormat

enum class ChatRole(val tag: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }

data class ChatMessage(val role: ChatRole, val text: String)

/** Сборка промпта по шаблону модели из каталога (F009 п. 7). */
object ChatTemplates {
    /** null — формат собирается встроенным шаблоном GGUF ([ChatFormat.EMBEDDED], F010). */
    fun build(format: ChatFormat, messages: List<ChatMessage>, thinking: Boolean): String? = when (format) {
        ChatFormat.QWEN3 -> QwenChatTemplate.build(messages, thinking)
        ChatFormat.QWEN35 -> QwenChatTemplate.build(messages, thinking, thinkingPrefix = QwenChatTemplate.OPEN_THINK)
        ChatFormat.GEMMA3 -> GemmaChatTemplate.build(messages)
        ChatFormat.EMBEDDED -> null
    }
}

/**
 * Шаблон чата Qwen3 / Qwen3.5 (ChatML). Повторяет встроенный шаблон модели для текстового диалога:
 * при выключенных размышлениях в начало ответа подставляется пустой блок `<think>`,
 * как делает шаблон с `enable_thinking=false`, и модель сразу пишет ответ.
 * Qwen3.5 при включённых размышлениях начинает ответ с `<think>\n` ([thinkingPrefix]).
 */
object QwenChatTemplate {
    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"
    const val EMPTY_THINK = "<think>\n\n</think>\n\n"
    const val OPEN_THINK = "<think>\n"

    fun build(messages: List<ChatMessage>, thinking: Boolean, thinkingPrefix: String = ""): String = buildString {
        for (m in messages) {
            append(IM_START).append(m.role.tag).append('\n')
            append(sanitize(m.text))
            append(IM_END).append('\n')
        }
        append(IM_START).append(ChatRole.ASSISTANT.tag).append('\n')
        append(if (thinking) thinkingPrefix else EMPTY_THINK)
    }

    /** Служебные метки в тексте пользователя не должны ломать разметку диалога. */
    internal fun sanitize(text: String): String =
        text.replace(IM_START, "<|im_start​|>").replace(IM_END, "<|im_end​|>")
}

/**
 * Шаблон чата Gemma 3: `<bos>`, ходы `<start_of_turn>user` / `<start_of_turn>model`, конец хода `<end_of_turn>`.
 * Роли system нет: системный промпт ставится в начало первого сообщения пользователя через пустую строку
 * (так делает шаблон модели). Тексты обрезаются по краям, как в шаблоне.
 */
object GemmaChatTemplate {
    private const val BOS = "<bos>"
    private const val START = "<start_of_turn>"
    private const val END = "<end_of_turn>"

    fun build(messages: List<ChatMessage>): String = buildString {
        append(BOS)
        val system = messages.filter { it.role == ChatRole.SYSTEM }.joinToString("\n\n") { it.text.trim() }
        var prefix = if (system.isEmpty()) "" else "$system\n\n"
        for (m in messages) {
            if (m.role == ChatRole.SYSTEM) continue
            val role = if (m.role == ChatRole.ASSISTANT) "model" else "user"
            append(START).append(role).append('\n')
            if (m.role == ChatRole.USER && prefix.isNotEmpty()) {
                append(sanitize(prefix))
                prefix = ""
            }
            append(sanitize(m.text.trim()))
            append(END).append('\n')
        }
        if (prefix.isNotEmpty()) {
            // Сообщений пользователя нет (не бывает в диалоге): системный промпт отдельным ходом user.
            append(START).append("user\n").append(sanitize(prefix.trim())).append(END).append('\n')
        }
        append(START).append("model\n")
    }

    internal fun sanitize(text: String): String = text
        .replace(START, "<start_of_turn​>")
        .replace(END, "<end_of_turn​>")
        .replace(BOS, "<bos​>")
        .replace("<eos>", "<eos​>")
}
