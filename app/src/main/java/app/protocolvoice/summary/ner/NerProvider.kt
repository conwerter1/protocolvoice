package app.protocolvoice.summary.ner

import app.protocolvoice.summary.default_tier.NerEntity

/**
 * Интерфейс для NER-провайдера.
 *
 * В Sprint 1 используется [NoOpNerProvider] — возвращает пустой результат, чтобы
 * сквозной пайплайн работал без NER. В Sprint 2 будет добавлен SlovnetNerProvider
 * с настоящим инференсом.
 */
interface NerProvider {

    /**
     * Описание провайдера (попадает в шапку markdown).
     */
    val description: String

    /**
     * Извлечь сущности из текста.
     */
    fun extract(text: String): Result

    data class Result(
        val persons: List<NerEntity>,
        val organizations: List<NerEntity>,
        val locations: List<NerEntity>,
        val providerDescription: String,
    )
}

/**
 * Stub-реализация для Sprint 1: возвращает пустые списки.
 * Цель — проверить сквозной пайплайн без NER-инфраструктуры.
 */
class NoOpNerProvider : NerProvider {
    override val description: String =
        "LexRank + правила (без NER, Sprint 1)"

    override fun extract(text: String): NerProvider.Result =
        NerProvider.Result(
            persons = emptyList(),
            organizations = emptyList(),
            locations = emptyList(),
            providerDescription = description,
        )
}
