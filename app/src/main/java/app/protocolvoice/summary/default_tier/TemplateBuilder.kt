package app.protocolvoice.summary.default_tier

import app.protocolvoice.summary.extraction.NumberExtractor
import app.protocolvoice.summary.extraction.RiskKeywordMatcher
import app.protocolvoice.summary.lexrank.LexRankSummarizer

/**
 * Сборка markdown-резюме из извлечённых данных.
 *
 * Структура совпадает с Test F (test_f_no_llm.md):
 *  1. Локализация и контекст
 *  2. Главные действующие лица (NER, заполняется когда NER появится)
 *  3. Организации
 *  4. Топ-5 ключевых цитат
 *  5. Top-15 фрагментов в порядке появления
 *  6. Выявленные проблемы и риски
 *  7. Цифры с контекстом
 *  8. Полный реестр имён и организаций
 */
object TemplateBuilder {

    fun build(
        transcriptWords: Int,
        sentencesCount: Int,
        persons: List<NerEntity>,
        organizations: List<NerEntity>,
        locations: List<NerEntity>,
        topQuotes: List<LexRankSummarizer.ScoredSentence>,
        keyFragments: List<LexRankSummarizer.ScoredSentence>,
        risks: List<RiskKeywordMatcher.RiskMatch>,
        numbers: List<NumberExtractor.NumberWithContext>,
        topWords: List<String> = emptyList(),
        processingTimeMs: Long = 0,
        modelDescription: String = "LexRank + правила (без NER)",
    ): String = buildString {
        appendLine("# Резюме интервью")
        appendLine()
        appendLine("**Метод:** $modelDescription")
        appendLine()
        appendLine("**Источник:** $transcriptWords слов, $sentencesCount предложений")
        appendLine()
        appendLine("**Время обработки:** ${"%.1f".format(processingTimeMs / 1000.0)} с")
        appendLine()
        appendLine("---")
        appendLine()

        // 1. Локализация
        appendLine("## 1. Локализация и контекст")
        appendLine()
        if (locations.isNotEmpty()) {
            val main = locations.first().text
            val others = locations.drop(1).take(2).joinToString(", ") { it.text }
            if (others.isNotBlank()) {
                appendLine("Интервью посвящено объектам в локации **$main** (также упоминаются: $others).")
            } else {
                appendLine("Интервью посвящено объектам в локации **$main**.")
            }
        } else {
            appendLine("*Локации не извлечены (NER пока не подключен).*")
        }
        appendLine()
        if (topWords.isNotEmpty()) {
            appendLine("Ключевые тематические слова: ${topWords.take(12).joinToString(", ")}.")
            appendLine()
        }

        // 2. Главные действующие лица
        appendLine("## 2. Главные действующие лица")
        appendLine()
        if (persons.isNotEmpty()) {
            appendLine("По частоте упоминаний (NER):")
            appendLine()
            for (p in persons.take(5)) {
                appendLine("- **${p.text}** — упомянут ${p.count} раз")
            }
        } else {
            appendLine("*Имена будут извлечены после подключения NER (Sprint 2).*")
        }
        appendLine()

        // 3. Организации
        appendLine("## 3. Организации в фокусе обсуждения")
        appendLine()
        if (organizations.isNotEmpty()) {
            for (o in organizations.take(8)) {
                appendLine("- **${o.text}** (${o.count}x)")
            }
        } else {
            appendLine("*Организации будут извлечены после подключения NER (Sprint 2).*")
        }
        appendLine()

        // 4. Топ-5 ключевых цитат
        appendLine("## 4. Ключевые цитаты из интервью")
        appendLine()
        appendLine("Топ-${topQuotes.size} предложений с наивысшим score (LexRank):")
        appendLine()
        for ((i, q) in topQuotes.withIndex()) {
            appendLine("${i + 1}. «${q.sentence}»")
            appendLine()
        }

        // 5. Top-K в порядке появления
        appendLine("## 5. Все ключевые фрагменты (${keyFragments.size} штук в порядке появления)")
        appendLine()
        for ((i, f) in keyFragments.withIndex()) {
            appendLine("${i + 1}. ${f.sentence}")
            appendLine()
        }

        // 6. Риски и проблемы
        appendLine("## 6. Выявленные проблемы и риски")
        appendLine()
        if (risks.isEmpty()) {
            appendLine("Не найдено предложений с триггер-словами проблем.")
        } else {
            appendLine("Найдено ${risks.size} предложений с триггер-словами проблем:")
            appendLine()
            for (r in risks) {
                appendLine("- ${r.sentence}")
                appendLine("  *(триггеры: ${r.triggers.joinToString(", ") { "`$it`" }})*")
                appendLine()
            }
        }

        // 7. Цифры
        appendLine("## 7. Цифры и количественные данные")
        appendLine()
        if (numbers.isEmpty()) {
            appendLine("Числовые значения не найдены.")
        } else {
            appendLine("Извлечено ${numbers.size} числовых значений с контекстом:")
            appendLine()
            for (n in numbers) {
                appendLine("- ...${n.context}...")
            }
        }
        appendLine()

        // 8. Полный реестр (только если NER заполнен)
        if (persons.isNotEmpty()) {
            appendLine("## 8. Полный реестр упомянутых имён (${persons.size})")
            appendLine()
            for (p in persons.take(25)) {
                appendLine("- ${p.text} (${p.count}x)")
            }
            appendLine()
        }
        if (organizations.isNotEmpty()) {
            appendLine("## 9. Полный реестр организаций (${organizations.size})")
            appendLine()
            for (o in organizations.take(25)) {
                appendLine("- ${o.text} (${o.count}x)")
            }
            appendLine()
        }

        appendLine("---")
        appendLine()
        appendLine("*Документ сгенерирован автоматически. Все факты — дословные цитаты из стенограммы.*")
    }
}
