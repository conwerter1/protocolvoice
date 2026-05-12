package app.protocolvoice.summary.ner

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Slovnet NER inference на чистом Kotlin.
 *
 * Архитектура (расшифрована из model.json, верифицирована Python-эталоном):
 *
 *   token_seq [N]
 *       │
 *       ├─ Navec embedding → [N, 300]
 *       └─ Shape embedding  → [N, 30]
 *                            ↓
 *                       concat [N, 330]
 *                            ↓
 *   Layer 1: conv1d(330→256, k=3, pad=1) + ReLU + BatchNorm
 *   Layer 2: conv1d(256→128, k=3, pad=1) + ReLU + BatchNorm
 *   Layer 3: conv1d(128→64,  k=3, pad=1) + ReLU + BatchNorm
 *                            ↓
 *   Linear(64 → 8 BIO tags)  →  emissions [N, 8]
 *                            ↓
 *   CRF Viterbi decode (transitions [8, 8])
 *                            ↓
 *   tag_seq [N]: O / B-PER / I-PER / B-LOC / I-LOC / B-ORG / I-ORG
 *
 * Веса хранятся в директории как 22 отдельных .bin файла:
 *   arrays/0.bin   shape_embedding [66, 30]    float32   ~8 KB
 *   arrays/1.bin   layer1_conv_w   [256, 330, 3] float32 ~1 MB
 *   arrays/2.bin   layer1_conv_b   [256]         float32 ~1 KB
 *   arrays/3-6     layer1_bn_*     [256]         float32 4× ~1 KB
 *   arrays/7-12    layer2_conv + bn (256→128)
 *   arrays/13-18   layer3_conv + bn (128→64)
 *   arrays/19      head_proj_w     [64, 8]
 *   arrays/20      head_proj_b     [8]
 *   arrays/21      crf_transitions [8, 8]
 *
 * BatchNorm в eval-режиме: y = (x - running_mean) / running_std * gamma + beta
 * (NB: в файле .ns хранится уже sqrt(var + eps), поэтому делим, не корнем извлекаем)
 *
 * Производительность Python-эталона на стенограмме AKKUYU (17,922 слов):
 *   - 5.9 секунд = ~330 ms на 1000 слов
 *   - На Xiaomi 12T (Kotlin JIT): ожидается ~700 ms на 1000 слов
 */
