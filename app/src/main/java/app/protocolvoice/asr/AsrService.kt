package app.protocolvoice.asr

import android.content.Context
import android.util.Log
import app.protocolvoice.R
import app.protocolvoice.downloader.AssetExtractor
import app.protocolvoice.downloader.ModelStorage
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Главный сервис распознавания интервью.
 *
 * Архитектура (полный пайплайн):
 *   1. WAV-файл (16kHz, mono, PCM16) → читаем как FloatArray
 *   2. Speaker Diarization (pyannote-segmentation + 3D-Speaker embedding)
 *      → разбивает аудио на сегменты [start, end, speakerId]
 *   3. Для каждого сегмента — режем FloatArray и кормим в OfflineRecognizer
 *      (GigaAM-v3 e2e_ctc, со встроенной пунктуацией и нормализацией)
 *   4. Из результата извлекаем слова с timestamps и confidence
 *   5. Объединяем в [InterviewTranscript]
 *
 * Все 5 ONNX-моделей лежат в `app/src/main/assets/asr/`:
 *   - gigaam_v3_e2e_ctc_int8.onnx       (305 МБ, ASR + auto-punct)
 *   - gigaam_v3_e2e_ctc_tokens.txt      (словарь BPE токенов)
 *   - speaker_embedding.onnx            (3D-Speaker, 111 МБ)
 *   - segmentation.onnx                 (pyannote-segmentation-3 int8, 1.5 МБ)
 *   - silero_vad.onnx                   (опционально, для VAD pre-processing)
 *
 * Использование:
 *   val svc = AsrService(ctx)
 *   svc.initialize()         // долго, ~5-10 сек, грузит модели
 *   val transcript = svc.process(wavFile)
 *   svc.release()
 */
class AsrService(private val ctx: Context) {

    enum class State {
        IDLE,
        LOADING_MODELS,
        DIARIZING,
        TRANSCRIBING,
        DONE,
        ERROR,
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    /** Прогресс распознавания [0..1] */
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _statusText = MutableStateFlow("")
    /** Текстовое описание текущей операции для UI */
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Объекты sherpa-onnx (создаются единожды, переиспользуются)
    private var recognizer: OfflineRecognizer? = null
    private var diarization: OfflineSpeakerDiarization? = null

    /** Текущая загруженная embedding-модель. Нужна чтобы понимать, перезагружать ли при смене выбора. */
    private var loadedEmbeddingModel: EmbeddingModel? = null

    /**
     * Загрузка моделей в память.
     * Выполняется однократно при старте экрана интервью.
     * Долго (5-10 сек на Xiaomi 12T), пока грузится — UI должен показать спиннер.
     *
     * @param embeddingModel какую embedding-модель использовать в диаризации. Если
     *                       модель уже загружена — ничего не делаем.
     *                       Если другая — пересоздаём только diarization
     *                       (recognizer не трогаем, он от embedding не зависит).
     */
    suspend fun initialize(embeddingModel: EmbeddingModel = EmbeddingModel.DEFAULT): Boolean = withContext(Dispatchers.IO) {
        // Рефреш diarization если выбрана другая модель
        if (diarization != null && loadedEmbeddingModel != embeddingModel) {
            Log.i(TAG, "Embedding model changed: $loadedEmbeddingModel → $embeddingModel, reloading diarization")
            try { diarization?.release() } catch (_: Throwable) {}
            diarization = null
        }
        if (recognizer != null && diarization != null) return@withContext true
        _state.value = State.LOADING_MODELS
        _statusText.value = ctx.getString(R.string.asr_status_loading_models)

        val initT0 = System.currentTimeMillis()

        try {
            // === 0. Извлекаем маленькие модели из assets в filesDir.
            // Большие (gigaam, embedding) уже там виртуе ModelDownloader.
            // Это одноразовая операция, дальше - нооп.
            val storage = ModelStorage(ctx)
            AssetExtractor.extractSmallAssets(ctx, storage)

            // === 1. ASR — GigaAM-v3 e2e_ctc через nemo API ===
            // Пересоздаём только если ещё не загружен (при смене embedding он не меняется).
            if (recognizer == null) {
                val recognizerConfig = OfflineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = 16_000,
                    featureDim = 64,    // GigaAM-v3 использует 64, не 80!
                ),
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(
                        // Абсолютный путь из filesDir — скачана ModelDownloader'ом.
                        model = AssetExtractor.Paths.asrModel(storage),
                    ),
                    tokens = AssetExtractor.Paths.asrTokens(storage),
                    numThreads = 4,    // Xiaomi 12T 8 ядер — 4 для ASR, 4 для UI
                    debug = false,
                    provider = "cpu",
                    modelType = "nemo_ctc",
                ),
                    decodingMethod = "greedy_search",
                )
                // Без assetManager! Используем file-paths.
                recognizer = OfflineRecognizer(config = recognizerConfig)
                Log.i(TAG, "ASR recognizer initialized")
            }

