package com.chechestor.localaichat

import android.app.Application
import com.chechestor.localaichat.llm.LlmEngine
import java.io.File

/** Приложение: один движок LLM на весь процесс. */
class LocalAiChatApp : Application() {
    val llm: LlmEngine by lazy { LlmEngine(nativeLibDir = applicationInfo.nativeLibraryDir) }

    /**
     * Файл модели для разработки F003 (до F008): кладётся вручную командой
     * `adb push Qwen3-0.6B-Q4_K_M.gguf /sdcard/Android/data/com.chechestor.localaichat/files/models/`.
     */
    val devModelFile: File
        get() = File(getExternalFilesDir(null), "models/$QWEN_FILE_NAME")

    companion object {
        const val QWEN_FILE_NAME = "Qwen3-0.6B-Q4_K_M.gguf"
        const val SYSTEM_PROMPT = "Ты — полезный ассистент. Отвечай по-русски, кратко и по делу."
    }
}
