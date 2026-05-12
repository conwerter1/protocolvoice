package app.protocolvoice.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Импорт аудиофайла любого формата (mp3/m4a/wav/ogg/aac/flac/3gp/amr — всё что
 * Android MediaCodec умеет нативно) и конвертация в 16kHz / mono / PCM16 WAV —
 * именно такой формат ожидает GigaAM-v3 ASR.
 *
 * Pipeline:
 *   1. [MediaExtractor] открывает URI и парсит контейнер
 *   2. Находим audio track, запоминаем source sampleRate и channels
 *   3. [MediaCodec] декодирует в PCM16 (сохраняя оригинальный sampleRate и кол-во каналов)
 *   4. Стерео → моно усреднением каналов (если нужно)
 *   5. Линейный ресемплинг до 16kHz (если нужно)
 *   6. Запись 44-байтового WAV header + PCM16 little-endian
 *
 * Прогресс репортится через [progressFlow] из [importToWav] — UI может его подписать
 * чтобы показать процентный индикатор.
 *
 * Ресемплинг — простой линейный (без windowed sinc / libsamplerate). Для ASR этого
 * достаточно — модель сама делает feature extraction и устойчива к небольшим аррифактам.
 */
object AudioImporter {

    private const val TAG = "AudioImporter"
    private const val TARGET_SAMPLE_RATE = 16_000
    private const val TARGET_CHANNELS = 1
    private const val TIMEOUT_US = 10_000L

    /** Результат импорта. */
    data class ImportResult(
        /** Куда был сохранён сконвертированный WAV. */
        val wavFile: File,
        /** Длительность аудио в миллисекундах. */
        val durationMs: Long,
        /** Исходный sample rate (до ресемплинга) — для отладки. */
        val sourceSampleRate: Int,
        /** Исходное число каналов (до микса в моно). */
        val sourceChannels: Int,
    )

