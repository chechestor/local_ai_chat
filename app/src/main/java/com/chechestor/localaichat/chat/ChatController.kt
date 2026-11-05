package com.chechestor.localaichat.chat

import android.content.Context
import com.chechestor.localaichat.LocalAiChatApp
import com.chechestor.localaichat.data.ChatRepository
import com.chechestor.localaichat.data.MessageEntity
import com.chechestor.localaichat.llm.ChatMessage
import com.chechestor.localaichat.llm.ChatRole
import com.chechestor.localaichat.llm.EngineState
import com.chechestor.localaichat.llm.FinishReason
import com.chechestor.localaichat.llm.GenerationEvent
import com.chechestor.localaichat.llm.GenerationParams
import com.chechestor.localaichat.llm.LlmEngine
import com.chechestor.localaichat.llm.ThinkingParser
import com.chechestor.localaichat.models.ModelStore
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
    suspend fun send(sessionId: Long?, text: String, thinking: Boolean = false): Long = sendMutex.withLock {
        stopAndJoin()
        val sid = sessionId ?: repo.createSession(text)
        repo.addMessage(sid, MessageEntity.ROLE_USER, text)
        val history = buildHistory(repo.messageList(sid))
        val messageId = repo.addMessage(sid, MessageEntity.ROLE_ASSISTANT, "")
        _active.value = ActiveGeneration(sid, messageId, "", thinkingInProgress = thinking)
        job = scope.launch { generate(sid, messageId, history, thinking) }
        sid
    }

    /** История для модели: системный промпт + сообщения сессии (сжатие длинной истории — F006). */
    private fun buildHistory(messages: List<MessageEntity>): List<ChatMessage> = buildList {
        add(ChatMessage(ChatRole.SYSTEM, LocalAiChatApp.SYSTEM_PROMPT))
        for (m in messages) {
            if (m.text.isBlank()) continue
            val role = if (m.role == MessageEntity.ROLE_USER) ChatRole.USER else ChatRole.ASSISTANT
            add(ChatMessage(role, m.text))
        }
    }

    private suspend fun generate(sessionId: Long, messageId: Long, history: List<ChatMessage>, thinking: Boolean) {
        GenerationService.start(context)
        val raw = StringBuilder()
        var reason = FinishReason.ERROR
        try {
            if (loadModel() !is EngineState.Ready) return
            var lastSave = System.currentTimeMillis()
            engine.generate(history, GenerationParams(thinking = thinking)).collect { ev ->
                when (ev) {
                    is GenerationEvent.Text -> {
                        raw.append(ev.piece)
                        val p = ThinkingParser.parse(raw.toString())
                        _active.value = ActiveGeneration(sessionId, messageId, p.answer, p.thinking, p.thinkingInProgress)
                        val now = System.currentTimeMillis()
                        if (now - lastSave > SAVE_INTERVAL_MS) {
                            lastSave = now
                            repo.updateMessageText(messageId, p.answer)
                        }
                    }
                    is GenerationEvent.Finished -> reason = ev.reason
                }
            }
        } finally {
            withContext(NonCancellable) {
                val answer = ThinkingParser.parse(raw.toString()).answer.trimEnd()
                val text = when {
                    answer.isNotEmpty() -> answer
                    reason == FinishReason.PROMPT_TOO_LONG -> "⚠ История диалога не помещается в контекст модели. Начните новый диалог."
                    reason == FinishReason.ERROR -> "⚠ Модель не смогла ответить."
                    else -> ""
                }
                if (text.isEmpty()) repo.deleteMessage(messageId) else repo.updateMessageText(messageId, text)
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
    }
}
