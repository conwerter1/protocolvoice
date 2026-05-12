package app.protocolvoice.summary.default_tier

import app.protocolvoice.summary.extraction.NumberExtractor
import app.protocolvoice.summary.extraction.RiskKeywordMatcher
import app.protocolvoice.summary.lexrank.LexRankSummarizer

/**
 * Tier — какой уровень саммаризации применён.
 */
enum class SummaryTier {
    DEFAULT,  // только NER + LexRank + шаблон
    PRO,      // Default + QVikhr literary summary
}

/**
 * Сущность, найденная NER. В Sprint 1 пустая (NER нет), заполнится в Sprint 2.
 */
data class NerEntity(
    val type: String,    // "PER" | "ORG" | "LOC"
    val text: String,    // как написано в тексте
    val normalized: String, // лемматизированная форма
    val count: Int,
)

/**
 * Полный результат саммаризации.
 */
data class SummaryResult(
    /** Какой tier дал результат. */
    val tier: SummaryTier,
    /** Дата генерации. */
    val generatedAtMs: Long,
    /** Время обработки в мс. */
    val processingTimeMs: Long,

    // Статистика по входу
    val transcriptWords: Int,
    val transcriptChars: Int,
    val sentencesCount: Int,

    // Извлечённые сущности (NER) — пусто в Sprint 1
    val persons: List<NerEntity>,
    val organizations: List<NerEntity>,
    val locations: List<NerEntity>,

    // Топ-цитаты по LexRank score
    val topQuotes: List<LexRankSummarizer.ScoredSentence>,

    // Top-K фрагментов в порядке появления (для секции "Ключевые фрагменты")
    val keyFragments: List<LexRankSummarizer.ScoredSentence>,

    // Риски с триггерами
    val risks: List<RiskKeywordMatcher.RiskMatch>,

    // Цифры с контекстом
    val numbers: List<NumberExtractor.NumberWithContext>,

    // Текстовое представление (готовый markdown для отображения)
    val markdown: String,

    // PRO tier addition (заполняется только если tier == PRO)
    val proSections: Map<String, String>? = null,
)
