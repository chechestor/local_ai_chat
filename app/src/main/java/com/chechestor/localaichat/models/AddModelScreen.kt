package com.chechestor.localaichat.models

import android.app.ActivityManager
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.chechestor.localaichat.LocalAiChatApp
import com.chechestor.localaichat.chat.ProbeResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/** Шаги экрана «Модель с Hugging Face» (F010). */
sealed interface AddUi {
    data object Input : AddUi
    data class Busy(val text: String) : AddUi
    data class Results(val query: String, val hits: List<HfRepoHit>) : AddUi
    data class PickFile(val repo: String, val commit: String, val files: List<HfFile>) : AddUi
    data class Confirm(val repo: String, val commit: String, val file: HfFile, val heavy: Boolean) : AddUi
    data class Error(val message: String, val canRetry: Boolean) : AddUi
    data class Downloading(val title: String, val percent: Int, val verifying: Boolean, val waiting: Boolean) : AddUi
    data class Done(val message: String) : AddUi
    data class Unsupported(val message: String, val model: LlmModel) : AddUi
}

class AddModelViewModel(app: Application) : AndroidViewModel(app) {
    private val chatApp = app as LocalAiChatApp
    private val store = chatApp.models

    private val _ui = MutableStateFlow<AddUi>(AddUi.Input)
    val ui: StateFlow<AddUi> = _ui.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    val wifi: StateFlow<Boolean> = WifiState.flow(app).stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var retry: (() -> Unit)? = null
    private var job: Job? = null
    private var downloadingId: String? = null

    fun onInput(text: String) {
        _input.value = text
    }

    /** «Найти» (F010 п. 3). */
    fun find() {
        when (val q = HfQuery.parse(_input.value)) {
            HfQuery.Empty -> Unit
            is HfQuery.Repo -> openRepo(q.repo)
            is HfQuery.Search -> search(q.text)
        }
    }

    fun retry() {
        retry?.invoke()
    }

    fun back() {
        job?.cancel()
        _ui.value = AddUi.Input
    }

    private fun launchStep(action: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch { action() }
    }

    private fun search(text: String) {
        retry = { search(text) }
        launchStep {
            _ui.value = AddUi.Busy("Ищу на Hugging Face…")
            _ui.value = when (val r = HfApi.search(text)) {
                HfSearchResult.NetworkError -> AddUi.Error(NO_NETWORK, canRetry = true)
                is HfSearchResult.Ok ->
                    if (r.hits.isEmpty()) AddUi.Error("На Hugging Face не найдено моделей GGUF по запросу «$text»", false)
                    else AddUi.Results(text, r.hits)
            }
        }
    }

    /** Сведения о репозитории и выбор файла (F010 п. 4–5). */
    fun openRepo(repo: String) {
        retry = { openRepo(repo) }
        launchStep {
            _ui.value = AddUi.Busy("Получаю сведения о «$repo»…")
            when (val r = HfApi.repo(repo)) {
                HfRepoResult.NotFound -> _ui.value = AddUi.Error("Модель «$repo» не найдена на Hugging Face", false)
                HfRepoResult.Gated -> _ui.value =
                    AddUi.Error("Модель «$repo» требует входа на Hugging Face — такие модели не поддерживаются", false)
                HfRepoResult.NetworkError -> _ui.value = AddUi.Error(NO_NETWORK, canRetry = true)
                is HfRepoResult.Ok -> when (val c = HfApi.chooseFile(r.files)) {
                    FileChoice.None -> _ui.value = AddUi.Error("В репозитории «${r.repo}» нет подходящего файла GGUF", false)
                    is FileChoice.PickOne -> _ui.value = AddUi.PickFile(r.repo, r.commit, c.files)
                    is FileChoice.Suggested -> checkFile(r.repo, r.commit, c.file)
                }
            }
        }
    }

    fun pickFile(repo: String, commit: String, file: HfFile) {
        launchStep { checkFile(repo, commit, file) }
    }

