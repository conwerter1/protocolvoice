package app.protocolvoice.summary.extraction

/**
 * Извлекатель чисел и важных темпоральных/количественных выражений с контекстом.
 *
 * Покрывает три категории:
 *  1. Числа с единицами измерения (20%, 5 млн, 10 тонн, 100 руб, 600 000 руб.)
 *  2. Даты/сроки (12.05.2026, "в понедельник", "до конца года", "в 2024 году")
 *  3. Количественные фразы ("в 10 раз", "в два раза", "пять человек")
 *
 * Используется в default tier как замена LLM для секции "Цифры":
 * вместо того чтобы LLM пыталась объяснить число, мы показываем его в окружении
 * исходного текста — пользователь сам интерпретирует.
 */
object NumberExtractor {

    /**
     * Регулярка для чисел с единицами измерения. Расширено:
     *  - Добавлены: л. (литр), км, кв.м, гр, процент, п.п., кВ, МВт
     *  - Добавлены: недел, месяц, суток, секунд
     *  - Добавлена лира и TL (турецкие лиры)
     */
    private val NUMBER_WITH_UNIT = Regex(
        """\d+[\d\s.,]*\s*(?:%|процент|п\.п\.|млн|млрд|тыс|руб|лир|TL|долл|\${'$'}|€|евро|м[23]|кв\.м|км|тонн|т/ч|кВт|МВт|год[аов]?|кг|гр|чел|раз|штук|шт|мин|час[аов]?|секунд|дн[ейя]|недел|месяц|суток|л\.)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Регулярка для дат и временных маркеров:
     *  - DD.MM.YYYY или DD.MM (даты)
     *  - YYYY год[аов] (2024 год, 2023 года)
     *  - Месяца (январь, февраль, ...)
     *  - Дни недели (понедельник, ...)
     *  - Частые временные обороты (сегодня, вчера, на следующей неделе)
     */
    private val DATE_TIME = Regex(
        """(?:\b\d{1,2}[./]\d{1,2}(?:[./]\d{2,4})?\b|\b(?:19|20)\d{2}\s*год[аов]?|январ[ья]|феврал[ья]|март[ае]?|апрел[ья]|ма[ея]|июн[ья]|июл[ья]|август[ае]?|сентябр[ья]|октябр[ья]|ноябр[ья]|декабр[ья]|в понедельник|во вторник|в среду|в четверг|в пятницу|в субботу|в воскресенье|сегодня|вчера|завтра|позавчера|на следующей|на этой неделе|в этом месяце|в прошлом месяце|в этом квартале|в 1кварт|в [1-4]кв)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Числительные словами: «в два раза», «пять человек», «сто тысяч».
     * Слова-числительные с последующими единицами.
     */
    private val WORD_NUMBERS = Regex(
        """\b(?:один|два|три|четыре|пят[ьи]|шест[ьи]|сем[ьи]|восем[ьи]|девят[ьи]|десят[ьи]|одиннадцат[ьи]|двенадцат[ьи]|двадцат[ьи]|тридцат[ьи]|сорок|пятьдесят|сто|тысяч[аи])\s+(?:раз[аов]?|человек|лет|год[аов]?|дн[ейя]|недель|месяцев?|час[аов]?|минут|процентов|миллионов|тысяч)""",
        RegexOption.IGNORE_CASE,
    )

    /** Левый и правый контекст вокруг числа в символах. */
    private const val LEFT_CONTEXT = 60
    private const val RIGHT_CONTEXT = 50

    /** Категория найденного числа — полезно для группировки в отчёте. */
    enum class NumberCategory {
        AMOUNT,    // с единицей: "20%", "5 млн", "10 раз"
        DATE,      // дата/срок: "12.05.2026", "в понедельник"
        WORD,      // словами: "пять человек"
    }

    data class NumberWithContext(
        /** Само число с единицей, как написано в тексте, например "20%". */
        val match: String,
        /** Кусок исходного текста: ~60 символов слева + число + ~50 справа. */
        val context: String,
        /** Позиция совпадения в исходном тексте (для дедупликации/сортировки). */
        val position: Int,
        /** Категория. */
        val category: NumberCategory = NumberCategory.AMOUNT,
    )

    /**
     * Извлечь все числа и важные количественные/временные выражения из текста.
     * Дедуплицирует по позиции (если два паттерна совпадают на одном месте, берём более ранний из AMOUNT).
     */
    fun extract(text: String, maxResults: Int = 50): List<NumberWithContext> {
        val all = mutableListOf<NumberWithContext>()

        // 1. Числа с единицами
        for (m in NUMBER_WITH_UNIT.findAll(text)) {
            all.add(buildEntry(text, m.value, m.range.first, m.range.last, NumberCategory.AMOUNT))
        }

        // 2. Даты/сроки
        for (m in DATE_TIME.findAll(text)) {
            // Пропускаем если уже есть AMOUNT на этом месте (напр. "2024 год" ловится обоими).
            if (all.any { kotlin.math.abs(it.position - m.range.first) < 5 }) continue
            all.add(buildEntry(text, m.value, m.range.first, m.range.last, NumberCategory.DATE))
        }

        // 3. Числительные словами
        for (m in WORD_NUMBERS.findAll(text)) {
            if (all.any { kotlin.math.abs(it.position - m.range.first) < 5 }) continue
            all.add(buildEntry(text, m.value, m.range.first, m.range.last, NumberCategory.WORD))
        }

        // Сортируем по позиции в тексте (логичный порядок в отчёте).
        return all.sortedBy { it.position }.take(maxResults)
    }

    private fun buildEntry(
        text: String,
        matchValue: String,
        rangeStart: Int,
        rangeLast: Int,
        category: NumberCategory,
    ): NumberWithContext {
        val start = (rangeStart - LEFT_CONTEXT).coerceAtLeast(0)
        val end = (rangeLast + 1 + RIGHT_CONTEXT).coerceAtMost(text.length)
        val ctx = text.substring(start, end)
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return NumberWithContext(matchValue.trim(), ctx, rangeStart, category)
    }
}
