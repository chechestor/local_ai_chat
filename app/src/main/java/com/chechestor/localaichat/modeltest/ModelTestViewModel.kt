package com.chechestor.localaichat.modeltest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chechestor.localaichat.LocalAiChatApp
import com.chechestor.localaichat.llm.ChatMessage
import com.chechestor.localaichat.llm.ChatRole
import com.chechestor.localaichat.llm.EngineState
import com.chechestor.localaichat.llm.FinishReason
import com.chechestor.localaichat.llm.GenerationEvent
import com.chechestor.localaichat.llm.GenerationParams
import com.chechestor.localaichat.llm.GenerationStats
import com.chechestor.localaichat.llm.ProcessMemory
import com.chechestor.localaichat.llm.ThinkingParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TurnUi(val user: String, val thinking: String, val answer: String)

data class ModelTestUi(
    val engine: EngineState = EngineState.NotLoaded,
    val input: String = "",
    val thinking: Boolean = false,
    val generating: Boolean = false,
    val turns: List<TurnUi> = emptyList(),
    val lastStats: GenerationStats? = null,
    val lastReason: FinishReason? = null,
    val memory: ProcessMemory = ProcessMemory(0, 0),
)

/** Временный экран проверки движка (F003); заменяется диалогами F004. */
class ModelTestViewModel(app: Application) : AndroidViewModel(app) {
    private val chatApp = app as LocalAiChatApp
    private val engine = chatApp.llm

    private val _ui = MutableStateFlow(ModelTestUi())
    val ui: StateFlow<ModelTestUi> = _ui.asStateFlow()

    private val history = mutableListOf<ChatMessage>()
    private var job: Job? = null

    init {
        viewModelScope.launch { engine.state.collect { s -> _ui.update { it.copy(engine = s, memory = ProcessMemory.read()) } } }
    }

    /** Загрузка один раз за запуск, в фоне: файл Qwen открывается из папки моделей по дескриптору (F008). */
    fun loadIfPossible() {
        if (engine.state.value is EngineState.Ready || engine.state.value is EngineState.Loading) return
        viewModelScope.launch {
            val pfd = withContext(Dispatchers.IO) { runCatching { chatApp.models.openQwen() }.getOrNull() }
            if (pfd == null) {
                _ui.update { it.copy(engine = EngineState.Failed("Нет доступа к файлу модели в папке ${chatApp.models.folderDisplayPath()}")) }
                return@launch
            }
            pfd.use { engine.load(it) }
            _ui.update { it.copy(memory = ProcessMemory.read()) }
        }
    }

    fun onInput(text: String) = _ui.update { it.copy(input = text) }
    fun onThinking(on: Boolean) = _ui.update { it.copy(thinking = on) }

    fun send() {
        val state = _ui.value
        val text = state.input.trim()
        if (text.isEmpty() || state.generating || state.engine !is EngineState.Ready) return
        if (history.isEmpty()) history += ChatMessage(ChatRole.SYSTEM, LocalAiChatApp.SYSTEM_PROMPT)
        history += ChatMessage(ChatRole.USER, text)
        val turnIndex = state.turns.size
        _ui.update { it.copy(input = "", generating = true, turns = it.turns + TurnUi(text, "", ""), lastStats = null, lastReason = null) }

        job = viewModelScope.launch {
            val raw = StringBuilder()
            engine.generate(history.toList(), GenerationParams(thinking = state.thinking)).collect { ev ->
                when (ev) {
                    is GenerationEvent.Text -> {
                        raw.append(ev.piece)
                        val parsed = ThinkingParser.parse(raw.toString())
                        _ui.update { s ->
                            s.copy(turns = s.turns.mapIndexed { i, t ->
                                if (i == turnIndex) t.copy(thinking = parsed.thinking, answer = parsed.answer) else t
                            })
                        }
                    }
                    is GenerationEvent.Finished -> {
                        // В историю для модели идёт только ответ, без размышлений.
                        val answer = ThinkingParser.parse(raw.toString()).answer
                        history += ChatMessage(ChatRole.ASSISTANT, answer)
                        _ui.update { it.copy(generating = false, lastStats = ev.stats, lastReason = ev.reason, memory = ProcessMemory.read()) }
                    }
                }
            }
            _ui.update { it.copy(generating = false) }
        }
    }

    fun stop() {
        engine.stop()
    }

    fun clearDialog() {
        if (_ui.value.generating) return
        history.clear()
        _ui.update { it.copy(turns = emptyList(), lastStats = null, lastReason = null) }
    }
}
