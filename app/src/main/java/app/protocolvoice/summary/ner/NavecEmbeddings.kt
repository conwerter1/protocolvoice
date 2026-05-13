package app.protocolvoice.summary.ner

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Загрузчик квантованных эмбеддингов Navec (Russian).
 *
 * Формат файла pq.bin (распакован из navec_news.tar):
 *   [16 bytes]   header: 4 × uint32 little-endian:
 *                  vocab_size (250002)
 *                  dim (300)
 *                  subdim (100)
 *                  centroids (256)
 *   [307200 b]   codes: float32[subdim=100][centroids=256][chunk=3]
 *                  Это центроиды кодбука для каждого из 100 подпространств.
 *   [25,000,200] indexes: uint8[vocab_size=250002][subdim=100]
 *                  Для каждого слова — 100 индексов центроидов (по 1 на подпространство).
 *
 * Полный размер файла: 16 + 307200 + 25000200 = 25 307 416 bytes ≈ 24.1 MB.
 *
 * Восстановление вектора слова w:
 *   for s in 0..99:
 *     centroid_idx = indexes[w][s]
 *     vector[s*3..s*3+3] = codes[s][centroid_idx][0..3]
 *
 * Это даёт 300-мерный float32 вектор.
 *
 * vocab.bin — UTF-8 текст со словами, разделёнными переносом строки.
 *
 * Производительность:
 *   - Файлы читаются через MappedByteBuffer (zero-copy)
 *   - Lookup слова: ~5 микросекунд (HashMap lookup + 100 byte reads)
 *   - На стенограмме 18k слов получается ~90ms всего на эмбеддинги
 *
 * Память:
 *   - Codes: 307200 bytes float32 = ~300 KB загружается в массив
 *   - Indexes: 25 MB остаётся mmap'ed (не загружается в кучу)
 *   - Vocab: ~1.3 MB строк + HashMap ~5 MB = ~6 MB
 */
