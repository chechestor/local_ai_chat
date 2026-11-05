package com.chechestor.localaichat

import android.app.Application
import com.chechestor.localaichat.chat.ChatController
import com.chechestor.localaichat.data.ChatDatabase
import com.chechestor.localaichat.data.ChatRepository
import com.chechestor.localaichat.llm.LlmEngine
import com.chechestor.localaichat.models.ModelStore
import com.chechestor.localaichat.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Приложение: общие объекты на весь процесс (зависимости связываются вручную). */
class LocalAiChatApp : Application() {
    /** Область для работы, которая переживает экраны: генерация ответа, загрузка модели. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val llm: LlmEngine by lazy { LlmEngine(nativeLibDir = applicationInfo.nativeLibraryDir) }

    /** Папка моделей Download/LocalAIChat (F008). */
    val models: ModelStore by lazy { ModelStore(this) }

    val chatRepository: ChatRepository by lazy { ChatRepository(ChatDatabase.create(this)) }

    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    val chat: ChatController by lazy { ChatController(this, chatRepository, llm, models, settings, appScope) }

    companion object {
        /** Системный промпт (F004 п. 10): фиксирован, пользователю не виден. */
        const val SYSTEM_PROMPT = "Ты — полезный ассистент. Отвечай по-русски, кратко и по делу."
    }
}
