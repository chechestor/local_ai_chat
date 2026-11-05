package com.chechestor.localaichat.setup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.chechestor.localaichat.LocalAiChatApp
import com.chechestor.localaichat.models.FolderCheck
import com.chechestor.localaichat.models.LlmModel
import com.chechestor.localaichat.models.ModelCatalog
import com.chechestor.localaichat.models.ModelDownloadWorker
import com.chechestor.localaichat.models.ModelStatus
import com.chechestor.localaichat.models.ModelStore
import com.chechestor.localaichat.models.WifiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Ход загрузки моделей (из WorkManager). */
data class DownloadUi(
    val active: Boolean = false,
    /** Задача ждёт Wi-Fi или повторной попытки (пауза). */
    val paused: Boolean = false,
    val modelId: String? = null,
    /** Номер текущей модели (с 0) и сколько всего в задаче: «N из M». */
    val index: Int = 0,
    val count: Int = 0,
    val bytes: Long = 0,
    val total: Long = 0,
    val verifying: Boolean = false,
)

sealed interface SetupUi {
    data object Starting : SetupUi
    data class NeedFolder(val wrongFolder: Boolean) : SetupUi
    data class Checking(val progress: Float) : SetupUi
    data class Install(
        val models: List<ModelStatus>,
        val vosk: ModelStatus,
        val selected: Set<String>,
        val wifi: Boolean,
        val requiredBytes: Long,
        val freeBytes: Long,
        val download: DownloadUi,
        val error: String?,
    ) : SetupUi

    /** Скачано несколько моделей, активная не выбрана (F009 п. 3). */
    data class Choose(val models: List<LlmModel>) : SetupUi

    /** Прошлый запуск с моделью завершился аварийно (F009 п. 9). */
    data class Crashed(val title: String) : SetupUi
    data object Ready : SetupUi
}

