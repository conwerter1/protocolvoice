package app.protocolvoice.summary.ner

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Диагностический прогон Kotlin порта Slovnet NER против Python ground truth.
 *
 * Цель: найти первое расхождение между Kotlin inference и эталонным numpy
 * inference на тестовом предложении "Александра Костюченко работает в компании Росатом ."
 *
 * Используется ground truth из assets/debug/:
 *   - input_emb.bin (FloatArray [7, 330]) — эмбеддинги из эталонного Python
 *   - expected_conv1_post_relu.bin, expected_norm1.bin, ... — эталоны каждого слоя
 *   - expected_emiss.bin — emissions перед CRF
 *   - expected_tags.bin (IntArray [7]) — финальные теги
 *
 * Результат пишется в filesDir/slovnet_diag.txt — можно достать через adb pull.
 */
object NerDiagnostics {

    private const val TAG = "NerDiag"
    private const val N_TOKENS = 7
    private const val INPUT_DIM = 330
    private const val L1_OUT = 256
    private const val L2_OUT = 128
    private const val L3_OUT = 64
    private const val NUM_TAGS = 8
    private const val EPS = 1e-3f

    /**
     * Запустить диагностику. Безопасно для повторных вызовов — каждый запуск
     * полностью перезаписывает файл результата.
     *
     * @param context для доступа к assets/ и filesDir/
     * @param ner предзагруженный SlovnetNer (загружен через SummaryFacade.withSlovnet)
     */
    fun run(context: Context, ner: SlovnetNer) {
        val sb = StringBuilder()
        fun log(s: String = "") {
            sb.appendLine(s)
            Log.i(TAG, s)
        }

        log("=== Slovnet NER Kotlin port — diagnostic against Python ground truth ===")
        log("Sentence: Александра Костюченко работает в компании Росатом .")
        log("Expected tags: [B-PER, I-PER, O, O, O, B-ORG, O]")
        log()

        try {
            // ===== Загрузка ground truth =====
            val input = loadFloatAsset(context, "debug/input_emb.bin", N_TOKENS * INPUT_DIM)
            val expConv1 = loadFloatAsset(context, "debug/expected_conv1_post_relu.bin", N_TOKENS * L1_OUT)
            val expNorm1 = loadFloatAsset(context, "debug/expected_norm1.bin", N_TOKENS * L1_OUT)
            val expConv2 = loadFloatAsset(context, "debug/expected_conv2_post_relu.bin", N_TOKENS * L2_OUT)
            val expNorm2 = loadFloatAsset(context, "debug/expected_norm2.bin", N_TOKENS * L2_OUT)
            val expConv3 = loadFloatAsset(context, "debug/expected_conv3_post_relu.bin", N_TOKENS * L3_OUT)
            val expNorm3 = loadFloatAsset(context, "debug/expected_norm3.bin", N_TOKENS * L3_OUT)
            val expEmiss = loadFloatAsset(context, "debug/expected_emiss.bin", N_TOKENS * NUM_TAGS)
            val expTags = loadIntAsset(context, "debug/expected_tags.bin", N_TOKENS)

            log("[Load] OK. Input sum=${"%.4f".format(input.sum())} mean=${"%.6f".format(input.average())}")
            log("       expConv1 sum=${"%.4f".format(expConv1.sum())}")
            log("       expNorm1 sum=${"%.4f".format(expNorm1.sum())}")
            log("       expEmiss sum=${"%.4f".format(expEmiss.sum())}")
            log("       expTags  = ${expTags.toList()}")
            log()

            // ===== Forward pass с захватом промежутков =====
            val dump = SlovnetNer.Intermediates()
            val tags = ner.forwardFromEmbeddings(input, N_TOKENS, dump)
            log("[Forward] complete")
            log()

            // ===== Сверка слой за слоем =====
            log("--- Layer-by-layer comparison ---")
            compare(sb, "conv1+relu", dump.conv1PostRelu, expConv1)
            compare(sb, "norm1     ", dump.norm1, expNorm1)
            compare(sb, "conv2+relu", dump.conv2PostRelu, expConv2)
            compare(sb, "norm2     ", dump.norm2, expNorm2)
            compare(sb, "conv3+relu", dump.conv3PostRelu, expConv3)
            compare(sb, "norm3     ", dump.norm3, expNorm3)
            compare(sb, "emissions ", dump.emissions, expEmiss)

            log()
            log("--- Final tags ---")
            log("Kotlin:   ${tags.toList()}")
            log("Expected: ${expTags.toList()}")
            val tagsMatch = tags.contentEquals(expTags)
            log("MATCH: $tagsMatch")
            if (!tagsMatch) {
                log()
                log("--- Emissions table (Kotlin) ---")
                val tagVocab = listOf("<pad>", "O", "B-PER", "I-PER", "B-LOC", "I-LOC", "B-ORG", "I-ORG")
                val tokens = listOf("Александра", "Костюченко", "работает", "в", "компании", "Росатом", ".")
                for (i in 0 until N_TOKENS) {
                    val row = (0 until NUM_TAGS).map { dump.emissions[i * NUM_TAGS + it] }
                    val argmaxKotlin = row.indices.maxBy { row[it] }
                    val expRow = (0 until NUM_TAGS).map { expEmiss[i * NUM_TAGS + it] }
                    val argmaxExp = expRow.indices.maxBy { expRow[it] }
                    log("  ${tokens[i].padEnd(12)} kotlinArgmax=${tagVocab[argmaxKotlin]} expectedArgmax=${tagVocab[argmaxExp]}")
                    log("    K: ${row.joinToString { "%.3f".format(it) }}")
                    log("    P: ${expRow.joinToString { "%.3f".format(it) }}")
                }
            }
        } catch (e: Throwable) {
            log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            log("STACK:")
            log(e.stackTraceToString().take(3000))
        }

        // Запись в файл
        val outFile = File(context.filesDir, "slovnet_diag.txt")
        outFile.writeText(sb.toString(), Charsets.UTF_8)
        Log.i(TAG, "Diagnostic written to ${outFile.absolutePath}")
    }

