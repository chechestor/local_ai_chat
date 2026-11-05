package com.chechestor.localaichat.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import com.chechestor.localaichat.R
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.max
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
    // Отправка ведёт ленту в конец; «Стоп» показывает уже полученный текст сразу (F004.01).
    var scrollSignal by remember { mutableIntStateOf(0) }
    var snapMessageId by remember { mutableStateOf<Long?>(null) }
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
                    sessionId = currentId,
                    messages = messages,
                    active = active?.takeIf { it.sessionId == currentId },
                    scrollSignal = scrollSignal,
                    snapMessageId = snapMessageId,
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
                    // Пока модель грузится (в том числе после «Применить»), отправить нельзя (F009 п. 6).
                    canSend = engine !is EngineState.Failed && engine !is EngineState.Loading,
                    onSend = { scrollSignal++; vm.send() },
                    onStop = { snapMessageId = active?.messageId; vm.stop() },
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
        is EngineState.Loading, EngineState.NotLoaded -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                if (engine is EngineState.Loading) "Загружаю ${engine.title}…" else "Загрузка модели…",
                style = MaterialTheme.typography.bodySmall,
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageList(
    sessionId: Long?,
    messages: List<MessageEntity>,
    active: ActiveGeneration?,
    /** Растёт при каждой отправке: лента едет в конец и следит за ним (F004.01 п. 12). */
    scrollSignal: Int,
    /** Ответ, остановленный «Стоп»: всё полученное показывается сразу (п. 7). */
    snapMessageId: Long?,
    modifier: Modifier,
    onCopy: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val nearEndPx = with(density) { NEAR_END.roundToPx() }
    val minStepPx = with(density) { 2.dp.toPx() }

    // Режим «следить за концом» (п. 9) и жест пользователя.
    var follow by remember { mutableStateOf(true) }
    var userDragging by remember { mutableStateOf(false) }

    // Сколько символов ответа уже показано — только для ответов, которые выводятся на этом экране (п. 7).
    val reveal = remember { mutableStateMapOf<Long, Int>() }
    val currentActive by rememberUpdatedState(active)
    val currentMessages by rememberUpdatedState(messages)
    val currentSnap by rememberUpdatedState(snapMessageId)

    fun fullLength(id: Long): Int? =
        currentActive?.takeIf { it.messageId == id }?.answer?.length
            ?: currentMessages.firstOrNull { it.id == id }?.text?.length

    LaunchedEffect(active?.messageId) {
        active?.messageId?.let { if (it !in reveal) reveal[it] = 0 }
    }

    // Показ ответа раз в кадр ровным темпом; без работы — ждём нового текста.
    LaunchedEffect(Unit) {
        val progress = HashMap<Long, Double>()
        while (true) {
            snapshotFlow { reveal.any { (id, n) -> n < (fullLength(id) ?: 0) } }.first { it }
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1e9
                last = now
                var pending = false
                for ((id, n) in reveal.toList()) {
                    val full = fullLength(id) ?: continue
                    if (n >= full) continue
                    if (currentSnap == id) {
                        reveal[id] = full
                        continue
                    }
                    val p = StreamingText.step(max(progress[id] ?: 0.0, n.toDouble()), full, dt)
                    progress[id] = p
                    val shown = p.toInt().coerceAtMost(full)
                    reveal[id] = shown
                    if (shown < full) pending = true
                }
                if (!pending) break
            }
        }
    }

    /** Насколько низ последнего сообщения ниже низа ленты, px; [FAR] — последнее сообщение не видно. */
    fun overflow(): Int {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return 0
        if (last.index < info.totalItemsCount - 1) return FAR
        return last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
    }

    fun atEnd(): Boolean {
        if (!listState.canScrollForward) return true
        val o = overflow()
        return o != FAR && o <= nearEndPx
    }

    // Жест пользователя выключает слежение; когда прокрутка остановилась — включаем, если он вернулся к концу.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            if (i is DragInteraction.Start) {
                userDragging = true
                follow = false
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (!inProgress && userDragging) {
                userDragging = false
                follow = atEnd()
            }
        }
    }

    // Слежение за концом: плавно доезжаем на прирост высоты, без прыжков (п. 9).
    LaunchedEffect(listState) {
        while (true) {
            snapshotFlow { follow && !userDragging && listState.canScrollForward }.first { it }
            try {
                val o = overflow()
                if (o == FAR) {
                    listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                } else {
                    withFrameNanos { }
                    val step = if (o <= 0) minStepPx else max(o * 0.25f, minStepPx).coerceAtMost(o.toFloat())
                    listState.scrollBy(step)
                }
            } catch (e: CancellationException) {
                // Прокрутку перехватил жест пользователя — это не отмена эффекта.
                if (!isActive) throw e
            }
        }
    }

    // Открытие диалога: сразу в конце, без анимации (п. 13).
    val hasMessages = messages.isNotEmpty()
    LaunchedEffect(sessionId, hasMessages) {
        if (hasMessages) {
            listState.scrollToItem(messages.size - 1, Int.MAX_VALUE / 2)
            follow = true
        }
    }
    LaunchedEffect(scrollSignal) {
        if (scrollSignal > 0) follow = true
    }

    if (messages.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Чем могу помочь?", style = MaterialTheme.typography.headlineSmall)
        }
        return
    }
    val showToEnd by remember { derivedStateOf { !follow && !atEnd() } }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages, key = { it.id }) { m ->
                if (m.role == MessageEntity.ROLE_USER) {
                    UserBubble(m.text)
                } else {
                    val generating = active?.messageId == m.id
                    val full = if (generating) active!!.answer else m.text
                    val shown = reveal[m.id]
                    AssistantMessage(
                        text = if (shown == null) full else StreamingText.safeTake(full, shown),
                        thinking = if (generating) active!!.thinking else m.thinking,
                        thinkingInProgress = generating && active!!.thinkingInProgress,
                        compressing = generating && active!!.compressing,
                        streaming = generating || (shown != null && shown < full.length),
                        revealed = shown != null,
                        fullText = full,
                        onCopy = onCopy,
                    )
                }
            }
        }
        if (showToEnd) {
            WithTooltip("К концу диалога") {
                SmallFloatingActionButton(
                    // Плавно до конца ленту ведёт цикл слежения выше (п. 11).
                    onClick = { follow = true },
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .semantics { contentDescription = "К концу диалога" },
                ) { Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = null) }
            }
        }
    }
}

