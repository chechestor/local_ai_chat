package com.chechestor.localaichat.setup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.chechestor.localaichat.LocalAiChatApp
import androidx.work.WorkManager
import com.chechestor.localaichat.models.FolderCheck
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
        val wifi: Boolean,
        val requiredBytes: Long,
        val freeBytes: Long,
        val download: DownloadUi,
        val error: String?,
    ) : SetupUi
    data object Ready : SetupUi
}

/** Порядок запуска (F008): папка → проверка моделей → установка → приложение. */
class SetupViewModel(app: Application) : AndroidViewModel(app) {
    val store: ModelStore = (app as LocalAiChatApp).models
    private val _ui = MutableStateFlow<SetupUi>(SetupUi.Starting)
    val ui: StateFlow<SetupUi> = _ui.asStateFlow()

    private var wifi = false
    private var download = DownloadUi()
    private var error: String? = null
    private var lastMissing: List<ModelStatus> = emptyList()
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
                    FolderCheck.Missing(lastMissing)
                }
            when (result) {
                is FolderCheck.NeedFolder -> _ui.value = SetupUi.NeedFolder(result.wrongFolder)
                is FolderCheck.Missing -> {
                    lastMissing = result.models
                    _ui.value = installState()
                }
                FolderCheck.Ready -> _ui.value = SetupUi.Ready
            }
        }
    }

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        if (!store.onFolderPicked(uri)) {
            _ui.value = SetupUi.NeedFolder(wrongFolder = true)
            return
        }
        check()
    }

    fun startDownload() {
        error = null
        val state = _ui.value as? SetupUi.Install ?: return
        if (!state.wifi || state.freeBytes < state.requiredBytes) return
        ModelDownloadWorker.start(getApplication())
        download = download.copy(active = true, paused = false)
        refreshInstall()
    }

    fun cancelDownload() {
        ModelDownloadWorker.cancel(getApplication())
    }

    /** «Удалить и скачать заново» (настройки, F007). */
    fun deleteAndReinstall() {
        viewModelScope.launch {
            ModelDownloadWorker.cancel(getApplication())
            getApplication<LocalAiChatApp>().chat.stop()
            getApplication<LocalAiChatApp>().llm.unload()
            store.deleteModels()
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
                    paused = false,
                    modelId = p.getString(ModelDownloadWorker.KEY_MODEL_ID) ?: download.modelId,
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

    private fun installState() = SetupUi.Install(
        models = lastMissing,
        wifi = wifi,
        requiredBytes = store.requiredBytes(lastMissing),
        freeBytes = store.freeBytes(),
        download = download,
        error = error,
    )

    private fun refreshInstall() {
        if (_ui.value is SetupUi.Install) _ui.update { installState() }
    }
}
