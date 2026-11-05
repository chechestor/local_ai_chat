package com.chechestor.localaichat.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageCatalogTest {
    @Test
    fun fourModelsWithUniqueIdsAndFiles() {
        assertEquals(listOf("Qwen3-0.6B", "Gemma 3 1B", "Qwen3-1.7B", "Qwen3.5-2B"), ModelCatalog.LANGUAGE.map { it.title })
        assertEquals(ModelCatalog.ALL.size, ModelCatalog.ALL.map { it.id }.toSet().size)
        assertEquals(ModelCatalog.ALL.size, ModelCatalog.ALL.map { it.fileName }.toSet().size)
    }

    @Test
    fun qwenKeepsOldIdAndFileName() {
        // Уже скачанный файл подхватывается без повторной загрузки (F009 п. 1).
        assertEquals("qwen", ModelCatalog.QWEN3_0_6B.id)
        assertEquals("Qwen3-0.6B-Q4_K_M.gguf", ModelCatalog.QWEN3_0_6B.spec.fileName)
    }

    @Test
    fun urlsPointToPinnedCommitsOfTheirRepos() {
        for (m in ModelCatalog.LANGUAGE) {
            assertEquals("https://huggingface.co/${m.repo}/resolve/${m.commit}/${m.spec.fileName}", m.spec.url)
            assertEquals(40, m.commit.length)
            assertTrue(ModelHttp.isAllowedUrl(m.spec.url))
        }
        assertTrue(ModelHttp.isAllowedUrl(ModelCatalog.VOSK.url))
    }

    @Test
    fun onlyGemmaHasNoThinking() {
        assertEquals(listOf("gemma3-1b"), ModelCatalog.LANGUAGE.filter { !it.supportsThinking }.map { it.id })
        assertEquals(ChatFormat.GEMMA3, ModelCatalog.GEMMA3_1B.format)
        assertEquals(ChatFormat.QWEN35, ModelCatalog.QWEN35_2B.format)
    }

    @Test
    fun samplingFromModelCards() {
        val q35 = ModelCatalog.QWEN35_2B
        assertEquals(Sampling(0.7f, 20, 0.8f, 1.5f), q35.sampling(thinking = false))
        assertEquals(Sampling(1.0f, 20, 0.95f, 1.5f), q35.sampling(thinking = true))
        assertEquals(Sampling(1.0f, 64, 0.95f), ModelCatalog.GEMMA3_1B.sampling(thinking = true))
        assertEquals(Sampling(0.6f, 20, 0.95f), ModelCatalog.QWEN3_1_7B.sampling(thinking = true))
    }

    @Test
    fun heavyModelsHaveMemoryNote() {
        for (m in ModelCatalog.LANGUAGE.filter { it.spec.sizeBytes > ModelCatalog.HEAVY_BYTES }) {
            assertTrue(m.note.contains("4 ГБ"))
        }
        assertEquals("Быстрая, качество ниже", ModelCatalog.QWEN3_0_6B.note)
        assertEquals("Без режима размышлений", ModelCatalog.GEMMA3_1B.note)
    }
}

class ActiveModelTest {
    @Test
    fun storedModelWinsWhenItsFileIsPresent() {
        assertEquals("b", ModelCatalog.resolveActive("b", listOf("a", "b")))
    }

    @Test
    fun singleDownloadedModelBecomesActive() {
        assertEquals("a", ModelCatalog.resolveActive(null, listOf("a")))
        assertEquals("a", ModelCatalog.resolveActive("gone", listOf("a")))
    }

    @Test
    fun severalModelsWithoutChoiceNeedTheChooseScreen() {
        assertNull(ModelCatalog.resolveActive(null, listOf("a", "b")))
        assertNull(ModelCatalog.resolveActive("gone", listOf("a", "b")))
    }
}

class MemoryRuleTest {
    private val gb = 1024L * 1024 * 1024

    @Test
    fun catalogFitsFourGigabytePhone() {
        for (m in ModelCatalog.LANGUAGE) assertTrue(ModelCatalog.fitsMemory(m.spec.sizeBytes, 4 * gb))
    }

    @Test
    fun fileOverHalfOfRamIsRejected() {
        assertFalse(ModelCatalog.fitsMemory(2 * gb + 1, 4 * gb))
        assertTrue(ModelCatalog.fitsMemory(2 * gb, 4 * gb))
    }
}

class CustomModelNamesTest {
    @Test
    fun repoIds() {
        assertTrue(HfNames.isRepoId("unsloth/SmolLM2-360M-Instruct-GGUF"))
        assertTrue(HfNames.isRepoId("a/b.c_d-e"))
        assertFalse(HfNames.isRepoId("gemma 3 1b"))
        assertFalse(HfNames.isRepoId("a/b/c"))
        assertFalse(HfNames.isRepoId("../x"))
        assertFalse(HfNames.isRepoId("a/.."))
    }

    @Test
    fun customFileDoesNotClashWithCatalog() {
        val name = ModelStore.customFileName("unsloth/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_K_M.gguf")
        assertEquals("unsloth__Qwen3-0.6B-Q4_K_M.gguf", name)
        assertFalse(ModelCatalog.ALL.any { it.fileName == name })
    }

    @Test
    fun startUrlsOnlyFromSources() {
        assertFalse(ModelHttp.isAllowedUrl("http://huggingface.co/a/b"))
        assertFalse(ModelHttp.isAllowedUrl("https://evil.com/a.gguf"))
        assertFalse(ModelHttp.isAllowedUrl("https://user@huggingface.co/a"))
    }
}
