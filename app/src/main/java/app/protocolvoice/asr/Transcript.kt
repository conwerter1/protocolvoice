package app.protocolvoice.asr

/**
 * Модели данных для протокола интервью.
 * Они независимы от sherpa-onnx API — это плоская структура для UI и DOCX.
 */

/**
 * Один сегмент непрерывной речи одного спикера.
 *
 * @param speakerId Внутренний идентификатор спикера (0, 1, 2…). Имена назначаются
 *                  пользователем отдельно через [Participants].
 * @param startMs Начало сегмента от старта записи в миллисекундах.
 * @param endMs Конец сегмента от старта записи в миллисекундах.
 * @param words Слова с уверенностью распознавания.
 */
data class TranscriptSegment(
    val speakerId: Int,
    val startMs: Long,
    val endMs: Long,
    val words: List<TranscriptWord>,
) {
    /** Полный текст сегмента (без разметки). */
    val text: String get() = words.joinToString(" ") { it.text }

    /** Длительность сегмента в миллисекундах. */
    val durationMs: Long get() = endMs - startMs

    /** Минимальный confidence среди всех слов сегмента. */
    val minConfidence: Float
        get() = if (words.isEmpty()) 1f else words.minOf { it.confidence }

    /** Средний confidence по словам сегмента. */
    val avgConfidence: Float
        get() = if (words.isEmpty()) 1f else words.map { it.confidence }.average().toFloat()
}

/**
 * Одно слово с метаданными.
 *
 * @param text Сам текст слова.
 * @param startMs Время начала слова от старта записи (для подсветки).
 * @param endMs Время конца слова.
 * @param confidence Уверенность распознавания [0..1]. Для CTC-моделей это
 *                   среднее softmax-значение по фреймам слова.
 */
data class TranscriptWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
) {
    /** Категория уверенности — для цветовой разметки в DOCX. */
    val confidenceLevel: ConfidenceLevel
        get() = when {
            confidence >= 0.85f -> ConfidenceLevel.HIGH
            confidence >= 0.65f -> ConfidenceLevel.MEDIUM
            else                -> ConfidenceLevel.LOW
        }
}

/**
 * Уровень уверенности — определяет цвет слова в DOCX.
 *  HIGH   — обычный чёрный текст.
 *  MEDIUM — оранжевый/жёлтый, требует проверки аудитором.
 *  LOW    — красный, скорее всего нрзб., вручную править.
 */
enum class ConfidenceLevel { HIGH, MEDIUM, LOW }

/**
 * Полный результат распознавания всего интервью.
 */
data class InterviewTranscript(
    val segments: List<TranscriptSegment>,
    val totalDurationMs: Long,
    val recordedAt: Long,                  // System.currentTimeMillis()
    val sourceWavPath: String,             // путь к исходному WAV-файлу
    val numSpeakers: Int,                  // фактическое число определённых спикеров
) {
    /** Статистика для отображения в UI. */
    val stats: Stats get() = Stats(
        totalSegments = segments.size,
        totalWords    = segments.sumOf { it.words.size },
        lowConfidenceWords  = segments.sumOf { s -> s.words.count { it.confidenceLevel == ConfidenceLevel.LOW } },
        mediumConfidenceWords = segments.sumOf { s -> s.words.count { it.confidenceLevel == ConfidenceLevel.MEDIUM } },
    )

    data class Stats(
        val totalSegments: Int,
        val totalWords: Int,
        val lowConfidenceWords: Int,
        val mediumConfidenceWords: Int,
    )
}

/**
 * Имена/роли участников интервью. Назначаются пользователем после распознавания —
 * по умолчанию «Спикер 1» / «Speaker 1» и т.д., локаль берётся из strings.xml.
 *
 * Две версии displayName():
 *  - displayName(id) — ASCII fallback («Speaker N»), используется в DOCX/внутреннем коде.
 *  - displayName(id, ctx) — локализованный, используется в UI.
 */
data class Participants(
    val names: Map<Int, String> = emptyMap(),
) {
    /** ASCII fallback — для DOCX/внутреннего кода без Context.
     *  По умолчанию «Спикер N» — DOCX-протокол на русском и это согласуется. */
    fun displayName(speakerId: Int): String =
        names[speakerId] ?: "Спикер ${speakerId + 1}"

    /** Локализованный — берёт из strings.xml по системной локали. */
    fun displayName(speakerId: Int, ctx: android.content.Context): String =
        names[speakerId] ?: ctx.getString(
            app.protocolvoice.R.string.speaker_default_label,
            speakerId + 1,
        )
}

/**
 * Метаданные интервью — попадают в шапку DOCX-протокола.
 */
data class InterviewMetadata(
    val title: String = "",
    val location: String = "",
    val auditorName: String = "",
    val date: Long = System.currentTimeMillis(),
    val notes: String = "",
)
