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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.chechestor.localaichat.APP_TITLE
import com.chechestor.localaichat.LocalAiChatApp
import com.chechestor.localaichat.models.ModelCatalog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val chatApp = app as LocalAiChatApp
    val thinking: StateFlow<Boolean> = chatApp.settings.thinking.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val folderPath: String = chatApp.models.folderDisplayPath()
    val versionName: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull() ?: "?"

    fun setThinking(on: Boolean) {
        viewModelScope.launch { chatApp.settings.setThinking(on) }
    }
}

/** Экран «Настройки» (F007): общий на приложение. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onDeleteModels: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val thinking by vm.thinking.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
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
                    Text("Ответы будут дольше и займут больше контекста", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = thinking, onCheckedChange = vm::setThinking)
            }

            HorizontalDivider()
            Section("Модели")
            for (m in ModelCatalog.ALL) {
                Column {
                    Text(m.title, style = MaterialTheme.typography.bodyLarge)
                    Text("${m.fileName} · ${m.sizeBytes / (1024 * 1024)} МБ", style = MaterialTheme.typography.bodySmall)
                }
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
            text = { Text("Файлы моделей будут удалены из папки ${vm.folderPath} и скачаны заново. Диалоги не пострадают.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteModels() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
