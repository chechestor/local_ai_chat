package com.chechestor.localaichat.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.chechestor.localaichat.chat.ApplyResult
import com.chechestor.localaichat.llm.EngineState
import com.chechestor.localaichat.models.LlmModel
import com.chechestor.localaichat.models.ModelDownloadWorker
import com.chechestor.localaichat.models.ModelStatus
import com.chechestor.localaichat.models.WifiState
import com.chechestor.localaichat.APP_TITLE
import com.chechestor.localaichat.LocalAiChatApp
import com.chechestor.localaichat.models.ModelCatalog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Состояние языковой модели в настройках (F009 п. 5). */
sealed interface ModelState {
    data object Active : ModelState
    data object Downloaded : ModelState
    data object NotDownloaded : ModelState
    data class Downloading(val percent: Int, val verifying: Boolean) : ModelState
    data object Queued : ModelState
    /** Модель по названию не прошла пробную загрузку (F010 п. 8). */
    data object Unsupported : ModelState
}

data class ModelRow(val status: ModelStatus, val state: ModelState) {
    val model: LlmModel get() = status.llm!!
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val chatApp = app as LocalAiChatApp
    private val store = chatApp.models
    val thinking: StateFlow<Boolean> = chatApp.settings.thinking.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val folderPath: String = store.folderDisplayPath()
    val versionName: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private val statuses = MutableStateFlow<List<ModelStatus>>(emptyList())
    private val works = MutableStateFlow<List<WorkInfo>>(emptyList())
    private val handledFailures = mutableSetOf<java.util.UUID>()

