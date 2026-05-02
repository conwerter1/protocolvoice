package app.protocolvoice.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Конвертация WAV (16kHz mono PCM16) → M4A (AAC-LC).
 *
 * Используется ТОЛЬКО `android.media.MediaCodec` + `android.media.MediaMuxer` —
 * никаких внешних библиотек. Размер: ~12x меньше чем WAV для речи (час → ~10 МБ).
 *
 * Параметры кодирования:
 *   - кодек:   audio/mp4a-latm (AAC-LC)
 *   - битрейт: 64 kbps (хватает для речи и диктофонной записи)
 *   - sample rate: 16000 Hz (как в WAV — без ресемплинга)
 *   - каналы:  1 (моно)
 *   - контейнер: MP4 / M4A
 *
 * Использование (suspend, IO):
 *   val m4a = Mp4Encoder.encode(wavFile, m4aOutFile)
 *   if (m4a != null) wavFile.delete()
 *
 * Возвращает выходной файл при успехе, null при ошибке.
 * Безопасно для повторных вызовов: если выходной файл уже есть — перезаписывается.
 *
 * Внимание: MediaCodec на Xiaomi/MediaTek известен капризами на edge cases
 * (некоторые форматы не поддерживаются нативно). Здесь параметры консервативные —
 * AAC-LC 16kHz mono всегда работает на любом Android 5+.
 */
object Mp4Encoder {

    private const val TAG = "Mp4Encoder"
    private const val MIME_AAC = "audio/mp4a-latm"
    private const val SAMPLE_RATE = 16_000
    private const val CHANNELS = 1
    private const val BITRATE = 64_000              // 64 kbps — речь, чисто и компактно
    private const val WAV_HEADER_SIZE = 44
    private const val TIMEOUT_US = 10_000L          // 10 ms на каждый buffer-операцию

    /**
     * @return выходной m4a-файл при успехе, null при ошибке.
     */
    suspend fun encode(wavFile: File, m4aOutFile: File): File? = withContext(Dispatchers.IO) {
        if (!wavFile.exists()) {
            Log.e(TAG, "WAV not found: ${wavFile.absolutePath}")
            return@withContext null
        }
        if (m4aOutFile.exists()) m4aOutFile.delete()
        m4aOutFile.parentFile?.mkdirs()

        val startTime = System.currentTimeMillis()
        val wavSize = wavFile.length()
        Log.i(TAG, "Encoding ${wavFile.name} (${wavSize / 1024} KB) -> ${m4aOutFile.name}")

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var fis: FileInputStream? = null

        try {
            // === 1. Настраиваем encoder ===
            val format = MediaFormat.createAudioFormat(MIME_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec = MediaCodec.createEncoderByType(MIME_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            // === 2. Настраиваем muxer ===
            muxer = MediaMuxer(m4aOutFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrackIdx = -1
            var muxerStarted = false

            // === 3. Открываем WAV, пропускаем 44-байтный заголовок ===
            fis = FileInputStream(wavFile)
            val skipped = fis.skip(WAV_HEADER_SIZE.toLong())
            if (skipped != WAV_HEADER_SIZE.toLong()) {
                Log.e(TAG, "Failed to skip WAV header")
                return@withContext null
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val pcmChunk = ByteArray(2_048)         // 1024 samples PCM16
            var totalSamplesRead = 0L
            var endOfWavReached = false
            var encoderDone = false

            // === 4. Главный цикл: читаем PCM -> encode -> пишем в muxer ===
            while (!encoderDone) {
                // 4a. Кормим encoder сырым PCM
                if (!endOfWavReached) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuf = codec.getInputBuffer(inputIndex)!!
                        inputBuf.clear()
                        val read = fis.read(pcmChunk, 0, minOf(pcmChunk.size, inputBuf.remaining()))
                        if (read <= 0) {
                            // Конец WAV — посылаем EOS
                            codec.queueInputBuffer(
                                inputIndex, 0, 0,
                                computePresentationTimeUs(totalSamplesRead),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            endOfWavReached = true
                        } else {
                            inputBuf.put(pcmChunk, 0, read)
                            val ptsUs = computePresentationTimeUs(totalSamplesRead)
                            codec.queueInputBuffer(inputIndex, 0, read, ptsUs, 0)
                            totalSamplesRead += read / 2     // PCM16 -> 2 байта на сэмпл
                        }
                    }
                }

                // 4b. Забираем закодированные кадры
                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                while (outputIndex >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputIndex)!!
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Это codec-config (header), не звук — игнорируем,
                        // muxer его получит из output format.
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        outputBuf.position(bufferInfo.offset)
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(muxerTrackIdx, outputBuf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true
                        break
                    }
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                }

                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !muxerStarted) {
                    val outFormat = codec.outputFormat
                    muxerTrackIdx = muxer.addTrack(outFormat)
                    muxer.start()
                    muxerStarted = true
                    Log.i(TAG, "Muxer started, track=$muxerTrackIdx, format=$outFormat")
                }
            }

            val elapsedMs = System.currentTimeMillis() - startTime
            val m4aSize = m4aOutFile.length()
            Log.i(TAG, "Encoded in ${elapsedMs}ms: ${wavSize / 1024} KB -> ${m4aSize / 1024} KB " +
                    "(${(m4aSize.toFloat() / wavSize * 100).toInt()}%)")
            return@withContext m4aOutFile

        } catch (e: Throwable) {
            Log.e(TAG, "Encoding failed", e)
            try { m4aOutFile.delete() } catch (_: Throwable) {}
            return@withContext null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { fis?.close() } catch (_: Throwable) {}
        }
    }

    private fun computePresentationTimeUs(samplesRead: Long): Long =
        samplesRead * 1_000_000L / SAMPLE_RATE
}
