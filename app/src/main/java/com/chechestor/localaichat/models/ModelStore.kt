package com.chechestor.localaichat.models

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Состояние одного файла модели в папке. [llm] — для языковых моделей. */
data class ModelStatus(
    val spec: ModelSpec,
    val present: Boolean,
    val partialBytes: Long,
    val llm: LlmModel? = null,
)

/** Итог проверки папки моделей при запуске. */
sealed interface FolderCheck {
    /** Папка не выбрана, доступа нет или выбрана не та папка. */
    data class NeedFolder(val wrongFolder: Boolean) : FolderCheck

    /** Нет Vosk или ни одной языковой модели: экран «Установка моделей». */
    data class Missing(val models: List<ModelStatus>, val vosk: ModelStatus) : FolderCheck

    /** Vosk на месте, [downloaded] — проверенные языковые модели (хотя бы одна). */
    data class Ready(val downloaded: List<LlmModel>) : FolderCheck
}

/** Содержимое `Download/LocalAIChat/models.json` (F009 п. 3, F010 п. 8). */
data class ModelsFile(val active: String?, val custom: List<LlmModel>)

/**
 * Папка `Download/LocalAIChat` (SAF) и файлы моделей в ней (F008, F009).
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

    // --- models.json ---

    /** Читает `models.json`; файла нет или он битый — пустой список. */
    suspend fun readModelsFile(): ModelsFile = withContext(Dispatchers.IO) {
        val dir = folder() ?: return@withContext ModelsFile(null, emptyList())
        readModelsFile(dir)
    }

    private fun readModelsFile(dir: DocumentFile): ModelsFile {
        val f = dir.findFile(MODELS_JSON) ?: return ModelsFile(null, emptyList())
        return runCatching {
            val text = app.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return ModelsFile(null, emptyList())
            decodeModelsFile(text)
        }.getOrElse {
            Log.w(TAG, "models.json не прочитан: ${it.message}")
            ModelsFile(null, emptyList())
        }
    }

    private suspend fun writeModelsFile(update: (ModelsFile) -> ModelsFile) = withContext(Dispatchers.IO) {
        val dir = folder() ?: return@withContext
        val next = update(readModelsFile(dir))
        val f = dir.findFile(MODELS_JSON) ?: dir.createFile("application/json", MODELS_JSON) ?: return@withContext
        runCatching {
            app.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(encodeModelsFile(next).toByteArray(Charsets.UTF_8)) }
        }.onFailure { Log.w(TAG, "models.json не записан: ${it.message}") }
    }

    /** Активная модель в `models.json` (восстанавливается после переустановки, F009 п. 3). */
    suspend fun writeActive(id: String?) = writeModelsFile { it.copy(active = id) }

    /** Добавляет или обновляет модель, скачанную по названию (F010 п. 8). */
    suspend fun addCustom(model: LlmModel) = writeModelsFile { f ->
        f.copy(custom = f.custom.filter { it.id != model.id } + model)
    }

    /** Итог пробной загрузки модели по названию (F010 п. 8). */
    suspend fun markCustom(id: String, supported: Boolean) = writeModelsFile { f ->
        f.copy(custom = f.custom.map { if (it.id == id) it.copy(checked = true, unsupported = !supported) else it })
    }

    /** Все языковые модели, которые знает приложение: каталог + `models.json` (F009 п. 5). */
    suspend fun languageModels(): List<LlmModel> = ModelCatalog.LANGUAGE + readModelsFile().custom

    suspend fun findModel(id: String): LlmModel? = languageModels().firstOrNull { it.id == id }

    // --- Проверка ---

    /**
     * Проверяет модели: наличие и размер — всегда; SHA-256 — при первой проверке после установки
     * (результат запоминается до переустановки). Битые файлы удаляются. [onProgress] — доля проверенного объёма 0..1.
     */
    suspend fun check(onProgress: (Float) -> Unit = {}): FolderCheck = withContext(Dispatchers.IO) {
        val hadTree = prefs.contains(KEY_TREE)
        val dir = folder() ?: return@withContext FolderCheck.NeedFolder(wrongFolder = hadTree)
        val files = dir.listFiles().associateBy { it.name }
        val llms = ModelCatalog.LANGUAGE + readModelsFile(dir).custom

        fun status(spec: ModelSpec, llm: LlmModel?): ModelStatus {
            val f = files[spec.fileName]
            return ModelStatus(
                spec,
                present = f != null && f.length() == spec.sizeBytes,
                partialBytes = files[partName(spec)]?.length() ?: 0L,
                llm = llm,
            )
        }

        var statuses = llms.map { status(it.spec, it) }
        var vosk = status(ModelCatalog.VOSK, null)

        val toHash = (statuses + vosk).filter { it.present && !isVerified(it.spec, files.getValue(it.spec.fileName)) }
        val total = toHash.sumOf { it.spec.sizeBytes }.coerceAtLeast(1)
        var done = 0L
        for (st in toHash) {
            val file = files.getValue(st.spec.fileName)
            val ok = sha256(file.uri) { n -> onProgress(((done + n).toFloat() / total).coerceIn(0f, 1f)) } == st.spec.sha256
            done += st.spec.sizeBytes
            if (ok) {
                markVerified(st.spec, file)
            } else {
                // Подменённый или битый файл удаляем — модель можно скачать заново.
                Log.w(TAG, "SHA-256 не совпал: ${st.spec.fileName}")
                file.delete()
                if (st.spec == vosk.spec) vosk = vosk.copy(present = false)
                else statuses = statuses.map { if (it.spec == st.spec) it.copy(present = false) else it }
            }
        }

        // Модель, которая не прошла пробную загрузку (F010), активной быть не может.
        val downloaded = statuses.filter { it.present }.mapNotNull { it.llm }.filter { !it.unsupported }
        if (!vosk.present || downloaded.isEmpty()) return@withContext FolderCheck.Missing(statuses, vosk)
        prepareVosk(dir)
        FolderCheck.Ready(downloaded)
    }

    /** Состояние языковых моделей в папке без проверки SHA-256 (настройки, F009 п. 5). */
    suspend fun scanLanguageModels(): List<ModelStatus> = withContext(Dispatchers.IO) {
        val dir = folder() ?: return@withContext emptyList()
        val files = dir.listFiles().associateBy { it.name }
        (ModelCatalog.LANGUAGE + readModelsFile(dir).custom).map { m ->
            val f = files[m.spec.fileName]
            ModelStatus(
                m.spec,
                present = f != null && f.length() == m.spec.sizeBytes,
                partialBytes = files[partName(m.spec)]?.length() ?: 0L,
                llm = m,
            )
        }
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

    /** Сколько места нужно для загрузки [toDownload] (вместе с распакованным Vosk, если он качается, и запасом). */
    fun requiredBytes(toDownload: List<ModelStatus>): Long =
        toDownload.filter { !it.present }.sumOf { (it.spec.sizeBytes - it.partialBytes).coerceAtLeast(0) } +
            (if (toDownload.any { it.spec.id == ModelCatalog.VOSK.id && !it.present }) ModelCatalog.VOSK_UNPACKED_BYTES else 0L) +
            ModelCatalog.FREE_SPACE_RESERVE

    fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    // --- Файлы для движков ---

    /** Дескриптор файла языковой модели для llama.cpp (без копирования, F003). Закрывает вызывающий. */
    fun openModel(model: LlmModel): ParcelFileDescriptor? {
        val f = folder()?.findFile(model.spec.fileName) ?: return null
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

    // --- Удаление ---

    /** Удаляет одну языковую модель (файл, `.part`, запись в `models.json` для добавленных по названию). */
    suspend fun deleteModel(model: LlmModel) = withContext(Dispatchers.IO) {
        folder()?.let { dir ->
            dir.findFile(model.spec.fileName)?.delete()
            dir.findFile(partName(model.spec))?.delete()
        }
        prefs.edit { remove(KEY_VERIFIED + model.id) }
        if (model.custom) writeModelsFile { f -> f.copy(custom = f.custom.filter { it.id != model.id }) }
    }

    /** «Удалить и скачать заново» (F009 п. 5): все модели, `.part`, `models.json` и распакованный Vosk. Диалоги не трогает. */
    suspend fun deleteModels() = withContext(Dispatchers.IO) {
        folder()?.let { dir ->
            val specs = ModelCatalog.ALL + readModelsFile(dir).custom.map { it.spec }
            val names = specs.flatMap { listOf(it.fileName, partName(it)) }.toSet() + MODELS_JSON
            for (f in dir.listFiles()) if (f.name in names) f.delete()
        }
        prefs.edit { prefs.all.keys.filter { it.startsWith(KEY_VERIFIED) }.forEach { remove(it) } }
        File(app.filesDir, "vosk").deleteRecursively()
    }

    companion object {
        const val FOLDER_NAME = "LocalAIChat"
        const val MODELS_JSON = "models.json"
        private const val TAG = "ModelStore"
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

        /** Модель, добавленная по названию (F010): запись `models.json` → модель приложения. */
        fun customModel(
            repo: String,
            commit: String,
            file: String,
            sizeBytes: Long,
            sha256: String,
            title: String,
            checked: Boolean = false,
            unsupported: Boolean = false,
        ): LlmModel {
            val local = customFileName(repo, file)
            return LlmModel(
                spec = ModelSpec(
                    id = "hf:$local",
                    title = title,
                    fileName = local,
                    url = "https://huggingface.co/$repo/resolve/$commit/$file",
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                ),
                note = "Добавлена по названию",
                format = ChatFormat.EMBEDDED,
                supportsThinking = false,
                sampling = ModelCatalog.CUSTOM_SAMPLING,
                custom = true,
                repo = repo,
                commit = commit,
                checked = checked,
                unsupported = unsupported,
            )
        }

        /** Название модели по названию: имя репозитория без `-GGUF` (F010 п. 8). */
        fun customTitle(repo: String): String {
            val name = repo.substringAfter('/')
            return if (name.endsWith("-GGUF", ignoreCase = true)) name.dropLast(5) else name
        }

        /** Имя файла в папке: `<владелец>__<файл>`, чтобы не совпасть с файлами каталога (F010 п. 7). */
        fun customFileName(repo: String, file: String): String = repo.substringBefore('/') + "__" + file

        private fun encodeModelsFile(f: ModelsFile): String {
            val root = JSONObject()
            root.put("active", f.active ?: JSONObject.NULL)
            val arr = JSONArray()
            for (m in f.custom) {
                arr.put(
                    JSONObject()
                        .put("repo", m.repo)
                        .put("commit", m.commit)
                        .put("file", m.spec.url.substringAfterLast('/'))
                        .put("size", m.spec.sizeBytes)
                        .put("sha256", m.spec.sha256)
                        .put("title", m.title)
                        .put("addedByName", true)
                        .put("checked", m.checked)
                        .put("unsupported", m.unsupported),
                )
            }
            root.put("custom", arr)
            return root.toString(2)
        }

        private fun decodeModelsFile(text: String): ModelsFile {
            val root = JSONObject(text)
            val active = if (root.isNull("active")) null else root.optString("active").ifEmpty { null }
            val custom = mutableListOf<LlmModel>()
            val arr = root.optJSONArray("custom") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val repo = o.optString("repo")
                val commit = o.optString("commit")
                val file = o.optString("file")
                val sha = o.optString("sha256")
                val size = o.optLong("size", -1)
                // Только записи с репозиторием Hugging Face, коммитом и контрольной суммой.
                if (!HfNames.isRepoId(repo) || commit.isEmpty() || file.isEmpty() || sha.length != 64 || size <= 0) continue
                if (file.contains('/') || file.contains("..")) continue
                custom += customModel(
                    repo, commit, file, size, sha,
                    title = o.optString("title").ifEmpty { file },
                    checked = o.optBoolean("checked", true),
                    unsupported = o.optBoolean("unsupported", false),
                )
            }
            return ModelsFile(active, custom)
        }
    }
}
