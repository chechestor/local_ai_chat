package com.chechestor.localaichat.chat

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.chechestor.localaichat.LocalAiChatApp
import com.chechestor.localaichat.data.ChatRepository
import com.chechestor.localaichat.data.MessageEntity
import com.chechestor.localaichat.llm.ChatMessage
import com.chechestor.localaichat.llm.EngineState
import com.chechestor.localaichat.llm.FinishReason
import com.chechestor.localaichat.llm.GenerationEvent
import com.chechestor.localaichat.llm.GenerationParams
import com.chechestor.localaichat.llm.LlmEngine
import com.chechestor.localaichat.llm.ProcessMemory
import com.chechestor.localaichat.models.ChatFormat
import com.chechestor.localaichat.models.LlmModel
import com.chechestor.localaichat.models.ModelCatalog
import com.chechestor.localaichat.llm.ThinkingParser
import com.chechestor.localaichat.models.ModelStore
import com.chechestor.localaichat.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Ответ, который сейчас пишет модель. */
data class ActiveGeneration(
    val sessionId: Long,
    val messageId: Long,
    /** Текст ответа без размышлений. */
    val answer: String,
    val thinking: String = "",
    val thinkingInProgress: Boolean = false,
    /** Идёт сжатие истории перед ответом (F006): «Сжимаю историю…». */
    val compressing: Boolean = false,
)

/** Результат «Применить» (F009 п. 6). */
sealed interface ApplyResult {
    data object Ok : ApplyResult
    /** Идёт ответ или сжатие — смена модели недоступна. */
    data object Busy : ApplyResult
    data class Failed(val message: String) : ApplyResult
}

/** Итог пробной загрузки модели, скачанной по названию (F010 п. 8). */
sealed interface ProbeResult {
    data object Supported : ProbeResult
    data class Unsupported(val reason: String) : ProbeResult
    /** Идёт ответ: проба откладывается до первого «Применить». */
    data object Deferred : ProbeResult
    data class Failed(val message: String) : ProbeResult
}

/** Результат отправки сообщения. */
sealed interface SendResult {
    data class Sent(val sessionId: Long) : SendResult
    /** Сообщение длиннее своей доли контекста — не отправлено (F006 п. 6). */
    data object TooLong : SendResult
    /** Модель не загружена — отправить нельзя. */
    data object ModelUnavailable : SendResult
}

/**
 * Отправка сообщений и генерация ответов (F004). Живёт на уровне приложения, поэтому ответ дописывается
 * и сохраняется в свою сессию, даже если пользователь ушёл в другой диалог или свернул приложение.
 * Одновременно идёт одна генерация. Длинная история сжимается в сводку (F006).
 */
