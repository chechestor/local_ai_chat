package com.chechestor.localaichat.llm

import java.io.File

/** Память процесса из /proc/self/status: текущая (VmRSS) и пиковая (VmHWM), в МБ. */
data class ProcessMemory(val rssMb: Int, val peakRssMb: Int) {
    companion object {
        fun read(): ProcessMemory = parse(
            runCatching { File("/proc/self/status").readText() }.getOrDefault(""),
        )

        internal fun parse(status: String): ProcessMemory {
            fun kb(key: String): Int = status.lineSequence()
                .firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toIntOrNull() ?: 0
            return ProcessMemory(kb("VmRSS") / 1024, kb("VmHWM") / 1024)
        }
    }
}
