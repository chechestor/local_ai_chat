package com.chechestor.localaichat.models

/** Файл, который приложение скачивает в папку `Download/LocalAIChat` (F008). */
data class ModelSpec(
    val id: String,
    val title: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** Шаблон чата языковой модели (F009 п. 7, F010 п. 9). */
enum class ChatFormat {
    /** ChatML Qwen3: размышления включаются отсутствием пустого блока `<think>`. */
    QWEN3,
    /** ChatML Qwen3.5: при включённых размышлениях ответ начинается с `<think>\n`. */
    QWEN35,
    /** Gemma 3: `<start_of_turn>user/model`, без роли system. */
    GEMMA3,
    /** Шаблон, встроенный в GGUF (`llama_chat_apply_template`, F010). */
    EMBEDDED,
}

/** Параметры сэмплинга из карточки модели. */
data class Sampling(
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val presencePenalty: Float = 0f,
)

/** Языковая модель: из каталога (F009) или добавленная по названию (F010). */
data class LlmModel(
    val spec: ModelSpec,
    /** Подпись в списках (F009 п. 1). */
    val note: String,
    val format: ChatFormat,
    val supportsThinking: Boolean,
    val sampling: Sampling,
    val samplingThinking: Sampling = sampling,
    /** Добавлена по названию с Hugging Face (F010). */
    val custom: Boolean = false,
    /** Репозиторий Hugging Face и коммит (для `models.json`). */
    val repo: String = "",
    val commit: String = "",
    /** Пробная загрузка прошла (F010 п. 8). false — ещё не проверена (откладывается, если шёл ответ). */
    val checked: Boolean = true,
    /** Пробная загрузка не удалась: «Не поддерживается», «Применить» недоступна. */
    val unsupported: Boolean = false,
) {
    val id: String get() = spec.id
    val title: String get() = spec.title

    fun sampling(thinking: Boolean): Sampling = if (thinking && supportsThinking) samplingThinking else sampling
}

object ModelCatalog {
    private const val NOTE_FAST = "Быстрая, качество ниже"
    private const val NOTE_HEAVY = "Точнее, но медленнее; на телефоне с 4 ГБ может не хватить памяти"
    private const val NOTE_NO_THINK = "Без режима размышлений"

    private val QWEN3_SAMPLING = Sampling(temperature = 0.7f, topK = 20, topP = 0.8f)
    private val QWEN3_SAMPLING_THINK = Sampling(temperature = 0.6f, topK = 20, topP = 0.95f)

    /** Сэмплинг для моделей, добавленных по названию (F010 п. 9). */
    val CUSTOM_SAMPLING = Sampling(temperature = 0.7f, topK = 40, topP = 0.9f)

    private fun hf(id: String, title: String, repo: String, commit: String, file: String, size: Long, sha: String) =
        ModelSpec(
            id = id,
            title = title,
            fileName = file,
            url = "https://huggingface.co/$repo/resolve/$commit/$file",
            sizeBytes = size,
            sha256 = sha,
        )

    /** Qwen3-0.6B: прежние `id` и имя файла — уже скачанный файл подхватывается (F009 п. 1). */
    val QWEN3_0_6B = LlmModel(
        spec = hf(
            "qwen", "Qwen3-0.6B", "unsloth/Qwen3-0.6B-GGUF", "50968a4468ef4233ed78cd7c3de230dd1d61a56b",
            "Qwen3-0.6B-Q4_K_M.gguf", 396_705_472L, "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
        ),
        note = NOTE_FAST,
        format = ChatFormat.QWEN3,
        supportsThinking = true,
        sampling = QWEN3_SAMPLING,
        samplingThinking = QWEN3_SAMPLING_THINK,
        repo = "unsloth/Qwen3-0.6B-GGUF",
        commit = "50968a4468ef4233ed78cd7c3de230dd1d61a56b",
    )

    val GEMMA3_1B = LlmModel(
        spec = hf(
            "gemma3-1b", "Gemma 3 1B", "unsloth/gemma-3-1b-it-GGUF", "f0b45be0aac41bd6a100a4b5734cad5f67255bfb",
            "gemma-3-1b-it-Q4_K_M.gguf", 806_058_272L, "8270790f3ab69fdfe860b7b64008d9a19986d8df7e407bb018184caa08798ebd",
        ),
        note = NOTE_NO_THINK,
        format = ChatFormat.GEMMA3,
        supportsThinking = false,
        sampling = Sampling(temperature = 1.0f, topK = 64, topP = 0.95f),
        repo = "unsloth/gemma-3-1b-it-GGUF",
        commit = "f0b45be0aac41bd6a100a4b5734cad5f67255bfb",
    )

    val QWEN3_1_7B = LlmModel(
        spec = hf(
            "qwen3-1.7b", "Qwen3-1.7B", "unsloth/Qwen3-1.7B-GGUF", "d7f544eead698dbd1f15126ef60b45a1e1933222",
            "Qwen3-1.7B-Q4_K_M.gguf", 1_107_409_472L, "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
        ),
        note = NOTE_HEAVY,
        format = ChatFormat.QWEN3,
        supportsThinking = true,
        sampling = QWEN3_SAMPLING,
        samplingThinking = QWEN3_SAMPLING_THINK,
        repo = "unsloth/Qwen3-1.7B-GGUF",
        commit = "d7f544eead698dbd1f15126ef60b45a1e1933222",
    )

    val QWEN35_2B = LlmModel(
        spec = hf(
            "qwen3.5-2b", "Qwen3.5-2B", "unsloth/Qwen3.5-2B-GGUF", "f6d5376be1edb4d416d56da11e5397a961aca8ae",
            "Qwen3.5-2B-Q4_K_M.gguf", 1_280_835_840L, "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
        ),
        note = NOTE_HEAVY,
        format = ChatFormat.QWEN35,
        supportsThinking = true,
        sampling = Sampling(temperature = 0.7f, topK = 20, topP = 0.8f, presencePenalty = 1.5f),
        samplingThinking = Sampling(temperature = 1.0f, topK = 20, topP = 0.95f, presencePenalty = 1.5f),
        repo = "unsloth/Qwen3.5-2B-GGUF",
        commit = "f6d5376be1edb4d416d56da11e5397a961aca8ae",
    )

    /** Языковые модели каталога в порядке показа (F009 п. 1). */
    val LANGUAGE = listOf(QWEN3_0_6B, GEMMA3_1B, QWEN3_1_7B, QWEN35_2B)

    /** Русская модель Vosk small 0.22 (официальный сайт alphacephei.com); обязательна (F008, F009 п. 1). */
    val VOSK = ModelSpec(
        id = "vosk",
        title = "Vosk — распознавание речи",
        fileName = "vosk-model-small-ru-0.22.zip",
        url = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
        sizeBytes = 46_236_750L,
        sha256 = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4",
    )

    /** Имя папки внутри архива Vosk; после распаковки — путь для движка Vosk (F005). */
    const val VOSK_DIR_NAME = "vosk-model-small-ru-0.22"

    /** Объём распакованной модели Vosk во внутренней памяти. */
    const val VOSK_UNPACKED_BYTES = 91_289_240L

    /** Все файлы каталога (языковые модели и Vosk). */
    val ALL: List<ModelSpec> = LANGUAGE.map { it.spec } + VOSK

    /** Запас свободного места сверх объёма моделей (F008 п. 7). */
    const val FREE_SPACE_RESERVE = 512L * 1024 * 1024

    /** Модель тяжелее этого — с предупреждением о памяти и скорости (F009 п. 1, F010 п. 6). */
    const val HEAVY_BYTES = 1024L * 1024 * 1024

    /** Правило памяти F009 п. 9: файл модели не больше половины общей ОЗУ телефона. */
    fun fitsMemory(fileBytes: Long, totalMemBytes: Long): Boolean = totalMemBytes <= 0 || fileBytes * 2 <= totalMemBytes

    /**
     * Какую модель считать активной при запуске (F009 п. 3): сохранённую, если её файл на месте;
     * иначе единственную скачанную; иначе — нужно выбрать (null).
     */
    fun resolveActive(stored: String?, downloaded: List<String>): String? = when {
        stored != null && stored in downloaded -> stored
        downloaded.size == 1 -> downloaded.single()
        else -> null
    }
}
