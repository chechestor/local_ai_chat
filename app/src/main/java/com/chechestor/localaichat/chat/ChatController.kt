package com.chechestor.localaichat.chat

import android.content.Context
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
)

/**
 * Отправка сообщений и генерация ответов (F004). Живёт на уровне приложения, поэтому ответ дописывается
 * и сохраняется в свою сессию, даже если пользователь ушёл в другой диалог или свернул приложение.
 * Одновременно идёт одна генерация.
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

    private val loadMutex = Mutex()

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
     * Возвращает id сессии.
     */
    suspend fun send(sessionId: Long?, text: String): Long = sendMutex.withLock {
        stopAndJoin()
        // Режим размышлений берётся из настроек на момент отправки (F007).
        val thinking = settings.thinkingNow()
        val sid = sessionId ?: repo.createSession(text)
        repo.addMessage(sid, MessageEntity.ROLE_USER, text)
        val history = ModelHistory.build(repo.messageList(sid))
        val messageId = repo.addMessage(sid, MessageEntity.ROLE_ASSISTANT, "")
        _active.value = ActiveGeneration(sid, messageId, "", thinkingInProgress = thinking)
        job = scope.launch { generate(sid, messageId, history, thinking) }
        sid
    }

    private suspend fun generate(sessionId: Long, messageId: Long, history: List<ChatMessage>, thinking: Boolean) {
        GenerationService.start(context)
        val raw = StringBuilder()
        var reason = FinishReason.ERROR
        try {
            if (loadModel() !is EngineState.Ready) return
            var lastSave = System.currentTimeMillis()
            val params = GenerationParams(thinking = thinking, maxTokens = if (thinking) MAX_TOKENS_THINKING else MAX_TOKENS)
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

    /** «Стоп»: прерывает ответ, частичный текст сохраняется. */
    fun stop() {
        engine.stop()
    }

    /** Останавливает генерацию в сессии (например, перед её удалением) и ждёт сохранения. */
    suspend fun stopIfSession(sessionId: Long) {
        if (_active.value?.sessionId == sessionId) stopAndJoin()
    }

    private suspend fun stopAndJoin() {
        val j = job ?: return
        if (j.isActive) engine.stop()
        j.join()
        job = null
    }

    companion object {
        private const val SAVE_INTERVAL_MS = 2_000L
        /** Лимит ответа без размышлений (бюджет контекста F006). */
        const val MAX_TOKENS = 512
        /** С размышлениями ответ длиннее: размышления тоже тратят токены. */
        const val MAX_TOKENS_THINKING = 1024
    }
}
