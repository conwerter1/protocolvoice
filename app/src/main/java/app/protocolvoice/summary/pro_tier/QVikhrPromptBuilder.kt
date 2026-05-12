package app.protocolvoice.summary.pro_tier

import app.protocolvoice.summary.default_tier.NerEntity
import app.protocolvoice.summary.extraction.NumberExtractor
import app.protocolvoice.summary.extraction.RiskKeywordMatcher
import app.protocolvoice.summary.lexrank.LexRankSummarizer

/**
 * Конструктор промптов для QVikhr 1.5B Q5 (Qwen 2.5 chat format).
 *
 * Стратегия — итеративная генерация по разделам, протестированная в Test E v3 FINAL:
 *  - 6 отдельных промптов (тема / участники / темы / риски / цифры / выводы)
 *  - вместо одного длинного промпта на всё резюме — каждый раздел получает только
 *    релевантный контекст
 *  - QVikhr использует chat template (system + user), без него возвращает 0 токенов
 *
 * Каждый PromptSpec содержит system + user сообщения для llama.cpp create_chat_completion.
 */
object QVikhrPromptBuilder {

    /** Системное сообщение, общее для всех разделов. */
    const val SYSTEM_PROMPT = "Ты — опытный аудитор. Твоя задача — отвечать строго " +
            "по предоставленному контексту, без выдумывания. Отвечай на русском языке, " +
            "кратко и по существу."

    /**
     * Один промпт-запрос к QVikhr.
     */
    data class PromptSpec(
        /** Идентификатор раздела: "topic" | "participants" | "topics" | "risks" | "numbers" | "conclusions" */
        val sectionId: String,
        /** Заголовок раздела для финального markdown. */
        val sectionTitle: String,
        /** System prompt (одинаковый для всех в текущей версии). */
        val systemMessage: String,
        /** User message — содержит контекст и вопрос. */
        val userMessage: String,
        /** Максимум токенов для генерации. */
        val maxTokens: Int,
        /** Температура (низкая для фактологических, выше для выводов). */
        val temperature: Float = 0.2f,
    )

    /**
     * Построить все 6 промптов из извлечённых данных Default tier.
     *
     * @param topQuotes top-N предложений LexRank (для тем и темы встречи)
     * @param topQuotesByOrder те же предложения в порядке появления (для тем)
     * @param persons NER PER (топ-12)
     * @param organizations NER ORG (топ-10)
     * @param locations NER LOC (топ-8)
     * @param risks предложения с триггерами
     * @param numbers числа с контекстом
     */
    fun buildAll(
        topQuotes: List<LexRankSummarizer.ScoredSentence>,
        topQuotesByOrder: List<LexRankSummarizer.ScoredSentence>,
        persons: List<NerEntity>,
        organizations: List<NerEntity>,
        locations: List<NerEntity>,
        risks: List<RiskKeywordMatcher.RiskMatch>,
        numbers: List<NumberExtractor.NumberWithContext>,
    ): List<PromptSpec> = listOf(
        buildTopicPrompt(topQuotesByOrder.take(8)),
        buildParticipantsPrompt(persons.take(12), organizations.take(10)),
        buildTopicsPrompt(topQuotesByOrder.take(20)),
        buildRisksPrompt(risks.take(12), topQuotesByOrder),
        buildNumbersPrompt(numbers.take(15)),
        // Conclusions требует результаты предыдущих разделов — строится отдельно после генерации
    )

    /**
     * Промпт для финального раздела "Выводы" — собирается ПОСЛЕ того, как
     * получены ответы тема/темы/риски (нужен их текст).
     */
    fun buildConclusionsPrompt(topicAnswer: String, topicsAnswer: String, risksAnswer: String): PromptSpec =
        PromptSpec(
            sectionId = "conclusions",
            sectionTitle = "6. Выводы аудитора",
            systemMessage = SYSTEM_PROMPT,
            userMessage = """Уже сформулированные результаты:

ТЕМА: $topicAnswer

ТЕМЫ:
$topicsAnswer

РИСКИ:
$risksAnswer

Сформулируй 3-5 кратких выводов аудитора. На что обратить внимание дальше?""",
            maxTokens = 500,
            temperature = 0.3f, // чуть выше для выводов
        )

    private fun buildTopicPrompt(topQuotes: List<LexRankSummarizer.ScoredSentence>): PromptSpec {
        val ctx = topQuotes.joinToString("\n") { "- ${it.sentence}" }
        return PromptSpec(
            sectionId = "topic",
            sectionTitle = "1. Тема встречи",
            systemMessage = SYSTEM_PROMPT,
            userMessage = """Перед тобой 8 ключевых фрагментов из аудиторского интервью на АЭС:

$ctx

Опиши в 2-3 предложениях: о чём это интервью и кто главные действующие лица?""",
            maxTokens = 300,
        )
    }

    private fun buildParticipantsPrompt(
        persons: List<NerEntity>,
        organizations: List<NerEntity>,
    ): PromptSpec {
        val sb = StringBuilder()
        sb.append("Имена из интервью (кол-во упоминаний):\n")
        for (p in persons) sb.append("- ${p.text} (${p.count}x)\n")
        sb.append("\nОрганизации:\n")
        for (o in organizations) sb.append("- ${o.text} (${o.count}x)\n")
        sb.append("\nПеречисли главных участников и их роль в проекте АЭС. ")
        sb.append("Если роль не ясна — пометь 'роль не указана'. Не выдумывай.")
        return PromptSpec(
            sectionId = "participants",
            sectionTitle = "2. Участники",
            systemMessage = SYSTEM_PROMPT,
            userMessage = sb.toString(),
            maxTokens = 500,
        )
    }

    private fun buildTopicsPrompt(topQuotes: List<LexRankSummarizer.ScoredSentence>): PromptSpec {
        val ctx = topQuotes.joinToString("\n") { "- ${it.sentence}" }
        return PromptSpec(
            sectionId = "topics",
            sectionTitle = "3. Ключевые темы",
            systemMessage = SYSTEM_PROMPT,
            userMessage = """Ключевые фрагменты интервью аудитора:

$ctx

Перечисли 5-7 главных тем, которые обсуждаются. Каждая — отдельный пункт списка.""",
            maxTokens = 600,
        )
    }

    private fun buildRisksPrompt(
        risks: List<RiskKeywordMatcher.RiskMatch>,
        fallbackQuotes: List<LexRankSummarizer.ScoredSentence>,
    ): PromptSpec {
        val ctx = if (risks.isNotEmpty()) {
            risks.joinToString("\n") { "- ${it.sentence}" }
        } else {
            fallbackQuotes.drop(10).take(15).joinToString("\n") { "- ${it.sentence}" }
        }
        return PromptSpec(
            sectionId = "risks",
            sectionTitle = "4. Риски и проблемы",
            systemMessage = SYSTEM_PROMPT,
            userMessage = """Фрагменты интервью с упоминанием проблем:

$ctx

Перечисли выявленные риски и проблемы. Каждый — отдельный пункт.""",
            maxTokens = 600,
        )
    }

    private fun buildNumbersPrompt(numbers: List<NumberExtractor.NumberWithContext>): PromptSpec {
        val ctx = numbers.joinToString("\n") { "- ...${it.context}..." }
        return PromptSpec(
            sectionId = "numbers",
            sectionTitle = "5. Конкретные данные",
            systemMessage = SYSTEM_PROMPT,
            userMessage = """Цифры с контекстом из интервью:

$ctx

Перечисли все упомянутые цифры и поясни значение. Формат: 'число — пояснение'.""",
            maxTokens = 600,
        )
    }
}
