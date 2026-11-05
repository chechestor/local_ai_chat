package com.chechestor.localaichat.chat

import android.content.Context
import com.chechestor.localaichat.LocalAiChatApp
import com.chechestor.localaichat.data.ChatRepository
import com.chechestor.localaichat.data.MessageEntity
import com.chechestor.localaichat.llm.ChatMessage
import com.chechestor.localaichat.llm.EngineState
import com.chechestor.localaichat.llm.FinishReason
import com.chechestor.localaichat.llm.GenerationEvent
import com.chechestor.localaichat.llm.GenerationParams
import com.chechestor.localaichat.llm.LlmEngine
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
    )

    /** Загружает модель в фоне один раз за запуск (после F008). */
    fun ensureModelLoaded() {
        scope.launch { loadModel() }
    }

    /** Загружает модель, если ещё не загружена, и возвращает состояние движка. */
    private suspend fun loadModel(): EngineState = loadMutex.withLock {
        val s = engine.state.value
        if (s is EngineState.Ready) return@withLock s
        val pfd = withContext(Dispatchers.IO) { runCatching { models.openQwen() }.getOrNull() }
            ?: return@withLock EngineState.Failed("Нет доступа к файлу модели")
        pfd.use { engine.load(it) }
    }

    /**
     * Отправляет сообщение в сессию [sessionId] (null — новый диалог: сессия создаётся сейчас).
     * Текущая генерация (в любом диалоге) останавливается, её частичный ответ сохраняется.
     */
    suspend fun send(sessionId: Long?, text: String): SendResult = sendMutex.withLock {
        // Сначала останавливаем текущий ответ: движок однопоточный, иначе подсчёт токенов ждал бы его конца.
        stopAndJoin()
        if (loadModel() !is EngineState.Ready) return@withLock SendResult.ModelUnavailable
        // Режим размышлений берётся из настроек на момент отправки (F007).
        val thinking = settings.thinkingNow()
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
            val history = prepareHistory(sessionId, messageId, thinking)
            if (history == null) {
                reason = if (stopRequested) FinishReason.STOPPED else FinishReason.PROMPT_TOO_LONG
                return
            }
            _active.value = ActiveGeneration(sessionId, messageId, "", thinkingInProgress = thinking)
            var lastSave = System.currentTimeMillis()
            val params = GenerationParams(thinking = thinking, maxTokens = answerReserve(thinking))
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
                    is GenerationEvent.Finished -> reason = ev.reason
                }
            }
        } finally {
            withContext(NonCancellable) {
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
        val out = StringBuilder()
        var reason = FinishReason.ERROR
        val params = GenerationParams(
            thinking = false,
            maxTokens = ContextPlanner.SUMMARY_MAX_TOKENS,
            temperature = 0.3f,
        )
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
        private const val SAVE_INTERVAL_MS = 2_000L
        private const val MAX_COMPRESS_ROUNDS = 3
        /** Лимит ответа без размышлений = запас под ответ в бюджете контекста (F006). */
        const val MAX_TOKENS = 512
        /** С размышлениями ответ длиннее: размышления тоже тратят токены. */
        const val MAX_TOKENS_THINKING = 1024
    }
}
