package app.protocolvoice.asr

/**
 * Режим выбора количества спикеров для распознавания.
 *
 * - [Single] — один спикер. Диаризация полностью пропускается. Самый быстрый режим.
 *   Аудио целиком отдаётся в ASR одним сегментом со speakerId=0.
 *
 * - [Fixed] — фиксированное количество спикеров (2/3/4). Диаризация выполняется,
 *   но clustering получает явный numClusters вместо автоопределения. Это:
 *     * чуть быстрее автоопределения (k-means на N центров вместо итеративного слияния)
 *     * стабильнее по качеству (нельзя ошибиться с количеством)
 *   Главный затык — embedding-модель — выполняется так же.
 *
 * - [Auto] — текущее поведение. numClusters = -1, алгоритм сам решает.
 *   Может ошибаться с количеством, но удобно когда не знаешь сколько спикеров.
 */
sealed class SpeakerCountMode {
    data object Single : SpeakerCountMode()
    data class Fixed(val count: Int) : SpeakerCountMode() {
        init { require(count in 2..4) { "Fixed count must be 2..4, got $count" } }
    }
    data object Auto : SpeakerCountMode()

    /** Короткая ASCII-метка для UI. Локализованная метка формируется в UI-слое
     *  (InterviewScreen.kt) через stringResource — сама модель не зависит от Context. */
    val label: String
        get() = when (this) {
            Single   -> "1"
            is Fixed -> count.toString()
            Auto     -> "AUTO"
        }

    companion object {
        /** Все доступные варианты в порядке для UI: 1, 2, 3, 4, Авто.
         *  Get-функция, не val — иначе при инициализации companion object
         *  ссылки на data object Single/Auto могут быть ещё null. */
        val all: List<SpeakerCountMode>
            get() = listOf(
                Single,
                Fixed(2),
                Fixed(3),
                Fixed(4),
                Auto,
            )
    }
}
