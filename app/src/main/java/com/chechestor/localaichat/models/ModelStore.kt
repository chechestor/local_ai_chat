package com.chechestor.localaichat.models

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Состояние одной модели в папке. */
data class ModelStatus(val spec: ModelSpec, val present: Boolean, val partialBytes: Long)

/** Итог проверки папки моделей при запуске. */
sealed interface FolderCheck {
    /** Папка не выбрана, доступа нет или выбрана не та папка. */
    data class NeedFolder(val wrongFolder: Boolean) : FolderCheck
    data class Missing(val models: List<ModelStatus>) : FolderCheck
    data object Ready : FolderCheck
}

/**
 * Папка `Download/LocalAIChat` (SAF) и файлы моделей в ней (F008).
 * Файлы в общей папке переживают удаление приложения; доступ к папке выдаётся заново после переустановки.
 */
class ModelStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("models", Context.MODE_PRIVATE)

    // --- Папка ---

    /** Окно выбора папки открывается сразу в `Download`. */
    fun pickFolderInitialUri(): Uri =
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:${Environment.DIRECTORY_DOWNLOADS}")

    /** Сохраняет выбранную папку. false — выбрана не `Download/LocalAIChat`. */
    fun onFolderPicked(treeUri: Uri): Boolean {
        if (!isExpectedFolder(treeUri)) return false
        app.contentResolver.takePersistableUriPermission(
            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit { putString(KEY_TREE, treeUri.toString()) }
        return true
    }

    /** Выбранная папка, если доступ к ней ещё есть. */
    fun folder(): DocumentFile? {
        val uri = prefs.getString(KEY_TREE, null)?.let(Uri::parse) ?: return null
        val granted = app.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!granted) return null
        return DocumentFile.fromTreeUri(app, uri)?.takeIf { it.isDirectory && it.canWrite() }
    }

    /** Путь к папке для показа в настройках (F007). */
    fun folderDisplayPath(): String = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME"

    // --- Проверка ---

    /**
     * Проверяет модели: наличие и размер — всегда; SHA-256 — при первой проверке после установки
     * (результат запоминается до переустановки). [onProgress] — доля проверенного объёма 0..1.
     */
    suspend fun check(onProgress: (Float) -> Unit = {}): FolderCheck = withContext(Dispatchers.IO) {
        val hadTree = prefs.contains(KEY_TREE)
        val dir = folder() ?: return@withContext FolderCheck.NeedFolder(wrongFolder = hadTree)
        val statuses = ModelCatalog.ALL.map { spec ->
            val f = dir.findFile(spec.fileName)
            val part = dir.findFile(partName(spec))
            ModelStatus(spec, present = f != null && f.length() == spec.sizeBytes, partialBytes = part?.length() ?: 0L)
        }
        if (statuses.any { !it.present }) return@withContext FolderCheck.Missing(statuses)

        val toHash = ModelCatalog.ALL.filter { !isVerified(it, dir.findFile(it.fileName)!!) }
        val total = toHash.sumOf { it.sizeBytes }.coerceAtLeast(1)
        var done = 0L
        for (spec in toHash) {
            val file = dir.findFile(spec.fileName)!!
            val ok = sha256(file.uri) { n -> onProgress(((done + n).toFloat() / total).coerceIn(0f, 1f)) } == spec.sha256
            done += spec.sizeBytes
            if (!ok) {
                // Подменённый или битый файл удаляем — модель будет скачана заново.
                file.delete()
                return@withContext FolderCheck.Missing(statuses.map { if (it.spec == spec) it.copy(present = false) else it })
            }
            markVerified(spec, file)
        }
        prepareVosk(dir)
        FolderCheck.Ready
    }

    private fun signature(file: DocumentFile) = "${file.length()}:${file.lastModified()}"
    private fun isVerified(spec: ModelSpec, file: DocumentFile) = prefs.getString(KEY_VERIFIED + spec.id, null) == signature(file)
    fun markVerified(spec: ModelSpec, file: DocumentFile) {
        prefs.edit { putString(KEY_VERIFIED + spec.id, signature(file)) }
    }

    fun sha256(uri: Uri, onBytes: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input: InputStream = app.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
        input.use {
            val buf = ByteArray(1 shl 20)
            var read = 0L
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                read += n
                onBytes(read)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    // --- Свободное место ---

    /** Сколько ещё нужно места для загрузки недостающих моделей (вместе с запасом). */
    fun requiredBytes(statuses: List<ModelStatus>): Long =
        statuses.filter { !it.present }.sumOf { (it.spec.sizeBytes - it.partialBytes).coerceAtLeast(0) } +
            ModelCatalog.VOSK_UNPACKED_BYTES + ModelCatalog.FREE_SPACE_RESERVE

    fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    // --- Файлы для движков ---

    /** Дескриптор файла Qwen для llama.cpp (без копирования, F003). Закрывает вызывающий. */
    fun openQwen(): ParcelFileDescriptor? {
        val f = folder()?.findFile(ModelCatalog.QWEN.fileName) ?: return null
        return app.contentResolver.openFileDescriptor(f.uri, "r")
    }

    /** Папка распакованной модели Vosk во внутренней памяти (F005). */
    fun voskModelDir(): File = File(app.filesDir, "vosk/${ModelCatalog.VOSK_DIR_NAME}")

    /** Распаковывает zip Vosk во внутреннюю память, если там ещё нет этой версии. */
    private fun prepareVosk(dir: DocumentFile) {
        val target = voskModelDir()
        val marker = File(target, ".sha256")
        if (marker.isFile && marker.readText() == ModelCatalog.VOSK.sha256) return
        val zip = dir.findFile(ModelCatalog.VOSK.fileName) ?: return
        val root = File(app.filesDir, "vosk")
        val tmp = File(root, "unpack.tmp").apply { deleteRecursively(); mkdirs() }
        app.contentResolver.openInputStream(zip.uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val out = File(tmp, e.name).canonicalFile
                    if (!out.path.startsWith(tmp.canonicalPath + File.separator)) throw IOException("Недопустимый путь в архиве")
                    if (e.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zin.copyTo(it) }
                    }
                }
            }
        } ?: throw IOException("Не удалось открыть архив Vosk")
        val unpacked = File(tmp, ModelCatalog.VOSK_DIR_NAME)
        if (!unpacked.isDirectory) throw IOException("В архиве Vosk нет папки модели")
        target.deleteRecursively()
        if (!unpacked.renameTo(target)) throw IOException("Не удалось распаковать Vosk")
        marker.writeText(ModelCatalog.VOSK.sha256)
        tmp.deleteRecursively()
    }

    // --- Удаление (настройки, F007) ---

    /** Удаляет файлы моделей в папке (и распакованный Vosk). Диалоги не трогает. */
    suspend fun deleteModels() = withContext(Dispatchers.IO) {
        folder()?.let { dir ->
            for (spec in ModelCatalog.ALL) {
                dir.findFile(spec.fileName)?.delete()
                dir.findFile(partName(spec))?.delete()
            }
        }
        prefs.edit { ModelCatalog.ALL.forEach { remove(KEY_VERIFIED + it.id) } }
        File(app.filesDir, "vosk").deleteRecursively()
    }

    companion object {
        const val FOLDER_NAME = "LocalAIChat"
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val KEY_TREE = "tree_uri"
        private const val KEY_VERIFIED = "verified_"

        fun partName(spec: ModelSpec) = spec.fileName + ".part"

        /** Папка должна быть именно `Download/LocalAIChat` во внутренней памяти телефона. */
        fun isExpectedFolder(treeUri: Uri): Boolean {
            if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return false
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return false
            return isExpectedDocId(docId)
        }

        internal fun isExpectedDocId(docId: String): Boolean =
            docId.trimEnd('/').equals("primary:Download/$FOLDER_NAME", ignoreCase = true)
    }
}
