package app.protocolvoice.summary.ner

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Диагностический тест Kotlin порта Slovnet NER.
 *
 * Зачем: на устройстве Slovnet модели загружаются ОК (`LOAD OK` в status файле),
 * но inference возвращает 0 entities. Эталонный Python на тех же файлах
 * находит «Александра Костюченко» (PER) и «Росатом» (ORG).
 *
 * Этот тест:
 *   1. Загружает веса напрямую из app/src/main/assets/summary/slovnet_ner/arrays/
 *   2. Берёт готовый input_emb.bin (эмбеддинги из эталонного Python для тестового предложения)
 *   3. Прогоняет через те же conv1d / BN / Linear / CRF функции что в проде
 *   4. После каждого слоя сравнивает с expected_*.bin (тоже из эталонного Python)
 *   5. Печатает в лог где первое расхождение
 *
 * Тестовое предложение: "Александра Костюченко работает в компании Росатом ."
 * Эталонные теги: [B-PER, I-PER, O, O, O, B-ORG, O]
 */
class SlovnetDumpTest {

    companion object {
        // Путь к файлам относительно корня модуля `app/`. Unit-тесты запускаются
        // с этим рабочим каталогом.
        private val PROJECT_ROOT = File(System.getProperty("user.dir") ?: ".")
        private val ARRAYS_DIR = File(PROJECT_ROOT, "src/main/assets/summary/slovnet_ner/arrays")
        private val GROUND_TRUTH_DIR = File(PROJECT_ROOT, "src/main/assets/debug")
        private val DUMP_OUT_FILE = File(PROJECT_ROOT, "build/reports/slovnet/kotlin_dump.txt")

        // Архитектура (как в SlovnetNer)
        const val INPUT_DIM = 330
        const val L1_OUT = 256
        const val L2_OUT = 128
        const val L3_OUT = 64
        const val NUM_TAGS = 8
        const val KERNEL = 3
        const val PADDING = 1
        const val N_TOKENS = 7   // длина тестового предложения

        // Маленькая толерантность для float32 сравнений
        const val EPS = 1e-3f
    }

    // ============================================================
    //   Утилиты загрузки бинарных файлов
    // ============================================================

    private fun loadFloat(file: File, expected: Int): FloatArray {
        require(file.exists()) { "Not found: ${file.absolutePath}" }
        val bytes = file.readBytes()
        require(bytes.size == expected * 4) {
            "${file.name}: ${bytes.size} bytes, expected ${expected * 4}"
        }
        val out = FloatArray(expected)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private fun loadInt(file: File, expected: Int): IntArray {
        val bytes = file.readBytes()
        require(bytes.size == expected * 4)
        val out = IntArray(expected)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out)
        return out
    }

    private fun loadWeightFromArrays(idx: Int, expected: Int): FloatArray =
        loadFloat(File(ARRAYS_DIR, "$idx.bin"), expected)

    // ============================================================
    //   Реализации слоёв (КОПИЯ из SlovnetNer.kt, чтобы не трогать прод)
    // ============================================================

    /** Conv1D с kernel=3, padding=1. Input [seqLen * inCh] channels-LAST. */
    private fun conv1d(
        input: FloatArray, seqLen: Int, inCh: Int,
        weight: FloatArray, bias: FloatArray, outCh: Int,
    ): FloatArray {
        val output = FloatArray(seqLen * outCh)
        for (t in 0 until seqLen) {
            for (oc in 0 until outCh) {
                var sum = bias[oc]
                for (dk in 0 until KERNEL) {
                    val srcT = t + dk - PADDING
                    if (srcT < 0 || srcT >= seqLen) continue
                    val inputRowOffset = srcT * inCh
                    for (ic in 0 until inCh) {
                        sum += input[inputRowOffset + ic] *
                            weight[oc * inCh * KERNEL + ic * KERNEL + dk]
                    }
                }
                output[t * outCh + oc] = sum
            }
        }
        return output
    }

    private fun reluInPlace(x: FloatArray) {
        for (i in x.indices) if (x[i] < 0f) x[i] = 0f
    }

