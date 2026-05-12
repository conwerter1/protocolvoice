package app.protocolvoice.summary.lexrank

/**
 * Высокоуровневый интерфейс LexRank: предложения → ранжированный score.
 *
 * Шаги:
 *  1. TF-IDF векторизация всех предложений
 *  2. Cosine similarity матрица (только пары с similarity > threshold)
 *  3. PageRank power iteration
 *  4. (Опционально) boost за содержание сущностей и чисел
 *
 * Возвращает массив ScoredSentence в исходном порядке. Чтобы получить top-K,
 * отсортируйте по score и возьмите top-K, а потом восстановите исходный порядок.
 */
class LexRankSummarizer(
    private val similarityThreshold: Double = 0.10,
    private val damping: Double = 0.85,
    private val pageRankIterations: Int = 50,
    private val pageRankTolerance: Double = 1e-6,
) {

    /**
     * Один результат.
     * @param index исходный индекс предложения
     * @param sentence сам текст
     * @param baseScore чистый PageRank score
     * @param boostedScore base * boost (если был задан)
     */
    data class ScoredSentence(
        val index: Int,
        val sentence: String,
        val baseScore: Double,
        val boostedScore: Double,
    )

    /**
     * Параметры boost'а.
     * @param entityBonus множитель за каждое найденное entity-токен (1.5 + 0.1 * min(count,5))
     * @param numberBonus множитель если в предложении есть цифры (1.3)
     * @param idealMinWords минимум слов для бонуса длины (15)
     * @param idealMaxWords максимум для бонуса длины (60)
     * @param tooShortPenalty штраф за слишком короткие (<10 слов): 0.5
     */
    data class BoostConfig(
        val entityTokens: Set<String> = emptySet(),
        val entityBaseMultiplier: Double = 1.5,
        val entityPerEntity: Double = 0.1,
        val entityCap: Int = 5,
        val numberMultiplier: Double = 1.3,
        val idealMinWords: Int = 15,
        val idealMaxWords: Int = 60,
        val idealLengthMultiplier: Double = 1.2,
        val tooShortThreshold: Int = 10,
        val tooShortPenalty: Double = 0.5,
    )

    /**
     * Полный пайплайн ранжирования.
     */
    fun rank(
        sentences: List<String>,
        boost: BoostConfig? = null,
        vectorizer: TfIdfVectorizer = TfIdfVectorizer(),
    ): List<ScoredSentence> {
        if (sentences.isEmpty()) return emptyList()
        if (sentences.size == 1) {
            return listOf(ScoredSentence(0, sentences[0], 1.0, 1.0))
        }

        // 1. TF-IDF
        val matrix = vectorizer.fitTransform(sentences)
        val n = matrix.nRows

        // 2. Similarity matrix (dense, потому что pageRank работает с dense)
        val sim = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val s = matrix.dot(i, j)
                if (s >= similarityThreshold) {
                    sim[i][j] = s
                    sim[j][i] = s
                }
            }
        }

        // 3. PageRank
        val baseScores = PageRank.compute(
            sim, damping, pageRankIterations, pageRankTolerance,
        )

        // 4. Boost (если задан)
        return List(n) { i ->
            val sentence = sentences[i]
            val base = baseScores[i]
            val boosted = if (boost != null) base * computeBoost(sentence, boost) else base
            ScoredSentence(i, sentence, base, boosted)
        }
    }

    private fun computeBoost(sentence: String, cfg: BoostConfig): Double {
        var b = 1.0
        val sl = sentence.lowercase()
        // Entity boost
        if (cfg.entityTokens.isNotEmpty()) {
            val nFound = cfg.entityTokens.count { it in sl }
            if (nFound > 0) {
                b *= cfg.entityBaseMultiplier +
                        cfg.entityPerEntity * kotlin.math.min(nFound, cfg.entityCap)
            }
        }
        // Number boost
        if (NUM_REGEX.containsMatchIn(sentence)) b *= cfg.numberMultiplier
        // Length boost/penalty
        val wc = sentence.split(WHITESPACE_REGEX).size
        when {
            wc in cfg.idealMinWords..cfg.idealMaxWords -> b *= cfg.idealLengthMultiplier
            wc < cfg.tooShortThreshold -> b *= cfg.tooShortPenalty
        }
        return b
    }

    private companion object {
        val NUM_REGEX = Regex("\\d")
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}

/**
 * Утилита: взять top-K из ранжирования и вернуть в порядке появления в тексте.
 */
fun List<LexRankSummarizer.ScoredSentence>.topKInOrder(
    k: Int,
    byBoosted: Boolean = true,
): List<LexRankSummarizer.ScoredSentence> {
    if (k <= 0 || isEmpty()) return emptyList()
    val sorted = if (byBoosted) sortedByDescending { it.boostedScore } else sortedByDescending { it.baseScore }
    val topK = sorted.take(k.coerceAtMost(size))
    return topK.sortedBy { it.index }
}

/**
 * Утилита: top-K просто по score (для "ключевых цитат").
 */
fun List<LexRankSummarizer.ScoredSentence>.topKByScore(
    k: Int,
    byBoosted: Boolean = true,
): List<LexRankSummarizer.ScoredSentence> {
    if (k <= 0 || isEmpty()) return emptyList()
    val sorted = if (byBoosted) sortedByDescending { it.boostedScore } else sortedByDescending { it.baseScore }
    return sorted.take(k.coerceAtMost(size))
}