    private fun loadFloatAsset(ctx: Context, path: String, expected: Int): FloatArray {
        val bytes = ctx.assets.open(path).use { it.readBytes() }
        require(bytes.size == expected * 4) {
            "$path: ${bytes.size} bytes, expected ${expected * 4}"
        }
        val out = FloatArray(expected)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private fun loadIntAsset(ctx: Context, path: String, expected: Int): IntArray {
        val bytes = ctx.assets.open(path).use { it.readBytes() }
        require(bytes.size == expected * 4)
        val out = IntArray(expected)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out)
        return out
    }

    private fun compare(sb: StringBuilder, name: String, actual: FloatArray, expected: FloatArray) {
        require(actual.size == expected.size) {
            "$name: shape mismatch actual=${actual.size}, expected=${expected.size}"
        }
        var maxDiff = 0f
        var maxDiffIdx = -1
        var sumSqErr = 0.0
        for (i in actual.indices) {
            val d = abs(actual[i] - expected[i])
            if (d > maxDiff) { maxDiff = d; maxDiffIdx = i }
            val diff = (actual[i] - expected[i]).toDouble()
            sumSqErr += diff * diff
        }
        val rmse = sqrt(sumSqErr / actual.size)
        val matches = maxDiff <= EPS
        val status = if (matches) "OK " else "BAD"
        val s1 = "  [$status] $name maxDiff=${"%.5f".format(maxDiff)} (idx=$maxDiffIdx) RMSE=${"%.5f".format(rmse)} actualSum=${"%.3f".format(actual.sum())} expectedSum=${"%.3f".format(expected.sum())}"
        sb.appendLine(s1)
        Log.i(TAG, s1)
        if (!matches) {
            val perTok = actual.size / N_TOKENS
            var shown = 0
            for (i in actual.indices) {
                val d = abs(actual[i] - expected[i])
                if (d > EPS) {
                    val tIdx = i / perTok
                    val cIdx = i % perTok
                    val s2 = "       [tok=$tIdx, ch=$cIdx] K=${"%.5f".format(actual[i])} P=${"%.5f".format(expected[i])} diff=${"%.5f".format(d)}"
                    sb.appendLine(s2)
                    Log.i(TAG, s2)
                    if (++shown >= 8) {
                        sb.appendLine("       ... (showing first 8 only)")
                        break
                    }
                }
            }
        }
    }

    private fun FloatArray.sum(): Float {
        var s = 0f
        for (v in this) s += v
        return s
    }

    private fun FloatArray.average(): Float = sum() / size
}
