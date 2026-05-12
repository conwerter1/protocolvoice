package app.protocolvoice.summary.ner

/**
 * Фильтр мусорных "сущностей" из NER на стенограммах разговорной речи.
 *
 * NER модели обучаются на новостных текстах. На стенограммах живой речи
 * они часто помечают как PER междометия типа "Угу", "Э-э", "А-а",
 * потому что они начинаются с заглавной буквы (после автокапитализации
 * от ASR).
 *
 * Этот фильтр выкидывает такие случаи на основе:
 *  1. Списка явных междометий
 *  2. Очень короткие токены (1-2 символа)
 *  3. Повторы гласных через дефис ("Э-э-э", "А-а-а")
 *  4. Слишком короткие "имена" PER (<4 символов)
 *
 * Логика портирована 1-в-1 из Test F (test_f_no_llm.md).
 */
object NerJunkFilter {

    /** Явные междометия из живой речи. */
    private val JUNK: Set<String> = setOf(
        "угу", "ага", "эээ", "э-э", "э-э-э", "аэ", "а-а", "а-а-а",
        "хм", "хмм", "хы", "ну", "ой", "ах", "ох", "ого", "оп",
        "м-м", "ммм", "паша", // "Паша" попадает как PER, а это разговорное обращение к Павлу
        "это", "тут", "там", "вот",
        "та", "то", "те", "ты", "вы", "мы", "он", "она", "они",
        "а", "о", "и", "у", "ы", "э", "я",
        "живи", "акую", "окую", // ASR-артефакты
    )

    /** Только гласные с дефисами: э-э, а-а-а, у-у, и-и-и */
    private val VOWEL_REPEAT_REGEX = Regex("^[аеёиоуыэюя]+(-[аеёиоуыэюя]+)*$")

    /**
     * Является ли строка мусором (для PER/ORG/LOC).
     *
     * @param entity текст entity (нормализованный или сырой — оба варианта проверяем)
     * @param entityType "PER" / "ORG" / "LOC" — для PER применяем дополнительные ограничения
     */
    fun isJunk(entity: String, entityType: String = "PER"): Boolean {
        val n = entity.lowercase().trim()
        if (n.isEmpty()) return true
        if (n.length <= 2) return true
        if (n in JUNK) return true
        if (VOWEL_REPEAT_REGEX.matches(n)) return true

        // Повтор одинаковых букв через дефис: "Э-Э-Э", "У-У"
        if ('-' in n) {
            val parts = n.split('-')
            if (parts.isNotEmpty() && parts.all { it == parts[0] }) return true
        }

        // Для PER: одиночное слово короче 4 символов — скорее всего междометие
        if (entityType == "PER" && ' ' !in n && n.length < 4) return true

        return false
    }

    /**
     * Применить фильтр к карте entity → count.
     */
    fun filter(entities: Map<String, Int>, entityType: String): Map<String, Int> =
        entities.filterKeys { !isJunk(it, entityType) }
}
