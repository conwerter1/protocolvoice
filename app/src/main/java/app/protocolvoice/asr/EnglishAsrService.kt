package app.protocolvoice.asr

import android.content.Context
import android.util.Log
import app.protocolvoice.R
import app.protocolvoice.downloader.AssetExtractor
import app.protocolvoice.downloader.ModelStorage
import app.protocolvoice.downloader.ModelRegistry
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Английский ASR через sherpa-onnx Whisper base.en (int8).
 *
 * Это отдельный сервис — не путать с основным [AsrService] (русский GigaAM).
 * Они могут существовать параллельно, и UI выбирает какой использовать
 * в зависимости от языка пользователя.
 *
 * Whisper имеет встроенную пунктуацию и капитализацию — это ключевое
 * преимущество перед другими английскими ASR-моделями.
 *
 * Особенности Whisper API в sherpa-onnx (отличается от nemo CTC):
 *   - Использует encoder + decoder (две ONNX-модели вместо одной)
 *   - featureDim другой (Whisper использует 80 mel-каналов)
 *   - Нет встроенной диаризации — для multi-speaker нужно использовать
 *     основной AsrService.diarization() для сегментации, потом резать
 *     аудио и кормить в Whisper по сегментам
 *
 * Использование:
 *   val svc = EnglishAsrService(ctx)
 *   svc.initialize()
 *   val text = svc.transcribe(samples, sampleRate)
 *   svc.release()
 */
class EnglishAsrService(private val ctx: Context) {

    private var recognizer: OfflineRecognizer? = null

    /**
     * Загрузка моделей в память.
     * Должна быть вызвана после того как все 3 EN-модели скачаны и валидны
     * на диске (ModelStorage.isValid).
     *
     * @return true если модели загружены, false при ошибке.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (recognizer != null) return@withContext true

        try {
            val storage = ModelStorage(ctx)

            // Проверяем что все 3 EN-модели на месте
            for (m in ModelRegistry.EN_ASR_BUNDLE) {
                if (!storage.isValid(m, checkHash = false)) {
                    Log.e(TAG, "EN model not on disk: ${m.id}")
                    return@withContext false
                }
            }

            // Пути к файлам в filesDir
            val encoder = storage.fileFor(ModelRegistry.ASR_EN_ENCODER).absolutePath
            val decoder = storage.fileFor(ModelRegistry.ASR_EN_DECODER).absolutePath
            val tokens = storage.fileFor(ModelRegistry.ASR_EN_TOKENS).absolutePath

            val recognizerConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = 16_000,
                    featureDim = 80,    // Whisper использует 80 mel-каналов!
                ),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = encoder,
                        decoder = decoder,
                        language = "en",
                        task = "transcribe",
                    ),
                    tokens = tokens,
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                    modelType = "whisper",
                ),
                decodingMethod = "greedy_search",
            )

            recognizer = OfflineRecognizer(config = recognizerConfig)
            Log.i(TAG, "English Whisper recognizer initialized")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize English ASR: ${e.message}", e)
            recognizer = null
            false
        }
    }

    /**
     * Распознать одну порцию аудио (один speaker segment).
     *
     * Whisper имеет лимит ~30 секунд на одну инференцию. Если segments длиннее —
     * вызывающий код должен резать на кусочки.
     *
     * @param samples float32 PCM в [-1, 1]
     * @param sampleRate частота (обычно 16000)
     * @return распознанный текст или null при ошибке
     */
    suspend fun transcribe(samples: FloatArray, sampleRate: Int = 16_000): String? = withContext(Dispatchers.IO) {
        val rec = recognizer ?: return@withContext null
        try {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, sampleRate = sampleRate)
            rec.decode(stream)
            val result: OfflineRecognizerResult = rec.getResult(stream)
            stream.release()
            result.text
        } catch (e: Throwable) {
            Log.e(TAG, "EN transcribe failed: ${e.message}", e)
            null
        }
    }

    /**
     * Освободить нативные ресурсы.
     */
    fun release() {
        try {
            recognizer?.release()
        } catch (_: Throwable) {}
        recognizer = null
    }

    companion object {
        private const val TAG = "EnglishAsrService"
    }
}
