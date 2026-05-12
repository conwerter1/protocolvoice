package app.protocolvoice.summary.pro_tier

import android.util.Log
import app.protocolvoice.summary.default_tier.SummaryResult
import app.protocolvoice.summary.default_tier.SummaryTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Сервис генерации литературного резюме через QVikhr 1.5B (PRO tier).
 *
 * Работает поверх результата Default tier (NER + LexRank уже выполнены) —
 * берёт оттуда top-30 предложений, NER сущности, риски, числа,
 * и генерирует 6 разделов через 6 раздельных промптов к QVikhr.
 *
 * Алгоритм валидирован Test E v3 FINAL: 3.4 минуты на стенограмму 18k слов
 * на PC, на Xiaomi 12T ожидается ~5-7 минут.
 *
 * UI должен подписаться на [summarize] (возвращает Flow), чтобы показывать
 * прогресс по разделам ("Генерация раздела 3/6...").
 */
class QVikhrSummaryService(
    private val bridge: LlamaCppBridge,
    private val modelPath: String,
) {

    /**
     * Прогресс генерации.
     */
    sealed class Progress {
        /** Загрузка модели. */
        object Loading : Progress()
        /** Готовится к генерации раздела N из total. */
        data class Section(val current: Int, val total: Int, val sectionTitle: String) : Progress()
        /** Финальный результат. */
        data class Done(val result: SummaryResult) : Progress()
        /** Ошибка. */
        data class Error(val message: String, val cause: Throwable?) : Progress()
    }

    /**
     * Запустить полный PRO-pipeline.
     *
     * @param defaultResult результат Default tier (NER + LexRank уже выполнены)
     */
    fun summarize(defaultResult: SummaryResult): Flow<Progress> = flow {
        emit(Progress.Loading)
        try {
            // 1. Загрузка модели (~1-2 сек на современном устройстве)
            if (!bridge.isLoaded) {
                val ok = bridge.load(modelPath)
                if (!ok) {
                    emit(Progress.Error("Failed to load QVikhr model", null))
                    return@flow
                }
            }

            // 2. Построить 5 первых промптов (без выводов — он зависит от других)
            val prompts = QVikhrPromptBuilder.buildAll(
                topQuotes = defaultResult.topQuotes,
                topQuotesByOrder = defaultResult.keyFragments,
                persons = defaultResult.persons,
                organizations = defaultResult.organizations,
                locations = defaultResult.locations,
                risks = defaultResult.risks,
                numbers = defaultResult.numbers,
            )
            val totalSections = prompts.size + 1 // +1 для conclusions

            val answers = HashMap<String, String>()

            // 3. Сгенерировать каждый раздел
            for ((idx, prompt) in prompts.withIndex()) {
                emit(Progress.Section(idx + 1, totalSections, prompt.sectionTitle))
                val text = bridge.chat(
                    systemMessage = prompt.systemMessage,
                    userMessage = prompt.userMessage,
                    maxTokens = prompt.maxTokens,
                    temperature = prompt.temperature,
                )
                answers[prompt.sectionId] = text.trim()
            }

            // 4. Conclusions — последний промпт, использует ответы предыдущих
            val concPrompt = QVikhrPromptBuilder.buildConclusionsPrompt(
                topicAnswer = answers["topic"] ?: "",
                topicsAnswer = answers["topics"] ?: "",
                risksAnswer = answers["risks"] ?: "",
            )
            emit(Progress.Section(totalSections, totalSections, concPrompt.sectionTitle))
            val concText = bridge.chat(
                systemMessage = concPrompt.systemMessage,
                userMessage = concPrompt.userMessage,
                maxTokens = concPrompt.maxTokens,
                temperature = concPrompt.temperature,
            )
            answers[concPrompt.sectionId] = concText.trim()

            // 5. Собрать финальный markdown с PRO-разделами добавленными к default
            val proMd = buildProMarkdown(defaultResult, answers)

            val proResult = defaultResult.copy(
                tier = SummaryTier.PRO,
                markdown = proMd,
                proSections = answers,
            )
            emit(Progress.Done(proResult))
        } catch (e: Exception) {
            Log.e(TAG, "PRO tier failed", e)
            emit(Progress.Error(e.message ?: "Unknown error", e))
        }
    }

    private fun buildProMarkdown(
        defaultResult: SummaryResult,
        answers: Map<String, String>,
    ): String = buildString {
        appendLine("# Резюме интервью (PRO)")
        appendLine()
        appendLine("**Метод:** Default tier (NER + LexRank) + QVikhr 1.5B Q5 (литературное резюме)")
        appendLine("**Источник:** ${defaultResult.transcriptWords} слов, ${defaultResult.sentencesCount} предложений")
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("# 📝 Литературное резюме")
        appendLine()
        appendLine("## 1. Тема встречи")
        appendLine()
        appendLine(answers["topic"] ?: "_не сгенерировано_")
        appendLine()
        appendLine("## 2. Участники")
        appendLine()
        appendLine(answers["participants"] ?: "_не сгенерировано_")
        appendLine()
        appendLine("## 3. Ключевые темы")
        appendLine()
        appendLine(answers["topics"] ?: "_не сгенерировано_")
        appendLine()
        appendLine("## 4. Риски и проблемы")
        appendLine()
        appendLine(answers["risks"] ?: "_не сгенерировано_")
        appendLine()
        appendLine("## 5. Конкретные данные")
        appendLine()
        appendLine(answers["numbers"] ?: "_не сгенерировано_")
        appendLine()
        appendLine("## 6. Выводы аудитора")
        appendLine()
        appendLine(answers["conclusions"] ?: "_не сгенерировано_")
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("# 📊 Факты (Default tier)")
        appendLine()
        appendLine("Эта часть собрана автоматически из стенограммы — все цитаты дословные.")
        appendLine()
        // Добавляем основные секции из default
        appendLine(defaultResult.markdown.substringAfter("---").substringAfter("---"))
    }

    fun release() = bridge.free()

    companion object {
        private const val TAG = "QVikhrSummaryService"

        /**
         * Получить путь к скачанной QVikhr модели.
         *
         * @param filesDir application files directory
         * @return File или null если модель не скачана
         */
        fun findModel(filesDir: File): File? {
            val expected = File(filesDir, "models/qvikhr/QVikhr-2.5-1.5B-Instruct-r.Q5_K_M.gguf")
            return if (expected.exists() && expected.length() > 100_000_000L) expected else null
        }

        /**
         * Создать сервис, если модель и native library доступны.
         * Иначе возвращает null.
         */
        fun loadIfAvailable(filesDir: File): QVikhrSummaryService? {
            if (!LlamaCppBridge.isNativeLibraryAvailable()) return null
            val model = findModel(filesDir) ?: return null
            return QVikhrSummaryService(LlamaCppBridge(), model.absolutePath)
        }
    }
}