    private fun batchNormInPlace(
        x: FloatArray, seqLen: Int, channels: Int,
        gamma: FloatArray, beta: FloatArray, mean: FloatArray, std: FloatArray,
    ) {
        for (t in 0 until seqLen) {
            val offset = t * channels
            for (c in 0 until channels) {
                x[offset + c] = (x[offset + c] - mean[c]) / std[c] * gamma[c] + beta[c]
            }
        }
    }

    private fun linearHead(
        x: FloatArray, n: Int, inDim: Int, outDim: Int,
        weight: FloatArray, bias: FloatArray,
    ): FloatArray {
        val out = FloatArray(n * outDim)
        for (i in 0 until n) {
            val rowOffset = i * inDim
            for (j in 0 until outDim) {
                var sum = bias[j]
                for (k in 0 until inDim) {
                    sum += x[rowOffset + k] * weight[k * outDim + j]
                }
                out[i * outDim + j] = sum
            }
        }
        return out
    }

    private fun crfDecode(emissions: FloatArray, seqLen: Int, crfTrans: FloatArray): IntArray {
        val numTags = NUM_TAGS
        var alpha = FloatArray(numTags)
        val backpointers = Array(seqLen) { IntArray(numTags) }
        for (s in 0 until numTags) alpha[s] = emissions[s]
        val newAlpha = FloatArray(numTags)
        for (t in 1 until seqLen) {
            for (cur in 0 until numTags) {
                var bestScore = Float.NEGATIVE_INFINITY
                var bestPrev = 0
                for (prev in 0 until numTags) {
                    val score = alpha[prev] + crfTrans[prev * numTags + cur] +
                        emissions[t * numTags + cur]
                    if (score > bestScore) { bestScore = score; bestPrev = prev }
                }
                newAlpha[cur] = bestScore
                backpointers[t][cur] = bestPrev
            }
            System.arraycopy(newAlpha, 0, alpha, 0, numTags)
        }
        var bestLast = 0
        var bestScore = alpha[0]
        for (s in 1 until numTags) {
            if (alpha[s] > bestScore) { bestScore = alpha[s]; bestLast = s }
        }
        val path = IntArray(seqLen)
        path[seqLen - 1] = bestLast
        for (t in seqLen - 1 downTo 1) path[t - 1] = backpointers[t][path[t]]
        return path
    }

    // ============================================================
    //   Утилиты сравнения и логгинга
    // ============================================================

    private val log = StringBuilder()

    private fun logLine(s: String = "") {
        println(s)
        log.appendLine(s)
    }

    private fun summarize(name: String, a: FloatArray): String {
        var sum = 0.0
        var maxAbs = 0f
        for (v in a) {
            sum += v
            if (kotlin.math.abs(v) > maxAbs) maxAbs = kotlin.math.abs(v)
        }
        return "$name shape=${a.size} sum=${"%.4f".format(sum)} mean=${"%.6f".format(sum / a.size)} maxAbs=${"%.4f".format(maxAbs)}"
    }

    private fun compare(name: String, actual: FloatArray, expected: FloatArray) {
        require(actual.size == expected.size) {
            "Shape mismatch for $name: actual=${actual.size}, expected=${expected.size}"
        }
        var maxDiff = 0f
        var maxDiffIdx = -1
        var sumSqErr = 0.0
        for (i in actual.indices) {
            val d = kotlin.math.abs(actual[i] - expected[i])
            if (d > maxDiff) { maxDiff = d; maxDiffIdx = i }
            sumSqErr += (actual[i] - expected[i]).toDouble() *
                (actual[i] - expected[i]).toDouble()
        }
        val rmse = kotlin.math.sqrt(sumSqErr / actual.size)
        val matches = actual.all { it.isFinite() } && maxDiff <= EPS
        val status = if (matches) "✓" else "✗"
        logLine("  $status $name: maxDiff=${"%.6f".format(maxDiff)} at idx=$maxDiffIdx, RMSE=${"%.6f".format(rmse)}")
        assertTrue("$name differs from the reference: maxDiff=$maxDiff", matches)
        if (!matches) {
            // Печатаем первые 5 расхождений
            var shown = 0
            for (i in actual.indices) {
                val d = kotlin.math.abs(actual[i] - expected[i])
                if (d > EPS) {
                    val tIdx = i / (actual.size / N_TOKENS)
                    val cIdx = i % (actual.size / N_TOKENS)
                    logLine("    [token=$tIdx, ch=$cIdx]: actual=${"%.6f".format(actual[i])} expected=${"%.6f".format(expected[i])} diff=${"%.6f".format(d)}")
                    if (++shown >= 5) break
                }
            }
        }
    }