    val activeId: StateFlow<String?> = chatApp.settings.activeModelId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Все модели каталога и добавленные по названию, с состояниями. */
    val rows: StateFlow<List<ModelRow>> = combine(statuses, works, activeId) { st, w, active ->
        st.filter { it.llm != null }.map { ModelRow(it, stateOf(it, w, active)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Идёт ответ или сжатие: «Применить» недоступна (F009 п. 6). */
    val generating: StateFlow<Boolean> = chatApp.chat.active.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val engineState: StateFlow<EngineState> = chatApp.chat.engineState

    val wifi: StateFlow<Boolean> = WifiState.flow(app).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        rescan()
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosByTagFlow(ModelDownloadWorker.TAG).collect { infos ->
                val finishedNow = infos.any { it.state.isFinished } && works.value.any { old ->
                    !old.state.isFinished && infos.any { it.id == old.id && it.state.isFinished }
                }
                works.value = infos
                for (info in infos) {
                    if (info.state == WorkInfo.State.FAILED && handledFailures.add(info.id) && finishedNow) {
                        _messages.tryEmit(info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Загрузка не удалась")
                    }
                }
                if (finishedNow) rescan()
            }
        }
    }

    private fun rescan() {
        viewModelScope.launch { statuses.value = runCatching { store.scanLanguageModels() }.getOrDefault(emptyList()) }
    }

    private fun stateOf(st: ModelStatus, infos: List<WorkInfo>, active: String?): ModelState {
        val tag = ModelDownloadWorker.TAG_MODEL_PREFIX + st.spec.id
        val info = infos.firstOrNull { !it.state.isFinished && tag in it.tags }
        if (info != null) {
            val p = info.progress
            val current = info.state == WorkInfo.State.RUNNING &&
                !p.getBoolean(ModelDownloadWorker.KEY_QUEUED, false) &&
                p.getString(ModelDownloadWorker.KEY_MODEL_ID) == st.spec.id
            if (!current) return ModelState.Queued
            val bytes = p.getLong(ModelDownloadWorker.KEY_MODEL_BYTES, 0)
            val total = p.getLong(ModelDownloadWorker.KEY_MODEL_TOTAL, st.spec.sizeBytes).coerceAtLeast(1)
            return ModelState.Downloading((bytes * 100 / total).toInt(), p.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false))
        }
        return when {
            !st.present -> ModelState.NotDownloaded
            st.llm?.unsupported == true -> ModelState.Unsupported
            st.spec.id == active -> ModelState.Active
            else -> ModelState.Downloaded
        }
    }

    fun setThinking(on: Boolean) {
        viewModelScope.launch { chatApp.settings.setThinking(on) }
    }

    /** «Применить»: прежняя модель выгружается, новая грузится (F009 п. 6). */
    fun apply(row: ModelRow) {
        viewModelScope.launch {
            when (val r = chatApp.chat.apply(row.model.id)) {
                ApplyResult.Ok -> _messages.tryEmit("Модель ${row.model.title} применена")
                ApplyResult.Busy -> _messages.tryEmit("Дождитесь окончания ответа")
                is ApplyResult.Failed -> _messages.tryEmit(r.message)
            }
        }
    }

    /** «Скачать» из настроек: те же правила, что в F008 (Wi-Fi, место, докачка, SHA-256). */
    fun download(row: ModelRow) {
        if (!wifi.value) {
            _messages.tryEmit("Подключитесь к Wi-Fi")
            return
        }
        val need = store.requiredBytes(listOf(row.status))
        val free = store.freeBytes()
        if (free < need) {
            _messages.tryEmit("Не хватает места: освободите ещё ${(need - free) / MB} МБ.")
            return
        }
        ModelDownloadWorker.start(getApplication(), listOf(row.status.spec), ModelDownloadWorker.workNameFor(row.status.spec.id))
    }

    fun cancel(row: ModelRow) {
        ModelDownloadWorker.cancelModel(getApplication(), row.status.spec.id)
        // Задача установки могла качать эту модель вместе с другими.
        rescan()
    }

    /** «Удалить» — только неактивную модель (F009 п. 5). */
    fun delete(row: ModelRow) {
        if (row.model.id == activeId.value) return
        viewModelScope.launch {
            store.deleteModel(row.model)
            rescan()
        }
    }

    private companion object {
        const val MB = 1024 * 1024
    }
}

/** Экран «Настройки» (F007): общий на приложение. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDeleteModels: () -> Unit,
    onAddByName: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val thinking by vm.thinking.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val activeModel = rows.firstOrNull { it.state == ModelState.Active }?.model
    // Пока активная не известна (до сканирования папки), переключатель не блокируем.
    val canThink = activeModel?.supportsThinking ?: true
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteModel by remember { mutableStateOf<ModelRow?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←", fontSize = 22.sp) } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Модель")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Размышления", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (canThink) "Ответы будут дольше и займут больше контекста"
                        else "Активная модель не умеет размышлять",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Сохранённое значение не меняется: снова действует, если вернуть Qwen (F009 п. 8).
                Switch(checked = thinking, onCheckedChange = vm::setThinking, enabled = canThink)
            }

            HorizontalDivider()
            Section("Модели")
            Text("Языковая модель", style = MaterialTheme.typography.titleSmall)
            (engine as? EngineState.Loading)?.let {
                Text("Загружаю ${it.title}…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            for (row in rows) {
                ModelRowView(
                    row = row,
                    generating = generating,
                    loading = engine is EngineState.Loading,
                    wifi = wifi,
                    onApply = { vm.apply(row) },
                    onDownload = { vm.download(row) },
                    onCancel = { vm.cancel(row) },
                    onDelete = { confirmDeleteModel = row },
                )
            }
            OutlinedButton(onClick = onAddByName) { Text("Добавить модель по названию") }
            Text("Распознавание речи", style = MaterialTheme.typography.titleSmall)
            Column {
                Text(ModelCatalog.VOSK.title, style = MaterialTheme.typography.bodyLarge)
                Text("${ModelCatalog.VOSK.fileName} · ${ModelCatalog.VOSK.sizeBytes / (1024 * 1024)} МБ", style = MaterialTheme.typography.bodySmall)
            }
            Text("Папка: ${vm.folderPath}", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Удалить и скачать заново") }

            HorizontalDivider()
            Section("О приложении")
            Text(APP_TITLE, style = MaterialTheme.typography.bodyLarge)
            Text("Версия ${vm.versionName}", style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить модели?") },
            text = { Text("Файлы всех моделей будут удалены из папки ${vm.folderPath}, затем откроется экран установки. Диалоги не пострадают.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteModels() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
    confirmDeleteModel?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDeleteModel = null },
            title = { Text("Удалить ${row.model.title}?") },
            text = { Text("Файл модели будет удалён из папки ${vm.folderPath}. Скачать его можно будет снова.") },
            confirmButton = { TextButton(onClick = { confirmDeleteModel = null; vm.delete(row) }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDeleteModel = null }) { Text("Отмена") } },
        )
    }
}

/** Строка языковой модели: название, размер, состояние, действия (F009 п. 5). */
@Composable
private fun ModelRowView(
    row: ModelRow,
    generating: Boolean,
    loading: Boolean,
    wifi: Boolean,
    onApply: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val m = row.model
    val state = when (val st = row.state) {
        ModelState.Active -> "Активна"
        ModelState.Downloaded -> "Скачана"
        ModelState.NotDownloaded -> "Не скачана"
        ModelState.Queued -> "В очереди"
        ModelState.Unsupported -> "Не поддерживается"
        is ModelState.Downloading -> if (st.verifying) "Проверка…" else "Скачивается ${st.percent}%"
    }
    Column(Modifier.fillMaxWidth()) {
        Text("${m.title} · ${m.spec.sizeBytes / (1024 * 1024)} МБ", style = MaterialTheme.typography.bodyLarge)
        Text("$state · ${m.note}", style = MaterialTheme.typography.bodySmall)
        (row.state as? ModelState.Downloading)?.takeIf { !it.verifying }?.let {
            LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (row.state) {
                ModelState.Active -> Text(
                    "Чтобы удалить, сначала примените другую модель",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModelState.Downloaded -> {
                    TextButton(onClick = onApply, enabled = !generating && !loading) { Text("Применить") }
                    TextButton(onClick = onDelete) { Text("Удалить") }
                    if (generating) Text("Дождитесь окончания ответа", style = MaterialTheme.typography.bodySmall)
                }
                ModelState.NotDownloaded -> {
                    TextButton(onClick = onDownload, enabled = wifi) { Text("Скачать") }
                    if (!wifi) Text("Подключитесь к Wi-Fi", style = MaterialTheme.typography.bodySmall)
                }
                ModelState.Queued, is ModelState.Downloading -> TextButton(onClick = onCancel) { Text("Отменить") }
                ModelState.Unsupported -> TextButton(onClick = onDelete) { Text("Удалить файл") }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
