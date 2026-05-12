package app.protocolvoice.audio

import android.util.Log
import app.protocolvoice.asr.AsrService
import app.protocolvoice.asr.InterviewTranscript
import app.protocolvoice.asr.SpeakerCountMode
import app.protocolvoice.asr.TranscriptSegment
import app.protocolvoice.asr.TranscriptWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Разбивает длинный WAV-файл на 30-минутные чанки и распознаёт каждый отдельно,
 * затем склеивает все [InterviewTranscript] в один с правильными timestamps.
 *
 * Зачем:
 *   - Файлы >30-40 мин сильно нагружают память (5GB+ float-сэмплов в RAM)
 *   - Диаризация на длинных файлах деградирует — речевые embedding'и теряют
 *     контекстуальные различия между спикерами
 *   - Прогон одной длинной диаризации может занять 10+ минут, без возможности
 *     отчитываться о прогрессе крупными гранулярностями
 *
 * Чанки нарезаются BY 30 минут с overlap-zone для switching спикеров. Сегменты
 * из второго чанка получают сдвинутые timestamps. SpeakerId между чанками
 * НЕ объединяется — это компромисс: после длинной паузы (>30 мин аудио) диаризатор
 * всё равно может перепутать кто есть кто.
 */
object LongAudioProcessor {

    private const val TAG = "LongAudioProcessor"

    /** 30 минут в миллисекундах. */
    const val CHUNK_DURATION_MS = 30 * 60 * 1000L

    /** Порог при котором запускается разбивка на чанки. */
    const val SPLIT_THRESHOLD_MS = CHUNK_DURATION_MS

    /**
     * Проверка нужно ли вообще разбивать. WAV header читается, длительность
     * вычисляется из data size / byte rate.
     */
    fun shouldSplit(wavFile: File): Boolean {
        return wavFileDurationMs(wavFile) > SPLIT_THRESHOLD_MS
    }

    /**
     * Распознать длинный WAV: нарезать на 30-минутные части и склеить результаты.
     *
     * @param asr инициализированный AsrService
     * @param wavFile исходный WAV (16kHz/mono/PCM16)
     * @param speakerMode режим выбора спикеров (применяется к каждому чанку)
     * @param onChunkProgress callback (chunkIdx, totalChunks) для UI
     * @return объединённый [InterviewTranscript] или null если что-то упало
     */
    suspend fun processLong(
        asr: AsrService,
        wavFile: File,
        speakerMode: SpeakerCountMode,
        onChunkProgress: (Int, Int) -> Unit = { _, _ -> },
    ): InterviewTranscript? = withContext(Dispatchers.IO) {
        val totalDurationMs = wavFileDurationMs(wavFile)
        if (totalDurationMs <= SPLIT_THRESHOLD_MS) {
            // не нужно резать — обычный путь
            return@withContext asr.process(wavFile, speakerMode)
        }

        val numChunks = ((totalDurationMs + CHUNK_DURATION_MS - 1) / CHUNK_DURATION_MS).toInt()
        Log.i(TAG, "Long audio ${totalDurationMs}ms → splitting into $numChunks chunks")

        val allSegments = mutableListOf<TranscriptSegment>()
        var lastSpeakerIdOffset = 0
        var actualSpeakers = 0

        // Создаём временную папку для чанков
        val chunksDir = File(wavFile.parentFile, "chunks_${wavFile.nameWithoutExtension}")
        chunksDir.mkdirs()

        try {
            for (chunkIdx in 0 until numChunks) {
                onChunkProgress(chunkIdx, numChunks)
                val chunkStartMs = chunkIdx * CHUNK_DURATION_MS
                val chunkEndMs = minOf((chunkIdx + 1) * CHUNK_DURATION_MS, totalDurationMs)

                val chunkFile = File(chunksDir, "chunk_${chunkIdx}.wav")
                writeWavChunk(wavFile, chunkFile, chunkStartMs, chunkEndMs)

                Log.i(TAG, "Processing chunk $chunkIdx: ${chunkStartMs}ms..${chunkEndMs}ms")
                val chunkResult = asr.process(chunkFile, speakerMode)
                    ?: run {
                        Log.e(TAG, "Chunk $chunkIdx failed — aborting")
                        return@withContext null
                    }

                // Сдвигаем timestamps и speakerId
                for (seg in chunkResult.segments) {
                    val shifted = TranscriptSegment(
                        speakerId = seg.speakerId + lastSpeakerIdOffset,
                        startMs = seg.startMs + chunkStartMs,
                        endMs = seg.endMs + chunkStartMs,
                        words = seg.words.map { w ->
                            TranscriptWord(
                                text = w.text,
                                startMs = w.startMs + chunkStartMs,
                                endMs = w.endMs + chunkStartMs,
                                confidence = w.confidence,
                            )
                        },
                    )
                    allSegments.add(shifted)
                }

                // Подсчитать сколько новых спикеров в этом чанке — следующий чанк
                // должен начинать со следующего id
                val chunkSpeakers = chunkResult.segments.map { it.speakerId }.distinct().size
                lastSpeakerIdOffset += chunkSpeakers
                actualSpeakers += chunkSpeakers

                // Удаляем чанк после обработки чтобы не забивать диск
                try { chunkFile.delete() } catch (_: Throwable) {}
            }
        } finally {
            // Чистим временную папку
            try {
                chunksDir.listFiles()?.forEach { it.delete() }
                chunksDir.delete()
            } catch (_: Throwable) {}
        }

        InterviewTranscript(
            segments = allSegments,
            totalDurationMs = totalDurationMs,
            recordedAt = System.currentTimeMillis(),
            sourceWavPath = wavFile.absolutePath,
            numSpeakers = actualSpeakers,
        )
    }

