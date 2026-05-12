package app.protocolvoice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.protocolvoice.summary.default_tier.SummaryResult

/**
 * Полноэкранный Dialog с результатами Default tier саммаризации.
 *
 * Состояния:
 *  - isGenerating == true → spinner и текст "Собираем резюме…"
 *  - error != null → красная карточка с ошибкой + кнопка "Попробовать ещё"
 *  - result != null → разделы: статистика, имена, организации, локации,
 *                      топ-цитаты, риски, цифры + sticky bottom bar с действиями
 *  - все три null/false → пустое состояние
 */
@Composable
fun SummarySheet(
    result: SummaryResult?,
    isGenerating: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRegenerate: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSaveTxt: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        val cs = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(cs.background),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Заголовок (фиксированный сверху)
                Surface_TopBar(onDismiss)

                // Содержимое — прокрутка (занимает всё доступное место кроме bottom bar)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    when {
                        isGenerating -> GeneratingState()
                        error != null -> ErrorState(error, onRegenerate)
                        result != null -> ResultContent(result)
                        else -> EmptyState()
                    }
                    Spacer(Modifier.height(40.dp))
                }

                // Sticky bottom bar — показываем только если есть результат
                if (result != null && !isGenerating && error == null) {
                    ActionBar(
                        onCopy = onCopy,
                        onShare = onShare,
                        onSaveTxt = onSaveTxt,
                    )
                }
            }
        }
    }
}

@Composable
private fun Surface_TopBar(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Резюме интервью",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Закрыть") }
    }
    HorizontalDivider(color = cs.outlineVariant)
}

/**
 * Нижняя панель с действиями: копировать / поделиться / сохранить .txt.
 */
@Composable
private fun ActionBar(
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSaveTxt: () -> Unit,
) {
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
                    top = 10.dp,
                    bottom = 10.dp + navInsets.calculateBottomPadding(),
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Копировать
            OutlinedButton(
                onClick = onCopy,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Копировать", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            // Поделиться
            OutlinedButton(
                onClick = onShare,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Поделиться", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            // Сохранить .txt
            Button(
                onClick = onSaveTxt,
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(
                    Icons.Default.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Сохранить", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun GeneratingState() {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            "Собираем резюме…",
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "На стенограмму 18 000 слов уходит около 6 секунд.",
            fontSize = 13.sp,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorState(error: String, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = cs.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Не удалось собрать резюме",
                fontWeight = FontWeight.Bold,
                color = cs.onErrorContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(error, fontSize = 13.sp, color = cs.onErrorContainer)
        }
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onRetry) { Text("Попробовать ещё раз") }
}

@Composable
private fun EmptyState() {
    Text(
        "Резюме пока не сформировано. Нажмите «Сделать резюме» в нижней панели.",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 80.dp),
    )
}

@Composable
private fun ResultContent(summary: SummaryResult) {
    val cs = MaterialTheme.colorScheme

    // 1. Статистика
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Статистика", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("Слов: ${summary.transcriptWords}", fontSize = 13.sp)
            Text("Предложений: ${summary.sentencesCount}", fontSize = 13.sp)
            Text(
                "Время обработки: ${"%.1f".format(summary.processingTimeMs / 1000.0)} сек",
                fontSize = 13.sp,
                color = cs.onSurfaceVariant,
            )
            if (summary.persons.isNotEmpty()) {
                Text(
                    "Имён: ${summary.persons.size}, организаций: ${summary.organizations.size}, " +
                        "локаций: ${summary.locations.size}",
                    fontSize = 13.sp,
                )
            } else {
                Text(
                    "NER-модели не загружены — список имён недоступен. " +
                        "Скачайте summary-модели в настройках, чтобы получить полный анализ.",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    // 2. Persons
    if (summary.persons.isNotEmpty()) {
        SectionHeader("👥 Главные действующие лица")
        for (p in summary.persons.take(10)) {
            Text("• ${p.text} — ${p.count}×", fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
    }

    // 3. Organizations
    if (summary.organizations.isNotEmpty()) {
        SectionHeader("🏢 Организации")
        for (o in summary.organizations.take(8)) {
            Text("• ${o.text} (${o.count}×)", fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
    }

    // 4. Locations
    if (summary.locations.isNotEmpty()) {
        SectionHeader("📍 Локации")
        for (l in summary.locations.take(8)) {
            Text("• ${l.text} (${l.count}×)", fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
    }

    // 5. Top quotes
    if (summary.topQuotes.isNotEmpty()) {
        SectionHeader("💬 Ключевые цитаты")
        for ((i, q) in summary.topQuotes.withIndex()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant.copy(alpha = 0.3f)),
            ) {
                Text(
                    "${i + 1}. «${q.sentence}»",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    // 6. Risks
    if (summary.risks.isNotEmpty()) {
        SectionHeader("⚠️ Риски и проблемы")
        for (r in summary.risks) {
            Text("• ${r.sentence}", fontSize = 13.sp)
            Text(
                "  триггеры: ${r.triggers.joinToString(", ")}",
                fontSize = 11.sp,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(12.dp))
    }

    // 7. Numbers
    if (summary.numbers.isNotEmpty()) {
        SectionHeader("🔢 Цифры и данные")
        for (n in summary.numbers.take(20)) {
            Text("• …${n.context}…", fontSize = 12.sp)
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
    )
}
