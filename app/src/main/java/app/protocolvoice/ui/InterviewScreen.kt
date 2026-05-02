package app.protocolvoice.ui

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.protocolvoice.R
import app.protocolvoice.asr.AsrLanguage
import app.protocolvoice.asr.AsrService
import app.protocolvoice.asr.ConfidenceLevel
import app.protocolvoice.asr.EmbeddingModel
import app.protocolvoice.asr.SpeakerCountMode
import app.protocolvoice.asr.TranscriptSegment
import app.protocolvoice.audio.AudioRecorder
import app.protocolvoice.ui.theme.BrandBlue
import app.protocolvoice.ui.theme.BrandCyan
import app.protocolvoice.ui.theme.BrandPurple
import app.protocolvoice.ui.theme.RecordingRed
import java.util.Locale

/**
 * Главный экран MK-Интервью. Один экран, три фазы:
 *
 *   ┌──────────────────────── TopAppBar ───────────────────────────┐
 *   │  Заголовок     [Шапка] [Участники] [Сброс]                  │
 *   ├───────────────────────────────────────────────────────────────┤
 *   │  StatusCard — крупный индикатор фазы (запись/обработка/готов) │
 *   │  Контролы записи (запись/пауза/стоп → распознать)            │
 *   │  Прогресс ASR (если идёт) + текст «Распознавание сегмента N»  │
 *   ├───────────────────────────────────────────────────────────────┤
 *   │  TranscriptList — таблица сегментов (после распознавания)     │
 *   │     [HH:MM:SS]  [Спикер 1]  Текст с цветовой разметкой       │
 *   │                                                               │
 *   ├───────────────────────────────────────────────────────────────┤
 *   │  Bottom bar (после распознавания): [Сохранить DOCX] [Отправить] │
 *   └───────────────────────────────────────────────────────────────┘
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterviewScreen(
    vm: InterviewViewModel = viewModel(),
    onOpenHistory: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val recorderState by vm.recorder.state.collectAsStateWithLifecycle()
    val durationMs by vm.recorder.durationMs.collectAsStateWithLifecycle()
    val level by vm.recorder.level.collectAsStateWithLifecycle()
    val asrState by vm.asr.state.collectAsStateWithLifecycle()
    val asrProgress by vm.asr.progress.collectAsStateWithLifecycle()
    val asrStatus by vm.asr.statusText.collectAsStateWithLifecycle()
    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val metadata by vm.metadata.collectAsStateWithLifecycle()
    val participants by vm.participants.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val exportedUri by vm.exportedDocx.collectAsStateWithLifecycle()
    val isPlaying by vm.player.isPlaying.collectAsStateWithLifecycle()
    val playerCurrentMs by vm.player.currentMs.collectAsStateWithLifecycle()
    val playerTotalMs by vm.player.totalMs.collectAsStateWithLifecycle()
    val asrLanguage by vm.asrLanguage.collectAsStateWithLifecycle()

    val ctx = LocalContext.current
    val snack = remember { SnackbarHostState() }

    var showMetadataSheet by remember { mutableStateOf(false) }
    var showParticipantsSheet by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val currentSessionId by vm.currentSessionId.collectAsStateWithLifecycle()

    // -- запросы разрешений ----------------------------------------------------
    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.startRecording() else {
            // toast через snackbar
        }
    }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* не блокируем */ }

    // -- стартовая инициализация ----------------------------------------------
    LaunchedEffect(Unit) {
        vm.preloadAsrModels()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            snack.showSnackbar(it)
            vm.consumeToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.title_main),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (metadata.title.isBlank()) stringResource(R.string.title_empty)
                                   else metadata.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    // Бэйдж языка распознавания — кликабельный, ведёт на About экран для смены.
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandBlue.copy(alpha = 0.18f))
                            .clickable { onOpenAbout() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = if (asrLanguage == AsrLanguage.EN) "EN" else "RU",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = BrandBlue,
                        )
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = stringResource(R.string.action_history))
                    }
                    IconButton(onClick = { showMetadataSheet = true }) {
                        Icon(Icons.Default.Description, contentDescription = stringResource(R.string.action_metadata))
                    }
                    IconButton(onClick = { showParticipantsSheet = true }) {
                        Icon(Icons.Default.Group, contentDescription = stringResource(R.string.action_participants))
                    }
                    if (phase == InterviewViewModel.Phase.READY ||
                        phase == InterviewViewModel.Phase.RECORDED ||
                        phase == InterviewViewModel.Phase.ERROR) {
                        IconButton(onClick = { vm.resetSession() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_new_session))
                        }
                    }
                    // overflow-меню «⋮» — всегда видно, с разными пунктами в зависимости от фазы.
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_overflow_menu))
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            // Пункт 1: удалить из истории (только если есть сохранённая сессия)
                            if (currentSessionId != null && phase == InterviewViewModel.Phase.READY) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_remove_from_history)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        vm.removeCurrentFromHistory()
                                    },
                                )
                            }
                            // Пункт 2: Перезагрузить модели (debug — стирает все модели в filesDir,
                            // при следующем запуске появится Downloader-экран).
                            // ТОЛЬКО В DEBUG-СБОРКАХ — в release этот пункт скрыт от конечных пользователей.
                            if (app.protocolvoice.BuildConfig.DEBUG &&
                                (phase == InterviewViewModel.Phase.IDLE ||
                                 phase == InterviewViewModel.Phase.ERROR)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_redownload_models)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        vm.deleteAllModelsAndRestart(ctx)
                                    },
                                )
                            }
                            // Пункт 3: О программе — доступ к экрану благодарностей и лицензий
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_about)) },
                                onClick = {
                                    showOverflowMenu = false
                                    onOpenAbout()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (phase == InterviewViewModel.Phase.READY) {
                val shareTitle = stringResource(R.string.action_share_docx)
                ExportBottomBar(
                    onExport = { vm.exportDocx() },
                    onShare = {
                        exportedUri?.let { uri ->
                            ctx.startActivity(Intent.createChooser(vm.shareDocxIntent(uri), shareTitle))
                        } ?: vm.exportDocx()
                    },
                    showShare = exportedUri != null,
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        ) {
            // ---- Карточка статуса ----
            StatusCard(
                phase = phase,
                durationMs = durationMs,
                level = level,
                asrState = asrState,
                asrProgress = asrProgress,
                asrStatus = asrStatus,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )

            // ---- Выбор количества спикеров и embedding-модели (в фазе RECORDED) ----
            if (phase == InterviewViewModel.Phase.RECORDED) {
                val speakerMode by vm.speakerCountMode.collectAsStateWithLifecycle()
                val embeddingModel by vm.embeddingModel.collectAsStateWithLifecycle()
                val isReloading by vm.isReloadingEmbedding.collectAsStateWithLifecycle()
                SpeakerCountSelector(
                    selected = speakerMode,
                    onSelect = { vm.setSpeakerCountMode(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
                EmbeddingModelSelector(
                    selected = embeddingModel,
                    isReloading = isReloading,
                    onSelect = { vm.setEmbeddingModel(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            // ---- Контролы записи ----
            RecordControls(
                phase = phase,
                onStart = {
                    if (vm.recorder.hasPermission()) vm.startRecording()
                    else recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onPause = { vm.pauseRecording() },
                onResume = { vm.resumeRecording() },
                onStop = { vm.stopRecording() },
                onProcess = { vm.runRecognition() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            // ---- Мини-плеер (виден в фазе READY когда есть аудио) ----
            if (phase == InterviewViewModel.Phase.READY && vm.hasAudio()) {
                // Лениво загружаем плеер при первом попадании в фазу READY,
                // чтобы totalMs выставился и slider отображался корректно до первого play.
                LaunchedEffect(phase, playerTotalMs) {
                    if (playerTotalMs == 0L) vm.ensurePlayerLoaded()
                }
                MiniPlayer(
                    isPlaying = isPlaying,
                    currentMs = playerCurrentMs,
                    totalMs = playerTotalMs,
                    onToggle = { vm.togglePlayer() },
                    onSeek = { ms -> vm.seekPlayer(ms) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(4.dp))
            }

            // ---- Транскрипт ----
            val t = transcript
            if (t == null) {
                EmptyTranscriptHint(phase = phase, modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp))
            } else {
                TranscriptList(
                    segments = t.segments,
                    nameOf = { id -> participants.displayName(id, ctx) },
                    isPlaying = isPlaying,
                    playerCurrentMs = playerCurrentMs,
                    onPlaySegment = { seg -> vm.playSegment(seg.startMs, seg.endMs) },
                    onPauseSegment = { vm.player.pause() },
                    canPlay = vm.hasAudio(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showMetadataSheet) {
        MetadataBottomSheet(
            initial = metadata,
            onDismiss = { showMetadataSheet = false },
            onSave = { md ->
                vm.updateMetadata { md }
                showMetadataSheet = false
            },
        )
    }
    if (showParticipantsSheet) {
        ParticipantsBottomSheet(
            initialNames = participants.names,
            knownSpeakerIds = vm.knownSpeakerIds(),
            onDismiss = { showParticipantsSheet = false },
            onSave = { newNames ->
                // переписываем целиком
                val current = participants.names.keys + newNames.keys
                for (id in current) {
                    val v = newNames[id].orEmpty()
                    vm.setParticipantName(id, v)
                }
                showParticipantsSheet = false
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Status card — крупная карточка-индикатор текущей фазы
// ---------------------------------------------------------------------------

@Composable
private fun StatusCard(
    phase: InterviewViewModel.Phase,
    durationMs: Long,
    level: Float,
    asrState: AsrService.State,
    asrProgress: Float,
    asrStatus: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val (label, sub, accent) = when (phase) {
        InterviewViewModel.Phase.IDLE -> Triple(
            stringResource(R.string.phase_idle_title),
            stringResource(R.string.phase_idle_subtitle),
            cs.primary,
        )
        InterviewViewModel.Phase.RECORDING -> Triple(
            stringResource(R.string.phase_recording_title),
            formatDuration(durationMs),
            RecordingRed,
        )
        InterviewViewModel.Phase.PAUSED -> Triple(
            stringResource(R.string.phase_paused_title),
            formatDuration(durationMs),
            BrandPurple,
        )
        InterviewViewModel.Phase.RECORDED -> Triple(
            stringResource(R.string.phase_recorded_title),
            stringResource(R.string.phase_recorded_subtitle, formatDuration(durationMs)),
            BrandCyan,
        )
        InterviewViewModel.Phase.PROCESSING -> Triple(
            stringResource(R.string.phase_processing_title),
            asrStatus.ifBlank { stringResource(R.string.phase_processing_subtitle) },
            BrandPurple,
        )
        InterviewViewModel.Phase.READY -> Triple(
            stringResource(R.string.phase_ready_title),
            stringResource(R.string.phase_ready_subtitle),
            BrandBlue,
        )
        InterviewViewModel.Phase.ERROR -> Triple(
            stringResource(R.string.phase_error_title),
            stringResource(R.string.phase_error_subtitle),
            cs.error,
        )
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(accent),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                Spacer(Modifier.weight(1f))
                if (phase == InterviewViewModel.Phase.RECORDING ||
                    phase == InterviewViewModel.Phase.PAUSED) {
                    Text(
                        text = formatDuration(durationMs),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )

            // Уровень микрофона
            if (phase == InterviewViewModel.Phase.RECORDING ||
                phase == InterviewViewModel.Phase.PAUSED) {
                Spacer(Modifier.height(10.dp))
                LevelMeter(level = level, paused = phase == InterviewViewModel.Phase.PAUSED)
            }

            // ASR прогресс
            if (phase == InterviewViewModel.Phase.PROCESSING) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { asrProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                val ctxLocal = LocalContext.current
                Text(
                    text = "${(asrProgress * 100).toInt()}%   ${asrStateLabel(asrState, ctxLocal)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

private fun asrStateLabel(s: AsrService.State, ctx: android.content.Context): String = when (s) {
    AsrService.State.IDLE          -> ""
    AsrService.State.LOADING_MODELS -> ctx.getString(R.string.asr_stage_loading)
    AsrService.State.DIARIZING     -> ctx.getString(R.string.asr_stage_diarizing)
    AsrService.State.TRANSCRIBING  -> ctx.getString(R.string.asr_stage_transcribing)
    AsrService.State.DONE          -> ctx.getString(R.string.asr_stage_done)
    AsrService.State.ERROR         -> ctx.getString(R.string.asr_stage_error)
}

@Composable
private fun LevelMeter(level: Float, paused: Boolean) {
    val pct = level.coerceIn(0f, 1f)
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(cs.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (paused) 0f else pct)
                .height(8.dp)
                .background(
                    when {
                        pct > 0.85f -> RecordingRed       // клиппинг — слишком громко
                        pct > 0.5f  -> BrandCyan          // оптимальный уровень
                        else        -> BrandBlue          // тихо
                    },
                ),
        )
    }
}

// ---------------------------------------------------------------------------
// Кнопки записи / распознавания
// ---------------------------------------------------------------------------

@Composable
private fun RecordControls(
    phase: InterviewViewModel.Phase,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onProcess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (phase) {
            InterviewViewModel.Phase.IDLE,
            InterviewViewModel.Phase.ERROR -> {
                // Премиум CTA: большая brand-blue кнопка Record с иконкой
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandBlue,
                        contentColor = Color.White,
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 8.dp,
                    ),
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.action_record),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        letterSpacing = 0.3.sp,
                    )
                }
            }
            InterviewViewModel.Phase.RECORDING -> {
                // Пауза — outline-style (secondary)
                OutlinedButton(
                    onClick = onPause,
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.PauseCircle, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_pause), fontWeight = FontWeight.SemiBold)
                }
                // Стоп — яркая recording-red кнопка
                Button(
                    onClick = onStop,
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RecordingRed,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_stop), fontWeight = FontWeight.Bold)
                }
            }
            InterviewViewModel.Phase.PAUSED -> {
                // Продолжить — brand-blue
                Button(
                    onClick = onResume,
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandBlue,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_resume), fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_stop), fontWeight = FontWeight.SemiBold)
                }
            }
            InterviewViewModel.Phase.RECORDED -> {
                // Process — яркая brand-blue кнопка «Распознать»
                Button(
                    onClick = onProcess,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandBlue,
                        contentColor = Color.White,
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 8.dp,
                    ),
                ) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.action_process),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        letterSpacing = 0.3.sp,
                    )
                }
            }
            InterviewViewModel.Phase.PROCESSING -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = BrandBlue,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.asr_status_processing),
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            InterviewViewModel.Phase.READY -> {
                // Контролы записи скрыты — низ занят bottom bar'ом экспорта.
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Список сегментов транскрипта
// ---------------------------------------------------------------------------

@Composable
private fun TranscriptList(
    segments: List<TranscriptSegment>,
    nameOf: (Int) -> String,
    isPlaying: Boolean,
    playerCurrentMs: Long,
    onPlaySegment: (TranscriptSegment) -> Unit,
    onPauseSegment: () -> Unit,
    canPlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(segments, key = { idx, _ -> idx }) { _, seg ->
            val isActive = isPlaying &&
                playerCurrentMs in seg.startMs..seg.endMs
            SegmentCard(
                seg = seg,
                nameOf = nameOf,
                isActive = isActive,
                canPlay = canPlay,
                onPlay = { onPlaySegment(seg) },
                onPause = onPauseSegment,
            )
        }
    }
}

@Composable
private fun SegmentCard(
    seg: TranscriptSegment,
    nameOf: (Int) -> String,
    isActive: Boolean,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val speakerColor = speakerAccent(seg.speakerId)
    val containerColor = if (isActive) {
        speakerColor.copy(alpha = 0.12f).compositeOver(cs.surface)
    } else cs.surface

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isActive) 4.dp else 1.dp,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Цветная полоса-индикатор спикера
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 24.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(speakerColor),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = nameOf(seg.speakerId),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = speakerColor,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatTime(seg.startMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurfaceVariant,
                )
                if (canPlay) {
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = if (isActive) onPause else onPlay,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (isActive) Icons.Default.Pause
                                          else Icons.Default.PlayArrow,
                            contentDescription = if (isActive) stringResource(R.string.player_pause) else stringResource(R.string.player_play_segment),
                            tint = speakerColor,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildAnnotatedString {
                    for ((idx, w) in seg.words.withIndex()) {
                        // Цвета для тёмной темы: светлые варианты warning/danger
                        val color = when (w.confidenceLevel) {
                            ConfidenceLevel.LOW    -> Color(0xFFFCA5A5)  // светло-красный для dark theme
                            ConfidenceLevel.MEDIUM -> Color(0xFFFCD34D)  // светло-жёлтый
                            ConfidenceLevel.HIGH   -> cs.onSurface
                        }
                        if (idx > 0) append(" ")
                        withStyle(SpanStyle(color = color)) { append(w.text) }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 22.sp,
                fontSize = 15.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Мини-плеер — всегда полная запись, seek-bar + play/pause
// ---------------------------------------------------------------------------

@Composable
private fun MiniPlayer(
    isPlaying: Boolean,
    currentMs: Long,
    totalMs: Long,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = onToggle,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause
                                  else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                val pct = if (totalMs > 0) (currentMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
                Slider(
                    value = pct,
                    onValueChange = { newPct ->
                        if (totalMs > 0) onSeek((newPct * totalMs).toLong())
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = cs.primary,
                        activeTrackColor = cs.primary,
                        inactiveTrackColor = cs.outlineVariant,
                    ),
                    modifier = Modifier.height(24.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatPlayerTime(currentMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                    )
                    Text(
                        text = formatPlayerTime(totalMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatPlayerTime(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(Locale.ROOT, "%d:%02d", m, s)
}

// Палитра для различения спикеров — яркие цвета для тёмной темы
private val SpeakerPalette = listOf(
    Color(0xFF60A5FA), // яркий синий
    Color(0xFF34D399), // яркий зелёный
    Color(0xFFA78BFA), // яркий фиолетовый
    Color(0xFFFBBF24), // яркий жёлтый
    Color(0xFFFB923C), // яркий оранжевый
    Color(0xFF22D3EE), // яркий циан
    Color(0xFFF472B6), // яркий розовый
    Color(0xFFA855F7), // яркий пурпурный
)

private fun speakerAccent(id: Int): Color =
    SpeakerPalette[((id % SpeakerPalette.size) + SpeakerPalette.size) % SpeakerPalette.size]

// ---------------------------------------------------------------------------
// Выбор количества спикеров (1 / 2 / 3 / 4 / Авто)
// ---------------------------------------------------------------------------

@Composable
private fun SpeakerCountSelector(
    selected: SpeakerCountMode,
    onSelect: (SpeakerCountMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = cs.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.speaker_count_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = describeSpeakerMode(selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (mode in SpeakerCountMode.all) {
                    SpeakerChip(
                        label = speakerModeLabel(mode),
                        active = mode == selected,
                        onClick = { onSelect(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeakerChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) cs.primary else cs.surface)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (active) cs.primary else cs.surface,
        tonalElevation = if (active) 2.dp else 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (active) cs.onPrimary else cs.onSurface,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun describeSpeakerMode(mode: SpeakerCountMode): String = when (mode) {
    SpeakerCountMode.Single -> stringResource(R.string.speaker_count_single_desc)
    is SpeakerCountMode.Fixed -> stringResource(R.string.speaker_count_fixed_desc, mode.count)
    SpeakerCountMode.Auto -> stringResource(R.string.speaker_count_auto_desc)
}

/** Локализованная метка для chip'а. Авто берётся из strings.xml, цифры — прямо. */
@Composable
private fun speakerModeLabel(mode: SpeakerCountMode): String = when (mode) {
    SpeakerCountMode.Single -> stringResource(R.string.speaker_count_single_label)
    is SpeakerCountMode.Fixed -> mode.count.toString()
    SpeakerCountMode.Auto -> stringResource(R.string.speaker_count_auto_label)
}

// ---------------------------------------------------------------------------
// Выбор embedding-модели для диаризации (ERes2Net / V2 / CAM++)
// ---------------------------------------------------------------------------

@Composable
private fun EmbeddingModelSelector(
    selected: EmbeddingModel,
    isReloading: Boolean,
    onSelect: (EmbeddingModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = cs.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.embedding_model_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (isReloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = "${stringResource(selected.sizeLabelRes)} · ${stringResource(selected.descriptionRes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (model in EmbeddingModel.all) {
                    SpeakerChip(
                        label = stringResource(model.displayNameRes),
                        active = model == selected,
                        onClick = { onSelect(model) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Подсказка пустого состояния с иллюстрацией
// ---------------------------------------------------------------------------

@Composable
private fun EmptyTranscriptHint(
    phase: InterviewViewModel.Phase,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme

    // Ресурс иллюстрации и текст под каждую фазу
    val (illustrationRes, title, subtitle) = when (phase) {
        InterviewViewModel.Phase.IDLE -> Triple(
            R.drawable.illustration_empty_no_transcripts,
            stringResource(R.string.phase_idle_title),
            stringResource(R.string.hint_idle),
        )
        InterviewViewModel.Phase.RECORDING -> Triple(
            R.drawable.illustration_onboarding_record,
            stringResource(R.string.phase_recording_title),
            stringResource(R.string.hint_recording),
        )
        InterviewViewModel.Phase.PAUSED -> Triple(
            R.drawable.illustration_onboarding_record,
            stringResource(R.string.phase_paused_title),
            stringResource(R.string.hint_paused),
        )
        InterviewViewModel.Phase.RECORDED -> Triple(
            R.drawable.illustration_empty_transcribing,
            stringResource(R.string.phase_recorded_title),
            stringResource(R.string.hint_recorded),
        )
        InterviewViewModel.Phase.PROCESSING -> Triple(
            R.drawable.illustration_empty_transcribing,
            stringResource(R.string.phase_processing_title),
            stringResource(R.string.hint_processing),
        )
        InterviewViewModel.Phase.READY -> Triple(
            R.drawable.illustration_empty_no_transcripts,
            stringResource(R.string.phase_ready_title),
            stringResource(R.string.hint_transcript_empty),
        )
        InterviewViewModel.Phase.ERROR -> Triple(
            R.drawable.illustration_empty_no_results,
            stringResource(R.string.phase_error_title),
            stringResource(R.string.hint_error),
        )
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(illustrationRes),
            contentDescription = null,
            modifier = Modifier
                .size(220.dp)
                .padding(bottom = 12.dp),
            contentScale = ContentScale.Fit,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Bottom bar экспорта
// ---------------------------------------------------------------------------

@Composable
private fun ExportBottomBar(
    onExport: () -> Unit,
    onShare: () -> Unit,
    showShare: Boolean,
) {
    // На MIUI/HyperOS системная навигация перекрывает нижние элементы в edge-to-edge режиме.
    // Добавляем navigationBars insets к паддингу снизу — кнопки уйдут выше.
    val navInsets = WindowInsets.navigationBars.asPaddingValues()
    Surface(
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = 12.dp,
                    bottom = 12.dp + navInsets.calculateBottomPadding(),
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onExport,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Default.Description, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_export_docx), fontWeight = FontWeight.SemiBold)
            }
            if (showShare) {
                ElevatedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_send))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Утилиты времени
// ---------------------------------------------------------------------------

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0)
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else
        String.format(Locale.ROOT, "%02d:%02d", m, s)
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s)
}
