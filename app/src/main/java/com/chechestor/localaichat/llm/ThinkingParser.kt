package com.chechestor.localaichat.llm

/** Ответ модели, разделённый на размышления (`<think>…</think>`) и сам ответ. */
data class ParsedAnswer(
    val thinking: String,
    val answer: String,
    /** Блок размышлений начат, но ещё не закрыт (модель размышляет). */
    val thinkingInProgress: Boolean,
)

object ThinkingParser {
    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    /** Разбирает накопленный текст ответа; подходит и для частичного текста во время генерации. */
    fun parse(raw: String): ParsedAnswer {
        val text = raw.trimStart()
        if (!text.startsWith(OPEN)) {
            // Метка ещё не дописана до конца: пока ничего не показываем как ответ.
            if (text.isNotEmpty() && OPEN.startsWith(text)) return ParsedAnswer("", "", true)
            return ParsedAnswer("", raw.trimStart(), false)
        }
        val body = text.substring(OPEN.length)
        val close = body.indexOf(CLOSE)
        if (close < 0) return ParsedAnswer(body.trim(), "", true)
        return ParsedAnswer(
            thinking = body.substring(0, close).trim(),
            answer = body.substring(close + CLOSE.length).trimStart(),
            thinkingInProgress = false,
        )
    }
}
