package com.chechestor.localaichat.models

import org.junit.Assert.assertEquals
import org.junit.Test

class HfQueryTest {
    @Test
    fun repoIdIsDirectQuery() {
        assertEquals(HfQuery.Repo("unsloth/SmolLM2-360M-Instruct-GGUF"), HfQuery.parse("  unsloth/SmolLM2-360M-Instruct-GGUF "))
    }

    @Test
    fun repoLinkGivesRepo() {
        assertEquals(
            HfQuery.Repo("unsloth/SmolLM2-360M-Instruct-GGUF"),
            HfQuery.parse("https://huggingface.co/unsloth/SmolLM2-360M-Instruct-GGUF/tree/main"),
        )
        assertEquals(HfQuery.Repo("a/b"), HfQuery.parse("https://huggingface.co/a/b"))
    }

    @Test
    fun wordsAreSearch() {
        assertEquals(HfQuery.Search("gemma 3 1b"), HfQuery.parse("gemma 3 1b"))
        assertEquals(HfQuery.Search("абвгд qwerty 12345"), HfQuery.parse("абвгд qwerty 12345"))
        assertEquals(HfQuery.Empty, HfQuery.parse("   "))
    }

    @Test
    fun siteSectionLinksAreNotRepos() {
        assertEquals(HfQuery.Search("https://huggingface.co/models/x"), HfQuery.parse("https://huggingface.co/models/x"))
    }
}

class HfFileChoiceTest {
    private val sha = "a".repeat(64)
    private fun f(path: String, size: Long = 100, lfs: String? = sha) = HfFile(path, size, lfs)

    @Test
    fun q4kmWithoutUdIsSuggested() {
        val files = listOf(
            f("README.md", lfs = null),
            f("model-UD-Q4_K_M.gguf"),
            f("model-Q4_K_M.gguf"),
            f("model-Q8_0.gguf"),
        )
        assertEquals(FileChoice.Suggested(f("model-Q4_K_M.gguf")), HfApi.chooseFile(files))
    }

    @Test
    fun unsuitableFilesAreSkipped() {
        val files = listOf(
            f("mmproj-model-f16.gguf"),
            f("model-Q4_K_M-00001-of-00002.gguf"),
            f("sub/model-Q4_K_M.gguf"),
            f("model-Q4_K_M.gguf", lfs = null),
            f("model.safetensors"),
        )
        assertEquals(FileChoice.None, HfApi.chooseFile(files))
    }

    @Test
    fun withoutQ4kmUserPicks() {
        val files = listOf(f("m-Q8_0.gguf", 200), f("m-Q5_K_M.gguf", 150))
        assertEquals(FileChoice.PickOne(listOf(f("m-Q5_K_M.gguf", 150), f("m-Q8_0.gguf", 200))), HfApi.chooseFile(files))
    }
}

class CustomModelTest {
    @Test
    fun titleIsRepoNameWithoutGguf() {
        assertEquals("SmolLM2-360M-Instruct", ModelStore.customTitle("unsloth/SmolLM2-360M-Instruct-GGUF"))
        assertEquals("tiny", ModelStore.customTitle("a/tiny"))
    }

    @Test
    fun customModelDownloadsFromPinnedCommit() {
        val m = ModelStore.customModel("unsloth/X-GGUF", "c".repeat(40), "X-Q4_K_M.gguf", 10, "d".repeat(64), "X")
        assertEquals("https://huggingface.co/unsloth/X-GGUF/resolve/${"c".repeat(40)}/X-Q4_K_M.gguf", m.spec.url)
        assertEquals("unsloth__X-Q4_K_M.gguf", m.spec.fileName)
        assertEquals(ChatFormat.EMBEDDED, m.format)
        assertEquals(false, m.supportsThinking)
        assertEquals(ModelCatalog.CUSTOM_SAMPLING, m.sampling(thinking = true))
    }
}