/** Порядок запуска (F008, F009): папка → проверка моделей → установка → выбор модели → приложение. */
class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val chatApp = app as LocalAiChatApp
    val store: ModelStore = chatApp.models
    private val settings = chatApp.settings
    private val _ui = MutableStateFlow<SetupUi>(SetupUi.Starting)
    val ui: StateFlow<SetupUi> = _ui.asStateFlow()

    private var wifi = false
    private var download = DownloadUi()
    private var error: String? = null
    private var lastMissing: FolderCheck.Missing? = null
    private var selected: Set<String> = emptySet()
    private var downloaded: List<LlmModel> = emptyList()
    private var checkJob: Job? = null

    init {
        viewModelScope.launch {
            WifiState.flow(app).collect { wifi = it; refreshInstall() }
        }
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.WORK_NAME).collect { infos ->
                onWork(infos.lastOrNull())
            }
        }
        check()
    }

    fun check() {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _ui.value = SetupUi.Checking(0f)
            val result = runCatching { store.check { p -> _ui.value = SetupUi.Checking(p) } }
                .getOrElse { e ->
                    error = "Ошибка проверки моделей: ${e.message}"
                    lastMissing ?: FolderCheck.Missing(
                        ModelCatalog.LANGUAGE.map { ModelStatus(it.spec, false, 0, it) },
                        ModelStatus(ModelCatalog.VOSK, false, 0),
                    )
                }
            when (result) {
                is FolderCheck.NeedFolder -> _ui.value = SetupUi.NeedFolder(result.wrongFolder)
                is FolderCheck.Missing -> {
                    lastMissing = result
                    // Уже скачанные модели не отмечаются: они не качаются повторно.
                    selected = selected.filter { id -> result.models.any { it.spec.id == id && !it.present } }.toSet()
                    _ui.value = installState()
                }
                is FolderCheck.Ready -> onReady(result.downloaded)
            }
        }
    }

    /** Все нужные модели на месте: выбираем активную (F009 п. 3) и проверяем прошлый запуск (п. 9). */
    private suspend fun onReady(models: List<LlmModel>) {
        downloaded = models
        val stored = settings.activeModelIdNow() ?: store.readModelsFile().active
        val active = ModelCatalog.resolveActive(stored, models.map { it.id })
        if (active == null) {
            _ui.value = SetupUi.Choose(models)
            return
        }
        if (active != settings.activeModelIdNow()) settings.setActiveModelId(active)
        if (active != stored) store.writeActive(active)
        val busy = settings.busyModelIdNow()
        if (busy != null) {
            val title = models.firstOrNull { it.id == busy }?.title ?: busy
            _ui.value = SetupUi.Crashed(title)
            return
        }
        _ui.value = SetupUi.Ready
    }

    /** Экран «Выберите модель»: модель становится активной, открываются диалоги. */
    fun choose(id: String) {
        viewModelScope.launch {
            settings.setBusyModelId(null)
            settings.setActiveModelId(id)
            store.writeActive(id)
            _ui.value = SetupUi.Ready
        }
    }

    /** Аварийное завершение: «Попробовать снова». */
    fun retryAfterCrash() {
        viewModelScope.launch {
            settings.setBusyModelId(null)
            _ui.value = SetupUi.Ready
        }
    }

    /** Аварийное завершение: «Выбрать другую модель». */
    fun chooseAfterCrash() {
        _ui.value = SetupUi.Choose(downloaded)
    }

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        if (!store.onFolderPicked(uri)) {
            _ui.value = SetupUi.NeedFolder(wrongFolder = true)
            return
        }
        check()
    }

    fun toggle(id: String, on: Boolean) {
        selected = if (on) selected + id else selected - id
        refreshInstall()
    }

    fun startDownload() {
        error = null
        val state = _ui.value as? SetupUi.Install ?: return
        if (!canDownload(state)) return
        val specs = toDownload(state).map { it.spec }
        if (specs.isEmpty()) {
            check()
            return
        }
        ModelDownloadWorker.start(getApplication(), specs)
        download = download.copy(active = true, paused = false)
        refreshInstall()
    }

    fun cancelDownload() {
        ModelDownloadWorker.cancel(getApplication())
    }

    /** «Удалить и скачать заново» (настройки, F007, F009 п. 5). */
    fun deleteAndReinstall() {
        viewModelScope.launch {
            ModelDownloadWorker.cancelAll(getApplication())
            chatApp.chat.stop()
            chatApp.chat.unloadModel()
            store.deleteModels()
            settings.setActiveModelId(null)
            settings.setBusyModelId(null)
            selected = emptySet()
            check()
        }
    }

    private fun onWork(info: WorkInfo?) {
        if (info == null) {
            download = DownloadUi()
            refreshInstall()
            return
        }
        when (info.state) {
            WorkInfo.State.RUNNING -> {
                val p = info.progress
                download = DownloadUi(
                    active = true,
                    paused = p.getBoolean(ModelDownloadWorker.KEY_QUEUED, false),
                    modelId = p.getString(ModelDownloadWorker.KEY_MODEL_ID) ?: download.modelId,
                    index = p.getInt(ModelDownloadWorker.KEY_MODEL_INDEX, download.index),
                    count = p.getInt(ModelDownloadWorker.KEY_MODEL_COUNT, download.count),
                    bytes = p.getLong(ModelDownloadWorker.KEY_MODEL_BYTES, download.bytes),
                    total = p.getLong(ModelDownloadWorker.KEY_MODEL_TOTAL, download.total),
                    verifying = p.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false),
                )
            }
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> download = download.copy(active = true, paused = true)
            WorkInfo.State.SUCCEEDED -> {
                download = DownloadUi()
                if (_ui.value is SetupUi.Install) check()
            }
            WorkInfo.State.FAILED -> {
                download = DownloadUi()
                error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Загрузка не удалась"
                if (_ui.value is SetupUi.Install) check()
            }
            WorkInfo.State.CANCELLED -> download = DownloadUi()
        }
        refreshInstall()
    }

    /** Что качать: отмеченные модели и Vosk, если его нет. */
    private fun toDownload(s: SetupUi.Install): List<ModelStatus> =
        s.models.filter { it.spec.id in s.selected && !it.present } + listOfNotNull(s.vosk.takeIf { !it.present })

    private fun canDownload(s: SetupUi.Install): Boolean = canStart(s)

    private fun installState(): SetupUi.Install {
        val missing = lastMissing!!
        val base = SetupUi.Install(
            models = missing.models,
            vosk = missing.vosk,
            selected = selected,
            wifi = wifi,
            requiredBytes = 0,
            freeBytes = store.freeBytes(),
            download = download,
            error = error,
        )
        return base.copy(requiredBytes = store.requiredBytes(toDownload(base)))
    }

    private fun refreshInstall() {
        if (_ui.value is SetupUi.Install) _ui.update { installState() }
    }

    companion object {
        /** Кнопка «Скачать и установить» активна (F009 п. 2). */
        fun canStart(s: SetupUi.Install): Boolean =
            s.wifi && s.freeBytes >= s.requiredBytes && (s.selected.isNotEmpty() || s.models.any { it.present })
    }
}
