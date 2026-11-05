package com.chechestor.localaichat.voice

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/** Состояние голосового ввода (F005). */
sealed interface VoiceState {
    data object Idle : VoiceState
    /** Загружается модель Vosk (1–3 с): «Подготовка…». */
    data object Preparing : VoiceState
    /** Идёт запись. [text] — распознанное на лету (готовые фразы + текущая). */
    data class Recording(val elapsedSec: Int, val text: String) : VoiceState
    /** Запись остановлена, ждём итог Vosk. */
    data class Finishing(val text: String) : VoiceState
}

/** Чем закончилась запись. */
sealed interface VoiceResult {
    data class Text(val text: String) : VoiceResult
    data object Nothing : VoiceResult
    data object Cancelled : VoiceResult
    data class Error(val message: String) : VoiceResult
}

/**
 * Распознавание речи Vosk (F005). Модель грузится в память при начале записи и выгружается сразу после неё.
 * Лимит записи — [LIMIT_SEC] секунд, по истечении запись завершается сама.
 */
class VoiceInput(
    private val scope: CoroutineScope,
    private val modelDir: () -> File,
    private val onResult: (VoiceResult) -> Unit,
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var service: SpeechService? = null
    private var job: Job? = null
    private var timerJob: Job? = null
    private var cancelled = false

    /** Готовые фразы (onResult) и текущая (onPartialResult). */
    private val committed = StringBuilder()
    private var partial = ""
    private var startedAt = 0L

    fun start() {
        if (_state.value != VoiceState.Idle || job?.isActive == true) return
        cancelled = false
        committed.clear()
        partial = ""
        _state.value = VoiceState.Preparing
        job = scope.launch {
            val dir = modelDir()
            val loaded = withContext(Dispatchers.IO) {
                runCatching { if (dir.isDirectory) Model(dir.absolutePath) else null }.getOrNull()
            }
            if (loaded == null) {
                if (cancelled) return@launch
                finishWith(VoiceResult.Error("Модель распознавания речи не найдена. Переустановите модели в настройках."))
                return@launch
            }
            if (cancelled) {
                loaded.close() // «Отменить» во время «Подготовки…»: результат уже отдан
                return@launch
            }
            model = loaded
            try {
                val rec = Recognizer(loaded, SAMPLE_RATE)
                recognizer = rec
                val svc = SpeechService(rec, SAMPLE_RATE)
                service = svc
                svc.startListening(listener)
            } catch (e: Exception) {
                finishWith(VoiceResult.Error("Не удалось начать запись: ${e.message}"))
                return@launch
            }
            startedAt = SystemClock.elapsedRealtime()
            _state.value = VoiceState.Recording(0, "")
            timerJob = scope.launch {
                while (true) {
                    delay(250)
                    val sec = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                    val s = _state.value
                    if (s is VoiceState.Recording) _state.value = s.copy(elapsedSec = sec.coerceAtMost(LIMIT_SEC))
                    if (sec >= LIMIT_SEC) {
                        finish() // лимит 60 с — как «Завершить»
                        break
                    }
                }
            }
        }
    }

    /** «Завершить»: Vosk выдаёт итог, текст дописывается в поле. */
    fun finish() {
        val svc = service ?: return
        if (_state.value !is VoiceState.Recording) return
        timerJob?.cancel()
        _state.value = VoiceState.Finishing(currentText())
        svc.stop() // → onFinalResult
    }

    /** «Отменить»: запись выбрасывается, поле не меняется. */
    fun cancel() {
        when (_state.value) {
            VoiceState.Idle -> return
            VoiceState.Preparing -> {
                cancelled = true // модель догрузится и сразу будет выгружена
                _state.value = VoiceState.Idle
                onResult(VoiceResult.Cancelled)
            }
            else -> {
                service?.cancel()
                finishWith(VoiceResult.Cancelled)
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            partial = field(hypothesis, "partial")
            publish()
        }

        override fun onResult(hypothesis: String?) {
            appendCommitted(field(hypothesis, "text"))
            partial = ""
            publish()
        }

        override fun onFinalResult(hypothesis: String?) {
            appendCommitted(field(hypothesis, "text"))
            partial = ""
            val text = committed.toString().trim()
            finishWith(if (text.isEmpty()) VoiceResult.Nothing else VoiceResult.Text(text))
        }

        override fun onError(exception: Exception?) {
            finishWith(VoiceResult.Error("Ошибка записи: ${exception?.message ?: "микрофон недоступен"}"))
        }

        override fun onTimeout() {
            finish()
        }
    }

    private fun appendCommitted(text: String) {
        if (text.isBlank()) return
        if (committed.isNotEmpty()) committed.append(' ')
        committed.append(text.trim())
    }

    private fun currentText(): String = listOf(committed.toString(), partial).filter { it.isNotBlank() }.joinToString(" ")

    private fun publish() {
        val s = _state.value
        if (s is VoiceState.Recording) _state.value = s.copy(text = currentText())
    }

    private fun finishWith(result: VoiceResult) {
        if (_state.value == VoiceState.Idle) return
        timerJob?.cancel()
        release()
        _state.value = VoiceState.Idle
        onResult(result)
    }

    /** Выгружает Vosk из памяти (F005: не держать модель вне записи). */
    private fun release() {
        runCatching { service?.shutdown() }
        runCatching { recognizer?.close() }
        runCatching { model?.close() }
        service = null
        recognizer = null
        model = null
    }

    companion object {
        const val LIMIT_SEC = 60
        private const val SAMPLE_RATE = 16000f

        private fun field(json: String?, name: String): String =
            runCatching { JSONObject(json ?: "{}").optString(name, "") }.getOrDefault("")

        /** Дописывает распознанный текст к введённому через пробел (F005 п. 8). */
        fun appendToInput(input: String, recognized: String): String {
            val r = recognized.trim()
            if (r.isEmpty()) return input
            if (input.isBlank()) return r
            return input.trimEnd() + " " + r
        }
    }
}
