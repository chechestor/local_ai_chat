package com.chechestor.localaichat.llm

enum class ChatRole(val tag: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }

data class ChatMessage(val role: ChatRole, val text: String)

/**
 * Шаблон чата Qwen3 (ChatML). Повторяет встроенный шаблон модели для текстового диалога:
 * при выключенных размышлениях в начало ответа подставляется пустой блок `<think>`,
 * как делает шаблон Qwen3 с `enable_thinking=false`, и модель сразу пишет ответ.
 */
object QwenChatTemplate {
    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"
    const val EMPTY_THINK = "<think>\n\n</think>\n\n"

    fun build(messages: List<ChatMessage>, thinking: Boolean): String = buildString {
        for (m in messages) {
            append(IM_START).append(m.role.tag).append('\n')
            append(sanitize(m.text))
            append(IM_END).append('\n')
        }
        append(IM_START).append(ChatRole.ASSISTANT.tag).append('\n')
        if (!thinking) append(EMPTY_THINK)
    }

    /** Служебные метки в тексте пользователя не должны ломать разметку диалога. */
    internal fun sanitize(text: String): String =
        text.replace(IM_START, "<|im_start​|>").replace(IM_END, "<|im_end​|>")
}
