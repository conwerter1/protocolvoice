package app.protocolvoice.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import app.protocolvoice.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Запись звука с микрофона в WAV-файл (16 kHz, mono, 16-bit PCM).
 *
 * Этот формат — точно то что нужно sherpa-onnx для GigaAM-v3.
 * Запись идёт в файл напрямую (не держим в памяти): для часового интервью
 * получится ~115 МБ WAV — слишком много чтобы держать в RAM, но для FS норма.
 *
 * Жизненный цикл:
 *   start(outputFile) → запись → pause()/resume() → stop()
 *
 * Состояние через StateFlow [state]: IDLE / RECORDING / PAUSED / STOPPED.
 * Также публикует уровень входного сигнала [level] (RMS в диапазоне 0..1)
 * для визуализации индикатором громкости в UI.
 */
class AudioRecorder(private val ctx: Context) {

    enum class State { IDLE, RECORDING, PAUSED, STOPPED, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** RMS-уровень входа в диапазоне 0..1 — для визуализации. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var recorder: AudioRecord? = null
    private var output: File? = null
    private var dataBytesWritten: Long = 0L
    private val job = SupervisorJob()
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + job)
    private var captureJob: Job? = null

    companion object {
        const val SAMPLE_RATE = 16_000   // sherpa-onnx требует 16 kHz
        const val CHANNELS = 1            // mono
        const val BITS_PER_SAMPLE = 16
        // Чем меньше буфер — тем быстрее обновляется индикатор уровня;
        // 0.05 сек = 50 мс, 800 сэмплов — комфортно для индикации.
        private const val BUFFER_FRAMES = SAMPLE_RATE / 20
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        private const val BUFFER_BYTES = BUFFER_FRAMES * BYTES_PER_SAMPLE * CHANNELS
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Начать запись в указанный файл. Если файл существует — перезаписывается.
     * Возвращает true если запись успешно стартовала.
     */
    @SuppressLint("MissingPermission")
    fun start(outputFile: File): Boolean {
        if (_state.value == State.RECORDING) return true
        if (!hasPermission()) {
            _errorMessage.value = ctx.getString(R.string.recorder_no_permission)
            _state.value = State.ERROR
            return false
        }

        try {
            // 1) Готовим WAV-файл с 44-байтовым placeholder-заголовком,
            //    реальные размеры пропишем в конце через RandomAccessFile.
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { fos ->
                writeWavHeader(DataOutputStream(fos), 0L)
            }
            output = outputFile
            dataBytesWritten = 0L

            // 2) AudioRecord size — берём минимум, умноженный на запас (×2)
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                _errorMessage.value = ctx.getString(R.string.recorder_unsupported)
                _state.value = State.ERROR
                return false
            }
            val bufSize = maxOf(minBuf * 2, BUFFER_BYTES * 4)

            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                recorder?.release()
                recorder = null
                _errorMessage.value = ctx.getString(R.string.recorder_init_failed)
                _state.value = State.ERROR
                return false
            }

            recorder?.startRecording()
            _state.value = State.RECORDING
            _errorMessage.value = null
            _durationMs.value = 0L

            captureJob = scope.launch { captureLoop() }
            return true
        } catch (e: Exception) {
            _errorMessage.value = ctx.getString(R.string.recorder_start_error, e.message ?: "")
            _state.value = State.ERROR
            return false
        }
    }

    fun pause() {
        if (_state.value == State.RECORDING) {
            _state.value = State.PAUSED
        }
    }

    fun resume() {
        if (_state.value == State.PAUSED) {
            _state.value = State.RECORDING
        }
    }

    /**
     * Остановить запись и финализировать WAV (записать правильные размеры в заголовок).
     * Возвращает путь к финальному WAV или null при ошибке.
     */
    fun stop(): File? {
        if (_state.value == State.IDLE) return null

        try {
            captureJob?.cancel()
            recorder?.stop()
        } catch (_: Exception) {}
        recorder?.release()
        recorder = null

        val out = output ?: return null
        // Допишем правильные размеры в заголовок WAV
        try {
            RandomAccessFile(out, "rw").use { raf ->
                val totalDataLen = dataBytesWritten + 36
                raf.seek(4)
                raf.writeInt(Integer.reverseBytes(totalDataLen.toInt()))
                raf.seek(40)
                raf.writeInt(Integer.reverseBytes(dataBytesWritten.toInt()))
            }
        } catch (e: IOException) {
            _errorMessage.value = ctx.getString(R.string.recorder_save_error, e.message ?: "")
        }

        _state.value = State.STOPPED
        _level.value = 0f
        return out
    }

    fun release() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        scope.coroutineContext.cancelChildren()
        _state.value = State.IDLE
    }

    /** Цикл чтения сэмплов и записи в файл. */
    private suspend fun captureLoop() = withContext(Dispatchers.IO) {
        val rec = recorder ?: return@withContext
        val out = output ?: return@withContext
        val buf = ShortArray(BUFFER_FRAMES)
        val byteBuf = ByteArray(BUFFER_FRAMES * BYTES_PER_SAMPLE)

        FileOutputStream(out, true).use { fos ->
            while (isActive && _state.value != State.STOPPED && _state.value != State.ERROR) {
                if (_state.value == State.PAUSED) {
                    // тонкий sleep чтобы не жечь CPU
                    Thread.sleep(50)
                    continue
                }
                val read = rec.read(buf, 0, buf.size)
                if (read <= 0) {
                    Thread.sleep(10)
                    continue
                }
                // конвертируем little-endian
                var sumSquares = 0L
                for (i in 0 until read) {
                    val s = buf[i].toInt()
                    byteBuf[2 * i]     = (s and 0xFF).toByte()
                    byteBuf[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
                    sumSquares += (s * s).toLong()
                }
                val rms = if (read > 0) Math.sqrt(sumSquares.toDouble() / read) / 32768.0 else 0.0
                _level.value = rms.toFloat().coerceIn(0f, 1f)

                fos.write(byteBuf, 0, read * BYTES_PER_SAMPLE)
                dataBytesWritten += read * BYTES_PER_SAMPLE
                _durationMs.value = bytesToMs(dataBytesWritten)
            }
        }
    }

    private fun bytesToMs(bytes: Long): Long =
        (bytes * 1000L) / (SAMPLE_RATE * BYTES_PER_SAMPLE * CHANNELS)

    /** Записывает 44-байтовый WAV-заголовок (RIFF/WAVE/fmt/data). */
    private fun writeWavHeader(out: DataOutputStream, dataLen: Long) {
        val byteRate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE
        out.writeBytes("RIFF")
        out.writeInt(Integer.reverseBytes((dataLen + 36).toInt()))      // ChunkSize
        out.writeBytes("WAVE")
        out.writeBytes("fmt ")
        out.writeInt(Integer.reverseBytes(16))                          // Subchunk1Size
        out.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // PCM
        out.writeShort(java.lang.Short.reverseBytes(CHANNELS.toShort()).toInt())
        out.writeInt(Integer.reverseBytes(SAMPLE_RATE))
        out.writeInt(Integer.reverseBytes(byteRate))
        out.writeShort(java.lang.Short.reverseBytes((CHANNELS * BYTES_PER_SAMPLE).toShort()).toInt())
        out.writeShort(java.lang.Short.reverseBytes(BITS_PER_SAMPLE.toShort()).toInt())
        out.writeBytes("data")
        out.writeInt(Integer.reverseBytes(dataLen.toInt()))
    }
}