    // ============================================================
    //   Главный тест
    // ============================================================

    @Test
    fun dumpAndCompare() {
        logLine("=== Slovnet NER Kotlin port — Layer-by-layer comparison ===")
        logLine("ARRAYS_DIR: ${ARRAYS_DIR.absolutePath}")
        logLine("GROUND_TRUTH_DIR: ${GROUND_TRUTH_DIR.absolutePath}")
        logLine()

        // ========== Шаг 1: Загрузить веса ==========
        logLine("[1] Loading weights...")
        val l1ConvW = loadWeightFromArrays(1, L1_OUT * INPUT_DIM * KERNEL)
        val l1ConvB = loadWeightFromArrays(2, L1_OUT)
        val l1BnW = loadWeightFromArrays(3, L1_OUT)
        val l1BnB = loadWeightFromArrays(4, L1_OUT)
        val l1BnMean = loadWeightFromArrays(5, L1_OUT)
        val l1BnStd = loadWeightFromArrays(6, L1_OUT)
        val l2ConvW = loadWeightFromArrays(7, L2_OUT * L1_OUT * KERNEL)
        val l2ConvB = loadWeightFromArrays(8, L2_OUT)
        val l2BnW = loadWeightFromArrays(9, L2_OUT)
        val l2BnB = loadWeightFromArrays(10, L2_OUT)
        val l2BnMean = loadWeightFromArrays(11, L2_OUT)
        val l2BnStd = loadWeightFromArrays(12, L2_OUT)
        val l3ConvW = loadWeightFromArrays(13, L3_OUT * L2_OUT * KERNEL)
        val l3ConvB = loadWeightFromArrays(14, L3_OUT)
        val l3BnW = loadWeightFromArrays(15, L3_OUT)
        val l3BnB = loadWeightFromArrays(16, L3_OUT)
        val l3BnMean = loadWeightFromArrays(17, L3_OUT)
        val l3BnStd = loadWeightFromArrays(18, L3_OUT)
        val headW = loadWeightFromArrays(19, L3_OUT * NUM_TAGS)
        val headB = loadWeightFromArrays(20, NUM_TAGS)
        val crfTrans = loadWeightFromArrays(21, NUM_TAGS * NUM_TAGS)
        logLine("  l1_w: shape=${l1ConvW.size} (=$L1_OUT*$INPUT_DIM*$KERNEL=${L1_OUT * INPUT_DIM * KERNEL})")
        logLine("  l1_w[0..4]: ${l1ConvW.sliceArray(0..4).toList()}")
        logLine()

        // ========== Шаг 2: Загрузить input и эталоны ==========
        logLine("[2] Loading input embeddings and expected outputs...")
        val input = loadFloat(File(GROUND_TRUTH_DIR, "input_emb.bin"), N_TOKENS * INPUT_DIM)
        val expConv1PostRelu = loadFloat(File(GROUND_TRUTH_DIR, "expected_conv1_post_relu.bin"), N_TOKENS * L1_OUT)
        val expNorm1 = loadFloat(File(GROUND_TRUTH_DIR, "expected_norm1.bin"), N_TOKENS * L1_OUT)
        val expConv2PostRelu = loadFloat(File(GROUND_TRUTH_DIR, "expected_conv2_post_relu.bin"), N_TOKENS * L2_OUT)
        val expNorm2 = loadFloat(File(GROUND_TRUTH_DIR, "expected_norm2.bin"), N_TOKENS * L2_OUT)
        val expConv3PostRelu = loadFloat(File(GROUND_TRUTH_DIR, "expected_conv3_post_relu.bin"), N_TOKENS * L3_OUT)
        val expNorm3 = loadFloat(File(GROUND_TRUTH_DIR, "expected_norm3.bin"), N_TOKENS * L3_OUT)
        val expEmiss = loadFloat(File(GROUND_TRUTH_DIR, "expected_emiss.bin"), N_TOKENS * NUM_TAGS)
        val expTags = loadInt(File(GROUND_TRUTH_DIR, "expected_tags.bin"), N_TOKENS)
        logLine("  ${summarize("input", input)}")
        logLine("  ${summarize("expConv1PostRelu", expConv1PostRelu)}")
        logLine("  ${summarize("expNorm1", expNorm1)}")
        logLine("  ${summarize("expEmiss", expEmiss)}")
        logLine("  expTags: ${expTags.toList()}")
        logLine()

        // ========== Шаг 3: Layer 1 ==========
        logLine("[3] Layer 1: conv1d(330→256) + ReLU + BN")
        var x = conv1d(input, N_TOKENS, INPUT_DIM, l1ConvW, l1ConvB, L1_OUT)
        logLine("  ${summarize("after conv1 (pre-relu)", x)}")
        reluInPlace(x)
        logLine("  ${summarize("after relu1", x)}")
        compare("conv1+relu", x, expConv1PostRelu)
        batchNormInPlace(x, N_TOKENS, L1_OUT, l1BnW, l1BnB, l1BnMean, l1BnStd)
        logLine("  ${summarize("after norm1", x)}")
        compare("norm1", x, expNorm1)
        logLine()

        // ========== Шаг 4: Layer 2 ==========
        logLine("[4] Layer 2: conv1d(256→128) + ReLU + BN")
        x = conv1d(x, N_TOKENS, L1_OUT, l2ConvW, l2ConvB, L2_OUT)
        reluInPlace(x)
        compare("conv2+relu", x, expConv2PostRelu)
        batchNormInPlace(x, N_TOKENS, L2_OUT, l2BnW, l2BnB, l2BnMean, l2BnStd)
        compare("norm2", x, expNorm2)
        logLine()

        // ========== Шаг 5: Layer 3 ==========
        logLine("[5] Layer 3: conv1d(128→64) + ReLU + BN")
        x = conv1d(x, N_TOKENS, L2_OUT, l3ConvW, l3ConvB, L3_OUT)
        reluInPlace(x)
        compare("conv3+relu", x, expConv3PostRelu)
        batchNormInPlace(x, N_TOKENS, L3_OUT, l3BnW, l3BnB, l3BnMean, l3BnStd)
        compare("norm3", x, expNorm3)
        logLine()

        // ========== Шаг 6: Head ==========
        logLine("[6] Linear head (64→8)")
        val emissions = linearHead(x, N_TOKENS, L3_OUT, NUM_TAGS, headW, headB)
        compare("emissions", emissions, expEmiss)
        logLine()

        // ========== Шаг 7: CRF ==========
        logLine("[7] CRF Viterbi decode")
        val tags = crfDecode(emissions, N_TOKENS, crfTrans)
        logLine("  Kotlin tags:   ${tags.toList()}")
        logLine("  Expected tags: ${expTags.toList()}")
        val tagVocab = listOf("<pad>", "O", "B-PER", "I-PER", "B-LOC", "I-LOC", "B-ORG", "I-ORG")
        logLine("  Kotlin tag names:   ${tags.map { tagVocab[it] }}")
        logLine("  Expected tag names: ${expTags.map { tagVocab[it] }}")
        val tagsMatch = tags.contentEquals(expTags)
        assertArrayEquals("Decoded NER tags differ from the reference", expTags, tags)
        logLine("  TAGS MATCH: $tagsMatch")
        logLine()

        // ========== Финал: сохранить лог ==========
        logLine("=== DONE ===")
        DUMP_OUT_FILE.parentFile?.mkdirs()
        DUMP_OUT_FILE.writeText(log.toString())
        println("\n--> Dump saved to ${DUMP_OUT_FILE.absolutePath}")
    }
}
