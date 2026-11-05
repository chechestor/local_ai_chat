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
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chechestor.localaichat.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Фоновая загрузка моделей (F008, F009): только Wi-Fi, докачка с места обрыва, проверка SHA-256,
 * уведомление с прогрессом. Пропал Wi-Fi — задача ставится на паузу (retry) и продолжается, когда он вернётся.
 * Каждая задача качает свой список файлов по очереди; задачи между собой тоже идут по одной ([downloadLock]).
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val store = ModelStore(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val specs = specsFrom(inputData)
        if (specs.isEmpty()) return@withContext Result.success()
        // Другая задача качает — ждём своей очереди («В очереди», F009 п. 5).
        setProgress(workDataOf(KEY_QUEUED to true, KEY_MODEL_ID to specs.first().id))
        downloadLock.withLock { downloadAll(specs) }
    }

    private suspend fun downloadAll(specs: List<ModelSpec>): Result {
        runCatching { setForeground(foregroundInfo(0, 0, "Подготовка…")) }
        val dir = store.folder() ?: return failure("Нет доступа к папке ${store.folderDisplayPath()}. Выберите её заново.")

        val total = specs.sumOf { it.sizeBytes }
        for ((index, spec) in specs.withIndex()) {
            val existing = dir.findFile(spec.fileName)
            if (existing != null && existing.length() == spec.sizeBytes) continue
            existing?.delete()
            if (!WifiState.isWifi(applicationContext)) return Result.retry()
            try {
                download(dir, spec, index, specs.size, total)
            } catch (e: ModelHttp.ForbiddenHostException) {
                return failure(e.message ?: "Недопустимый адрес загрузки")
            } catch (e: ShaMismatchException) {
                return failure("Файл «${spec.fileName}» скачался с ошибкой (не совпала контрольная сумма). Нажмите «Повторить».")
            } catch (e: IOException) {
                if (isStopped) return Result.retry()
                // Обрыв сети: продолжим с места обрыва, когда снова будет Wi-Fi.
                return if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
                else failure("Не удалось скачать «${spec.fileName}»: ${e.message}")
            }
            if (isStopped) return Result.retry()
        }
        return Result.success()
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
                KEY_MODEL_COUNT to count,
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
        /** Задача экрана «Установка моделей» (F008, F009 п. 2). */
        const val WORK_NAME = "model-download"
        /** Метка всех задач загрузки; `model:<id>` — метка каждой модели в задаче. */
        const val TAG = "model-download"
        const val TAG_MODEL_PREFIX = "model:"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_MODEL_INDEX = "model_index"
        const val KEY_MODEL_COUNT = "model_count"
        const val KEY_QUEUED = "queued"
        const val KEY_MODEL_BYTES = "model_bytes"
        const val KEY_MODEL_TOTAL = "model_total"
        const val KEY_VERIFYING = "verifying"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_ATTEMPTS = 50
        private const val MB = 1024 * 1024

        private const val KEY_IDS = "ids"
        private const val KEY_TITLES = "titles"
        private const val KEY_FILES = "files"
        private const val KEY_URLS = "urls"
        private const val KEY_SIZES = "sizes"
        private const val KEY_SHAS = "shas"

        /** Задачи загрузки в процессе идут по одной: одна сеть, одно уведомление. */
        private val downloadLock = Mutex()

        /**
         * Ставит загрузку [specs] по очереди. [workName] — уникальное имя задачи: экран установки — [WORK_NAME],
         * настройки — [workNameFor] модели (F009 п. 5).
         */
        fun start(context: Context, specs: List<ModelSpec>, workName: String = WORK_NAME) {
            val data = Data.Builder()
                .putStringArray(KEY_IDS, specs.map { it.id }.toTypedArray())
                .putStringArray(KEY_TITLES, specs.map { it.title }.toTypedArray())
                .putStringArray(KEY_FILES, specs.map { it.fileName }.toTypedArray())
                .putStringArray(KEY_URLS, specs.map { it.url }.toTypedArray())
                .putLongArray(KEY_SIZES, specs.map { it.sizeBytes }.toLongArray())
                .putStringArray(KEY_SHAS, specs.map { it.sha256 }.toTypedArray())
                .build()
            val builder = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                // UNMETERED: WorkManager не запустит задачу по мобильной сети; внутри — ещё проверка именно Wi-Fi.
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .setInputData(data)
                .addTag(TAG)
            specs.forEach { builder.addTag(TAG_MODEL_PREFIX + it.id) }
            WorkManager.getInstance(context).enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, builder.build())
        }

        fun workNameFor(id: String) = "$WORK_NAME-$id"

        /** Отменяет задачу экрана установки. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** Отменяет загрузку модели из настроек (F009 п. 5). */
        fun cancelModel(context: Context, id: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workNameFor(id))
        }

        /** Отменяет все загрузки («Удалить и скачать заново»). */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }

        private fun specsFrom(data: Data): List<ModelSpec> {
            val ids = data.getStringArray(KEY_IDS) ?: return emptyList()
            val titles = data.getStringArray(KEY_TITLES) ?: return emptyList()
            val files = data.getStringArray(KEY_FILES) ?: return emptyList()
            val urls = data.getStringArray(KEY_URLS) ?: return emptyList()
            val sizes = data.getLongArray(KEY_SIZES) ?: return emptyList()
            val shas = data.getStringArray(KEY_SHAS) ?: return emptyList()
            return ids.indices.map { i -> ModelSpec(ids[i], titles[i], files[i], urls[i], sizes[i], shas[i]) }
        }
    }
}
