package app.protocolvoice.summary.ner

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

/**
 * Загрузчик квантованных эмбеддингов Navec (Russian).
 *
 * Формат файла pq.bin (распакован из navec_news.tar):
 *   [4 bytes]    magic = 0x53 0x4D 0x4B 0x4E (or similar — see source)
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
 * Восстановление вектора слова w:
 *   for s in 0..99:
 *     centroid_idx = indexes[w][s]
 *     vector[s*3..s*3+3] = codes[s][centroid_idx][0..3]
 *
 * Это даёт 300-мерный float32 вектор. Размер файла на диске: 25.4 MB.
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
    private val indexesBuffer: MappedByteBuffer,
    private val word2idx: HashMap<String, Int>,
    private val unkVector: FloatArray,
) {
    companion object {
        const val DIM = 300
        const val SUBDIM = 100
        const val CENTROIDS = 256
        const val CHUNK = 3              // DIM / SUBDIM
        const val VOCAB_SIZE = 250002
        const val HEADER_SIZE = 20        // 4 magic + 16 header
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
            val words = vocabText.split('\n').filter { it.isNotEmpty() }
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

                // Прочитаем header (для валидации)
                val headerBuf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                channel.read(headerBuf, 0)
                headerBuf.flip()
                headerBuf.position(4) // skip magic
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

                // Indexes (25 MB): mmap для zero-copy
                val indexesBuffer = channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    (HEADER_SIZE + CODES_SIZE).toLong(),
                    INDEXES_SIZE.toLong(),
                )
                indexesBuffer.order(ByteOrder.LITTLE_ENDIAN)

                // <unk> вектор (если есть в словаре)
                val unkIdx = word2idx["<unk>"] ?: 0
                val unkVec = FloatArray(DIM)
                fillVector(unkVec, unkIdx, codes, indexesBuffer)

                return NavecEmbeddings(codes, indexesBuffer, word2idx, unkVec)
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
            indexes: MappedByteBuffer,
        ) {
            require(out.size == DIM)
            val baseOffset = wordIdx * SUBDIM
            for (s in 0 until SUBDIM) {
                val centroidIdx = indexes.get(baseOffset + s).toInt() and 0xFF
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
        if (idx == null) {
            unkVector.copyInto(out)
        } else {
            fillVector(out, idx, codes, indexesBuffer)
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
        if (idx == null) {
            unkVector.copyInto(out, 0, 0, DIM)
        } else {
            fillVector(out, idx, codes, indexesBuffer)
        }
    }

    /**
     * Заполнить i-ю строку матрицы [N, DIM] хранящейся как FloatArray размера N*DIM.
     */
    fun fillEmbeddingRow(word: String, matrix: FloatArray, rowIdx: Int) {
        val offset = rowIdx * DIM
        val idx = word2idx[word.lowercase()]
        if (idx == null) {
            unkVector.copyInto(matrix, offset, 0, DIM)
        } else {
            // inline fillVector
            val baseOffset = idx * SUBDIM
            for (s in 0 until SUBDIM) {
                val centroidIdx = indexesBuffer.get(baseOffset + s).toInt() and 0xFF
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
