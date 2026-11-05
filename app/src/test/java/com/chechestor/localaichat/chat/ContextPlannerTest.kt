package com.chechestor.localaichat.chat

import com.chechestor.localaichat.data.MessageEntity
import com.chechestor.localaichat.llm.ChatRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPlannerTest {
    /** Поддельный токенизатор: 1 токен = 1 слово (разметка тоже считается словами). */
    private val counter: suspend (String) -> Int = { text -> text.split(Regex("\\s+")).count { it.isNotEmpty() } }
    private val planner = ContextPlanner(counter, contextSize = 2048, systemPrompt = "Системный промпт")

    private var nextId = 1L
    private fun msg(role: String, words: Int) = MessageEntity(
        id = nextId++, sessionId = 1, role = role, text = List(words) { "слово" }.joinToString(" "), createdAt = 0,
    )

    private fun dialog(turns: Int, wordsPerMessage: Int): List<MessageEntity> = buildList {
        repeat(turns) {
            add(msg(MessageEntity.ROLE_USER, wordsPerMessage))
            add(msg(MessageEntity.ROLE_ASSISTANT, wordsPerMessage))
        }
        add(msg(MessageEntity.ROLE_USER, 10))
    }

    @Test
    fun shortDialogIsSentAsIs() = runBlocking {
        val plan = planner.plan("", dialog(turns = 3, wordsPerMessage = 20), thinking = false, answerReserve = 512)
        assertTrue(plan is ContextPlanner.Plan.Ready)
        val history = (plan as ContextPlanner.Plan.Ready).history
        assertEquals(ChatRole.SYSTEM, history.first().role)
        assertEquals(8, history.size) // system + 7 сообщений
    }

    @Test
    fun longDialogCompressesOldestKeepingRecentWithin70Percent() = runBlocking {
        val messages = dialog(turns = 20, wordsPerMessage = 60) // ~2500 «токенов»
        val plan = planner.plan("", messages, thinking = false, answerReserve = 512)
        assertTrue(plan is ContextPlanner.Plan.Compress)
        val toSummarize = (plan as ContextPlanner.Plan.Compress).toSummarize
        // Сжимаются самые старые сообщения, по порядку.
        assertEquals(messages.first().id, toSummarize.first().id)
        val kept = messages.drop(toSummarize.size)
        val keptTokens = kept.sumOf { counter(it.text) + ContextPlanner.MESSAGE_OVERHEAD }
        assertTrue(keptTokens <= planner.recentShare(512) * ContextPlanner.KEEP_RATIO)
        // Новое сообщение пользователя всегда остаётся.
        assertEquals(messages.last().id, kept.last().id)
    }

    @Test
    fun afterCompressionWithSummaryItFits() = runBlocking {
        val messages = dialog(turns = 20, wordsPerMessage = 60)
        val plan = planner.plan("", messages, false, 512) as ContextPlanner.Plan.Compress
        val kept = messages.drop(plan.toSummarize.size)
        val summary = List(250) { "сводка" }.joinToString(" ")
        val next = planner.plan(summary, kept, false, 512)
        assertTrue(next is ContextPlanner.Plan.Ready)
        assertTrue((next as ContextPlanner.Plan.Ready).history.first().text.contains("Краткое содержание"))
    }

    @Test
    fun hugeNewMessageIsTooLong() = runBlocking {
        val messages = listOf(msg(MessageEntity.ROLE_USER, 1900))
        assertEquals(ContextPlanner.Plan.MessageTooLong, planner.plan("", messages, false, 512))
        assertTrue(planner.isMessageTooLong(messages.first().text, 512))
        assertFalse(planner.isMessageTooLong("короткий вопрос", 512))
    }

    @Test
    fun summaryChunksFitBudget() = runBlocking {
        val messages = dialog(turns = 30, wordsPerMessage = 100)
        val chunks = planner.summaryChunks(messages)
        assertTrue(chunks.size > 1)
        assertEquals(messages, chunks.flatten())
        for (c in chunks) {
            val tokens = c.sumOf { counter(it.text) + ContextPlanner.MESSAGE_OVERHEAD }
            assertTrue(tokens <= 2048 - ContextPlanner.SUMMARY_MAX_TOKENS * 2)
        }
    }

    @Test
    fun summaryRequestContainsPreviousSummaryAndInstruction() {
        val req = ContextPlanner.summaryRequest("Пользователя зовут Аня.", listOf(msg(MessageEntity.ROLE_USER, 3)))
        val text = req.last().text
        assertTrue(text.contains("Пользователя зовут Аня."))
        assertTrue(text.contains("Пользователь: слово слово слово"))
        assertTrue(text.contains(ContextPlanner.SUMMARY_INSTRUCTION))
    }
}
