package com.chechestor.localaichat.modeltest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chechestor.localaichat.APP_TITLE
import com.chechestor.localaichat.llm.EngineState
import com.chechestor.localaichat.llm.FinishReason

/** Временный экран проверки движка Qwen (F003). Будет заменён диалогами (F004). */
@Composable
fun ModelTestScreen(onDeleteModels: () -> Unit, vm: ModelTestViewModel = viewModel()) {
    var confirmDelete by remember { mutableStateOf(false) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { vm.loadIfPossible() }
    LaunchedEffect(ui.turns.size, ui.turns.lastOrNull()?.answer?.length) {
        if (ui.turns.isNotEmpty()) listState.scrollToItem(ui.turns.size - 1)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить модели?") },
            text = { Text("Файлы моделей будут удалены из папки Download/LocalAIChat и скачаны заново. Диалоги не пострадают.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteModels() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(APP_TITLE, style = MaterialTheme.typography.titleLarge)
            Text("Проверка модели (F003/F008)", style = MaterialTheme.typography.labelMedium)
            EngineStatus(ui, onRetry = vm::loadIfPossible)

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(ui.turns) { _, t ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Вы: ${t.user}", style = MaterialTheme.typography.bodyMedium)
                        if (t.thinking.isNotEmpty()) {
                            Text("Размышления: ${t.thinking}", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                        }
                        Card(Modifier.fillMaxWidth()) { Text(t.answer.ifEmpty { "…" }, Modifier.padding(8.dp)) }
                    }
                }
            }

            Stats(ui)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Размышления")
                Spacer(Modifier.width(8.dp))
                Switch(checked = ui.thinking, onCheckedChange = vm::onThinking, enabled = !ui.generating)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = vm::clearDialog, enabled = !ui.generating) { Text("Новый диалог") }
            }
            // Временно до F007 (там эта кнопка будет в настройках).
            TextButton(onClick = { confirmDelete = true }, enabled = !ui.generating) { Text("Удалить и скачать заново") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ui.input,
                    onValueChange = vm::onInput,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение") },
                    maxLines = 4,
                )
                if (ui.generating) {
                    Button(onClick = vm::stop) { Text("Стоп") }
                } else {
                    Button(
                        onClick = vm::send,
                        enabled = ui.engine is EngineState.Ready && ui.input.isNotBlank(),
                    ) { Text("Отправить") }
                }
            }
        }
    }
}

@Composable
private fun EngineStatus(ui: ModelTestUi, onRetry: () -> Unit) {
    when (val s = ui.engine) {
        EngineState.NotLoaded -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Модель не загружена")
            OutlinedButton(onClick = onRetry) { Text("Загрузить") }
        }
        EngineState.Loading -> Column {
            Text("Загрузка модели…")
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is EngineState.Ready -> Text(
            "Модель загружена за ${s.loadMillis} мс, контекст ${s.contextSize} токенов",
            style = MaterialTheme.typography.bodySmall,
        )
        is EngineState.Failed -> Column {
            Text(s.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry) { Text("Повторить") }
        }
    }
}

@Composable
private fun Stats(ui: ModelTestUi) {
    val st = ui.lastStats
    val reason = when (ui.lastReason) {
        FinishReason.DONE, null -> null
        FinishReason.STOPPED -> "остановлено"
        FinishReason.LENGTH -> "достигнут лимит длины"
        FinishReason.PROMPT_TOO_LONG -> "история не помещается в контекст — начните новый диалог"
        FinishReason.ERROR -> "ошибка генерации"
    }
    val parts = buildList {
        add("ОЗУ: ${ui.memory.rssMb} МБ, пик ${ui.memory.peakRssMb} МБ")
        if (st != null) {
            add("промпт ${st.promptTokens} ток.")
            if (st.timeToFirstTokenMillis >= 0) add("1-й токен ${st.timeToFirstTokenMillis} мс")
            add("%.1f ток/с".format(st.tokensPerSecond))
        }
        if (reason != null) add(reason)
    }
    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
}