    /** Проверки до скачивания (F010 п. 6). */
    private suspend fun checkFile(repo: String, commit: String, file: HfFile) {
        val ram = totalMem()
        if (!ModelCatalog.fitsMemory(file.size, ram)) {
            _ui.value = AddUi.Error("Модель слишком большая для этого телефона (${gb(file.size)} ГБ при ОЗУ ${gb(ram)} ГБ)", false)
            return
        }
        val sameSha = store.scanLanguageModels().any { it.present && it.spec.sha256.equals(file.sha256, ignoreCase = true) }
        if (sameSha) {
            _ui.value = AddUi.Error("Эта модель уже скачана", false)
            return
        }
        _ui.value = AddUi.Confirm(repo, commit, file, heavy = file.size > ModelCatalog.HEAVY_BYTES)
    }

    /** «Скачать» / «Всё равно скачать» (F010 п. 7). */
    fun download(c: AddUi.Confirm) {
        val model = ModelStore.customModel(
            c.repo, c.commit, c.file.path, c.file.size, c.file.sha256!!.lowercase(), ModelStore.customTitle(c.repo),
        )
        if (!wifi.value) {
            _ui.value = AddUi.Error("Подключитесь к Wi-Fi", false)
            return
        }
        val status = ModelStatus(model.spec, present = false, partialBytes = 0)
        val need = store.requiredBytes(listOf(status))
        val free = store.freeBytes()
        if (free < need) {
            _ui.value = AddUi.Error("Не хватает места: освободите ещё ${(need - free) / MB} МБ.", false)
            return
        }
        retry = { download(c) }
        downloadingId = model.id
        ModelDownloadWorker.startCustom(getApplication(), model)
        _ui.value = AddUi.Downloading(model.title, 0, verifying = false, waiting = true)
        launchStep {
            WorkManager.getInstance(getApplication())
                .getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.workNameFor(model.id))
                .collect { infos -> onWork(model, infos.lastOrNull()) }
        }
    }

    private suspend fun onWork(model: LlmModel, info: WorkInfo?) {
        if (_ui.value !is AddUi.Downloading) return
        when (info?.state) {
            null, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                _ui.value = AddUi.Downloading(model.title, 0, verifying = false, waiting = true)
            WorkInfo.State.RUNNING -> {
                val p = info.progress
                val bytes = p.getLong(ModelDownloadWorker.KEY_MODEL_BYTES, 0)
                _ui.value = AddUi.Downloading(
                    model.title,
                    (bytes * 100 / model.spec.sizeBytes.coerceAtLeast(1)).toInt(),
                    verifying = p.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false),
                    waiting = p.getBoolean(ModelDownloadWorker.KEY_QUEUED, false),
                )
            }
            WorkInfo.State.FAILED -> _ui.value =
                AddUi.Error(info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Загрузка не удалась", true)
            WorkInfo.State.CANCELLED -> _ui.value = AddUi.Error("Загрузка отменена", true)
            WorkInfo.State.SUCCEEDED -> probe(model)
        }
    }

    /** Пробная загрузка в движок (F010 п. 8). */
    private suspend fun probe(model: LlmModel) {
        _ui.value = AddUi.Busy("Проверяю модель ${model.title}…")
        _ui.value = when (val r = chatApp.chat.probe(model)) {
            ProbeResult.Supported -> {
                store.markCustom(model.id, supported = true)
                AddUi.Done("Модель ${model.title} скачана и проверена. Применить её можно в настройках, в разделе «Модели».")
            }
            ProbeResult.Deferred ->
                AddUi.Done("Модель ${model.title} скачана. Она будет проверена при первом нажатии «Применить».")
            is ProbeResult.Unsupported -> {
                store.markCustom(model.id, supported = false)
                AddUi.Unsupported("Модель ${model.repo} не поддерживается приложением: ${r.reason}", model)
            }
            is ProbeResult.Failed -> AddUi.Error(r.message, false)
        }
    }

    fun cancelDownload() {
        val ui = _ui.value as? AddUi.Downloading ?: return
        job?.cancel()
        downloadingId?.let { ModelDownloadWorker.cancelModel(getApplication(), it) }
        _ui.value = AddUi.Error("Загрузка модели ${ui.title} отменена", true)
    }

    /** «Удалить файл» у неподдерживаемой модели. */
    fun deleteUnsupported(model: LlmModel) {
        launchStep {
            store.deleteModel(model)
            _ui.value = AddUi.Done("Файл модели ${model.title} удалён")
        }
    }

    private fun totalMem(): Long = runCatching {
        val info = ActivityManager.MemoryInfo()
        getApplication<Application>().getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(0L)

    companion object {
        const val NO_NETWORK = "Не удалось связаться с Hugging Face. Повторите позже"
        private const val MB = 1024 * 1024

        fun gb(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024 * 1024)).replace('.', ',')
    }
}

