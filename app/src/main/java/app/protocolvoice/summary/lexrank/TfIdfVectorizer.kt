package app.protocolvoice.summary.lexrank

import kotlin.math.ln

/**
 * Простой TF-IDF векторизатор для русского текста.
 * Аналог sklearn TfidfVectorizer с базовыми настройками.
 *
 * Возвращает вектора в SPARSE формате (Map<termIdx, value>) — это эффективнее
 * для больших словарей, где большинство значений нулевые.
 *
 * Особенности:
 *  - Нижний регистр
 *  - Мин. длина токена 3 символа
 *  - Стоп-слова + min_df + max_df фильтрация
 *  - L2-нормализация выходных векторов (чтобы cosine similarity = dot product)
 */
class TfIdfVectorizer(
    private val stopwords: Set<String> = DEFAULT_STOPWORDS,
    private val minDf: Int = 2,
    private val maxDfRatio: Double = 0.85,
    private val minTokenLen: Int = 3,
) {
    /** Словарь: term → index. Заполняется в [fit]. */
    var vocabulary: Map<String, Int> = emptyMap()
        private set

    /** IDF веса по индексу терма. */
    private var idf: DoubleArray = DoubleArray(0)

    /** Размер словаря после fit. */
    val vocabularySize: Int get() = vocabulary.size

    /**
     * Регулярка для токенизации: слова из букв/дефисов длиной от minTokenLen.
     * Точно такая же, как в Test E python: re.findall(r"[\w-]+", s.lower())
     */
    private val tokenRegex = Regex("[\\p{L}\\p{Nd}-]+")

    private fun tokenize(text: String): List<String> {
        return tokenRegex.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= minTokenLen && it !in stopwords }
            .toList()
    }

    /**
     * Обучить векторизатор и вернуть TF-IDF матрицу (sparse).
     */
    fun fitTransform(documents: List<String>): SparseMatrix {
        val nDocs = documents.size

        // 1. Подсчёт document frequency
        val rawTokens: List<List<String>> = documents.map { tokenize(it) }
        val df = HashMap<String, Int>()
        for (tokens in rawTokens) {
            for (t in tokens.toSet()) {
                df[t] = (df[t] ?: 0) + 1
            }
        }

        // 2. Фильтрация по min_df и max_df
        val maxDf = (maxDfRatio * nDocs).toInt().coerceAtLeast(minDf)
        val keptTerms = df.entries
            .filter { it.value >= minDf && it.value <= maxDf }
            .map { it.key }
            .sorted() // детерминированный порядок

        vocabulary = keptTerms.withIndex().associate { (i, t) -> t to i }
        idf = DoubleArray(keptTerms.size).also { arr ->
            for ((t, idx) in vocabulary) {
                val dfT = df[t]!!.toDouble()
                // sklearn IDF: ln((1 + n) / (1 + df)) + 1, smooth_idf=True
                arr[idx] = ln((1.0 + nDocs) / (1.0 + dfT)) + 1.0
            }
        }

        // 3. Построение TF-IDF матрицы
        val rows = ArrayList<HashMap<Int, Double>>(nDocs)
        for (tokens in rawTokens) {
            val tf = HashMap<Int, Int>()
            for (t in tokens) {
                val idx = vocabulary[t] ?: continue
                tf[idx] = (tf[idx] ?: 0) + 1
            }
            val row = HashMap<Int, Double>(tf.size)
            // sklearn использует raw TF * IDF (без log)
            for ((idx, count) in tf) {
                row[idx] = count.toDouble() * idf[idx]
            }
            // L2 нормализация
            l2Normalize(row)
            rows.add(row)
        }

        return SparseMatrix(rows, vocabulary.size)
    }

    private fun l2Normalize(row: HashMap<Int, Double>) {
        var sumSq = 0.0
        for (v in row.values) sumSq += v * v
        if (sumSq <= 0.0) return
        val norm = kotlin.math.sqrt(sumSq)
        for (k in row.keys.toList()) {
            row[k] = row[k]!! / norm
        }
    }

    companion object {
        /**
         * Базовый набор стоп-слов из Test E (отбрасываем местоимения,
         * частицы и общеупотребительные служебные слова).
         */
        val DEFAULT_STOPWORDS: Set<String> = setOf(
            "это", "что", "как", "все", "ещё", "или", "для", "над", "под", "при", "ну",
            "там", "тут", "вот", "так", "уже", "чтобы", "если", "потом", "тоже", "тогда",
            "даже", "сейчас", "теперь", "потому", "которые", "который", "которая", "которое",
            "когда", "можно", "нужно", "надо", "есть", "быть", "был", "была", "были",
            "будет", "будут", "нет", "да", "эт", "всё", "всех", "всем", "нам", "них",
            "мне", "меня", "нас", "ним", "ней", "свой", "свою", "свои", "своих", "его",
            "её", "ее", "там", "тоже", "также", "от", "до", "на", "за", "по", "в", "к",
            "с", "о", "об", "из", "у", "и", "а", "но", "не", "же", "ли", "бы", "то",
            "они", "оно", "она", "он", "моя", "мой", "моё", "моих", "ваш", "наш", "эта",
            "эти", "этих", "этого", "этому", "этой", "эту", "себя", "себе", "собой",
            "значит", "говорит",
        )
    }
}

/**
 * Разреженная матрица TF-IDF: список строк, каждая — мапа termIdx→tfidf.
 * Для скалярного произведения / cosine similarity достаточно.
 */
class SparseMatrix(
    val rows: List<Map<Int, Double>>,
    val nCols: Int,
) {
    val nRows: Int get() = rows.size

    /**
     * Точечное произведение строки i на строку j.
     * Поскольку строки уже L2-нормализованы, dot = cosine similarity.
     */
    fun dot(i: Int, j: Int): Double {
        val a = rows[i]
        val b = rows[j]
        // Перебираем меньшую из двух
        val (small, large) = if (a.size <= b.size) a to b else b to a
        var sum = 0.0
        for ((k, v) in small) {
            val u = large[k] ?: continue
            sum += v * u
        }
        return sum
    }
}