class SlovnetNer private constructor(
    private val navec: NavecEmbeddings,
    private val shapeClassifier: SlovnetShapeClassifier,
    private val tagVocab: List<String>,

    private val shapeEmb: FloatArray,           // [66 * 30]
    private val l1ConvW: FloatArray,            // [256 * 330 * 3]
    private val l1ConvB: FloatArray,            // [256]
    private val l1BnW: FloatArray, private val l1BnB: FloatArray,
    private val l1BnMean: FloatArray, private val l1BnStd: FloatArray,
    private val l2ConvW: FloatArray,            // [128 * 256 * 3]
    private val l2ConvB: FloatArray,
    private val l2BnW: FloatArray, private val l2BnB: FloatArray,
    private val l2BnMean: FloatArray, private val l2BnStd: FloatArray,
    private val l3ConvW: FloatArray,            // [64 * 128 * 3]
    private val l3ConvB: FloatArray,
    private val l3BnW: FloatArray, private val l3BnB: FloatArray,
    private val l3BnMean: FloatArray, private val l3BnStd: FloatArray,
    private val headW: FloatArray,              // [64 * 8]
    private val headB: FloatArray,              // [8]
    private val crfTrans: FloatArray,           // [8 * 8]
) {
    companion object {
        const val SHAPE_DIM = 30
        const val SHAPE_VOCAB_SIZE = 66
        const val INPUT_DIM = NavecEmbeddings.DIM + SHAPE_DIM   // 330
        const val L1_OUT = 256
        const val L2_OUT = 128
        const val L3_OUT = 64
        const val NUM_TAGS = 8
        const val KERNEL = 3
        const val PADDING = 1

        /**
         * Загрузить Slovnet NER из распакованной директории.
         *
         * @param slovnetDir директория, содержащая arrays/<i>.bin и vocabs/<name>.gz
         * @param navec предзагруженный Navec
         */
        fun load(slovnetDir: File, navec: NavecEmbeddings): SlovnetNer {
            val arraysDir = File(slovnetDir, "arrays")
            val vocabsDir = File(slovnetDir, "vocabs")
            require(arraysDir.exists()) { "arrays/ not found in ${slovnetDir.path}" }
            require(vocabsDir.exists()) { "vocabs/ not found in ${slovnetDir.path}" }

            // Vocabs
            val shapeVocab = readGzippedLines(File(vocabsDir, "shape.gz"))
            val tagVocab = readGzippedLines(File(vocabsDir, "tag.gz"))
            require(shapeVocab.size == SHAPE_VOCAB_SIZE) {
                "shape vocab size ${shapeVocab.size} != $SHAPE_VOCAB_SIZE"
            }
            require(tagVocab.size == NUM_TAGS) {
                "tag vocab size ${tagVocab.size} != $NUM_TAGS"
            }

            val shapeClassifier = SlovnetShapeClassifier(shapeVocab)

            // Helper to load FloatArray from .bin
            fun loadFloat(idx: Int, expectedSize: Int): FloatArray {
                val f = File(arraysDir, "$idx.bin")
                val bytes = f.readBytes()
                require(bytes.size == expectedSize * 4) {
                    "arrays/$idx.bin size ${bytes.size} != ${expectedSize * 4} (expected $expectedSize floats)"
                }
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val out = FloatArray(expectedSize)
                buf.asFloatBuffer().get(out)
                return out
            }

            return SlovnetNer(
                navec = navec,
                shapeClassifier = shapeClassifier,
                tagVocab = tagVocab,
                shapeEmb = loadFloat(0, SHAPE_VOCAB_SIZE * SHAPE_DIM),
                l1ConvW = loadFloat(1, L1_OUT * INPUT_DIM * KERNEL),
                l1ConvB = loadFloat(2, L1_OUT),
                l1BnW = loadFloat(3, L1_OUT),
                l1BnB = loadFloat(4, L1_OUT),
                l1BnMean = loadFloat(5, L1_OUT),
                l1BnStd = loadFloat(6, L1_OUT),
                l2ConvW = loadFloat(7, L2_OUT * L1_OUT * KERNEL),
                l2ConvB = loadFloat(8, L2_OUT),
                l2BnW = loadFloat(9, L2_OUT),
                l2BnB = loadFloat(10, L2_OUT),
                l2BnMean = loadFloat(11, L2_OUT),
                l2BnStd = loadFloat(12, L2_OUT),
                l3ConvW = loadFloat(13, L3_OUT * L2_OUT * KERNEL),
                l3ConvB = loadFloat(14, L3_OUT),
                l3BnW = loadFloat(15, L3_OUT),
                l3BnB = loadFloat(16, L3_OUT),
                l3BnMean = loadFloat(17, L3_OUT),
                l3BnStd = loadFloat(18, L3_OUT),
                headW = loadFloat(19, L3_OUT * NUM_TAGS),
                headB = loadFloat(20, NUM_TAGS),
                crfTrans = loadFloat(21, NUM_TAGS * NUM_TAGS),
            )
        }

        private fun readGzippedLines(f: File): List<String> {
            GZIPInputStream(f.inputStream()).bufferedReader(Charsets.UTF_8).use { r ->
                return r.readLines().filter { it.isNotEmpty() }
            }
        }
    }

    /**
     * Запустить NER на последовательности токенов.
     * Возвращает BIO-теги для каждого токена.
     */
    fun predict(tokens: List<String>): List<String> {
        if (tokens.isEmpty()) return emptyList()
        val n = tokens.size

        // 1. Embeddings: [N, 330] хранится как FloatArray размера N*330
        val input = FloatArray(n * INPUT_DIM)
        for (i in 0 until n) {
            val rowOffset = i * INPUT_DIM
            // Navec [0..299]
            val navecVec = navec.get(tokens[i])
            navecVec.copyInto(input, rowOffset, 0, NavecEmbeddings.DIM)
            // Shape [300..329]
            val shapeIdx = shapeClassifier.classifyToIdx(tokens[i])
            val shapeOffset = shapeIdx * SHAPE_DIM
            for (k in 0 until SHAPE_DIM) {
                input[rowOffset + NavecEmbeddings.DIM + k] = shapeEmb[shapeOffset + k]
            }
        }

        // 2. Layer 1
        var x = conv1d(input, n, INPUT_DIM, l1ConvW, l1ConvB, L1_OUT)
        reluInPlace(x)
        batchNormInPlace(x, n, L1_OUT, l1BnW, l1BnB, l1BnMean, l1BnStd)

        // 3. Layer 2
        x = conv1d(x, n, L1_OUT, l2ConvW, l2ConvB, L2_OUT)
        reluInPlace(x)
        batchNormInPlace(x, n, L2_OUT, l2BnW, l2BnB, l2BnMean, l2BnStd)

        // 4. Layer 3
        x = conv1d(x, n, L2_OUT, l3ConvW, l3ConvB, L3_OUT)
        reluInPlace(x)
        batchNormInPlace(x, n, L3_OUT, l3BnW, l3BnB, l3BnMean, l3BnStd)

        // 5. Linear head: emissions [N, 8]
        val emissions = FloatArray(n * NUM_TAGS)
        for (i in 0 until n) {
            val rowOffset = i * L3_OUT
            for (j in 0 until NUM_TAGS) {
                var sum = headB[j]
                // headW shape [64, 8] stored как [in*out]
                for (k in 0 until L3_OUT) {
                    sum += x[rowOffset + k] * headW[k * NUM_TAGS + j]
                }
                emissions[i * NUM_TAGS + j] = sum
            }
        }

        // 6. CRF Viterbi
        val tagIndices = crfDecode(emissions, n)
        return tagIndices.map { tagVocab[it] }
    }

    /**
     * Conv1D с kernel=3, padding=1.
     *
     * @param input [seqLen * inCh] row-major
     * @param seqLen длина последовательности
     * @param inCh входные каналы
     * @param weight [outCh * inCh * 3] row-major (как в PyTorch Conv1d.weight)
     * @param bias [outCh]
     * @param outCh выходные каналы
     * @return [seqLen * outCh] новый массив
     */
    private fun conv1d(
        input: FloatArray, seqLen: Int, inCh: Int,
        weight: FloatArray, bias: FloatArray, outCh: Int,
    ): FloatArray {
        val output = FloatArray(seqLen * outCh)
        // Для каждого выходного позиции t и канала oc:
        //   output[t, oc] = bias[oc] + sum_{ic, dk in {-1, 0, +1}} weight[oc, ic, dk+1] * input[t+dk, ic]
        for (t in 0 until seqLen) {
            for (oc in 0 until outCh) {
                var sum = bias[oc]
                // dk = 0 (sliding позиция)
                for (dk in 0 until KERNEL) {
                    val srcT = t + dk - PADDING
                    if (srcT < 0 || srcT >= seqLen) continue
                    val inputRowOffset = srcT * inCh
                    // weight indexed как [outCh, inCh, kernel] row-major:
                    //   weight[oc * inCh * KERNEL + ic * KERNEL + dk]
                    for (ic in 0 until inCh) {
                        sum += input[inputRowOffset + ic] * weight[oc * inCh * KERNEL + ic * KERNEL + dk]
                    }
                }
                output[t * outCh + oc] = sum
            }
        }
        return output
    }

    /** ReLU in-place. */
    private fun reluInPlace(x: FloatArray) {
        for (i in x.indices) {
            if (x[i] < 0f) x[i] = 0f
        }
    }

    /**
     * BatchNorm eval-mode in-place.
     * y = (x - mean) / std * gamma + beta
     *
     * @param x [seqLen * channels] row-major
     */
    private fun batchNormInPlace(
        x: FloatArray, seqLen: Int, channels: Int,
        gamma: FloatArray, beta: FloatArray,
        mean: FloatArray, std: FloatArray,
    ) {
        for (t in 0 until seqLen) {
            val offset = t * channels
            for (c in 0 until channels) {
                x[offset + c] = (x[offset + c] - mean[c]) / std[c] * gamma[c] + beta[c]
            }
        }
    }

    /**
     * CRF Viterbi decoding.
     *
     * @param emissions [seqLen * numTags]
     * @param seqLen
     * @return List<Int> длины seqLen — индексы тегов
     */
    private fun crfDecode(emissions: FloatArray, seqLen: Int): List<Int> {
        if (seqLen == 0) return emptyList()
        val numTags = NUM_TAGS

        // alpha[t][s] = max score любого пути, заканчивающегося тегом s в позиции t
        var alpha = FloatArray(numTags)
        // backpointers[t][s] = best previous tag для (t, s)
        val backpointers = Array(seqLen) { IntArray(numTags) }

        // Инициализация: alpha[0] = emissions[0]
        for (s in 0 until numTags) alpha[s] = emissions[s]

        val newAlpha = FloatArray(numTags)
        for (t in 1 until seqLen) {
            for (cur in 0 until numTags) {
                var bestScore = Float.NEGATIVE_INFINITY
                var bestPrev = 0
                for (prev in 0 until numTags) {
                    val score = alpha[prev] + crfTrans[prev * numTags + cur] + emissions[t * numTags + cur]
                    if (score > bestScore) {
                        bestScore = score
                        bestPrev = prev
                    }
                }
                newAlpha[cur] = bestScore
                backpointers[t][cur] = bestPrev
            }
            // copy newAlpha → alpha
            System.arraycopy(newAlpha, 0, alpha, 0, numTags)
        }

        // Найти лучший последний тег
        var bestLast = 0
        var bestScore = alpha[0]
        for (s in 1 until numTags) {
            if (alpha[s] > bestScore) {
                bestScore = alpha[s]
                bestLast = s
            }
        }

        // Backtrack
        val path = IntArray(seqLen)
        path[seqLen - 1] = bestLast
        for (t in seqLen - 1 downTo 1) {
            path[t - 1] = backpointers[t][path[t]]
        }
        return path.toList()
    }

    /**
     * Утилита: извлечь spans из BIO-тегов.
     *
     * Пример:
     *   tokens = ["Россия", "и", "США"]
     *   tags   = ["B-LOC",  "O", "B-LOC"]
     *   →  [Span(LOC, "Россия"), Span(LOC, "США")]
     *
     * I-X сразу после B-X того же типа продолжает тот же span.
     */
    fun extractSpans(tokens: List<String>, tags: List<String>): List<Span> {
        require(tokens.size == tags.size)
        val spans = mutableListOf<Span>()
        var currentType: String? = null
        var currentTokens = mutableListOf<String>()

        for ((i, tag) in tags.withIndex()) {
            val tok = tokens[i]
            when {
                tag.startsWith("B-") -> {
                    if (currentType != null) {
                        spans.add(Span(currentType!!, currentTokens.joinToString(" ")))
                    }
                    currentType = tag.removePrefix("B-")
                    currentTokens = mutableListOf(tok)
                }
                tag.startsWith("I-") && currentType == tag.removePrefix("I-") -> {
                    currentTokens.add(tok)
                }
                else -> {
                    if (currentType != null) {
                        spans.add(Span(currentType, currentTokens.joinToString(" ")))
                        currentType = null
                        currentTokens = mutableListOf()
                    }
                }
            }
        }
        if (currentType != null) {
            spans.add(Span(currentType, currentTokens.joinToString(" ")))
        }
        return spans
    }

    /** Распознанный span — тип + объединённый текст. */
    data class Span(val type: String, val text: String)
}
