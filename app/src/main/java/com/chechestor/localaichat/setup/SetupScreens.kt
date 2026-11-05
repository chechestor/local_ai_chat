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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chechestor.localaichat.models.ModelCatalog

/** Корень приложения: пока модели не готовы, доступны только экраны установки (F008). */
@Composable
fun SetupGate(vm: SetupViewModel = viewModel(), content: @Composable () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    when (val s = ui) {
        SetupUi.Starting -> Centered { CircularProgressIndicator() }
        is SetupUi.NeedFolder -> FolderScreen(s.wrongFolder, vm)
        is SetupUi.Checking -> Centered {
            Text("Проверка моделей…", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Text("${(s.progress * 100).toInt()}%", Modifier.padding(top = 4.dp))
        }
        is SetupUi.Install -> InstallScreen(s, vm)
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
private fun InstallScreen(s: SetupUi.Install, vm: SetupViewModel) {
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
    val total = ModelCatalog.ALL.sumOf { it.sizeBytes }

    Centered {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Установка моделей", style = MaterialTheme.typography.headlineSmall)
            Text("Приложение работает без интернета, но сначала нужно один раз скачать модели (только по Wi-Fi).")
            for (m in s.models) {
                val d = s.download
                val line = when {
                    m.present -> "установлена"
                    d.active && d.modelId == m.spec.id && d.verifying -> "проверка…"
                    d.active && d.modelId == m.spec.id -> "${percent(d.bytes, d.total)}% · ${mb(d.bytes)} из ${mb(m.spec.sizeBytes)} МБ"
                    m.partialBytes > 0 -> "скачано ${mb(m.partialBytes)} из ${mb(m.spec.sizeBytes)} МБ"
                    else -> "${mb(m.spec.sizeBytes)} МБ"
                }
                Column {
                    Text(m.spec.title, style = MaterialTheme.typography.bodyLarge)
                    Text(line, style = MaterialTheme.typography.bodySmall)
                    if (d.active && d.modelId == m.spec.id && !d.verifying && d.total > 0) {
                        LinearProgressIndicator(progress = { d.bytes.toFloat() / d.total }, modifier = Modifier.fillMaxWidth())
                    }
                }
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

            if (s.download.active) {
                if (s.download.paused) Text("Загрузка на паузе: ждём Wi-Fi…")
                OutlinedButton(onClick = vm::cancelDownload) { Text("Отменить") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload, enabled = s.wifi && enoughSpace) {
                        Text(if (s.error != null) "Повторить" else "Скачать и установить")
                    }
                }
            }
        }
    }
}

private fun mb(bytes: Long) = bytes / (1024 * 1024)
private fun percent(done: Long, total: Long) = if (total > 0) done * 100 / total else 0
