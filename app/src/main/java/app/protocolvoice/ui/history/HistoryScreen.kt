package app.protocolvoice.ui.history

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.protocolvoice.R
import app.protocolvoice.data.SessionStore
import app.protocolvoice.ui.theme.BrandBlue
import app.protocolvoice.ui.theme.BrandPurple
import app.protocolvoice.ui.theme.RecordingRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран истории интервью. Показывает список сохранённых сессий,
 * позволяет открыть одну из них или удалить.
 *
 * Premium-стиль: brand-colored иконки, аккуратные карточки,
 * иллюстрация для пустой истории.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var summaries by remember { mutableStateOf<List<SessionStore.SessionSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<SessionStore.SessionSummary?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        loading = true
        summaries = withContext(Dispatchers.IO) { SessionStore.listSummaries(ctx) }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.history_title),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (summaries.isEmpty()) stringResource(R.string.history_subtitle_empty)
                                   else stringResource(R.string.history_subtitle_count, summaries.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (loading) {
            // Premium лоадинг: brand-blue spinner
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = BrandBlue,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.history_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (summaries.isEmpty()) {
            // Premium пустое состояние с иллюстрацией
            EmptyHistoryHint(modifier = Modifier
                .fillMaxSize()
                .padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(summaries, key = { it.id }) { summary ->
                    SessionCard(
                        summary = summary,
                        onOpen = { onOpen(summary.id) },
                        onDelete = { pendingDelete = summary },
                    )
                }
            }
        }
    }

    pendingDelete?.let { s ->
        val titleForDialog = s.title.ifBlank { stringResource(R.string.history_session_no_title) }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = {
                Text(stringResource(R.string.history_delete_message, titleForDialog))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = s.id
                        pendingDelete = null
                        loading = true
                        // Удаляем синхронно — операция быстрая
                        SessionStore.delete(ctx, id)
                        refreshTick++   // триггерим LaunchedEffect для перезагрузки
                    },
                ) {
                    Text(stringResource(R.string.action_delete), color = RecordingRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SessionCard(
    summary: SessionStore.SessionSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Иконка слева — brand-coloured 48dp с скруглением
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (summary.audioExists) BrandBlue.copy(alpha = 0.15f)
                        else cs.surfaceContainerHighest.copy(alpha = 0.5f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (summary.audioExists) Icons.Default.GraphicEq
                                  else Icons.Default.MicOff,
                    contentDescription = null,
                    tint = if (summary.audioExists) BrandBlue else cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            // Содержимое
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.title.ifBlank { stringResource(R.string.history_session_no_title) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = formatDateTime(summary.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                if (summary.location.isNotBlank() || summary.auditorName.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    val sub = listOfNotNull(
                        summary.location.ifBlank { null },
                        summary.auditorName.ifBlank { null },
                    ).joinToString(" · ")
                    if (sub.isNotEmpty()) {
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    StatChip(
                        text = formatDuration(summary.durationMs),
                        tint = BrandBlue,
                    )
                    Spacer(Modifier.width(6.dp))
                    StatChip(
                        text = "${summary.numSpeakers} ${if (summary.numSpeakers == 1) "speaker" else "speakers"}",
                        tint = BrandPurple,
                    )
                    Spacer(Modifier.width(6.dp))
                    StatChip(
                        text = "${summary.totalWords} ${stringResource(R.string.history_words_label)}",
                        tint = cs.onSurfaceVariant,
                    )
                    if (!summary.audioExists) {
                        Spacer(Modifier.width(6.dp))
                        StatChip(
                            text = stringResource(R.string.history_no_audio_chip),
                            tint = RecordingRed,
                        )
                    }
                }
            }
            // Кнопка удаления
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatChip(text: String, tint: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = tint.copy(alpha = 0.15f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun EmptyHistoryHint(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.illustration_empty_no_transcripts),
            contentDescription = null,
            modifier = Modifier
                .size(240.dp)
                .padding(bottom = 16.dp),
            contentScale = ContentScale.Fit,
        )
        Text(
            text = stringResource(R.string.history_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.history_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

private fun formatDateTime(epochMs: Long): String {
    val fmt = SimpleDateFormat("dd.MM.yyyy  HH:mm", Locale.getDefault())
    return fmt.format(Date(epochMs))
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else  -> String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}
