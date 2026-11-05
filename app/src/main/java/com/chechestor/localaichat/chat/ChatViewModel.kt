package com.chechestor.localaichat.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chechestor.localaichat.LocalAiChatApp
import com.chechestor.localaichat.data.MessageEntity
import com.chechestor.localaichat.data.SessionEntity
import com.chechestor.localaichat.llm.EngineState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Экран диалога и боковая панель (F004). */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val chatApp = app as LocalAiChatApp
    private val repo = chatApp.chatRepository
    private val controller = chatApp.chat

    /** Открытая сессия; null — новый (ещё не сохранённый) диалог. */
    private val _currentId = MutableStateFlow<Long?>(null)
    val currentId: StateFlow<Long?> = _currentId.asStateFlow()

    private val _messagesToUser = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Короткие сообщения для пользователя (snackbar). */
    val messagesToUser: SharedFlow<String> = _messagesToUser.asSharedFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    val sessions: StateFlow<List<SessionEntity>> =
        repo.sessions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val title: StateFlow<String> = _currentId.flatMapLatest { id ->
        if (id == null) flowOf(NEW_DIALOG_TITLE) else repo.session(id).map { it?.title ?: NEW_DIALOG_TITLE }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NEW_DIALOG_TITLE)

    val messages: StateFlow<List<MessageEntity>> = _currentId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.messages(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val active: StateFlow<ActiveGeneration?> = controller.active
    val engineState: StateFlow<EngineState> = controller.engineState

    init {
        controller.ensureModelLoaded()
    }

    fun onInput(text: String) {
        _input.value = text
    }

    fun clearInput() {
        _input.value = ""
    }

    fun newDialog() {
        _currentId.value = null
    }

    fun open(sessionId: Long) {
        _currentId.value = sessionId
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isEmpty()) return
        _input.value = ""
        val target = _currentId.value
        viewModelScope.launch {
            when (val r = controller.send(target, text)) {
                is SendResult.Sent -> {
                    // Пользователь мог за это время открыть другой диалог — не переключаем его.
                    if (_currentId.value == target) _currentId.value = r.sessionId
                }
                SendResult.TooLong -> {
                    if (_input.value.isEmpty()) _input.value = text
                    _messagesToUser.tryEmit("Сообщение слишком длинное, сократите его")
                }
                SendResult.ModelUnavailable -> {
                    if (_input.value.isEmpty()) _input.value = text
                    _messagesToUser.tryEmit("Модель не загружена")
                }
            }
        }
    }

    fun stop() = controller.stop()

    fun rename(sessionId: Long, title: String) {
        viewModelScope.launch { repo.rename(sessionId, title) }
    }

    fun delete(sessionId: Long) {
        viewModelScope.launch {
            controller.stopIfSession(sessionId)
            repo.delete(sessionId)
            if (_currentId.value == sessionId) _currentId.value = null
        }
    }

    companion object {
        const val NEW_DIALOG_TITLE = "Новый диалог"
    }
}