/** Экран «Модель с Hugging Face» (F010 п. 2): вход из настроек и с экрана установки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddModelScreen(onBack: () -> Unit, vm: AddModelViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Модель с Hugging Face") },
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
            val editable = ui is AddUi.Input || ui is AddUi.Error || ui is AddUi.Results
            OutlinedTextField(
                value = input,
                onValueChange = vm::onInput,
                modifier = Modifier.fillMaxWidth(),
                enabled = editable,
                singleLine = true,
                placeholder = { Text("Например: unsloth/gemma-3-1b-it-GGUF или gemma 3 1b") },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::find, enabled = editable && wifi && input.isNotBlank()) { Text("Найти") }
                if (!wifi) Text("Подключитесь к Wi-Fi", color = MaterialTheme.colorScheme.error)
            }
            Text(
                "Подходят открытые модели в формате GGUF. Модели, для которых нужен вход на Hugging Face, не поддерживаются.",
                style = MaterialTheme.typography.bodySmall,
            )
            AddStep(ui, vm, onBack)
        }
    }
}

@Composable
private fun AddStep(ui: AddUi, vm: AddModelViewModel, onBack: () -> Unit) {
    when (ui) {
        AddUi.Input -> Unit
        is AddUi.Busy -> {
            Text(ui.text)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is AddUi.Results -> {
            Text("Найдено по запросу «${ui.query}»:", style = MaterialTheme.typography.titleSmall)
            for (h in ui.hits) {
                OutlinedCard(onClick = { vm.openRepo(h.repo) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(h.repo, style = MaterialTheme.typography.bodyLarge)
                        Text("Загрузок: ${h.downloads}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        is AddUi.PickFile -> {
            Text("Выберите файл в «${ui.repo}»:", style = MaterialTheme.typography.titleSmall)
            for (f in ui.files) {
                OutlinedCard(onClick = { vm.pickFile(ui.repo, ui.commit, f) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(f.path, style = MaterialTheme.typography.bodyLarge)
                        Text("${f.size / (1024 * 1024)} МБ", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        is AddUi.Confirm -> {
            Text("Репозиторий: ${ui.repo}")
            Text("Файл: ${ui.file.path}")
            Text("Размер: ${ui.file.size / (1024 * 1024)} МБ")
            if (ui.heavy) {
                Text("Модель большая: ответы будут медленными, памяти может не хватить", color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.download(ui) }) { Text(if (ui.heavy) "Всё равно скачать" else "Скачать") }
                OutlinedButton(onClick = vm::back) { Text("Отмена") }
            }
        }
        is AddUi.Error -> {
            Text(ui.message, color = MaterialTheme.colorScheme.error)
            if (ui.canRetry) OutlinedButton(onClick = vm::retry) { Text("Повторить") }
        }
        is AddUi.Downloading -> {
            Text(
                when {
                    ui.verifying -> "Проверяю файл ${ui.title}…"
                    ui.waiting -> "${ui.title}: ждём Wi-Fi или окончания другой загрузки…"
                    else -> "Скачиваю ${ui.title}: ${ui.percent}%"
                },
            )
            if (ui.verifying || ui.waiting) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { ui.percent / 100f }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = vm::cancelDownload) { Text("Отменить") }
        }
        is AddUi.Done -> {
            Text(ui.message)
            Button(onClick = onBack) { Text("Готово") }
        }
        is AddUi.Unsupported -> {
            Text(ui.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = { vm.deleteUnsupported(ui.model) }) { Text("Удалить файл") }
        }
    }
}
