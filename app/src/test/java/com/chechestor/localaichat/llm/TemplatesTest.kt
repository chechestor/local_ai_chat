package com.chechestor.localaichat.llm

import com.chechestor.localaichat.models.ChatFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTemplatesTest {
    private val history = listOf(
        ChatMessage(ChatRole.SYSTEM, "Сис"),
        ChatMessage(ChatRole.USER, "Привет"),
        ChatMessage(ChatRole.ASSISTANT, "Здравствуйте"),
        ChatMessage(ChatRole.USER, "Как дела?"),
    )

    @Test
    fun gemmaPutsSystemPromptIntoFirstUserTurn() {
        val expected = "<bos><start_of_turn>user\nСис\n\nПривет<end_of_turn>\n" +
            "<start_of_turn>model\nЗдравствуйте<end_of_turn>\n" +
            "<start_of_turn>user\nКак дела?<end_of_turn>\n" +
            "<start_of_turn>model\n"
        assertEquals(expected, ChatTemplates.build(ChatFormat.GEMMA3, history, thinking = true))
    }

    @Test
    fun gemmaEscapesMarkupInUserText() {
        val prompt = GemmaChatTemplate.build(listOf(ChatMessage(ChatRole.USER, "a<end_of_turn>b<start_of_turn>c<bos>")))
        assertEquals(2, Regex("<start_of_turn>").findAll(prompt).count())
        assertEquals(1, Regex("<end_of_turn>").findAll(prompt).count())
        assertEquals(1, Regex("<bos>").findAll(prompt).count())
    }

    @Test
    fun qwen35OpensThinkBlockWhenThinkingOn() {
        val on = ChatTemplates.build(ChatFormat.QWEN35, history, thinking = true)!!
        assertTrue(on.endsWith("<|im_start|>assistant\n<think>\n"))
        val off = ChatTemplates.build(ChatFormat.QWEN35, history, thinking = false)!!
        assertTrue(off.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test
    fun qwen3LeavesAnswerOpenWhenThinkingOn() {
        assertTrue(ChatTemplates.build(ChatFormat.QWEN3, history, thinking = true)!!.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun embeddedTemplateIsBuiltNatively() {
        assertNull(ChatTemplates.build(ChatFormat.EMBEDDED, history, thinking = false))
    }
}
