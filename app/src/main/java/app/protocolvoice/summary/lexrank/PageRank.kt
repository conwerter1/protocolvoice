package app.protocolvoice.summary.lexrank

import kotlin.math.abs

/**
 * Power iteration PageRank для LexRank.
 *
 * Принимает квадратную матрицу схожести (sparse), запускает итерации до сходимости.
 * Возвращает массив score'ов для каждой вершины.
 *
 * Аналог networkx.pagerank() с настройками по умолчанию.
 */
object PageRank {

    /**
     * Расчёт PageRank.
     *
     * @param similarity квадратная матрица схожести (рёбра больше threshold уже отфильтрованы)
     * @param damping коэффициент демпфирования (обычно 0.85)
     * @param maxIter максимум итераций
     * @param tolerance допуск сходимости (по сумме абсолютных разностей)
     */
    fun compute(
        similarity: Array<DoubleArray>,
        damping: Double = 0.85,
        maxIter: Int = 50,
        tolerance: Double = 1e-6,
    ): DoubleArray {
        val n = similarity.size
        if (n == 0) return DoubleArray(0)

        // Row-normalize (transition matrix M[i][j] = sim[i][j] / sum(sim[i][:]))
        val rowSums = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (j in 0 until n) s += similarity[i][j]
            rowSums[i] = if (s == 0.0) 1.0 else s
        }

        // Стартовое распределение
        var scores = DoubleArray(n) { 1.0 / n }
        val newScores = DoubleArray(n)

        val baseRank = (1.0 - damping) / n

        for (iter in 0 until maxIter) {
            // newScores[j] = baseRank + damping * sum_i ( M[i][j] * scores[i] )
            //              = baseRank + damping * sum_i ( sim[i][j] / rowSums[i] * scores[i] )
            for (j in 0 until n) newScores[j] = baseRank
            for (i in 0 until n) {
                val factor = damping * scores[i] / rowSums[i]
                if (factor == 0.0) continue
                val row = similarity[i]
                for (j in 0 until n) {
                    val w = row[j]
                    if (w != 0.0) newScores[j] += factor * w
                }
            }
            // Проверка сходимости
            var diff = 0.0
            for (i in 0 until n) {
                diff += abs(newScores[i] - scores[i])
                scores[i] = newScores[i]
            }
            if (diff < tolerance) break
        }
        return scores
    }
}