private val NEAR_END = 48.dp
private const val FAR = Int.MAX_VALUE

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
    /** Ответ выводился на этом экране: Markdown только для законченных строк, без пропаданий. */
    revealed: Boolean,
    fullText: String,
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
        when {
            text.isEmpty() && streaming -> if (!thinkingInProgress) Text("…", style = MaterialTheme.typography.bodyLarge)
            revealed -> StreamingMarkdown(text, streaming)
            else -> Markdown(content = text)
        }
        if (!streaming && fullText.isNotEmpty()) {
            WithTooltip("Копировать ответ") {
                IconButton(onClick = { onCopy(fullText) }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_content_copy),
                        contentDescription = "Копировать ответ",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Ответ, который дописывается (F004.01 п. 8): Markdown разбирается только по законченным строкам и сразу,
 * без пустого промежутка; всё, что ещё не разобрано, — обычным текстом ниже.
 * Так уже показанный текст не пропадает и не мигает.
 */
@Composable
private fun StreamingMarkdown(text: String, streaming: Boolean) {
    val toParse = if (streaming) StreamingText.completeLines(text) else text
    // immediate: разбор сразу при смене текста, без пустого состояния «загрузка» (в 0.35.0 нет retainState).
    val state = rememberMarkdownState(toParse, immediate = true)
    val parsed by state.state.collectAsStateWithLifecycle()
    val rendered = (parsed as? State.Success)?.content ?: ""
    val tail = text.substring(text.commonPrefixWith(rendered).length)
    if (rendered.isNotEmpty()) Markdown(parsed)
    if (tail.isNotBlank()) {
        Text(StreamingText.plainTail(tail.trimStart('\n')), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Подсказка при долгом нажатии (F004.01 п. 3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WithTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) { content() }
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

/** Панель ввода (F004.01 п. 1–4): один ряд — поле, микрофон, круглая «отправить» / «Стоп». */
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
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (voice) {
            VoiceState.Idle -> {
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    modifier = Modifier.weight(1f).heightIn(min = BAR_HEIGHT),
                    placeholder = { Text("Сообщение") },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    trailingIcon = if (text.isEmpty()) null else {
                        {
                            WithTooltip("Очистить поле") {
                                IconButton(onClick = onClear) {
                                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Очистить поле")
                                }
                            }
                        }
                    },
                )
                WithTooltip("Записать голос") {
                    IconButton(onClick = onMic, enabled = micEnabled, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_mic), contentDescription = "Записать голос")
                    }
                }
                if (generating) {
                    WithTooltip("Остановить ответ") {
                        FilledIconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_stop), contentDescription = "Остановить ответ")
                        }
                    }
                } else {
                    WithTooltip("Отправить") {
                        FilledIconButton(
                            onClick = onSend,
                            enabled = text.isNotBlank() && canSend,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = "Отправить")
                        }
                    }
                }
            }
            VoiceState.Preparing -> {
                VoiceStatus(Modifier.weight(1f), status = { Text("Подготовка…") }, typed = text, live = null)
                OutlinedButton(onClick = onVoiceCancel, contentPadding = COMPACT) { Text("Отменить", maxLines = 1, softWrap = false) }
            }
            is VoiceState.Recording -> {
                VoiceStatus(
                    Modifier.weight(1f),
                    status = {
                        Text(
                            "● ${formatSec(voice.elapsedSec)} / ${formatSec(VoiceInput.LIMIT_SEC)}",
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                        )
                    },
                    typed = text,
                    live = voice.text,
                )
                OutlinedButton(onClick = onVoiceCancel, contentPadding = COMPACT) { Text("Отменить", maxLines = 1, softWrap = false) }
                Button(onClick = onVoiceFinish, contentPadding = COMPACT) { Text("Завершить", maxLines = 1, softWrap = false) }
            }
            is VoiceState.Finishing -> {
                VoiceStatus(
                    Modifier.weight(1f),
                    status = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text("Распознаю…", Modifier.padding(start = 8.dp), maxLines = 1)
                        }
                    },
                    typed = text,
                    live = voice.text,
                )
            }
        }
    }
}

/** Голосовой ввод в том же ряду и той же высоты (F004.01 п. 4); распознанное на лету — серым. */
@Composable
private fun VoiceStatus(modifier: Modifier, status: @Composable () -> Unit, typed: String, live: String?) {
    val gray = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.heightIn(min = BAR_HEIGHT),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            status()
            val body = buildAnnotatedString {
                append(typed)
                if (!live.isNullOrBlank()) {
                    if (typed.isNotBlank()) append(" ")
                    withStyle(SpanStyle(color = gray)) { append(live) }
                }
            }
            if (body.isNotEmpty()) Text(body, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val BAR_HEIGHT = 56.dp
private val COMPACT = PaddingValues(horizontal = 12.dp)

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
