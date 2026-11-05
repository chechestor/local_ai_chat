package com.chechestor.localaichat.models

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chechestor.localaichat.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Фоновая загрузка моделей (F008): только Wi-Fi, докачка с места обрыва, проверка SHA-256,
 * уведомление с прогрессом. Пропал Wi-Fi — задача ставится на паузу (retry) и продолжается, когда он вернётся.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val store = ModelStore(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { setForeground(foregroundInfo(0, 0, "Подготовка…")) }
        val dir = store.folder() ?: return@withContext failure("Нет доступа к папке ${store.folderDisplayPath()}. Выберите её заново.")

        val specs = ModelCatalog.ALL
        val total = specs.sumOf { it.sizeBytes }
        for ((index, spec) in specs.withIndex()) {
            val existing = dir.findFile(spec.fileName)
            if (existing != null && existing.length() == spec.sizeBytes) continue
            existing?.delete()
            if (!WifiState.isWifi(applicationContext)) return@withContext Result.retry()
            try {
                download(dir, spec, index, specs.size, total)
            } catch (e: ModelHttp.ForbiddenHostException) {
                return@withContext failure(e.message ?: "Недопустимый адрес загрузки")
            } catch (e: ShaMismatchException) {
                return@withContext failure("Файл «${spec.fileName}» скачался с ошибкой (не совпала контрольная сумма). Нажмите «Повторить».")
            } catch (e: IOException) {
                if (isStopped) return@withContext Result.retry()
                // Обрыв сети: продолжим с места обрыва, когда снова будет Wi-Fi.
                return@withContext if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
                else failure("Не удалось скачать «${spec.fileName}»: ${e.message}")
            }
            if (isStopped) return@withContext Result.retry()
        }
        Result.success()
    }

    private class ShaMismatchException : IOException()

    private suspend fun download(dir: DocumentFile, spec: ModelSpec, index: Int, count: Int, totalAll: Long) {
        val partName = ModelStore.partName(spec)
        val part = dir.findFile(partName) ?: dir.createFile("application/octet-stream", partName)
            ?: throw IOException("Не удалось создать файл в папке")
        var offset = part.length().coerceAtMost(spec.sizeBytes)

        if (offset < spec.sizeBytes) {
            val conn = ModelHttp.open(spec.url, offset)
            try {
                if (conn.responseCode == 200) offset = 0 // сервер не умеет докачку — начинаем сначала
                val pfd = applicationContext.contentResolver.openFileDescriptor(part.uri, "rw")
                    ?: throw IOException("Не удалось открыть файл для записи")
                pfd.use {
                    FileOutputStream(it.fileDescriptor).use { out ->
                        val channel = out.channel
                        channel.truncate(offset)
                        channel.position(offset)
                        if (conn.responseCode != 416) conn.inputStream.use { input ->
                            val buf = ByteArray(256 * 1024)
                            var lastReport = 0L
                            while (true) {
                                if (isStopped) throw IOException("Остановлено")
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                offset += n
                                if (offset > spec.sizeBytes) throw ShaMismatchException()
                                if (offset - lastReport >= 2L * 1024 * 1024 || offset == spec.sizeBytes) {
                                    lastReport = offset
                                    report(spec, index, count, offset, totalAll)
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        if (offset != spec.sizeBytes) throw IOException("Загрузка прервалась")

        report(spec, index, count, offset, totalAll, verifying = true)
        val sha = store.sha256(part.uri)
        if (sha != spec.sha256) {
            part.delete()
            throw ShaMismatchException()
        }
        if (!part.renameTo(spec.fileName)) throw IOException("Не удалось переименовать файл")
        dir.findFile(spec.fileName)?.let { store.markVerified(spec, it) }
    }

    private suspend fun report(spec: ModelSpec, index: Int, count: Int, bytes: Long, totalAll: Long, verifying: Boolean = false) {
        setProgress(
            workDataOf(
                KEY_MODEL_ID to spec.id,
                KEY_MODEL_INDEX to index,
                KEY_MODEL_BYTES to bytes,
                KEY_MODEL_TOTAL to spec.sizeBytes,
                KEY_VERIFYING to verifying,
            ),
        )
        val title = if (verifying) "Проверка ${index + 1} из $count" else "Модель ${index + 1} из $count"
        runCatching {
            setForeground(foregroundInfo(bytes, spec.sizeBytes, "$title: ${bytes / MB} из ${spec.sizeBytes / MB} МБ"))
        }
    }

    private fun failure(message: String) = Result.failure(workDataOf(KEY_ERROR to message))

    private fun foregroundInfo(done: Long, total: Long, text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Загрузка моделей", NotificationManager.IMPORTANCE_LOW),
        )
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Загрузка моделей")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, total <= 0)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val WORK_NAME = "model-download"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_MODEL_INDEX = "model_index"
        const val KEY_MODEL_BYTES = "model_bytes"
        const val KEY_MODEL_TOTAL = "model_total"
        const val KEY_VERIFYING = "verifying"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_ATTEMPTS = 50
        private const val MB = 1024 * 1024

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                // UNMETERED: WorkManager не запустит задачу по мобильной сети; внутри — ещё проверка именно Wi-Fi.
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
