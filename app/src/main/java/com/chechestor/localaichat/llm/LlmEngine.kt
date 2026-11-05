package com.chechestor.localaichat.llm

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.chechestor.localaichat.models.LlmModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Состояние движка LLM. */
sealed interface EngineState {
    data object NotLoaded : EngineState
    /** Модель грузится: «Загружаю <название>…» (F009 п. 6). */
    data class Loading(val title: String) : EngineState
    data class Ready(val model: LlmModel, val loadMillis: Long, val contextSize: Int) : EngineState
    data class Failed(val message: String) : EngineState
}

/** Почему закончилась генерация. */
enum class FinishReason { DONE, STOPPED, LENGTH, PROMPT_TOO_LONG, ERROR }

sealed interface GenerationEvent {
    /** Очередной кусок ответа (текст модели как есть, вместе с `<think>`, если размышления включены). */
    data class Text(val piece: String) : GenerationEvent
    data class Finished(val reason: FinishReason, val stats: GenerationStats) : GenerationEvent
}

data class GenerationStats(
    val promptTokens: Int,
    val generatedChunks: Int,
    /** Время от запуска до первого куска ответа, мс (-1 — ответа не было). */
    val timeToFirstTokenMillis: Long,
    /** Скорость выдачи, кусков (≈ токенов) в секунду после первого. */
    val tokensPerSecond: Double,
)

/** Параметры генерации; сэмплинг — из карточки модели (F009 п. 7). */
data class GenerationParams(
    val thinking: Boolean = false,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topK: Int = 20,
    val topP: Float = 0.8f,
    val presencePenalty: Float = 0f,
    val seed: Int = (System.nanoTime() and 0x7fffffff).toInt(),
) {
    companion object {
        fun of(model: LlmModel, thinking: Boolean, maxTokens: Int, temperature: Float? = null): GenerationParams {
            val s = model.sampling(thinking)
            return GenerationParams(
                thinking = thinking,
                maxTokens = maxTokens,
                temperature = temperature ?: s.temperature,
                topK = s.topK,
                topP = s.topP,
                presencePenalty = s.presencePenalty,
            )
        }
    }
}

/**
 * Движок языковой модели на llama.cpp (F003, F009). В памяти одна модель. Все обращения к нативному коду идут в одном фоновом потоке,
 * поэтому интерфейс не зависает, а загрузка, генерация и освобождение не пересекаются.
 * Одновременно идёт одна генерация.
 */