class ChatController(
    private val context: Context,
    private val repo: ChatRepository,
    private val engine: LlmEngine,
    private val models: ModelStore,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _active = MutableStateFlow<ActiveGeneration?>(null)
    val active: StateFlow<ActiveGeneration?> = _active.asStateFlow()
    val engineState: StateFlow<EngineState> = engine.state

    private val sendMutex = Mutex()
    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    private val loadMutex = Mutex()

    private val planner = ContextPlanner(
        countTokens = { engine.countTokens(it) },
        contextSize = LlmEngine.DEFAULT_CONTEXT,
        systemPrompt = LocalAiChatApp.SYSTEM_PROMPT,
        buildPrompt = { messages, thinking -> engine.buildPrompt(messages, thinking) },
    )

    /** Загружает активную модель в фоне один раз за запуск (после F008). */
    fun ensureModelLoaded() {
        scope.launch { loadModel() }
    }

    /** Загружает активную модель, если ещё не загружена, и возвращает состояние движка. */
    private suspend fun loadModel(): EngineState = loadMutex.withLock {
        val s = engine.state.value
        if (s is EngineState.Ready) return@withLock s
        val id = settings.activeModelIdNow()
        val model = id?.let { models.findModel(it) }
            ?: return@withLock fail("Модель не выбрана: выберите её в настройках")
        loadInto(model)
    }

    private fun fail(message: String): EngineState {
        engine.reportFailure(message)
        return EngineState.Failed(message)
    }

    /** Загрузка [model] в пустой движок: правило памяти, отметка «грузится» на случай аварии (F009 п. 9). */
    private suspend fun loadInto(model: LlmModel): EngineState {
        if (!ModelCatalog.fitsMemory(model.spec.sizeBytes, totalMemBytes())) return fail(NO_MEMORY)
        val pfd = withContext(Dispatchers.IO) { runCatching { models.openModel(model) }.getOrNull() }
            ?: return fail("Не удалось загрузить ${model.title}: нет доступа к файлу модели")
        settings.setBusyModelId(model.id)
        var result = pfd.use { engine.load(it, model) }
        settings.setBusyModelId(null)
        // Модель по названию без распознанного шаблона чата работать не сможет (F010 п. 9).
        if (result is EngineState.Ready && model.format == ChatFormat.EMBEDDED && !engine.hasChatTemplate()) {
            engine.unload()
            result = fail("Не удалось загрузить ${model.title}: $NO_TEMPLATE")
        }
        if (result is EngineState.Ready) {
            val mem = ProcessMemory.read()
            Log.i(LOG_TAG, "load model=${model.id} ms=${result.loadMillis} rssMb=${mem.rssMb} peakMb=${mem.peakRssMb}")
        }
        return result
    }

    private fun totalMemBytes(): Long = runCatching {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(0L)

    /**
     * «Применить» (F009 п. 6): выгружает прежнюю модель и грузит [id]. Во время ответа недоступно.
     * Если новая не загрузилась — возвращается прежняя.
     */
    suspend fun apply(id: String): ApplyResult = sendMutex.withLock {
        if (_active.value != null || job?.isActive == true) return@withLock ApplyResult.Busy
        val model = models.findModel(id) ?: return@withLock ApplyResult.Failed("Модель не найдена")
        if (model.unsupported) return@withLock ApplyResult.Failed("Модель ${model.repo} не поддерживается приложением")
        loadMutex.withLock {
            val previous = engine.model
            if (previous?.id == id && engine.state.value is EngineState.Ready) {
                saveActive(id)
                return@withLock ApplyResult.Ok
            }
            // Правило памяти — до выгрузки: прежняя модель остаётся рабочей.
            if (!ModelCatalog.fitsMemory(model.spec.sizeBytes, totalMemBytes())) return@withLock ApplyResult.Failed(NO_MEMORY)
            engine.unload()
            val result = loadInto(model)
            if (result is EngineState.Ready) {
                if (model.custom && !model.checked) models.markCustom(model.id, supported = true)
                saveActive(id)
                ApplyResult.Ok
            } else {
                val message = (result as? EngineState.Failed)?.message ?: "Не удалось загрузить ${model.title}"
                // Отложенная проба модели по названию не прошла — «Не поддерживается» (F010 п. 8).
                if (model.custom && !model.checked) models.markCustom(model.id, supported = false)
                if (previous != null) loadInto(previous)
                ApplyResult.Failed(message)
            }
        }
    }

    /**
     * Пробная загрузка модели, скачанной по названию (F010 п. 8): грузится вместо текущей, затем текущая
     * возвращается. Во время ответа проба откладывается до первого «Применить».
     */
    suspend fun probe(model: LlmModel): ProbeResult = sendMutex.withLock {
        if (_active.value != null || job?.isActive == true) return@withLock ProbeResult.Deferred
        if (!ModelCatalog.fitsMemory(model.spec.sizeBytes, totalMemBytes())) return@withLock ProbeResult.Failed(NO_MEMORY)
        loadMutex.withLock {
            val previous = engine.model
            engine.unload()
            settings.setBusyModelId(model.id)
            val pfd = withContext(Dispatchers.IO) { runCatching { models.openModel(model) }.getOrNull() }
            val result = if (pfd == null) {
                ProbeResult.Failed("Нет доступа к файлу модели")
            } else {
                when (val st = pfd.use { engine.load(it, model) }) {
                    is EngineState.Ready ->
                        if (engine.hasChatTemplate()) ProbeResult.Supported else ProbeResult.Unsupported(NO_TEMPLATE)
                    is EngineState.Failed -> ProbeResult.Unsupported("неизвестная архитектура или повреждённый файл")
                    else -> ProbeResult.Unsupported("модель не загрузилась")
                }
            }
            settings.setBusyModelId(null)
            engine.unload()
            if (previous != null) loadInto(previous)
            result
        }
    }

    private suspend fun saveActive(id: String) {
        settings.setActiveModelId(id)
        models.writeActive(id)
    }

    /** Выгружает модель (перед удалением файлов, F007). */
    suspend fun unloadModel() = loadMutex.withLock { engine.unload() }

    /**
     * Отправляет сообщение в сессию [sessionId] (null — новый диалог: сессия создаётся сейчас).
     * Текущая генерация (в любом диалоге) останавливается, её частичный ответ сохраняется.
     */
    suspend fun send(sessionId: Long?, text: String): SendResult = sendMutex.withLock {
        // Сначала останавливаем текущий ответ: движок однопоточный, иначе подсчёт токенов ждал бы его конца.
        stopAndJoin()
        if (loadModel() !is EngineState.Ready) return@withLock SendResult.ModelUnavailable
        // Режим размышлений берётся из настроек на момент отправки (F007), если модель его умеет (F009 п. 8).
        val thinking = settings.thinkingNow() && engine.model?.supportsThinking == true
        if (planner.isMessageTooLong(text, answerReserve(thinking))) return@withLock SendResult.TooLong
        val sid = sessionId ?: repo.createSession(text)
        repo.addMessage(sid, MessageEntity.ROLE_USER, text)
        val messageId = repo.addMessage(sid, MessageEntity.ROLE_ASSISTANT, "")
        _active.value = ActiveGeneration(sid, messageId, "", thinkingInProgress = thinking)
        stopRequested = false
        job = scope.launch { generate(sid, messageId, thinking) }
        SendResult.Sent(sid)
    }

    private fun answerReserve(thinking: Boolean) = if (thinking) MAX_TOKENS_THINKING else MAX_TOKENS

    private suspend fun generate(sessionId: Long, messageId: Long, thinking: Boolean) {
        GenerationService.start(context)
        val raw = StringBuilder()
        var reason = FinishReason.ERROR
        try {
            if (loadModel() !is EngineState.Ready) return
            val model = engine.model ?: return
            // Отметка «отвечает»: если систему убьёт процесс (память), при запуске покажем сообщение (F009 п. 9).
            settings.setBusyModelId(model.id)
            val history = prepareHistory(sessionId, messageId, thinking)
            if (history == null) {
                reason = if (stopRequested) FinishReason.STOPPED else FinishReason.PROMPT_TOO_LONG
                return
            }
            _active.value = ActiveGeneration(sessionId, messageId, "", thinkingInProgress = thinking)
            var lastSave = System.currentTimeMillis()
            val params = GenerationParams.of(model, thinking, answerReserve(thinking))
            engine.generate(history, params).collect { ev ->
                when (ev) {
                    is GenerationEvent.Text -> {
                        raw.append(ev.piece)
                        val p = ThinkingParser.parse(raw.toString())
                        _active.value = ActiveGeneration(sessionId, messageId, p.answer, p.thinking, p.thinkingInProgress)
                        val now = System.currentTimeMillis()
                        if (now - lastSave > SAVE_INTERVAL_MS) {
                            lastSave = now
                            repo.updateMessage(messageId, p.answer, p.thinking)
                        }
                    }
                    is GenerationEvent.Finished -> {
                        reason = ev.reason
                        val st = ev.stats
                        val mem = ProcessMemory.read()
                        // Замеры для карточек F003/F009 (adb logcat -s LocalAIChat).
                        Log.i(
                            LOG_TAG,
                            "answer model=${model.id} reason=${ev.reason} promptTokens=${st.promptTokens} " +
                                "ttftMs=${st.timeToFirstTokenMillis} tps=${"%.2f".format(st.tokensPerSecond)} " +
                                "chunks=${st.generatedChunks} rssMb=${mem.rssMb} peakMb=${mem.peakRssMb}",
                        )
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                settings.setBusyModelId(null)
                val parsed = ThinkingParser.parse(raw.toString())
                val answer = parsed.answer.trimEnd()
                val text = when {
                    answer.isNotEmpty() -> answer
                    reason == FinishReason.PROMPT_TOO_LONG -> "⚠ История диалога не помещается в контекст модели. Начните новый диалог."
                    reason == FinishReason.ERROR -> "⚠ Модель не смогла ответить."
                    reason == FinishReason.LENGTH && parsed.thinking.isNotEmpty() ->
                        "⚠ Размышления заняли весь лимит ответа. Спросите короче или выключите размышления."
                    else -> ""
                }
                if (text.isEmpty() && parsed.thinking.isEmpty()) repo.deleteMessage(messageId)
                else repo.updateMessage(messageId, text, parsed.thinking)
                if (_active.value?.messageId == messageId) _active.value = null
                GenerationService.stop(context)
            }
        }
    }

    /**
     * История для модели с учётом бюджета контекста (F006). Если не помещается — старые сообщения
     * пересказываются в сводку (Qwen, без размышлений), сводка сохраняется у сессии.
     * null — остановлено пользователем или история не помещается даже после сжатия.
     */
    private suspend fun prepareHistory(sessionId: Long, messageId: Long, thinking: Boolean): List<ChatMessage>? {
        val reserve = answerReserve(thinking)
        repeat(MAX_COMPRESS_ROUNDS) {
            val session = repo.getSession(sessionId) ?: return null
            val recent = repo.messageList(sessionId).filter { it.id > session.summaryUpTo && it.id != messageId }
            when (val plan = planner.plan(session.summary, recent, thinking, reserve)) {
                is ContextPlanner.Plan.Ready -> return plan.history
                ContextPlanner.Plan.MessageTooLong -> return null
                is ContextPlanner.Plan.Compress -> {
                    _active.value = ActiveGeneration(sessionId, messageId, "", compressing = true)
                    var summary = session.summary
                    for (chunk in planner.summaryChunks(plan.toSummarize)) {
                        summary = summarize(summary, chunk) ?: return null // «Стоп» — прежняя сводка остаётся
                    }
                    repo.saveSummary(sessionId, summary, plan.toSummarize.last().id)
                }
            }
        }
        return null
    }

    /** Новая сводка: прежняя сводка + отделённые сообщения. null — остановлено или ошибка. */
    private suspend fun summarize(previous: String, messages: List<MessageEntity>): String? {
        if (stopRequested) return null
        val model = engine.model ?: return null
        val out = StringBuilder()
        var reason = FinishReason.ERROR
        // Сводку пишет активная модель (F009 п. 7), без размышлений и с низкой температурой.
        val params = GenerationParams.of(model, thinking = false, maxTokens = ContextPlanner.SUMMARY_MAX_TOKENS, temperature = 0.3f)
        engine.generate(ContextPlanner.summaryRequest(previous, messages), params).collect { ev ->
            when (ev) {
                is GenerationEvent.Text -> out.append(ev.piece)
                is GenerationEvent.Finished -> reason = ev.reason
            }
        }
        if (stopRequested || reason == FinishReason.STOPPED || reason == FinishReason.ERROR ||
            reason == FinishReason.PROMPT_TOO_LONG
        ) return null
        return ThinkingParser.parse(out.toString()).answer.trim().ifEmpty { null }
    }

    /** «Стоп»: прерывает сжатие и ответ; частичный текст ответа сохраняется. */
    fun stop() {
        stopRequested = true
        engine.stop()
    }

    /** Останавливает генерацию в сессии (например, перед её удалением) и ждёт сохранения. */
    suspend fun stopIfSession(sessionId: Long) {
        if (_active.value?.sessionId == sessionId) stopAndJoin()
    }

    private suspend fun stopAndJoin() {
        val j = job ?: return
        if (j.isActive) stop()
        j.join()
        job = null
    }

    companion object {
        private const val LOG_TAG = "LocalAIChat"
        const val NO_MEMORY = "Модели не хватит памяти этого телефона"
        const val NO_TEMPLATE = "шаблон чата модели не распознан"
        private const val SAVE_INTERVAL_MS = 2_000L
        private const val MAX_COMPRESS_ROUNDS = 3
        /** Лимит ответа без размышлений = запас под ответ в бюджете контекста (F006). */
        const val MAX_TOKENS = 512
        /** С размышлениями ответ длиннее: размышления тоже тратят токены. */
        const val MAX_TOKENS_THINKING = 1024
    }
}