class NavecEmbeddings private constructor(
    private val codes: FloatArray,        // [subdim * centroids * chunk]
    private val indexes: ByteArray,        // [vocab_size * subdim] = 25 МБ в heap (было mmap, но давал SIGSEGV в многопоточных контекстах)
    private val word2idx: HashMap<String, Int>,
    private val unkVector: FloatArray,
) {
    companion object {
        const val DIM = 300
        const val SUBDIM = 100
        const val CENTROIDS = 256
        const val CHUNK = 3              // DIM / SUBDIM
        const val VOCAB_SIZE = 250002
        const val HEADER_SIZE = 16        // 4 × uint32 little-endian (vocab_size, dim, subdim, centroids), без magic
        const val CODES_SIZE = SUBDIM * CENTROIDS * CHUNK * 4   // 307200 bytes float32
        const val INDEXES_SIZE = VOCAB_SIZE * SUBDIM            // 25 000 200 bytes uint8

        /**
         * Загрузить Navec из распакованной директории, содержащей pq.bin и vocab.bin.
         */
        fun load(navecDir: File): NavecEmbeddings {
            val pqFile = File(navecDir, "pq.bin")
            val vocabFile = File(navecDir, "vocab.bin")
            require(pqFile.exists()) { "pq.bin not found: ${pqFile.path}" }
            require(vocabFile.exists()) { "vocab.bin not found: ${vocabFile.path}" }

            // Загрузка vocab (gzipped или plain UTF-8?)
            // Файл из tar — plain UTF-8 текст, но если gzipped — снимаем
            val vocabBytes = vocabFile.readBytes()
            val vocabText = if (vocabBytes.size >= 2 && vocabBytes[0] == 0x1F.toByte() && vocabBytes[1] == 0x8B.toByte()) {
                GZIPInputStream(vocabBytes.inputStream()).bufferedReader(Charsets.UTF_8).readText()
            } else {
                String(vocabBytes, Charsets.UTF_8)
            }
            val allWords = vocabText.split('\n').filter { it.isNotEmpty() }
            // ВАЖНО: pq.bin хранит индексы только для первых VOCAB_SIZE слов.
            // Если vocab содержит больше — обрезаем хвост, иначе при попытке прочитать
            // индексы за пределами 25 000 200 байт получим IndexOutOfBoundsException.
            val words = if (allWords.size > VOCAB_SIZE) allWords.subList(0, VOCAB_SIZE) else allWords
            val word2idx = HashMap<String, Int>(words.size * 2)
            for ((i, w) in words.withIndex()) word2idx[w] = i

            // Загрузка codes (300 KB) полностью в память
            val raf = RandomAccessFile(pqFile, "r")
            try {
                val channel = raf.channel
                val totalSize = channel.size()
                require(totalSize >= HEADER_SIZE + CODES_SIZE + INDEXES_SIZE) {
                    "pq.bin too small: $totalSize, expected ≥ ${HEADER_SIZE + CODES_SIZE + INDEXES_SIZE}"
                }

                // Прочитаем header (для валидации): 4 × uint32 LE без magic prefix
                val headerBuf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                channel.read(headerBuf, 0)
                headerBuf.flip()
                val vocabSizeFromFile = headerBuf.int
                val dimFromFile = headerBuf.int
                val subdimFromFile = headerBuf.int
                val centroidsFromFile = headerBuf.int
                require(vocabSizeFromFile == VOCAB_SIZE) {
                    "Unexpected vocab_size in pq.bin: $vocabSizeFromFile != $VOCAB_SIZE"
                }
                require(dimFromFile == DIM)
                require(subdimFromFile == SUBDIM)
                require(centroidsFromFile == CENTROIDS)

                // Codes (300 KB): прочитать в FloatArray
                val codesBuf = ByteBuffer.allocate(CODES_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                channel.read(codesBuf, HEADER_SIZE.toLong())
                codesBuf.flip()
                val codes = FloatArray(SUBDIM * CENTROIDS * CHUNK)
                codesBuf.asFloatBuffer().get(codes)

                // Indexes (25 MB): загружаем в ByteArray.
                // Раньше было mmap (zero-copy), но это давало SIGSEGV когда NER работал на
                // Dispatchers.Default одновременно с другими mmap'ed onnxruntime регионами.
                // 25 МБ в heap это ок — у GigaAM всё равно в ~300 МБ идёт в native.
                val indexes = ByteArray(INDEXES_SIZE)
                val indexesByteBuf = ByteBuffer.wrap(indexes).order(ByteOrder.LITTLE_ENDIAN)
                channel.read(indexesByteBuf, (HEADER_SIZE + CODES_SIZE).toLong())

                // <unk> вектор — если не в первых VOCAB_SIZE слов, используем индекс 0
                val unkIdxRaw = word2idx["<unk>"] ?: 0
                val unkIdx = if (unkIdxRaw in 0 until VOCAB_SIZE) unkIdxRaw else 0
                val unkVec = FloatArray(DIM)
                fillVector(unkVec, unkIdx, codes, indexes)

                return NavecEmbeddings(codes, indexes, word2idx, unkVec)
            } catch (e: Exception) {
                raf.close()
                throw e
            }
        }

        /**
         * Восстановить 300d вектор для слова с заданным индексом.
         * Заполняет переданный массив [out] (должен быть размера DIM).
         */
        private fun fillVector(
            out: FloatArray,
            wordIdx: Int,
            codes: FloatArray,
            indexes: ByteArray,
        ) {
            require(out.size == DIM)
            val baseOffset = wordIdx * SUBDIM
            for (s in 0 until SUBDIM) {
                val centroidIdx = indexes[baseOffset + s].toInt() and 0xFF
                val codesOffset = (s * CENTROIDS + centroidIdx) * CHUNK
                val outOffset = s * CHUNK
                out[outOffset] = codes[codesOffset]
                out[outOffset + 1] = codes[codesOffset + 1]
                out[outOffset + 2] = codes[codesOffset + 2]
            }
        }
    }

    /**
     * Получить эмбеддинг слова. Если слова нет в словаре — возвращает <unk>.
     * Слово приводится к нижнему регистру.
     *
     * NB: это создаёт новый FloatArray. Для inference на длинных sequences
     * лучше использовать [fillEmbedding].
     */
    fun get(word: String): FloatArray {
        val out = FloatArray(DIM)
        val idx = word2idx[word.lowercase()]
        if (idx == null || idx >= VOCAB_SIZE) {
            unkVector.copyInto(out)
        } else {
            fillVector(out, idx, codes, indexes)
        }
        return out
    }

    /**
     * Заполнить готовый массив эмбеддингом. Удобно для inference,
     * когда у нас уже есть матрица [N, 300] и мы пишем в её i-ю строку.
     *
     * @param out массив длины [DIM] куда записать
     */
    fun fillEmbedding(word: String, out: FloatArray) {
        require(out.size >= DIM)
        val idx = word2idx[word.lowercase()]
        if (idx == null || idx >= VOCAB_SIZE) {
            unkVector.copyInto(out, 0, 0, DIM)
        } else {
            fillVector(out, idx, codes, indexes)
        }
    }

    /**
     * Заполнить i-ю строку матрицы [N, DIM] хранящейся как FloatArray размера N*DIM.
     */
    fun fillEmbeddingRow(word: String, matrix: FloatArray, rowIdx: Int) {
        val offset = rowIdx * DIM
        val idx = word2idx[word.lowercase()]
        if (idx == null || idx >= VOCAB_SIZE) {
            unkVector.copyInto(matrix, offset, 0, DIM)
        } else {
            // inline fillVector
            val baseOffset = idx * SUBDIM
            for (s in 0 until SUBDIM) {
                val centroidIdx = indexes[baseOffset + s].toInt() and 0xFF
                val codesOffset = (s * CENTROIDS + centroidIdx) * CHUNK
                val outOffset = offset + s * CHUNK
                matrix[outOffset] = codes[codesOffset]
                matrix[outOffset + 1] = codes[codesOffset + 1]
                matrix[outOffset + 2] = codes[codesOffset + 2]
            }
        }
    }

    /** Известно ли слово в словаре. */
    operator fun contains(word: String): Boolean = word.lowercase() in word2idx

    val vocabularySize: Int get() = word2idx.size
}
