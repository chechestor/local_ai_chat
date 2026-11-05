package com.chechestor.localaichat

import android.app.Application
import com.chechestor.localaichat.llm.LlmEngine
import com.chechestor.localaichat.models.ModelStore

/** Приложение: один движок LLM на весь процесс. */
class LocalAiChatApp : Application() {
    val llm: LlmEngine by lazy { LlmEngine(nativeLibDir = applicationInfo.nativeLibraryDir) }

    /** Папка моделей Download/LocalAIChat (F008). */
    val models: ModelStore by lazy { ModelStore(this) }

    companion object {
        const val SYSTEM_PROMPT = "Ты — полезный ассистент. Отвечай по-русски, кратко и по делу."
    }
}
