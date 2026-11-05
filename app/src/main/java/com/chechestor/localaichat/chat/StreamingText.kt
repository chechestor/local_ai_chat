package com.chechestor.localaichat.chat

import kotlin.math.max
import kotlin.math.min

/**
 * Плавный вывод ответа (F004.01 п. 7–8): текст модели копится в буфер, экран показывает его ровным темпом,
 * не меньше [MIN_CHARS_PER_SEC] символов в секунду и с отставанием не больше [MAX_LAG_SEC].
 */
object StreamingText {
    const val MIN_CHARS_PER_SEC = 40.0
    const val MAX_LAG_SEC = 0.5

    /** Новая показанная длина (дробная — для ровного темпа) после [dtSec] секунд. */
    fun step(shown: Double, target: Int, dtSec: Double): Double {
        if (shown >= target) return target.toDouble()
        val backlog = target - shown
        val rate = max(MIN_CHARS_PER_SEC, backlog / MAX_LAG_SEC)
        return min(target.toDouble(), shown + rate * dtSec.coerceIn(0.0, 0.1))
    }

    /** Первые [n] символов, не разрывая суррогатную пару (эмодзи). */
    fun safeTake(text: String, n: Int): String {
        if (n >= text.length) return text
        if (n <= 0) return ""
        val end = if (Character.isHighSurrogate(text[n - 1])) n - 1 else n
        return text.substring(0, end)
    }

    /**
     * Часть ответа для разбора Markdown: только законченные строки (до последнего перевода строки).
     * Незаконченная строка показывается обычным текстом, пока не придёт перевод строки —
     * тогда незакрытая разметка (`**`, начало списка) не меняет вид уже показанного.
     */
    fun completeLines(text: String): String {
        val i = text.lastIndexOf('\n')
        return if (i < 0) "" else text.substring(0, i + 1)
    }

    /** Незаконченная строка без символов разметки: так она ближе к виду после разбора. */
    fun plainTail(tail: String): String {
        var s = tail
        val trimmed = s.trimStart()
        val indent = s.length - trimmed.length
        s = when {
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") ->
                " ".repeat(indent) + "• " + trimmed.substring(2)
            trimmed.startsWith("#") -> trimmed.trimStart('#').trimStart()
            else -> s
        }
        return s.replace("**", "").replace("__", "").replace("`", "")
    }
}
