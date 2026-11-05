package com.chechestor.localaichat.llm

/** Прямые вызовы JNI (app/src/main/cpp/llm_jni.cpp). Использовать через [LlmEngine]. */
internal object LlamaNative {
    const val RESULT_DONE = 0
    const val RESULT_STOPPED = 1
    const val RESULT_LENGTH = 2
    const val ERR_PROMPT_TOO_LONG = -2
    const val ERR_DECODE = -3
    const val ERR_TOKENIZE = -4
    const val ERR_BAD_HANDLE = -5

    init {
        System.loadLibrary("llm_jni")
    }

    fun interface BytesCallback {
        /** Кусок ответа: целые символы UTF-8. */
        fun onBytes(bytes: ByteArray)
    }

    external fun backendInit(nativeLibDir: String)
    external fun systemInfo(): String
    external fun loadModel(fd: Int, nCtx: Int, nThreads: Int, quantizeKv: Boolean): Long
    external fun contextSize(handle: Long): Int
    external fun countTokens(handle: Long, utf8: ByteArray): Int
    external fun requestStop(handle: Long)
    external fun generate(
        handle: Long,
        promptUtf8: ByteArray,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        seed: Int,
        callback: BytesCallback,
    ): Int
    external fun free(handle: Long)
}
