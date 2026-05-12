package app.protocolvoice.summary

import app.protocolvoice.summary.default_tier.SummaryResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Форматирование [SummaryResult] в plain-text для:
 *   - клипборда / share intent
 *   - .txt файла на диске
 *
 * Формат human-readable, с разделителями секций и эмодзи-маркерами
 * (как в SummarySheet UI, но в виде текста).
 *
 * Для аудиторской работы выводим всё: имена, организации, локации, цитаты,
 * риски и числа. Это позволяет вставить результат как есть в отчёт или письмо.
 */
object SummaryFormatter {

    private val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT)

    /**
     * Полный plain-text для clipboard / share / .txt.
     *
     * @param interviewTitle опциональный заголовок интервью (из metadata)
     * @param participants опциональный список участников (из participants)
     */
    fun toPlainText(
        result: SummaryResult,
        interviewTitle: String? = null,
        participants: List<String> = emptyList(),
    ): String = buildString {
        appendLine("РЕЗЮМЕ ИНТЕРВЬЮ")
        appendLine("=".repeat(60))
        if (!interviewTitle.isNullOrBlank()) {
            appendLine("Тема: $interviewTitle")
        }
        appendLine("Дата генерации: ${dateFmt.format(Date(result.generatedAtMs))}")
        appendLine()

        // 1. Статистика
        appendLine("СТАТИСТИКА")
        appendLine("-".repeat(40))
        appendLine("Слов в стенограмме: ${result.transcriptWords}")
        appendLine("Предложений: ${result.sentencesCount}")
        appendLine("Время обработки: ${"%.1f".format(result.processingTimeMs / 1000.0)} сек")
        if (result.persons.isNotEmpty()) {
            appendLine(
                "Найдено: ${result.persons.size} имён, " +
                    "${result.organizations.size} организаций, " +
                    "${result.locations.size} локаций"
            )
        } else {
            appendLine("(NER-модели не загружены — список имён недоступен)")
        }
        appendLine()

        // 2. Участники из метаданных, если есть
        if (participants.isNotEmpty()) {
            appendLine("УЧАСТНИКИ ВСТРЕЧИ")
            appendLine("-".repeat(40))
            for (p in participants) appendLine("• $p")
            appendLine()
        }

        // 3. Persons (NER)
        if (result.persons.isNotEmpty()) {
            appendLine("ГЛАВНЫЕ ДЕЙСТВУЮЩИЕ ЛИЦА")
            appendLine("-".repeat(40))
            for (p in result.persons.take(15)) {
                appendLine("• ${p.text} — ${p.count}×")
            }
            appendLine()
        }

        // 4. Organizations
        if (result.organizations.isNotEmpty()) {
            appendLine("ОРГАНИЗАЦИИ")
            appendLine("-".repeat(40))
            for (o in result.organizations.take(15)) {
                appendLine("• ${o.text} (${o.count}×)")
            }
            appendLine()
        }

        // 5. Locations
        if (result.locations.isNotEmpty()) {
            appendLine("ЛОКАЦИИ")
            appendLine("-".repeat(40))
            for (l in result.locations.take(15)) {
                appendLine("• ${l.text} (${l.count}×)")
            }
            appendLine()
        }

        // 6. Top quotes
        if (result.topQuotes.isNotEmpty()) {
            appendLine("КЛЮЧЕВЫЕ ЦИТАТЫ")
            appendLine("-".repeat(40))
            for ((i, q) in result.topQuotes.withIndex()) {
                appendLine("${i + 1}. «${q.sentence}»")
                appendLine()
            }
        }

        // 7. Risks
        if (result.risks.isNotEmpty()) {
            appendLine("РИСКИ И ПРОБЛЕМЫ")
            appendLine("-".repeat(40))
            for (r in result.risks) {
                appendLine("• ${r.sentence}")
                appendLine("  [триггеры: ${r.triggers.joinToString(", ")}]")
                appendLine()
            }
        }

        // 8. Numbers
        if (result.numbers.isNotEmpty()) {
            appendLine("ЦИФРЫ И КОЛИЧЕСТВЕННЫЕ ДАННЫЕ")
            appendLine("-".repeat(40))
            for (n in result.numbers.take(25)) {
                appendLine("• …${n.context}…")
            }
            appendLine()
        }

        appendLine("=".repeat(60))
        appendLine("Сгенерировано ProtocolVoice (offline, on-device)")
    }

    /**
     * Краткое имя файла для сохранения (.txt).
     * Пример: "Резюме_2026-05-12_12-30.txt"
     */
    fun suggestedFilename(result: SummaryResult, prefix: String = "Резюме"): String {
        val fileTimeFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT)
        return "${prefix}_${fileTimeFmt.format(Date(result.generatedAtMs))}.txt"
    }
}
