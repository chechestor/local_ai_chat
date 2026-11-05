package com.chechestor.localaichat.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chechestor.localaichat.models.LlmModel
import com.chechestor.localaichat.models.ModelStatus

/**
 * Корень приложения: пока модели не готовы, доступны только экраны установки (F008, F009).
 * [onOtherModel] — «Другая модель…» на экране установки (F010); null — ссылки нет.
 */
@Composable
fun SetupGate(
    vm: SetupViewModel = viewModel(),
    onOtherModel: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    when (val s = ui) {
        SetupUi.Starting -> Centered { CircularProgressIndicator() }
        is SetupUi.NeedFolder -> FolderScreen(s.wrongFolder, vm)
        is SetupUi.Checking -> Centered {
            Text("Проверка моделей…", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Text("${(s.progress * 100).toInt()}%", Modifier.padding(top = 4.dp))
        }
        is SetupUi.Install -> InstallScreen(s, vm, onOtherModel)
        is SetupUi.Choose -> ChooseScreen(s.models, vm)
        is SetupUi.Crashed -> CrashedScreen(s.title, vm)
        SetupUi.Ready -> content()
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
        }
    }
}

@Composable
private fun FolderScreen(wrongFolder: Boolean, vm: SetupViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> vm.onFolderPicked(uri) }
    Centered {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Выберите папку для моделей", style = MaterialTheme.typography.headlineSmall)
            if (wrongFolder) {
                Text(
                    "Нужна папка Download/LocalAIChat, а доступа к ней нет или выбрана другая папка.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text("Модели хранятся в общей папке телефона и не удаляются вместе с приложением. После переустановки достаточно снова выбрать эту папку.")
            Text("В открывшемся окне: Download → LocalAIChat (если папки нет, создайте её) → «Использовать эту папку» → «Разрешить».")
            Button(onClick = { picker.launch(vm.store.pickFolderInitialUri()) }) { Text("Выбрать папку") }
        }
    }
}

@Composable
private fun InstallScreen(s: SetupUi.Install, vm: SetupViewModel, onOtherModel: (() -> Unit)?) {
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // При отказе загрузка всё равно идёт, просто без видимого уведомления.
        vm.startDownload()
    }
    val onDownload = {
        val needAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needAsk) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startDownload()
    }
    val enoughSpace = s.freeBytes >= s.requiredBytes
    val d = s.download
    // Общий объём — по отмеченным моделям (и Vosk, если его нет), F009 п. 2.
    val total = s.models.filter { it.spec.id in s.selected && !it.present }.sumOf { it.spec.sizeBytes } +
        (if (s.vosk.present) 0L else s.vosk.spec.sizeBytes)

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Установка моделей", style = MaterialTheme.typography.headlineSmall)
            Text("Приложение работает без интернета, но сначала нужно один раз скачать модели (только по Wi-Fi). Отметьте языковые модели, которые хотите скачать.")
            for (m in s.models) {
                val llm = m.llm ?: continue
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = m.present || m.spec.id in s.selected,
                        onCheckedChange = { vm.toggle(m.spec.id, it) },
                        enabled = !m.present && !d.active,
                    )
                    Column(Modifier.weight(1f)) {
                        Text("${llm.title} · ${mb(m.spec.sizeBytes)} МБ", style = MaterialTheme.typography.bodyLarge)
                        Text(llm.note, style = MaterialTheme.typography.bodySmall)
                        DownloadLine(m, d)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // Vosk обязателен: флажок не снимается (F009 п. 2).
                Checkbox(checked = true, onCheckedChange = null, enabled = false)
                Column(Modifier.weight(1f)) {
                    Text("${s.vosk.spec.title} · ${mb(s.vosk.spec.sizeBytes)} МБ", style = MaterialTheme.typography.bodyLarge)
                    DownloadLine(s.vosk, d)
                }
            }
            if (onOtherModel != null && !d.active) {
                TextButton(onClick = onOtherModel) { Text("Другая модель…") }
            }
            Text("Всего: ${mb(total)} МБ. Папка: ${vm.store.folderDisplayPath()}", style = MaterialTheme.typography.bodySmall)

            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!s.wifi) Text("Подключитесь к Wi-Fi", color = MaterialTheme.colorScheme.error)
            if (!enoughSpace) {
                Text(
                    "Не хватает места: освободите ещё ${mb(s.requiredBytes - s.freeBytes)} МБ.",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (d.active) {
                if (d.count > 0) Text("Модель ${d.index + 1} из ${d.count}")
                if (d.paused) Text("Загрузка на паузе: ждём Wi-Fi…")
                OutlinedButton(onClick = vm::cancelDownload) { Text("Отменить") }
            } else {
                Button(onClick = onDownload, enabled = SetupViewModel.canStart(s)) {
                    Text(if (s.error != null) "Повторить" else "Скачать и установить")
                }
            }
        }
    }
}

@Composable
private fun DownloadLine(m: ModelStatus, d: DownloadUi) {
    val current = d.active && d.modelId == m.spec.id && !d.paused
    val line = when {
        m.present -> "Скачана"
        current && d.verifying -> "проверка…"
        current -> "${percent(d.bytes, d.total)}% · ${mb(d.bytes)} из ${mb(m.spec.sizeBytes)} МБ"
        m.partialBytes > 0 -> "скачано ${mb(m.partialBytes)} из ${mb(m.spec.sizeBytes)} МБ"
        else -> null
    }
    if (line != null) Text(line, style = MaterialTheme.typography.bodySmall)
    if (current && !d.verifying && d.total > 0) {
        LinearProgressIndicator(progress = { d.bytes.toFloat() / d.total }, modifier = Modifier.fillMaxWidth())
    }
}

/** Экран «Выберите модель» (F009 п. 3). */
@Composable
private fun ChooseScreen(models: List<LlmModel>, vm: SetupViewModel) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Выберите модель", style = MaterialTheme.typography.headlineSmall)
            Text("Эта модель будет отвечать в диалогах. Сменить её можно в настройках.")
            for (m in models) {
                OutlinedCard(onClick = { vm.choose(m.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${m.title} · ${mb(m.spec.sizeBytes)} МБ", style = MaterialTheme.typography.bodyLarge)
                        Text(m.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Прошлый запуск завершился аварийно (F009 п. 9). */
@Composable
private fun CrashedScreen(title: String, vm: SetupViewModel) {
    Centered {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Прошлый запуск с моделью $title завершился аварийно — возможно, не хватило памяти",
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = vm::chooseAfterCrash) { Text("Выбрать другую модель") }
            OutlinedButton(onClick = vm::retryAfterCrash) { Text("Попробовать снова") }
        }
    }
}

private fun mb(bytes: Long) = bytes / (1024 * 1024)
private fun percent(done: Long, total: Long) = if (total > 0) done * 100 / total else 0