class LlmEngine(
    private val nativeLibDir: String,
    private val contextSize: Int = DEFAULT_CONTEXT,
    private val threads: Int = defaultThreads(),
    private val quantizeKv: Boolean = true,
) {
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "llm-engine") }.asCoroutineDispatcher()

    private val _state = MutableStateFlow<EngineState>(EngineState.NotLoaded)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    private var handle = 0L

    /** Загруженная модель (шаблон чата и сэмплинг). */
    @Volatile
    var model: LlmModel? = null
        private set
    private var backendReady = false

    /**
     * Загружает [llm] из открытого дескриптора (файл или документ из папки моделей). Дескриптор не закрывает.
     * Прежнюю модель нужно сначала выгрузить ([unload]): две модели в памяти не держим.
     */
    suspend fun load(fd: ParcelFileDescriptor, llm: LlmModel): EngineState = withContext(dispatcher) {
        if (handle != 0L) return@withContext _state.value
        _state.value = EngineState.Loading(llm.title)
        val started = SystemClock.elapsedRealtime()
        val result = try {
            if (!backendReady) {
                LlamaNative.backendInit(nativeLibDir)
                backendReady = true
            }
            val h = LlamaNative.loadModel(fd.fd, contextSize, threads, quantizeKv)
            if (h == 0L) {
                EngineState.Failed("Не удалось загрузить ${llm.title}: файл повреждён или модель не поддерживается")
            } else {
                handle = h
                model = llm
                EngineState.Ready(llm, SystemClock.elapsedRealtime() - started, LlamaNative.contextSize(h))
            }
        } catch (t: Throwable) {
            EngineState.Failed("Не удалось загрузить ${llm.title}: ${t.message ?: t.javaClass.simpleName}")
        }
        _state.value = result
        result
    }

    /** Ошибка до обращения к движку (нет файла, не хватит памяти): показывается вместо «Загружаю…». */
    fun reportFailure(message: String) {
        if (handle == 0L) _state.value = EngineState.Failed(message)
    }

    /** Число токенов в тексте (для бюджета контекста, F006). */
    suspend fun countTokens(text: String): Int = withContext(dispatcher) {
        val h = handle
        check(h != 0L) { "Модель не загружена" }
        LlamaNative.countTokens(h, text.toByteArray(Charsets.UTF_8))
    }

    /** Промпт по шаблону загруженной модели (для подсчёта токенов, F006). */
    fun buildPrompt(messages: List<ChatMessage>, thinking: Boolean): String {
        val m = model ?: error("Модель не загружена")
        return ChatTemplates.build(m.format, messages, thinking && m.supportsThinking)
            ?: error("Шаблон чата модели не поддерживается")
    }

    /**
     * Генерирует ответ на историю [messages] (системное, пользователь, ассистент) по шаблону модели.
     * Отмена сбора потока (или [stop]) прерывает генерацию.
     */
    fun generate(messages: List<ChatMessage>, params: GenerationParams = GenerationParams()): Flow<GenerationEvent> =
        callbackFlow {
            val h = handle
            if (h == 0L) {
                send(GenerationEvent.Finished(FinishReason.ERROR, GenerationStats(0, 0, -1, 0.0)))
                close()
                return@callbackFlow
            }
            val prompt = buildPrompt(messages, params.thinking).toByteArray(Charsets.UTF_8)
            val promptTokens = LlamaNative.countTokens(h, prompt)
            val started = SystemClock.elapsedRealtime()
            var firstAt = -1L
            var chunks = 0
            val rc = LlamaNative.generate(
                h, prompt, params.maxTokens, params.temperature, params.topK, params.topP, params.presencePenalty, params.seed,
            ) { bytes ->
                if (firstAt < 0) firstAt = SystemClock.elapsedRealtime()
                chunks++
                val sent = trySendBlocking(GenerationEvent.Text(String(bytes, Charsets.UTF_8)))
                // Сборщик ушёл (отмена корутины) — останавливаем модель.
                if (sent.isFailure || !isActive) LlamaNative.requestStop(h)
            }
            val ended = SystemClock.elapsedRealtime()
            val reason = when (rc) {
                LlamaNative.RESULT_DONE -> FinishReason.DONE
                LlamaNative.RESULT_STOPPED -> FinishReason.STOPPED
                LlamaNative.RESULT_LENGTH -> FinishReason.LENGTH
                LlamaNative.ERR_PROMPT_TOO_LONG -> FinishReason.PROMPT_TOO_LONG
                else -> FinishReason.ERROR
            }
            val ttft = if (firstAt < 0) -1L else firstAt - started
            val tps = if (firstAt < 0 || chunks < 2 || ended <= firstAt) 0.0
            else (chunks - 1) * 1000.0 / (ended - firstAt)
            trySendBlocking(GenerationEvent.Finished(reason, GenerationStats(promptTokens, chunks, ttft, tps)))
            close()
            awaitClose { }
        }.flowOn(dispatcher)

    /** Прерывает текущую генерацию (можно вызывать из любого потока). */
    fun stop() {
        val h = handle
        if (h != 0L) LlamaNative.requestStop(h)
    }

    /** Освобождает модель и память движка. */
    suspend fun unload() = withContext(dispatcher) {
        val h = handle
        if (h != 0L) {
            handle = 0L
            model = null
            LlamaNative.free(h)
        }
        _state.value = EngineState.NotLoaded
    }

    companion object {
        const val DEFAULT_CONTEXT = 2048

        /** Генерация на 4 потоках: больше на телефонах обычно не быстрее (малые ядра тормозят большие). */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}
