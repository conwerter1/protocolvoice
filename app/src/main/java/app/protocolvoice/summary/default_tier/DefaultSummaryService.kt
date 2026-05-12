package app.protocolvoice.summary.default_tier

import app.protocolvoice.summary.core.RuSentenceSegmenter
import app.protocolvoice.summary.extraction.NumberExtractor
import app.protocolvoice.summary.extraction.RiskKeywordMatcher
import app.protocolvoice.summary.lexrank.LexRankSummarizer
import app.protocolvoice.summary.lexrank.topKByScore
import app.protocolvoice.summary.lexrank.topKInOrder
import app.protocolvoice.summary.ner.NerProvider

/**
 * Сервис генерации резюме по умолчанию (Default tier).
 *
 * Алгоритм:
 *  1. Сегментация на предложения (RuSentenceSegmenter)
 *  2. NER (через [NerProvider]; в Sprint 1 = NoOpNerProvider, в Sprint 2 = SlovnetNerProvider)
 *  3. LexRank ранжирование с entity boost (если NER вернул сущности)
 *  4. Извлечение чисел и рисков (regex)
 *  5. Сборка markdown через TemplateBuilder
 *
 * Чистый Kotlin, никаких сторонних зависимостей кроме Android API.
 */
class DefaultSummaryService(
    private val nerProvider: NerProvider,
    private val minSentenceWords: Int = 8,
    private val topKFragments: Int = 15,
    private val topKQuotes: Int = 5,
    private val maxNumbers: Int = 30,
    private val maxRisks: Int = 12,
) {

    fun summarize(transcript: String): SummaryResult {
        val t0 = System.currentTimeMillis()

        // 1. Сегментация
        val allSentences = RuSentenceSegmenter.segment(transcript)
        val sentences = allSentences.filter { it.split(Regex("\\s+")).size >= minSentenceWords }

        // 2. NER
        val nerResult = nerProvider.extract(transcript)

        // 3. LexRank
        val entityTokens = collectEntityTokens(nerResult)
        val boost = LexRankSummarizer.BoostConfig(entityTokens = entityTokens)
        val ranked = LexRankSummarizer().rank(sentences, boost = boost)

        val keyFragments = ranked.topKInOrder(topKFragments)
        val topQuotes = ranked.topKByScore(topKQuotes)

        // 4. Числа и риски
        val numbers = NumberExtractor.extract(transcript, maxResults = maxNumbers)
        val risks = RiskKeywordMatcher.findRiskSentences(
            sentences, maxResults = maxRisks,
        )

        // 5. Top words for "topic" — самые частые в TF-IDF (без stop)
        val topWords = computeTopWords(sentences, limit = 12)

        // 6. Markdown
        val totalMs = System.currentTimeMillis() - t0
        val md = TemplateBuilder.build(
            transcriptWords = transcript.split(Regex("\\s+")).size,
            sentencesCount = sentences.size,
            persons = nerResult.persons,
            organizations = nerResult.organizations,
            locations = nerResult.locations,
            topQuotes = topQuotes,
            keyFragments = keyFragments,
            risks = risks,
            numbers = numbers,
            topWords = topWords,
            processingTimeMs = totalMs,
            modelDescription = nerResult.providerDescription,
        )

        return SummaryResult(
            tier = SummaryTier.DEFAULT,
            generatedAtMs = System.currentTimeMillis(),
            processingTimeMs = totalMs,
            transcriptWords = transcript.split(Regex("\\s+")).size,
            transcriptChars = transcript.length,
            sentencesCount = sentences.size,
            persons = nerResult.persons,
            organizations = nerResult.organizations,
            locations = nerResult.locations,
            topQuotes = topQuotes,
            keyFragments = keyFragments,
            risks = risks,
            numbers = numbers,
            markdown = md,
        )
    }

    private fun collectEntityTokens(result: NerProvider.Result): Set<String> {
        val tokens = HashSet<String>()
        val all = result.persons + result.organizations + result.locations
        for (e in all) {
            tokens += e.text.lowercase()
            tokens += e.normalized.lowercase()
            for (word in e.normalized.lowercase().split(Regex("\\s+"))) {
                if (word.length >= 4) tokens += word
            }
        }
        return tokens
    }

    /**
     * Простые "top words": подсчёт частотности всех токенов (>=4 буквы, не стоп-слова).
     * Это эвристика для "тематических ключевых слов" в шапке резюме.
     */
    private fun computeTopWords(sentences: List<String>, limit: Int): List<String> {
        val tokRegex = Regex("[\\p{L}-]+")
        val stop = app.protocolvoice.summary.lexrank.TfIdfVectorizer.DEFAULT_STOPWORDS
        val freq = HashMap<String, Int>()
        for (s in sentences) {
            for (m in tokRegex.findAll(s.lowercase())) {
                val t = m.value
                if (t.length < 4 || t in stop) continue
                freq[t] = (freq[t] ?: 0) + 1
            }
        }
        return freq.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }
}