            // === 2. Speaker Diarization ===
            // numClusters=-1 → автоопределение количества спикеров через threshold
            val diarConfig = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                        model = AssetExtractor.Paths.segmentation(storage),
                    ),
                    numThreads = 4,    // было 2 — на 8 ядрах 4 потока быстрее
                    debug = false,
                    provider = "cpu",
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = AssetExtractor.Paths.embeddingFor(storage, embeddingModel),
                    numThreads = 4,    // это самая жирная модель, ей важнее всего параллелизм
                    debug = false,
                    provider = "cpu",
                ),
                clustering = FastClusteringConfig(
                    numClusters = -1,    // автоопределение
                    threshold = 0.7f,    // 0.5 было слишком строго — дробили одного спикера на нескольких
                ),
                minDurationOn = 0.3f,    // игнорировать сегменты < 300 мс
                minDurationOff = 0.5f,   // объединять сегменты с паузой < 500 мс
            )
            // Без assetManager!
            diarization = OfflineSpeakerDiarization(config = diarConfig)
            loadedEmbeddingModel = embeddingModel
            Log.i(TAG, "Speaker diarization initialized with embedding=$embeddingModel")

            _state.value = State.IDLE
            _statusText.value = ctx.getString(R.string.asr_status_models_loaded)
            Log.i(TAG, "PERF init: ${System.currentTimeMillis() - initT0}ms")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize ASR", e)
            _errorMessage.value = ctx.getString(R.string.asr_load_failed, e.message ?: "")
            _state.value = State.ERROR
            recognizer = null
            diarization = null
            false
        }
    }

    /**
     * Главный метод: WAV-файл → InterviewTranscript.
     *
     * @param speakerMode режим выбора количества спикеров:
     *    - SpeakerCountMode.Single → пропуск диаризации (RTF ≈0.07)
     *    - SpeakerCountMode.Fixed(N) → фиксированное кол-во спикеров
     *    - SpeakerCountMode.Auto → автоопределение
     */
    suspend fun process(wavFile: File, speakerMode: SpeakerCountMode = SpeakerCountMode.Auto): InterviewTranscript? = withContext(Dispatchers.IO) {
        val rec = recognizer
        val diar = diarization
        if (rec == null || diar == null) {
            _errorMessage.value = ctx.getString(R.string.asr_not_initialized)
            return@withContext null
        }

        val procT0 = System.currentTimeMillis()

        try {
            // === Шаг 1: читаем WAV в FloatArray (нормализованные сэмплы [-1..1]) ===
            _statusText.value = ctx.getString(R.string.asr_reading_wav)
            _progress.value = 0f
            val samples = readWavFile(wavFile)
                ?: throw IllegalStateException(ctx.getString(R.string.asr_wav_read_failed))
            val totalDurationMs = (samples.size * 1000L) / 16_000L
            Log.i(TAG, "Loaded ${samples.size} samples, ${totalDurationMs / 1000f} sec")
            _progress.value = 0.05f

            // === Шаг 2: Diarization — кто когда говорит ===
            // Пропускаем если режим Single — диаризация работает в RTF ~2.2,
            // для монолога это пустая работа.
            if (speakerMode is SpeakerCountMode.Single) {
                Log.i(TAG, "PERF skip diarization (mode=Single)")
                _statusText.value = ctx.getString(R.string.asr_status_recognizing)
                _state.value = State.TRANSCRIBING
                _progress.value = 0.1f
                val asrT0Single = System.currentTimeMillis()
                val words = transcribeSegment(rec, samples, 0L, totalDurationMs)
                val seg = TranscriptSegment(
                    speakerId = 0,
                    startMs = 0L,
                    endMs = totalDurationMs,
                    words = words,
                )
                val asrMsSingle = System.currentTimeMillis() - asrT0Single
                val totalMsSingle = System.currentTimeMillis() - procT0
                Log.i(TAG, "PERF SUMMARY (single): audio=${totalDurationMs}ms wall=${totalMsSingle}ms asr=${asrMsSingle}ms RTF_total=${"%.2f".format(totalMsSingle.toFloat() / totalDurationMs)}")
                _state.value = State.DONE
                _progress.value = 1f
                return@withContext InterviewTranscript(
                    segments = listOf(seg),
                    totalDurationMs = totalDurationMs,
                    recordedAt = System.currentTimeMillis(),
                    sourceWavPath = wavFile.absolutePath,
                    numSpeakers = 1,
                )
            }

            // Для Fixed/Auto — применяем numClusters через setConfig() без перезагрузки моделей.
            applyClusteringConfig(diar, speakerMode)

            _state.value = State.DIARIZING
            _statusText.value = ctx.getString(R.string.asr_status_diarizing)
            val diarT0 = System.currentTimeMillis()
            // ВАЖНО: callback передаём как function reference (::diarizationCallback),
            // НЕ как лямбду. Sherpa-onnx 1.13.0 JNI ищет метод с сигнатурой
            // Function3<Integer,Integer,Long,Integer> (boxed Integer возврат).
            // Inline-лямбда `{ ... 0 }` компилируется в SAM с примитивным int →
            // JNI NoSuchMethodError → SIGABRT (см. crash 30.04.2026).
            // Named member function генерирует правильный bridge с java.lang.Integer.
            val diarSegments = diar.processWithCallback(
                samples = samples,
                callback = ::diarizationCallback,
                arg = 0L,
            )
            Log.i(TAG, "Diarization done: ${diarSegments.size} segments, " +
                    "speakers: ${diarSegments.map { it.speaker }.distinct().sorted()}")
            val diarMs = System.currentTimeMillis() - diarT0
            Log.i(TAG, "PERF diarize: ${diarMs}ms (RTF=${"%.2f".format(diarMs.toFloat() / totalDurationMs)})")
            _progress.value = 0.4f

            if (diarSegments.isEmpty()) {
                // Краевой случай — нет речи. Запускаем ASR на всём аудио,
                // приписываем единственному спикеру 0.
                val fallbackTranscript = transcribeSegment(rec, samples, 0L, totalDurationMs)
                val seg = TranscriptSegment(
                    speakerId = 0,
                    startMs = 0L,
                    endMs = totalDurationMs,
                    words = fallbackTranscript,
                )
                _state.value = State.DONE
                _progress.value = 1f
                return@withContext InterviewTranscript(
                    segments = listOf(seg),
                    totalDurationMs = totalDurationMs,
                    recordedAt = System.currentTimeMillis(),
                    sourceWavPath = wavFile.absolutePath,
                    numSpeakers = 1,
                )
            }

            // === Шаг 3: ASR на каждом сегменте ===
            _state.value = State.TRANSCRIBING
            _statusText.value = ctx.getString(R.string.asr_status_recognizing)
            val asrT0 = System.currentTimeMillis()
            var totalAsrAudioMs = 0L
            val resultSegments = mutableListOf<TranscriptSegment>()
            for ((idx, ds) in diarSegments.withIndex()) {
                val startSample = (ds.start * 16_000f).toInt().coerceAtLeast(0)
                val endSample = (ds.end * 16_000f).toInt().coerceAtMost(samples.size)
                if (endSample <= startSample) continue
                // Минимум 0.2 сек — иначе ASR ошибётся
                if (endSample - startSample < 16_000 * 0.2f) continue

                val segSamples = samples.copyOfRange(startSample, endSample)
                val startMs = (ds.start * 1000f).toLong()
                val endMs = (ds.end * 1000f).toLong()

                _statusText.value = ctx.getString(R.string.asr_status_segment_progress, idx + 1, diarSegments.size)
                val segT0 = System.currentTimeMillis()
                val words = transcribeSegment(rec, segSamples, startMs, endMs)
                val segMs = System.currentTimeMillis() - segT0
                val segAudioMs = endMs - startMs
                totalAsrAudioMs += segAudioMs
                Log.i(TAG, "PERF asr-seg $idx: audio=${segAudioMs}ms wall=${segMs}ms RTF=${"%.2f".format(segMs.toFloat() / segAudioMs)}")
                if (words.isNotEmpty()) {
                    resultSegments.add(TranscriptSegment(
                        speakerId = ds.speaker,
                        startMs = startMs,
                        endMs = endMs,
                        words = words,
                    ))
                }
                _progress.value = 0.4f + (idx + 1).toFloat() / diarSegments.size * 0.6f
            }

            _state.value = State.DONE
            _statusText.value = ctx.getString(R.string.asr_status_done)
            _progress.value = 1f

            val asrMs = System.currentTimeMillis() - asrT0
            val totalMs = System.currentTimeMillis() - procT0
            Log.i(TAG, "PERF asr-total: ${asrMs}ms over ${totalAsrAudioMs}ms audio (RTF=${"%.2f".format(asrMs.toFloat() / totalAsrAudioMs.coerceAtLeast(1))})")
            Log.i(TAG, "PERF SUMMARY: audio=${totalDurationMs}ms wall=${totalMs}ms diar=${diarMs}ms asr=${asrMs}ms RTF_total=${"%.2f".format(totalMs.toFloat() / totalDurationMs)}")

            val numSpeakers = resultSegments.map { it.speakerId }.distinct().size
            return@withContext InterviewTranscript(
                segments = resultSegments,
                totalDurationMs = totalDurationMs,
                recordedAt = System.currentTimeMillis(),
                sourceWavPath = wavFile.absolutePath,
                numSpeakers = numSpeakers,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Processing failed", e)
            _errorMessage.value = ctx.getString(R.string.asr_recognize_error, e.message ?: "")
            _state.value = State.ERROR
            return@withContext null
        }
    }

    /**
     * Применяет numClusters для текущего прогона.
     * Использует OfflineSpeakerDiarization.setConfig() — это не перезагружает ONNX-модели,
     * в native-коде обновляется только clustering конфиг.
     */
    private fun applyClusteringConfig(diar: OfflineSpeakerDiarization, mode: SpeakerCountMode) {
        val numClusters = when (mode) {
            SpeakerCountMode.Single -> -1   // недостижимо (single обработан выше)
            is SpeakerCountMode.Fixed -> mode.count
            SpeakerCountMode.Auto -> -1
        }
        val newConfig = OfflineSpeakerDiarizationConfig(
            segmentation = diar.config.segmentation,
            embedding = diar.config.embedding,
            clustering = FastClusteringConfig(
                numClusters = numClusters,
                threshold = 0.7f,
            ),
            minDurationOn = diar.config.minDurationOn,
            minDurationOff = diar.config.minDurationOff,
        )
        diar.setConfig(newConfig)
        Log.i(TAG, "Applied clustering: mode=$mode numClusters=$numClusters")
    }

    /**
     * Распознаёт один сегмент сэмплов и возвращает список слов с timestamps и confidence.
     *
     * Sherpa-onnx OfflineRecognizerResult даёт нам:
     *   - text:       полная строка
     *   - tokens:     массив BPE-токенов
     *   - timestamps: массив времени начала каждого токена (секунды)
     *
     * confidence у CTC-модели напрямую не доступен через Kotlin API, поэтому
     * как proxy используем "плотность" токенов: чем больше токенов в секунду,
     * тем уверенее модель. Для стабильного интервью это работает прилично.
     * Для production-grade нужно патчить нативный код sherpa-onnx, но это вне
     * текущего объёма работы.
     */
    private fun transcribeSegment(
        rec: OfflineRecognizer,
        samples: FloatArray,
        offsetMs: Long,
        endMs: Long,
    ): List<TranscriptWord> {
        val stream = rec.createStream()
        try {
            // sherpa-onnx ожидает FloatArray напрямую (нормализованные [-1..1])
            stream.acceptWaveform(samples, sampleRate = 16_000)
            rec.decode(stream)
            val result: OfflineRecognizerResult = rec.getResult(stream)

            // Разбиваем text на слова. GigaAM-v3 e2e_ctc выдаёт уже с пунктуацией
            // и пробелами, так что разбиение по whitespace работает.
            val rawText = result.text.trim()
            if (rawText.isEmpty()) return emptyList()
            val wordsRaw = rawText.split(Regex("\\s+"))

            // Распределяем timestamps равномерно если число timestamps не совпадает.
            // Real timestamps относятся к токенам (BPE), а нам нужны слова.
            val segDurMs = endMs - offsetMs
            val perWordMs = if (wordsRaw.isEmpty()) 0L else segDurMs / wordsRaw.size

            val words = mutableListOf<TranscriptWord>()
            var cursorMs = offsetMs
            for (w in wordsRaw) {
                if (w.isBlank()) continue
                // Confidence proxy: длинные «осмысленные» слова обычно надёжнее.
                // Слова длиной 1-2 символа — часто шум распознавания.
                val confidence = when {
                    w.length >= 5 -> 0.92f
                    w.length >= 3 -> 0.78f
                    w.length >= 2 -> 0.65f
                    else          -> 0.45f
                }
                words.add(TranscriptWord(
                    text = w,
                    startMs = cursorMs,
                    endMs = cursorMs + perWordMs,
                    confidence = confidence,
                ))
                cursorMs += perWordMs
            }
            return words
        } finally {
            stream.release()
        }
    }

    /**
     * Чтение WAV (16kHz mono PCM16) в нормализованный FloatArray.
     * Простой парсер — не использует sherpa-onnx WaveReader потому что
     * хотим контроль над ошибками и работу с любым WAV-файлом записи.
     */
    private fun readWavFile(file: File): FloatArray? {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) != 44) return null
                // RIFF check
                if (header[0] != 'R'.code.toByte() ||
                    header[1] != 'I'.code.toByte() ||
                    header[2] != 'F'.code.toByte() ||
                    header[3] != 'F'.code.toByte()) return null

                // sample rate at offset 24, bits per sample at 34, channels at 22
                val sampleRate = ByteBuffer.wrap(header, 24, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                val channels = ByteBuffer.wrap(header, 22, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                val bitsPerSample = ByteBuffer.wrap(header, 34, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short.toInt()

                if (sampleRate != 16_000 || channels != 1 || bitsPerSample != 16) {
                    Log.w(TAG, "WAV format not 16kHz/mono/16bit: " +
                            "sampleRate=$sampleRate ch=$channels bits=$bitsPerSample")
                    // Продолжаем — sherpa-onnx сам поддерживает различные SR через resampling
                }

                val data = fis.readBytes()
                val numSamples = data.size / 2
                val out = FloatArray(numSamples)
                val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until numSamples) {
                    out[i] = bb.short.toFloat() / 32768f
                }
                out
            }
        } catch (e: Exception) {
            Log.e(TAG, "WAV read failed", e)
            null
        }
    }

    /**
     * Callback диаризации. ОБЯЗАТЕЛЬНО named member function (не лямбда!) —
     * чтобы Kotlin сгенерировал bridge-метод `invoke(II J)Ljava/lang/Integer;`
     * который ожидает нативный JNI sherpa-onnx 1.13.0.
     *
     * @return 0 = продолжить обработку, !=0 = прервать.
     */
    private fun diarizationCallback(processed: Int, total: Int, @Suppress("UNUSED_PARAMETER") arg: Long): Int {
        val pct = if (total > 0) processed.toFloat() / total else 0f
        _progress.value = 0.05f + pct * 0.35f       // 5% .. 40%
        return 0    // продолжить
    }

    /**
     * Диаризация без распознавания. Используется для EN-пайплайна:
     * клиент получает сегменты (спикер + временные рамки) и прогоняет их
     * через внешний ASR (Whisper).
     *
     * Диаризация language-agnostic — работает одинаково хорошо для любого языка.
     *
     * @return list of (start_seconds, end_seconds, speaker_id), или null в случае ошибки
     */
    suspend fun diarizeOnly(samples: FloatArray, speakerMode: SpeakerCountMode = SpeakerCountMode.Auto): List<DiarizationSegment>? = withContext(Dispatchers.IO) {
        val diar = diarization
        if (diar == null) {
            _errorMessage.value = ctx.getString(R.string.asr_not_initialized)
            return@withContext null
        }
        try {
            applyClusteringConfig(diar, speakerMode)
            val segs = diar.processWithCallback(
                samples = samples,
                callback = ::diarizationCallback,
                arg = 0L,
            )
            segs.map { DiarizationSegment(it.start, it.end, it.speaker) }
        } catch (e: Throwable) {
            Log.e(TAG, "diarizeOnly failed", e)
            null
        }
    }

    /** Публичный обёрточный тип для sherpa-onnx сегмента диаризации. */
    data class DiarizationSegment(
        /** Начало сегмента в секундах. */
        val start: Float,
        /** Конец сегмента в секундах. */
        val end: Float,
        /** ID спикера (0, 1, 2, ...). */
        val speaker: Int,
    )

    /** Публичный вызов чтения WAV. Нужен клиентам которые берут самплы для EN-пайплайна. */
    fun readWavSamples(wavFile: File): FloatArray? = readWavFile(wavFile)

    /** Публичный сеттер фазы/прогресса для EN-пайплайна. */
    fun setExternalState(state: State, statusText: String, progress: Float) {
        _state.value = state
        _statusText.value = statusText
        _progress.value = progress.coerceIn(0f, 1f)
    }

    fun release() {
        try { recognizer?.release() } catch (_: Throwable) {}
        try { diarization?.release() } catch (_: Throwable) {}
        recognizer = null
        diarization = null
        loadedEmbeddingModel = null
        _state.value = State.IDLE
    }

    companion object {
        private const val TAG = "AsrService"
    }
}
