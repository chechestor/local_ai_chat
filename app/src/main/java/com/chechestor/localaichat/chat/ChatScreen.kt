package com.chechestor.localaichat.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chechestor.localaichat.data.MessageEntity
import com.chechestor.localaichat.data.SessionEntity
import com.chechestor.localaichat.llm.EngineState
import com.chechestor.localaichat.voice.VoiceInput
import com.chechestor.localaichat.voice.VoiceState
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.launch

/** Главный экран: диалог и боковая панель со списком диалогов (F004). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onOpenSettings: () -> Unit, vm: ChatViewModel = viewModel()) {
    // При старте приложения панель со списком диалогов открыта (F004 п. 4).
    val drawerState = rememberDrawerState(DrawerValue.Open)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val currentId by vm.currentId.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val engine by vm.engineState.collectAsStateWithLifecycle()
    val input by vm.input.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.messagesToUser.collect { snackbar.showSnackbar(it) } }
    val voice by vm.voiceState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoice() else vm.onMicPermissionDenied()
    }
    val onMic = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startVoice()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionsDrawer(
                sessions = sessions,
                currentId = currentId,
                onNew = { vm.newDialog(); scope.launch { drawerState.close() } },
                onOpen = { vm.open(it); scope.launch { drawerState.close() } },
                onRename = vm::rename,
                onDelete = vm::delete,
                onSettings = { scope.launch { drawerState.close() }; onOpenSettings() },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.semantics { contentDescription = "Список диалогов" },
                        ) { Text("☰", fontSize = 22.sp) }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                EngineBanner(engine)
                val generatingHere = active != null && active?.sessionId == currentId
                MessageList(
                    messages = messages,
                    active = active?.takeIf { it.sessionId == currentId },
                    modifier = Modifier.weight(1f),
                    onCopy = { text ->
                        copyToClipboard(vm.getApplication(), text)
                        scope.launch { snackbar.showSnackbar("Скопировано") }
                    },
                )
                InputBar(
                    text = input,
                    onText = vm::onInput,
                    onClear = vm::clearInput,
                    generating = generatingHere,
                    canSend = engine !is EngineState.Failed,
                    onSend = vm::send,
                    onStop = vm::stop,
                    voice = voice,
                    // Во время ответа модели (в любом диалоге) запись недоступна (F005 п. 3).
                    micEnabled = active == null,
                    onMic = onMic,
                    onVoiceCancel = vm::cancelVoice,
                    onVoiceFinish = vm::finishVoice,
                )
            }
        }
    }
}

@Composable
private fun EngineBanner(engine: EngineState) {
    when (engine) {
        EngineState.Loading, EngineState.NotLoaded -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Загрузка модели…", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is EngineState.Failed -> Text(
            engine.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        is EngineState.Ready -> Unit
    }
}

@Composable
private fun MessageList(
    messages: List<MessageEntity>,
    active: ActiveGeneration?,
    modifier: Modifier,
    onCopy: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, active?.answer?.length, active?.thinking?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1, Int.MAX_VALUE / 2)
    }
    if (messages.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Чем могу помочь?", style = MaterialTheme.typography.headlineSmall)
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages, key = { it.id }) { m ->
            if (m.role == MessageEntity.ROLE_USER) {
                UserBubble(m.text)
            } else {
                val streaming = active?.messageId == m.id
                AssistantMessage(
                    text = if (streaming) active!!.answer else m.text,
                    thinking = if (streaming) active!!.thinking else m.thinking,
                    thinkingInProgress = streaming && active!!.thinkingInProgress,
                    compressing = streaming && active!!.compressing,
                    streaming = streaming,
                    onCopy = onCopy,
                )
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun AssistantMessage(
    text: String,
    thinking: String,
    thinkingInProgress: Boolean,
    compressing: Boolean,
    streaming: Boolean,
    onCopy: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (compressing) {
            // F006: перед ответом длинная история пересказывается в сводку.
            Text("Сжимаю историю…", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            return@Column
        }
        if (thinkingInProgress || thinking.isNotEmpty()) {
            ThinkingBlock(thinking, thinkingInProgress)
        }
        if (text.isEmpty() && streaming) {
            if (!thinkingInProgress) Text("…", style = MaterialTheme.typography.bodyLarge)
        } else {
            Markdown(content = text)
        }
        if (!streaming && text.isNotEmpty()) {
            TextButton(onClick = { onCopy(text) }) { Text("Копировать") }
        }
    }
}

/** Свёрнутый блок «Размышления» над ответом (F007): нажатие разворачивает и сворачивает. */
@Composable
private fun ThinkingBlock(thinking: String, inProgress: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Column(
            Modifier
                .combinedClickable(onClick = { expanded = !expanded })
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                (if (expanded) "▾ " else "▸ ") + if (inProgress) "Размышляю…" else "Размышления",
                style = MaterialTheme.typography.labelLarge,
                fontStyle = FontStyle.Italic,
            )
            if (expanded && thinking.isNotEmpty()) {
                Text(
                    thinking,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    text: String,
    onText: (String) -> Unit,
    onClear: () -> Unit,
    generating: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    voice: VoiceState,
    micEnabled: Boolean,
    onMic: () -> Unit,
    onVoiceCancel: () -> Unit,
    onVoiceFinish: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp)) {
        val live = when (voice) {
            is VoiceState.Recording -> voice.text
            is VoiceState.Finishing -> voice.text
            else -> null
        }
        if (voice == VoiceState.Idle) {
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Сообщение") },
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
            )
        } else {
            // Во время записи поле не редактируется; распознанное на лету — серым после введённого текста.
            val gray = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    buildAnnotatedString {
                        append(text)
                        if (!live.isNullOrBlank()) {
                            if (text.isNotBlank()) append(" ")
                            withStyle(SpanStyle(color = gray)) { append(live) }
                        }
                        if (text.isBlank() && live.isNullOrBlank()) {
                            withStyle(SpanStyle(color = gray)) { append("Говорите…") }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    maxLines = 6,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (voice) {
                VoiceState.Idle -> {
                    TextButton(onClick = onClear, enabled = text.isNotEmpty()) { Text("Очистить поле") }
                    TextButton(onClick = onMic, enabled = micEnabled) { Text("🎤 Записать голос") }
                    Spacer(Modifier.weight(1f))
                    if (generating) {
                        Button(onClick = onStop) { Text("Стоп") }
                    } else {
                        Button(onClick = onSend, enabled = text.isNotBlank() && canSend) { Text("Отправить") }
                    }
                }
                VoiceState.Preparing -> {
                    Text("Подготовка…", Modifier.weight(1f).padding(start = 8.dp))
                    OutlinedButton(onClick = onVoiceCancel) { Text("Отменить") }
                }
                is VoiceState.Recording -> {
                    Text(
                        "● ${formatSec(voice.elapsedSec)} / ${formatSec(VoiceInput.LIMIT_SEC)}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    OutlinedButton(onClick = onVoiceCancel) { Text("Отменить") }
                    Button(onClick = onVoiceFinish) { Text("Завершить") }
                }
                is VoiceState.Finishing -> {
                    Text("Распознаю…", Modifier.weight(1f).padding(start = 8.dp))
                }
            }
        }
    }
}

private fun formatSec(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

@Composable
private fun SessionsDrawer(
    sessions: List<SessionEntity>,
    currentId: Long?,
    onNew: () -> Unit,
    onOpen: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    var menuFor by remember { mutableStateOf<SessionEntity?>(null) }
    var renameFor by remember { mutableStateOf<SessionEntity?>(null) }
    var deleteFor by remember { mutableStateOf<SessionEntity?>(null) }

    ModalDrawerSheet {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            OutlinedButton(onClick = onNew, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("+ Новый диалог") }
            HorizontalDivider()
            if (sessions.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Диалогов пока нет", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(sessions, key = { it.id }) { s ->
                        Box {
                            Text(
                                s.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (s.id == currentId) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow,
                                    )
                                    .combinedClickable(onClick = { onOpen(s.id) }, onLongClick = { menuFor = s })
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                            DropdownMenu(expanded = menuFor?.id == s.id, onDismissRequest = { menuFor = null }) {
                                DropdownMenuItem(text = { Text("Переименовать") }, onClick = { renameFor = s; menuFor = null })
                                DropdownMenuItem(text = { Text("Удалить") }, onClick = { deleteFor = s; menuFor = null })
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text("⚙ Настройки") }
            Spacer(Modifier.height(4.dp))
        }
    }

    renameFor?.let { s ->
        var value by rememberSaveable(s.id) { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Переименовать диалог") },
            text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { onRename(s.id, value); renameFor = null }, enabled = value.isNotBlank()) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Отмена") } },
        )
    }
    deleteFor?.let { s ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Удалить диалог?") },
            text = { Text("«${s.title}» и все его сообщения будут удалены.") },
            confirmButton = { TextButton(onClick = { onDelete(s.id); deleteFor = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Отмена") } },
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Ответ", text))
}
