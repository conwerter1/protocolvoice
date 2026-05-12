package app.protocolvoice.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.protocolvoice.summary.SummaryFacade
import app.protocolvoice.summary.default_tier.SummaryResult
import app.protocolvoice.summary.default_tier.SummaryTier
import app.protocolvoice.summary.pro_tier.QVikhrSummaryService

/**
 * Экран резюме интервью с двумя вкладками:
 *  - "Факты" (Default tier) — генерируется автоматически за ~5-10 сек
 *  - "Описание" (PRO tier) — литературное резюме через QVikhr 1.5B,
 *                            доступно только если QVikhr скачана
 *
 * UI прост: текст в Markdown-подобном формате (без рендеринга MD пока),
 * прокрутка, кнопка "Закрыть".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    summary: SummaryResult,
    proStatus: SummaryFacade.ProStatus,
    proProgress: QVikhrSummaryService.Progress?,
    onGeneratePro: () -> Unit,
    onClose: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Резюме интервью") },
                navigationIcon = {
                    TextButton(onClick = onClose) { Text("Закрыть") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("📊 Факты") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("📝 Описание") },
                )
            }

            when (selectedTab) {
                0 -> FactsTab(summary)
                1 -> DescriptionTab(
                    summary = summary,
                    proStatus = proStatus,
                    proProgress = proProgress,
                    onGenerate = onGeneratePro,
                )
            }
        }
    }
}

@Composable
private fun FactsTab(summary: SummaryResult) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Statistics header
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Статистика",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text("Слов: ${summary.transcriptWords}", fontSize = 13.sp)
                Text("Предложений: ${summary.sentencesCount}", fontSize = 13.sp)
                Text(
                    "Время обработки: ${"%.1f".format(summary.processingTimeMs / 1000.0)} сек",
                    fontSize = 13.sp,
                )
                if (summary.persons.isNotEmpty()) {
                    Text(
                        "Имён найдено: ${summary.persons.size} (${summary.persons.sumOf { it.count }} упоминаний)",
                        fontSize = 13.sp,
                    )
                    Text(
                        "Организаций: ${summary.organizations.size}, локаций: ${summary.locations.size}",
                        fontSize = 13.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Persons
        if (summary.persons.isNotEmpty()) {
            SectionHeader("👥 Главные действующие лица")
            for (p in summary.persons.take(8)) {
                Text("• ${p.text} — ${p.count}x", fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
        }

        // Organizations
        if (summary.organizations.isNotEmpty()) {
            SectionHeader("🏢 Организации")
            for (o in summary.organizations.take(6)) {
                Text("• ${o.text} (${o.count}x)", fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
        }

        // Top quotes
        SectionHeader("💬 Ключевые цитаты")
        for ((i, q) in summary.topQuotes.withIndex()) {
            Text(
                "${i + 1}. «${q.sentence}»",
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))

        // Risks
        if (summary.risks.isNotEmpty()) {
            SectionHeader("⚠️ Риски и проблемы")
            for (r in summary.risks) {
                Text("• ${r.sentence}", fontSize = 13.sp)
                Text(
                    "  триггеры: ${r.triggers.joinToString(", ")}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(12.dp))
        }

        // Numbers
        if (summary.numbers.isNotEmpty()) {
            SectionHeader("🔢 Цифры и количественные данные")
            for (n in summary.numbers.take(15)) {
                Text("• ...${n.context}...", fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun DescriptionTab(
    summary: SummaryResult,
    proStatus: SummaryFacade.ProStatus,
    proProgress: QVikhrSummaryService.Progress?,
    onGenerate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        when {
            // Уже готовое PRO резюме
            summary.tier == SummaryTier.PRO && summary.proSections != null -> {
                ProSummaryView(summary)
            }

            // Активная генерация
            proProgress is QVikhrSummaryService.Progress.Section -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Генерация раздела ${proProgress.current}/${proProgress.total}",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(proProgress.sectionTitle, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Это занимает 3-5 минут на устройстве",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            proProgress is QVikhrSummaryService.Progress.Loading -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Загрузка модели QVikhr...")
                }
            }

            proProgress is QVikhrSummaryService.Progress.Error -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Ошибка генерации", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(proProgress.message, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onGenerate) { Text("Попробовать ещё раз") }
            }

            // PRO доступен — кнопка "Сгенерировать"
            proStatus == SummaryFacade.ProStatus.Available -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Литературное резюме",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "QVikhr 1.5B сгенерирует связное описание интервью " +
                                "по 6 разделам: тема, участники, темы, риски, цифры, выводы.",
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Время: ~3-5 минут. Полностью offline.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onGenerate,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Сгенерировать описание")
                        }
                    }
                }
            }

            // PRO модель не скачана
            proStatus == SummaryFacade.ProStatus.ModelNotDownloaded -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "AI-резюме недоступно",
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Чтобы получить связное литературное описание интервью, " +
                                "скачайте AI-модель QVikhr 1.5B (1.0 GB) в настройках приложения.",
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Базовое резюме (вкладка «Факты») работает без неё.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Нативная библиотека не собрана
            proStatus == SummaryFacade.ProStatus.NativeMissing -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "AI-резюме недоступно в этой сборке",
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Эта сборка приложения не включает нативный движок " +
                                "для AI-резюме. Используйте вкладку «Факты».",
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProSummaryView(summary: SummaryResult) {
    val sections = summary.proSections ?: return
    Column {
        for ((id, title) in PRO_SECTION_TITLES) {
            val text = sections[id] ?: continue
            SectionHeader(title)
            Text(text, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
        }
    }
}

private val PRO_SECTION_TITLES = listOf(
    "topic" to "1. Тема встречи",
    "participants" to "2. Участники",
    "topics" to "3. Ключевые темы",
    "risks" to "4. Риски и проблемы",
    "numbers" to "5. Конкретные данные",
    "conclusions" to "6. Выводы аудитора",
)

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
    )
}