    /**
     * Извлекает из WAV-файла фрагмент [startMs..endMs] и записывает в [outFile]
     * как самостоятельный WAV (16kHz/mono/PCM16).
     */
    private fun writeWavChunk(srcWav: File, outFile: File, startMs: Long, endMs: Long) {
        FileInputStream(srcWav).use { fis ->
            val header = ByteArray(44)
            if (fis.read(header) != 44) throw IllegalStateException("Bad WAV header")
            val sampleRate = ByteBuffer.wrap(header, 24, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            val channels = ByteBuffer.wrap(header, 22, 2)
                .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            val bytesPerSample = 2

            // Сколько байт пропустить от начала data
            val skipBytes = (startMs * sampleRate * channels * bytesPerSample / 1000L)
            val takeBytes = ((endMs - startMs) * sampleRate * channels * bytesPerSample / 1000L)

            // Пропускаем skipBytes
            var skipped = 0L
            val skipBuf = ByteArray(65536)
            while (skipped < skipBytes) {
                val toSkip = minOf(skipBytes - skipped, skipBuf.size.toLong()).toInt()
                val r = fis.read(skipBuf, 0, toSkip)
                if (r <= 0) break
                skipped += r
            }

            // Читаем takeBytes
            FileOutputStream(outFile).use { fos ->
                val dos = DataOutputStream(fos)
                // header для нового чанка
                val byteRate = sampleRate * channels * bytesPerSample
                dos.writeBytes("RIFF")
                dos.writeInt(Integer.reverseBytes((takeBytes + 36).toInt()))
                dos.writeBytes("WAVE")
                dos.writeBytes("fmt ")
                dos.writeInt(Integer.reverseBytes(16))
                dos.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
                dos.writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt())
                dos.writeInt(Integer.reverseBytes(sampleRate))
                dos.writeInt(Integer.reverseBytes(byteRate))
                dos.writeShort(java.lang.Short.reverseBytes((channels * bytesPerSample).toShort()).toInt())
                dos.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
                dos.writeBytes("data")
                dos.writeInt(Integer.reverseBytes(takeBytes.toInt()))

                // body
                val buf = ByteArray(65536)
                var written = 0L
                while (written < takeBytes) {
                    val toRead = minOf(takeBytes - written, buf.size.toLong()).toInt()
                    val r = fis.read(buf, 0, toRead)
                    if (r <= 0) break
                    fos.write(buf, 0, r)
                    written += r
                }
            }
        }
    }

    /**
     * Длительность WAV в миллисекундах из header'а.
     */
    fun wavFileDurationMs(wavFile: File): Long {
        return try {
            FileInputStream(wavFile).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) != 44) return 0L
                val sampleRate = ByteBuffer.wrap(header, 24, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                val channels = ByteBuffer.wrap(header, 22, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                val bytesPerSample = 2
                val dataLen = wavFile.length() - 44
                (dataLen * 1000L) / (sampleRate.toLong() * channels.toLong() * bytesPerSample.toLong())
            }
        } catch (_: Throwable) { 0L }
    }
}
