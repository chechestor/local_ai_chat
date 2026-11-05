package com.chechestor.localaichat.models

/** Модель, которую приложение скачивает в папку `Download/LocalAIChat` (F008). */
data class ModelSpec(
    val id: String,
    val title: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

object ModelCatalog {
    /** Qwen3-0.6B Q4_K_M от unsloth, зафиксированный коммит репозитория (decisions.md, 2026-11-05). */
    val QWEN = ModelSpec(
        id = "qwen",
        title = "Qwen3-0.6B (Q4_K_M) — языковая модель",
        fileName = "Qwen3-0.6B-Q4_K_M.gguf",
        url = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/" +
            "50968a4468ef4233ed78cd7c3de230dd1d61a56b/Qwen3-0.6B-Q4_K_M.gguf",
        sizeBytes = 396_705_472L,
        sha256 = "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
    )

    /** Русская модель Vosk small 0.22 (официальный сайт alphacephei.com). */
    val VOSK = ModelSpec(
        id = "vosk",
        title = "Vosk small ru 0.22 — распознавание речи",
        fileName = "vosk-model-small-ru-0.22.zip",
        url = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
        sizeBytes = 46_236_750L,
        sha256 = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4",
    )

    /** Имя папки внутри архива Vosk; после распаковки — путь для движка Vosk (F005). */
    const val VOSK_DIR_NAME = "vosk-model-small-ru-0.22"

    /** Объём распакованной модели Vosk во внутренней памяти. */
    const val VOSK_UNPACKED_BYTES = 91_289_240L

    val ALL = listOf(QWEN, VOSK)

    /** Запас свободного места сверх объёма моделей (F008 п. 7). */
    const val FREE_SPACE_RESERVE = 512L * 1024 * 1024
}
