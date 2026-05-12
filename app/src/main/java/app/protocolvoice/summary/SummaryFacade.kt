package app.protocolvoice.summary

import app.protocolvoice.summary.default_tier.DefaultSummaryService
import app.protocolvoice.summary.default_tier.SummaryResult
import app.protocolvoice.summary.ner.NerProvider
import app.protocolvoice.summary.ner.NoOpNerProvider
import app.protocolvoice.summary.ner.SlovnetNerProvider
import app.protocolvoice.summary.pro_tier.LlamaCppBridge
import app.protocolvoice.summary.pro_tier.QVikhrSummaryService
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Главный публичный API саммаризации.
 *
 * Двухтиерная архитектура:
 *  - Default tier: NER (Slovnet) + LexRank + шаблон. ~28 MB моделей, ~5-10 сек на 18k слов.
 *  - PRO tier: + QVikhr 1.5B chat для литературного резюме. +1.0 GB, +3-5 минут.
 *
 * Использование:
 *
 *   // Default без NER (NoOp) — для отладки или fallback:
 *   val facade = SummaryFacade.withoutNer()
 *
 *   // Default с NER (когда модели скачаны):
 *   val facade = SummaryFacade.withSlovnet(navecDir, slovnetDir)
 *
 *   // С PRO tier (когда QVikhr скачана и native lib собрана):
 *   val facade = SummaryFacade.full(navecDir, slovnetDir, filesDir)
 *
 *   // Default
 *   val result = facade.summarizeDefault(transcript)
 *
 *   // PRO (если доступен)
 *   if (facade.isProTierAvailable()) {
 *     facade.summarizePro(result).collect { progress -> ... }
 *   }
 */
class SummaryFacade(
    private val nerProvider: NerProvider = NoOpNerProvider(),
    private val proService: QVikhrSummaryService? = null,
) {

    private val defaultService = DefaultSummaryService(nerProvider)

    /**
     * Сделать резюме default tier.
     * Полностью offline, не требует интернета и LLM.
     */
    fun summarizeDefault(transcript: String): SummaryResult =
        defaultService.summarize(transcript)

    /** Версия с конвертацией структурированного транскрипта. */
    fun summarizeDefault(segments: List<TranscriptSegment>): SummaryResult {
        val plain = segments.joinToString(" ") { it.text }
        return summarizeDefault(plain)
    }

    /**
     * Запустить PRO tier поверх существующего Default результата.
     *
     * Возвращает Flow с прогрессом по разделам.
     * Бросает IllegalStateException если PRO tier недоступен.
     */
    fun summarizePro(defaultResult: SummaryResult): Flow<QVikhrSummaryService.Progress> {
        val service = proService ?: throw IllegalStateException(
            "PRO tier недоступен. Скачайте QVikhr и убедитесь что native library собрана."
        )
        return service.summarize(defaultResult)
    }

    /**
     * Доступен ли PRO tier (т.е. native lib загружена И QVikhr-модель скачана).
     */
    fun isProTierAvailable(): Boolean = proService != null

    /** Освободить ресурсы PRO tier (опционально, при выходе). */
    fun release() {
        proService?.release()
    }

    data class TranscriptSegment(
        val speaker: String?,
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    companion object {
        /**
         * Только Default tier с NER (рекомендуемый минимум).
         */
        fun withSlovnet(navecDir: File, slovnetDir: File): SummaryFacade {
            val provider = SlovnetNerProvider.load(navecDir, slovnetDir)
            return SummaryFacade(provider)
        }

        /**
         * Только Default tier без NER (Sprint 1 fallback / отладка).
         */
        fun withoutNer(): SummaryFacade = SummaryFacade(NoOpNerProvider())

        /**
         * Полная конфигурация: Default + PRO если доступен.
         *
         * @param navecDir путь к navec_extracted/
         * @param slovnetDir путь к slovnet_extracted/
         * @param filesDir application files directory (для поиска QVikhr GGUF)
         */
        fun full(navecDir: File, slovnetDir: File, filesDir: File): SummaryFacade {
            val provider = SlovnetNerProvider.load(navecDir, slovnetDir)
            val pro = QVikhrSummaryService.loadIfAvailable(filesDir)
            return SummaryFacade(provider, pro)
        }

        /**
         * Проверить, можно ли в принципе использовать PRO tier:
         *  1. Native library libllama-android.so должна загружаться
         *  2. QVikhr GGUF должен быть в filesDir
         *
         * Используется в UI для показа disabled-состояния PRO опции.
         */
        fun checkProTierStatus(filesDir: File): ProStatus {
            val hasNative = LlamaCppBridge.isNativeLibraryAvailable()
            val model = QVikhrSummaryService.findModel(filesDir)
            return when {
                !hasNative -> ProStatus.NativeMissing
                model == null -> ProStatus.ModelNotDownloaded
                else -> ProStatus.Available
            }
        }
    }

    /**
     * Статус PRO tier для UI.
     */
    enum class ProStatus {
        /** Native library не собрана (compile-time issue). */
        NativeMissing,
        /** QVikhr GGUF не скачан в filesDir/models/qvikhr/. */
        ModelNotDownloaded,
        /** Всё готово, можно использовать. */
        Available,
    }
}