    /**
     * Конвертирует аудиофайл из URI в filesDir/recordings/imported_<ts>.wav (16kHz mono PCM16).
     *
     * @param ctx контекст для доступа к ContentResolver и filesDir
     * @param uri источник аудио (любой URI: content:// из SAF, file://, http://...)
     * @param onProgress callback (0.0..1.0) для UI-прогресса
     * @return [ImportResult] с путём к WAV-файлу, или throws IOException/IllegalStateException
     */
    suspend fun importToWav(
        ctx: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis()
        val outFile = File(ctx.filesDir, "recordings/imported_$ts.wav")
        outFile.parentFile?.mkdirs()

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(ctx, uri, null)

            // Найти audio track
            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    inputFormat = fmt
                    break
                }
            }
            if (audioTrack < 0 || inputFormat == null) {
                throw IllegalStateException("В файле нет аудио-дорожки")
            }

            extractor.selectTrack(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val totalDurationUs = try {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } catch (_: Throwable) { 0L }
            val totalDurationMs = totalDurationUs / 1000L

            Log.i(TAG, "Importing: mime=$mime sampleRate=$srcSampleRate channels=$srcChannels durationMs=$totalDurationMs")

            // Уже WAV 16kHz/mono/PCM16 — fast path: просто скопировать
            // (MediaCodec для PCM не нужен).
            if (mime == "audio/raw" || mime == "audio/x-wav" || mime == "audio/wav") {
                val fast = tryFastCopyWav(ctx, uri, outFile, srcSampleRate, srcChannels)
                if (fast != null) {
                    onProgress(1f)
                    return@withContext ImportResult(
                        wavFile = outFile,
                        durationMs = fast,
                        sourceSampleRate = srcSampleRate,
                        sourceChannels = srcChannels,
                    )
                }
                // Если fast path не сработал — fallthrough на MediaCodec ниже
            }

            // === MediaCodec decode → PCM16 ===
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            // PCM-байты на оригинальной частоте и каналах
            val pcmBuffer = ByteArray(srcSampleRate * srcChannels * 2 * 4) // ~4 секунды
            var pcmAccumulator = ByteArray(0)
            var endOfInput = false
            var endOfOutput = false
            var lastReportedProgress = 0f

            while (!endOfOutput) {
                if (!endOfInput) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            endOfInput = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)!!
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.position(bufferInfo.offset)
                            outBuf.get(chunk, 0, bufferInfo.size)
                            outBuf.clear()
                            pcmAccumulator += chunk
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            endOfOutput = true
                        }
                        // Прогресс
                        if (totalDurationUs > 0) {
                            val pct = (bufferInfo.presentationTimeUs.toFloat() / totalDurationUs)
                                .coerceIn(0f, 1f)
                            // Импорт == 90% всего процесса, ресемпл/запись == 10%
                            val reportPct = pct * 0.9f
                            if (reportPct - lastReportedProgress > 0.02f) {
                                onProgress(reportPct)
                                lastReportedProgress = reportPct
                            }
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // на mp3/aac формат может прийти с задержкой — проверим обновлённое
                        val newFmt = codec.outputFormat
                        Log.i(TAG, "Output format changed: $newFmt")
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // подождём
                    }
                }
            }

            // === Стерео → моно ===
            val pcmMono = if (srcChannels == 1) pcmAccumulator else mixToMono(pcmAccumulator, srcChannels)

            // === Ресемплинг до 16kHz ===
            val pcmFinal = if (srcSampleRate == TARGET_SAMPLE_RATE) pcmMono
                           else resampleLinear(pcmMono, srcSampleRate, TARGET_SAMPLE_RATE)

            onProgress(0.95f)

            // === Запись WAV ===
            writeWavFile(outFile, pcmFinal, TARGET_SAMPLE_RATE, TARGET_CHANNELS)

            val durationMs = (pcmFinal.size / 2L * 1000L) / TARGET_SAMPLE_RATE
            onProgress(1f)
            Log.i(TAG, "Import done: ${outFile.absolutePath} (${pcmFinal.size} bytes, ${durationMs}ms)")

            ImportResult(
                wavFile = outFile,
                durationMs = durationMs,
                sourceSampleRate = srcSampleRate,
                sourceChannels = srcChannels,
            )
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            extractor.release()
        }
    }

    /**
     * Микс стерео (или N-канального) в моно усреднением.
     * pcm — PCM16 little-endian, interleaved.
     */
    private fun mixToMono(pcm: ByteArray, channels: Int): ByteArray {
        if (channels == 1) return pcm
        val samplesPerChannel = pcm.size / 2 / channels
        val out = ByteArray(samplesPerChannel * 2)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val obb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samplesPerChannel) {
            var sum = 0
            for (c in 0 until channels) {
                sum += bb.short.toInt()
            }
            val mixed = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            obb.putShort(mixed.toShort())
        }
        return out
    }

    /**
     * Линейный ресемплинг PCM16 mono из srcRate в dstRate.
     * Для ASR-целей качество достаточно: фичи MFCC устойчивы к небольшим артефактам.
     */
    private fun resampleLinear(pcm: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        if (srcRate == dstRate) return pcm
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val srcSamples = ShortArray(pcm.size / 2)
        for (i in srcSamples.indices) srcSamples[i] = bb.short

        val ratio = dstRate.toDouble() / srcRate
        val dstLen = (srcSamples.size * ratio).toInt()
        val dst = ShortArray(dstLen)

        for (i in 0 until dstLen) {
            val srcPos = i / ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx
            val s1 = srcSamples[srcIdx.coerceAtMost(srcSamples.size - 1)].toInt()
            val s2 = if (srcIdx + 1 < srcSamples.size) srcSamples[srcIdx + 1].toInt() else s1
            dst[i] = (s1 + (s2 - s1) * frac).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        val out = ByteArray(dst.size * 2)
        val obb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (s in dst) obb.putShort(s)
        return out
    }

    /**
     * Запись PCM16 mono данных в WAV-файл (44-байт RIFF header + data).
     */
    private fun writeWavFile(outFile: File, pcmData: ByteArray, sampleRate: Int, channels: Int) {
        FileOutputStream(outFile).use { fos ->
            val dos = DataOutputStream(fos)
            val byteRate = sampleRate * channels * 2
            val dataLen = pcmData.size
            // RIFF
            dos.writeBytes("RIFF")
            dos.writeInt(Integer.reverseBytes(dataLen + 36))
            dos.writeBytes("WAVE")
            // fmt subchunk
            dos.writeBytes("fmt ")
            dos.writeInt(Integer.reverseBytes(16))                      // Subchunk1Size = 16 (PCM)
            dos.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())            // AudioFormat = 1 (PCM)
            dos.writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt())
            dos.writeInt(Integer.reverseBytes(sampleRate))
            dos.writeInt(Integer.reverseBytes(byteRate))
            dos.writeShort(java.lang.Short.reverseBytes((channels * 2).toShort()).toInt())  // BlockAlign
            dos.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())              // BitsPerSample = 16
            // data subchunk
            dos.writeBytes("data")
            dos.writeInt(Integer.reverseBytes(dataLen))
            dos.write(pcmData)
            dos.flush()
        }
    }

    /**
     * Быстрая копия для случая когда источник — уже WAV 16kHz/mono/PCM16.
     * Просто копируем байты, не разбирая через MediaCodec. Возвращает durationMs.
     * Если что-то не так — возвращает null, тогда caller использует MediaCodec путь.
     */
    private fun tryFastCopyWav(
        ctx: Context,
        uri: Uri,
        outFile: File,
        sampleRate: Int,
        channels: Int,
    ): Long? {
        if (sampleRate != TARGET_SAMPLE_RATE || channels != TARGET_CHANNELS) return null
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Размер - 44 = data len; data len / (sampleRate * 2) = duration sec
            val size = outFile.length()
            if (size <= 44) return null
            val dataLen = size - 44
            (dataLen * 1000L) / (sampleRate.toLong() * 2L * channels.toLong())
        } catch (e: Throwable) {
            Log.w(TAG, "fast copy failed, will use MediaCodec", e)
            try { outFile.delete() } catch (_: Throwable) {}
            null
        }
    }
}
