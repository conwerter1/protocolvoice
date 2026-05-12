package app.protocolvoice.ui

import android.util.Log
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.protocolvoice.R
import app.protocolvoice.asr.AsrLanguage
import app.protocolvoice.asr.AsrService
import app.protocolvoice.asr.EmbeddingModel
import app.protocolvoice.asr.EnglishAsrService
import app.protocolvoice.asr.InterviewMetadata
import app.protocolvoice.asr.InterviewTranscript
import app.protocolvoice.asr.Participants
import app.protocolvoice.asr.SpeakerCountMode
import app.protocolvoice.asr.TranscriptSegment
import app.protocolvoice.audio.AudioPlayer
import app.protocolvoice.audio.AudioRecorder
import app.protocolvoice.audio.AudioImporter
import app.protocolvoice.audio.LongAudioProcessor
import app.protocolvoice.audio.Mp4Encoder
import app.protocolvoice.data.SessionStore
import app.protocolvoice.docx.DocxBuilder
import app.protocolvoice.downloader.ModelDownloader
import app.protocolvoice.downloader.ModelRegistry
import app.protocolvoice.downloader.ModelStorage
import app.protocolvoice.summary.SummaryFacade
import app.protocolvoice.summary.SummaryFormatter
import app.protocolvoice.summary.default_tier.SummaryResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Единый ViewModel для экрана интервью. Управляет жизненным циклом записи,
 * запуска ASR и экспортом DOCX. Composable-экран только наблюдает StateFlow'ы.
 *
 * Workflow:
 *   IDLE → RECORDING ↔ PAUSED → STOPPED → PROCESSING → READY → (ExportDocx)
 *
 * Воспроизведения аудио и редактирования сегментов в этой версии нет —
 * только фиксация распознанного и выгрузка протокола.
 */
class InterviewViewModel(app: Application) : AndroidViewModel(app) {

    enum class Phase {
        /** Стартовое состояние, можно настраивать метаданные/участников и записывать. */
        IDLE,
        /** Идёт запись. */
        RECORDING,
        /** Пауза в записи. */
        PAUSED,
        /** Запись остановлена, файл готов, ASR ещё не запущен. */
        RECORDED,
        /** ASR работает. */
        PROCESSING,
        /** Распознавание завершилось, можно править и экспортировать. */
        READY,
        /** Ошибка. */
        ERROR,
    }


    val recorder: AudioRecorder = AudioRecorder(app)
    val asr: AsrService = AsrService(app)
    val player: AudioPlayer = AudioPlayer(app)

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _metadata = MutableStateFlow(InterviewMetadata())
    val metadata: StateFlow<InterviewMetadata> = _metadata.asStateFlow()

    private val _participants = MutableStateFlow(Participants())
    val participants: StateFlow<Participants> = _participants.asStateFlow()

    private val _transcript = MutableStateFlow<InterviewTranscript?>(null)
    val transcript: StateFlow<InterviewTranscript?> = _transcript.asStateFlow()

    private val _wavFile = MutableStateFlow<File?>(null)
    val wavFile: StateFlow<File?> = _wavFile.asStateFlow()

    /** Путь к M4A-файлу записи. Заполняется после фоновой конвертации WAV → AAC.
     *  Пока null — исходный WAV в _wavFile ещё живёт и используется как fallback. */
    private val _m4aFile = MutableStateFlow<File?>(null)
    val m4aFile: StateFlow<File?> = _m4aFile.asStateFlow()

    /** true пока WAV→M4A конвертируется в фоне (для UI-индикатора). */
    private val _isCompressing = MutableStateFlow(false)
    val isCompressing: StateFlow<Boolean> = _isCompressing.asStateFlow()

    private val _exportedDocx = MutableStateFlow<Uri?>(null)
    val exportedDocx: StateFlow<Uri?> = _exportedDocx.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** ID текущей сохранённой сессии. null = ещё не сохраняли. */
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    /**
     * Режим выбора количества спикеров: 1 / 2 / 3 / 4 / Авто.
     * Выставляется в UI до нажатия «Распознать». Single пропускает диаризацию
     * целиком (RTF ≈0.07). Fixed(N) даёт стабильное количество спикеров
     * и небольшое ускорение кластеризации. Auto — прежнее поведение.
     */
    private val _speakerCountMode = MutableStateFlow<SpeakerCountMode>(SpeakerCountMode.Auto)
    val speakerCountMode: StateFlow<SpeakerCountMode> = _speakerCountMode.asStateFlow()

    fun setSpeakerCountMode(mode: SpeakerCountMode) {
        _speakerCountMode.value = mode
    }

    /**
     * Выбранная embedding-модель для диаризации. Сохраняется в SharedPreferences —
     * переживает рестарт приложения. При изменении триггерит перезагрузку
     * диаризации в фоне (~1-2 сек, не блокирует UI).
     */
    private val _embeddingModel = MutableStateFlow(loadEmbeddingPref())
    val embeddingModel: StateFlow<EmbeddingModel> = _embeddingModel.asStateFlow()

    /** true пока идёт перезагрузка embedding-модели. UI показывает индикатор. */
    private val _isReloadingEmbedding = MutableStateFlow(false)
    val isReloadingEmbedding: StateFlow<Boolean> = _isReloadingEmbedding.asStateFlow()


    fun setEmbeddingModel(model: EmbeddingModel) {
        if (_embeddingModel.value == model) return
        _embeddingModel.value = model
        saveEmbeddingPref(model)
        // Релоад в фоне — UI покажет индикатор через isReloadingEmbedding.
        viewModelScope.launch {
            _isReloadingEmbedding.value = true
            asr.initialize(model)
            _isReloadingEmbedding.value = false
        }
    }

    private fun prefs() = getApplication<Application>()
        .getSharedPreferences("interview_prefs", Context.MODE_PRIVATE)

    private fun loadEmbeddingPref(): EmbeddingModel {
        val name = prefs().getString("embedding_model", null) ?: return EmbeddingModel.DEFAULT
        return runCatching { EmbeddingModel.valueOf(name) }.getOrDefault(EmbeddingModel.DEFAULT)
    }

    private fun saveEmbeddingPref(model: EmbeddingModel) {
        prefs().edit().putString("embedding_model", model.name).apply()
    }

    /** Подгрузить ASR-модели в фоне, если ещё не загружены. */
    /**
     * Язык распознавания речи. RU — GigaAM-v3 (русский, по умолчанию),
     * EN — Whisper base.en (английский, скачивается опционально).
     * Сохраняется в SharedPreferences — выбор переживает рестарт приложения.
     */
    private val _asrLanguage = MutableStateFlow(loadAsrLanguagePref())
    val asrLanguage: StateFlow<AsrLanguage> = _asrLanguage.asStateFlow()

    fun setAsrLanguage(lang: AsrLanguage) {
        if (_asrLanguage.value == lang) return
        _asrLanguage.value = lang
        prefs().edit().putString("asr_language", lang.name).apply()

        // Одновременно меняем язык интерфейса. Архитектурное решение:
        // RU/EN язык распознавания = RU/EN язык UI. Проще и логичнее для пользователя.
        // Используем AppCompatDelegate.setApplicationLocales() — работает в runtime без перезапуска
        // или с автоматическим рестартом Activity (в зависимости от configChanges).
        val localeTag = when (lang) {
            AsrLanguage.RU -> "ru"
            AsrLanguage.EN -> "en"
        }
        val locales = androidx.core.os.LocaleListCompat.forLanguageTags(localeTag)
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun loadAsrLanguagePref(): AsrLanguage {
        val name = prefs().getString("asr_language", null) ?: return AsrLanguage.RU
        return runCatching { AsrLanguage.valueOf(name) }.getOrDefault(AsrLanguage.RU)
    }

    /**
     * Скачаны ли все модели для выбранного языка. UI использует чтобы
     * показать diaog "скачать" или бадж "нужны модели".
     */
    fun isAsrLanguageReady(ctx: Context, lang: AsrLanguage): Boolean {
        val storage = ModelStorage(ctx)
        return when (lang) {
            AsrLanguage.RU -> ModelRegistry.FIRST_RUN_REQUIRED.all { storage.isValid(it, checkHash = false) }
            AsrLanguage.EN -> ModelRegistry.EN_ASR_BUNDLE.all { storage.isValid(it, checkHash = false) }
        }
    }

    /** Скачать EN модели. Вызывается из UI диалога "Скачать?". */
    private val _isDownloadingEn = MutableStateFlow(false)
    val isDownloadingEn: StateFlow<Boolean> = _isDownloadingEn.asStateFlow()

    private val _enDownloadProgress = MutableStateFlow(0f)
    val enDownloadProgress: StateFlow<Float> = _enDownloadProgress.asStateFlow()

    fun downloadEnglishModels(ctx: Context, onComplete: (Boolean) -> Unit = {}) {
        if (_isDownloadingEn.value) return  // уже качается
        viewModelScope.launch(Dispatchers.IO) {
            _isDownloadingEn.value = true
            _enDownloadProgress.value = 0f
            try {
                val storage = ModelStorage(ctx)
                val missing = ModelRegistry.EN_ASR_BUNDLE.filter { !storage.isValid(it, checkHash = false) }
                if (missing.isEmpty()) {
                    _enDownloadProgress.value = 1f
                    withContext(Dispatchers.Main) { onComplete(true) }
                    return@launch
                }
                val downloader = ModelDownloader(storage)
                // Подписываемся на прогресс
                val totalBytes = missing.sumOf { it.sizeBytes }
                val progressJob = launch {
                    downloader.bytesDownloaded.collect { bytes ->
                        if (totalBytes > 0) _enDownloadProgress.value = bytes.toFloat() / totalBytes
                    }
                }
                val ok = downloader.downloadAll(missing)
                progressJob.cancel()
                _enDownloadProgress.value = if (ok) 1f else 0f
                withContext(Dispatchers.Main) {
                    _toast.value = if (ok) "Английские модели скачаны" else "Не удалось скачать английские модели"
                    onComplete(ok)
                }
            } catch (e: Throwable) {
                android.util.Log.e("InterviewViewModel", "downloadEnglishModels failed", e)
                withContext(Dispatchers.Main) {
                    _toast.value = "Ошибка скачивания: ${e.message?.take(80)}"
                    onComplete(false)
                }
            } finally {
                _isDownloadingEn.value = false
            }
        }
    }

    /**
     * EnglishAsrService — singleton lazy. Создаётся только когда нужен.
     * Переиспользуется между вызовами transcribe() чтобы не перезагружать Whisper.
     */
    private var enAsr: EnglishAsrService? = null

    /** Получить (или создать) EN ASR-сервис и инициализировать его. */
    private suspend fun ensureEnAsr(ctx: Context): EnglishAsrService? {
        var svc = enAsr
        if (svc == null) {
            svc = EnglishAsrService(ctx)
            if (!svc.initialize()) {
                svc.release()
                return null
            }
            enAsr = svc
        }
        return svc
    }

    fun preloadAsrModels() {
        viewModelScope.launch {
            asr.initialize(_embeddingModel.value)
        }
    }

    // -----------------------------------------------------------------
    // Импорт аудиофайла из внешнего источника (SAF / file picker)
    // -----------------------------------------------------------------

    /** Идёт импорт аудио (mp3/m4a/wav/...) → WAV 16kHz mono. */
    private val _isImportingAudio = MutableStateFlow(false)
    val isImportingAudio: StateFlow<Boolean> = _isImportingAudio.asStateFlow()

    /** Прогресс импорта [0..1]. */
    private val _importProgress = MutableStateFlow(0f)
    val importProgress: StateFlow<Float> = _importProgress.asStateFlow()

    /**
     * Импортировать аудиофайл из внешнего URI (из файл-пикера SAF).
     *
     * Источник может быть любым (mp3/m4a/wav/ogg/aac/flac/3gp/amr) — всё что андроид
     * MediaCodec умеет распаковать. На выходе — 16kHz mono PCM16 WAV в filesDir/recordings/.
     *
     * После успеха приложение переходит в фазу RECORDED — пользователь может выбрать
     * режим спикеров и нажать «Распознать» как обычно.
     */
    fun importAudioFromUri(uri: Uri) {
        if (_isImportingAudio.value) return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _isImportingAudio.value = true
            _importProgress.value = 0f
            // Сбрасываем предыдущую сессию (как в resetSession, но не трогаем metadata).
            player.stop()
            player.release()
            _transcript.value = null
            _m4aFile.value = null
            _exportedDocx.value = null
            _summaryResult.value = null
            _summaryError.value = null
            _exportedSummaryUri.value = null
            _currentSessionId.value = null

            try {
                val result = AudioImporter.importToWav(
                    ctx = ctx,
                    uri = uri,
                    onProgress = { p -> _importProgress.value = p },
                )
                _wavFile.value = result.wavFile
                _phase.value = Phase.RECORDED
                _toast.value = "Аудио импортировано (${result.durationMs / 1000}с). Нажмите «Распознать»."
                Log.i(
                    "InterviewVM",
                    "Audio imported: ${result.wavFile.absolutePath} " +
                        "(${result.durationMs}ms, src=${result.sourceSampleRate}Hz/${result.sourceChannels}ch)",
                )
            } catch (e: Throwable) {
                Log.e("InterviewVM", "Audio import failed", e)
                _toast.value = "Не удалось импортировать аудио: ${e.message}"
                _phase.value = Phase.IDLE
            } finally {
                _isImportingAudio.value = false
                _importProgress.value = 0f
            }
        }
    }

    // -----------------------------------------------------------------
    // Саммаризация интервью (Default tier: NER + LexRank)
    // -----------------------------------------------------------------

    /** Результат саммаризации. null пока не сгенерировано. */
    private val _summaryResult = MutableStateFlow<SummaryResult?>(null)
    val summaryResult: StateFlow<SummaryResult?> = _summaryResult.asStateFlow()

    /** Идёт генерация резюме (UI показывает spinner). */
    private val _isGeneratingSummary = MutableStateFlow(false)
    val isGeneratingSummary: StateFlow<Boolean> = _isGeneratingSummary.asStateFlow()

    /** Ошибка генерации (null если ок). */
    private val _summaryError = MutableStateFlow<String?>(null)
    val summaryError: StateFlow<String?> = _summaryError.asStateFlow()

    /** Lazy-созданный facade. Null если модели NER не скачаны. */
    @Volatile private var summaryFacade: SummaryFacade? = null

    /**
     * Собрать [SummaryFacade]. Если модели NER распакованы в filesDir/models/summary/ —
     * создаётся с NER (full Default tier). Иначе без NER (только LexRank + regex).
     */
    private fun obtainSummaryFacade(): SummaryFacade {
        summaryFacade?.let { return it }
        val ctx = getApplication<Application>()
        val summaryDir = File(ctx.filesDir, "models/summary")
        val navecDir = File(summaryDir, "navec_news")
        val slovnetDir = File(summaryDir, "slovnet_ner")

        val facade = if (navecDir.isDirectory && slovnetDir.isDirectory) {
            try {
                SummaryFacade.withSlovnet(navecDir, slovnetDir)
            } catch (e: Throwable) {
                Log.w("InterviewVM", "Slovnet load failed, falling back to no-NER", e)
                SummaryFacade.withoutNer()
            }
        } else {
            Log.i("InterviewVM", "Summary NER models not unpacked yet, using no-NER mode")
            SummaryFacade.withoutNer()
        }
        summaryFacade = facade
        return facade
    }

    /**
     * Сгенерировать резюме по текущему transcript (Default tier).
     * Результат появится в [summaryResult]. На время генерации [isGeneratingSummary] = true.
     */
    fun generateSummary() {
        val transcript = _transcript.value ?: run {
            _toast.value = "Нет распознанного текста для резюме"
            return
        }
        if (_isGeneratingSummary.value) return  // уже идёт

        _isGeneratingSummary.value = true
        _summaryError.value = null
        viewModelScope.launch {
            try {
                val plainText = transcript.segments.joinToString(" ") { it.text }
                val result = withContext(Dispatchers.Default) {
                    obtainSummaryFacade().summarizeDefault(plainText)
                }
                _summaryResult.value = result
                Log.i(
                    "InterviewVM",
                    "Summary done in ${result.processingTimeMs}ms: " +
                        "${result.persons.size} PER, ${result.organizations.size} ORG, " +
                        "${result.locations.size} LOC, ${result.topQuotes.size} quotes",
                )
            } catch (e: Throwable) {
                Log.e("InterviewVM", "Summary generation failed", e)
                _summaryError.value = e.message ?: "Неизвестная ошибка"
                _toast.value = "Ошибка генерации резюме: ${e.message}"
            } finally {
                _isGeneratingSummary.value = false
            }
        }
    }

    /** Сбросить текущий результат резюме (напр. при закрытии экрана). */
    fun clearSummary() {
        _summaryResult.value = null
        _summaryError.value = null
    }

    /** URI последнего сохранённого .txt файла резюме (для повторного шаринга). */
    private val _exportedSummaryUri = MutableStateFlow<Uri?>(null)
    val exportedSummaryUri: StateFlow<Uri?> = _exportedSummaryUri.asStateFlow()

    /**
     * Получить plain-text резюме для клипборда / share intent. Добавляет
     * заголовок интервью и участников из текущих metadata/participants.
     */
    fun summaryToPlainText(): String? {
        val r = _summaryResult.value ?: return null
        val parts = _participants.value.names.values
            .filter { it.isNotBlank() }
            .toList()
        return SummaryFormatter.toPlainText(
            result = r,
            interviewTitle = _metadata.value.title.takeIf { it.isNotBlank() },
            participants = parts,
        )
    }

    /**
     * Сохранить резюме как .txt файл в Downloads/ProtocolVoice/.
     * Результат доступен в [exportedSummaryUri].
     */
    fun exportSummaryToTxt() {
        val text = summaryToPlainText() ?: run {
            _toast.value = "Нет резюме для сохранения"
            return
        }
        val r = _summaryResult.value ?: return

        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { writeSummaryTxt(text, r) }
            if (uri == null) {
                _toast.value = "Не удалось сохранить резюме"
            } else {
                _exportedSummaryUri.value = uri
                _toast.value = "Резюме сохранено в Downloads/ProtocolVoice/"
            }
        }
    }

    /** Низкоуровневая запись в Downloads/ProtocolVoice/<filename>.txt через MediaStore.
     *
     * При сбое MediaStore (это бывает на MIUI/HyperOS) делает fallback на запись
     * в app-private exports/ + FileProvider для возможности шаринга.
     */
    private fun writeSummaryTxt(text: String, result: SummaryResult): Uri? {
        val ctx = getApplication<Application>()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(result.generatedAtMs))
        val baseName = (_metadata.value.title.ifBlank { "Interview" })
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(80)
        val fileName = "Rezume_${baseName}_$ts.txt"  // ASCII-имя, в теле название проекта
        Log.i("InterviewVM", "writeSummaryTxt: fileName=$fileName, text=${text.length} chars")

        // Сначала пробуем MediaStore (Android Q+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/ProtocolVoice")
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri == null) {
                    Log.w("InterviewVM", "MediaStore.insert returned null, falling back to FileProvider")
                } else {
                    resolver.openOutputStream(uri)?.use { os ->
                        os.write(text.toByteArray(Charsets.UTF_8))
                    } ?: run {
                        Log.w("InterviewVM", "openOutputStream returned null")
                    }
                    Log.i("InterviewVM", "writeSummaryTxt MediaStore OK: $uri")
                    return uri
                }
            } catch (e: Exception) {
                Log.w("InterviewVM", "MediaStore write failed, will fallback", e)
            }
        }

        // Fallback: пытаемся записать напрямую в публичный Downloads (работает
        // до Android 10, или если MediaStore недоступен).
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val pvDir = File(downloads, "ProtocolVoice")
            if (!pvDir.exists()) pvDir.mkdirs()
            val outFile = File(pvDir, fileName)
            outFile.writeText(text, Charsets.UTF_8)
            Log.i("InterviewVM", "writeSummaryTxt public Downloads OK: ${outFile.absolutePath}")
            return Uri.fromFile(outFile)
        } catch (e: Exception) {
            Log.w("InterviewVM", "public Downloads write failed, last resort: app-private", e)
        }

        // Last resort: app-private + FileProvider (всегда работает, но невидимо в проводнике).
        return try {
            val out = File(ctx.filesDir, "exports/$fileName")
            out.parentFile?.mkdirs()
            out.writeText(text, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
            Log.i("InterviewVM", "writeSummaryTxt FileProvider fallback OK: ${out.absolutePath}")
            uri
        } catch (e: Exception) {
            Log.e("InterviewVM", "writeSummaryTxt ALL paths failed", e)
            null
        }
    }

    /** Intent для шаринга резюме как plain-text (без файла). */
    fun shareSummaryAsTextIntent(): Intent? {
        val text = summaryToPlainText() ?: return null
        val title = _metadata.value.title.ifBlank { "Резюме интервью" }
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Резюме: $title")
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    /** Intent для шаринга сохранённого .txt файла. */
    fun shareSummaryTxtIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Скопировать резюме в системный буфер обмена через нативный Android API
     * (не Compose обёртку). Это важно потому что Compose ClipboardManager.setText()
     * в некоторых версиях обрезает длинные строки. Нативный клипборд Android не имеет
     * такого лимита.
     *
     * Вызывает toast внутри себя — вызывающий код просто вызывает без дополнений.
     */
    fun copySummaryToClipboard() {
        val text = summaryToPlainText() ?: run {
            _toast.value = "Нет резюме для копирования"
            return
        }
        try {
            val ctx = getApplication<Application>()
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Резюме интервью", text)
            clipboard.setPrimaryClip(clip)
            Log.i("InterviewVM", "Summary copied to clipboard: ${text.length} chars")
            _toast.value = "Резюме скопировано (${text.length} симв.)"
        } catch (e: Exception) {
            Log.e("InterviewVM", "Clipboard copy failed", e)
            _toast.value = "Не удалось скопировать: ${e.message}"
        }
    }


    // -----------------------------------------------------------------
    // Запись
    // -----------------------------------------------------------------

    fun startRecording() {
        if (!recorder.hasPermission()) {
            _toast.value = getApplication<Application>().getString(R.string.toast_no_microphone_permission)
            return
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val out = File(getApplication<Application>().filesDir, "recordings/interview_$ts.wav")
        if (recorder.start(out)) {
            _wavFile.value = out
            _phase.value = Phase.RECORDING
        } else {
            _phase.value = Phase.ERROR
            _toast.value = recorder.errorMessage.value ?: getApplication<Application>().getString(R.string.toast_record_start_failed)
        }
    }

    fun pauseRecording() {
        recorder.pause()
        _phase.value = Phase.PAUSED
    }

    fun resumeRecording() {
        recorder.resume()
        _phase.value = Phase.RECORDING
    }

    fun stopRecording() {
        val file = recorder.stop()
        if (file != null) {
            _wavFile.value = file
            _phase.value = Phase.RECORDED
        } else {
            _phase.value = Phase.ERROR
            _toast.value = getApplication<Application>().getString(R.string.toast_record_save_failed)
        }
    }

    // -----------------------------------------------------------------
    // Распознавание
    // -----------------------------------------------------------------

    fun runRecognition() {
        val wav = _wavFile.value ?: return
        // Перед новым распознаванием сбрасываем резюме и docx-uri от предыдущего прогона —
        // иначе после «Сделать резюме» пользователь будет видеть старый результат.
        _summaryResult.value = null
        _summaryError.value = null
        _exportedSummaryUri.value = null
        _exportedDocx.value = null
        // Разветвление по языку: RU идёт в родной пайплайн (GigaAM + diarization),
        // EN — в Whisper-обёртку (single-speaker в версии 1.0; multi-speaker будет в Шаге 3).
        when (_asrLanguage.value) {
            AsrLanguage.RU -> runRussianRecognition(wav)
            AsrLanguage.EN -> runEnglishRecognition(wav)
        }
    }

    private fun runRussianRecognition(wav: File) {
        viewModelScope.launch {
            _phase.value = Phase.PROCESSING
            // initialize() идемпотентен — если уже загружено, вернёт сразу.
            // При смене embedding-модели перезагружается только diarization (~1-2 сек).
            if (!asr.initialize(_embeddingModel.value)) {
                _phase.value = Phase.ERROR
                _toast.value = asr.errorMessage.value ?: getApplication<Application>().getString(R.string.toast_models_load_failed)
                return@launch
            }
            // Для длинных файлов (>30 мин) — режем на части и склеиваем.
            // Это избегает OOM на длинных письмах (файл 1ч = 220MB FloatArray) и улучшает
            // качество диаризации.
            val result = if (LongAudioProcessor.shouldSplit(wav)) {
                val totalMs = LongAudioProcessor.wavFileDurationMs(wav)
                val totalChunks = ((totalMs + LongAudioProcessor.CHUNK_DURATION_MS - 1) /
                    LongAudioProcessor.CHUNK_DURATION_MS).toInt()
                _toast.value = "Длинный файл (${totalMs / 60000}мин) — режу на $totalChunks частей по 30мин"
                LongAudioProcessor.processLong(
                    asr = asr,
                    wavFile = wav,
                    speakerMode = _speakerCountMode.value,
                    onChunkProgress = { idx, total ->
                        Log.i("InterviewVM", "Processing chunk ${idx + 1}/$total")
                    },
                )
            } else {
                asr.process(wav, speakerMode = _speakerCountMode.value)
            }
            if (result == null) {
                _phase.value = Phase.ERROR
                _toast.value = asr.errorMessage.value ?: getApplication<Application>().getString(R.string.toast_recognize_failed)
            } else {
                _transcript.value = result
                // Предзаполняем участников, оставляя ранее введённые имена
                val current = _participants.value.names.toMutableMap()
                for (id in result.segments.map { it.speakerId }.distinct()) {
                    if (!current.containsKey(id)) {
                        // оставляем как «Спикер N+1» — Participants.displayName сам подставит
                    }
                }
                _participants.value = Participants(current)
                _phase.value = Phase.READY

                // Автосохранение в историю — сразу после распознавания.
                // Сейчас аудио ещё WAV (M4A появится после конвертации) — это ок,
                // в saveSession() мы всё равно берём «что есть». После конвертации
                // пересохраним автоматически — в compressWavToM4aInBackground.
                _currentSessionId.value = SessionStore.newId()
                saveCurrentSession()

                // Фоновая конвертация WAV → M4A. Не блокирует UI — пользователь
                // может править участников и жать «Сохранить DOCX» пока это идёт.
                compressWavToM4aInBackground(wav)
            }
        }
    }

    /**
     * EN-ветка распознавания — Whisper base.en + диаризация.
     *
     * Пайплайн:
     *   1. Читаем WAV в FloatArray
     *   2. Диаризация (language-agnostic) через основной AsrService → сегменты [start, end, speakerId]
     *   3. Для каждого сегмента — Whisper.transcribe()
     *   4. Сливаем в InterviewTranscript с правильными speakerId и timestamps
     *
     * Whisper имеет лимит ~30 сек на инференцию. Диаризация обычно даёт сегменты короче
     * 30 сек, но если кто-то говорит без пауз дольше — добавляем sub-чанкинг.
     */
    private fun runEnglishRecognition(wav: File) {
        viewModelScope.launch {
            _phase.value = Phase.PROCESSING
            val ctx = getApplication<Application>()

            // Проверяем что EN модели скачаны
            if (!isAsrLanguageReady(ctx, AsrLanguage.EN)) {
                _phase.value = Phase.ERROR
                _toast.value = "Английские модели не скачаны. Откройте «О программе» и скачайте."
                return@launch
            }

            // Инициализируем основной AsrService — он даёт diarization
            // (recognizer тоже загружается, но это приемлемый overhead)
            if (!asr.initialize(_embeddingModel.value)) {
                _phase.value = Phase.ERROR
                _toast.value = "Не удалось загрузить модели диаризации."
                return@launch
            }

            val enAsrSvc = ensureEnAsr(ctx)
            if (enAsrSvc == null) {
                _phase.value = Phase.ERROR
                _toast.value = "Не удалось инициализировать Whisper."
                return@launch
            }

            val procT0 = System.currentTimeMillis()
            try {
                // === Шаг 1: Читаем WAV ===
                asr.setExternalState(AsrService.State.LOADING_MODELS, ctx.getString(R.string.asr_reading_wav), 0f)
                val samples = withContext(Dispatchers.IO) { asr.readWavSamples(wav) }
                if (samples == null || samples.isEmpty()) {
                    _phase.value = Phase.ERROR
                    _toast.value = "Не удалось прочитать запись."
                    return@launch
                }
                val totalDurationMs = (samples.size * 1000L) / 16_000L
                asr.setExternalState(AsrService.State.LOADING_MODELS, "", 0.05f)

                // === Шаг 2: Диаризация (если Single — пропускаем) ===
                val mode = _speakerCountMode.value
                val diarSegs: List<AsrService.DiarizationSegment> = if (mode is SpeakerCountMode.Single) {
                    Log.i("InterviewViewModel", "EN: skip diarization (mode=Single)")
                    listOf(AsrService.DiarizationSegment(0f, totalDurationMs / 1000f, 0))
                } else {
                    asr.setExternalState(AsrService.State.DIARIZING, ctx.getString(R.string.asr_status_diarizing), 0.1f)
                    val diarT0 = System.currentTimeMillis()
                    val segs = asr.diarizeOnly(samples, mode)
                    if (segs == null) {
                        _phase.value = Phase.ERROR
                        _toast.value = "Ошибка диаризации."
                        return@launch
                    }
                    Log.i("InterviewViewModel", "EN diarization: ${segs.size} segs in ${System.currentTimeMillis() - diarT0}ms, speakers=${segs.map { it.speaker }.distinct().sorted()}")
                    if (segs.isEmpty()) {
                        // Fallback — нет речи. Обрабатываем всё как single-speaker.
                        listOf(AsrService.DiarizationSegment(0f, totalDurationMs / 1000f, 0))
                    } else segs
                }
                asr.setExternalState(AsrService.State.TRANSCRIBING, ctx.getString(R.string.asr_status_recognizing), 0.4f)

                // === Шаг 3: Whisper на каждом сегменте ===
                val WHISPER_CHUNK_SEC = 25  // лимит Whisper ~30 сек, держимся ниже
                val chunkSize = WHISPER_CHUNK_SEC * 16_000
                val resultSegments = mutableListOf<TranscriptSegment>()

                for ((idx, ds) in diarSegs.withIndex()) {
                    val startSample = (ds.start * 16_000f).toInt().coerceAtLeast(0)
                    val endSample = (ds.end * 16_000f).toInt().coerceAtMost(samples.size)
                    if (endSample <= startSample) continue
                    if (endSample - startSample < 16_000 * 0.2f) continue  // игнорируем <0.2s

                    val segSamples = samples.copyOfRange(startSample, endSample)
                    val startMs = (ds.start * 1000f).toLong()
                    val endMs = (ds.end * 1000f).toLong()

                    asr.setExternalState(
                        AsrService.State.TRANSCRIBING,
                        ctx.getString(R.string.asr_status_segment_progress, idx + 1, diarSegs.size),
                        0.4f + (idx + 1).toFloat() / diarSegs.size * 0.6f,
                    )

                    // Если сегмент длиннее 25с — режем на sub-чанки
                    val segText = if (segSamples.size <= chunkSize) {
                        enAsrSvc.transcribe(segSamples, sampleRate = 16_000) ?: ""
                    } else {
                        val parts = mutableListOf<String>()
                        var off = 0
                        while (off < segSamples.size) {
                            val e = (off + chunkSize).coerceAtMost(segSamples.size)
                            val chunk = segSamples.copyOfRange(off, e)
                            val t = enAsrSvc.transcribe(chunk, sampleRate = 16_000)
                            if (!t.isNullOrBlank()) parts.add(t.trim())
                            off = e
                        }
                        parts.joinToString(" ")
                    }

                    val cleanText = segText.trim()
                    if (cleanText.isBlank()) continue

                    // Разбиваем текст на слова и оцениваем timestamps для каждого
                    val words = cleanText.split(Regex("\\s+")).filter { it.isNotBlank() }
                    val perWordMs = if (words.isEmpty()) 0L else (endMs - startMs) / words.size.coerceAtLeast(1)
                    val transcriptWords = mutableListOf<app.protocolvoice.asr.TranscriptWord>()
                    var cursor = startMs
                    for (w in words) {
                        val confidence = when {
                            w.length >= 5 -> 0.92f
                            w.length >= 3 -> 0.78f
                            w.length >= 2 -> 0.65f
                            else          -> 0.45f
                        }
                        transcriptWords.add(app.protocolvoice.asr.TranscriptWord(
                            text = w,
                            startMs = cursor,
                            endMs = cursor + perWordMs,
                            confidence = confidence,
                        ))
                        cursor += perWordMs
                    }

                    resultSegments.add(TranscriptSegment(
                        speakerId = ds.speaker,
                        startMs = startMs,
                        endMs = endMs,
                        words = transcriptWords,
                    ))
                }

                if (resultSegments.isEmpty()) {
                    _phase.value = Phase.ERROR
                    _toast.value = "Whisper вернул пустой результат. Возможно в записи нет речи."
                    return@launch
                }

                val numSpeakers = resultSegments.map { it.speakerId }.distinct().size
                val totalMs = System.currentTimeMillis() - procT0
                Log.i("InterviewViewModel", "PERF EN: audio=${totalDurationMs}ms wall=${totalMs}ms RTF=${"%.2f".format(totalMs.toFloat() / totalDurationMs)} speakers=$numSpeakers segments=${resultSegments.size}")

                val result = app.protocolvoice.asr.InterviewTranscript(
                    segments = resultSegments,
                    totalDurationMs = totalDurationMs,
                    recordedAt = System.currentTimeMillis(),
                    sourceWavPath = wav.absolutePath,
                    numSpeakers = numSpeakers,
                )

                _transcript.value = result
                _participants.value = app.protocolvoice.asr.Participants()
                _phase.value = Phase.READY
                asr.setExternalState(AsrService.State.DONE, ctx.getString(R.string.asr_status_done), 1f)

                _currentSessionId.value = SessionStore.newId()
                saveCurrentSession()
                compressWavToM4aInBackground(wav)
            } catch (e: Throwable) {
                Log.e("InterviewViewModel", "runEnglishRecognition failed", e)
                _phase.value = Phase.ERROR
                _toast.value = "Ошибка EN распознавания: ${e.message?.take(80)}"
            }
        }
    }

    /**
     * Фоновая конвертация исходного WAV в M4A для экономии места.
     * При успехе WAV удаляется, _m4aFile получает путь к M4A.
     * При ошибке WAV остаётся как fallback.
     */
    private fun compressWavToM4aInBackground(wav: File) {
        viewModelScope.launch {
            _isCompressing.value = true
            val m4a = File(wav.parentFile, wav.nameWithoutExtension + ".m4a")
            val result = Mp4Encoder.encode(wav, m4a)
            if (result != null && result.exists() && result.length() > 0) {
                _m4aFile.value = result
                // WAV больше не нужен — резко экономим диск (115 МБ за час → ~10 МБ)
                try { wav.delete() } catch (_: Throwable) {}
                _wavFile.value = null
                // Если плеер был загружен на WAV — перезагружаем на M4A
                if (!player.isPlaying.value) {
                    player.loadFromFile(result)
                }
                // Пересохраняем сессию с уже сжатым аудио
                saveCurrentSession()
            }
            // При ошибке конвертации оставляем WAV. _m4aFile остаётся null,
            // экспорт при этом копирования аудио (шаг 2) возьмёт WAV.
            _isCompressing.value = false
        }
    }

    /**
     * Сохранить текущую сессию в историю (JSON). Не блокирует.
     * Вызывается автоматически после распознавания и после конвертации в M4A,
     * а также при изменении имён участников / метаданных (через setParticipantName и updateMetadata).
     */
    private fun saveCurrentSession() {
        val transcript = _transcript.value ?: return
        val id = _currentSessionId.value ?: return
        val audio = _m4aFile.value ?: _wavFile.value
        viewModelScope.launch(Dispatchers.IO) {
            SessionStore.save(
                ctx = getApplication(),
                id = id,
                metadata = _metadata.value,
                participants = _participants.value,
                transcript = transcript,
                audioFile = audio,
            )
        }
    }

    /**
     * Загружает сессию из истории и ставит в текущий ViewModel.
     * Фаза → READY. Пользователь может редактировать и переэкспортировать.
     */
    fun loadFromHistory(id: String) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                SessionStore.load(getApplication(), id)
            }
            if (loaded == null) {
                _toast.value = getApplication<Application>().getString(R.string.toast_session_load_failed)
                return@launch
            }
            // Сбрасываем текущий плеер перед загрузкой нового файла
            player.stop()
            player.release()

            _currentSessionId.value = loaded.id
            _metadata.value = loaded.metadata
            _participants.value = loaded.participants
            _transcript.value = loaded.transcript
            // Сбрасываем резюме при загрузке другой сессии — резюме было от предыдущей записи.
            _summaryResult.value = null
            _summaryError.value = null
            _exportedSummaryUri.value = null
            _exportedDocx.value = null
            // Аудио: выставляем либо M4A либо WAV в соответствующий StateFlow,
            // чтобы hasAudio() / ensurePlayerLoaded() работали.
            val audio = loaded.audioFile
            if (audio != null) {
                if (audio.extension.equals("m4a", ignoreCase = true)) {
                    _m4aFile.value = audio
                    _wavFile.value = null
                } else {
                    _wavFile.value = audio
                    _m4aFile.value = null
                }
            } else {
                _m4aFile.value = null
                _wavFile.value = null
            }
            _exportedDocx.value = null
            _phase.value = Phase.READY
            if (audio == null) {
                _toast.value = getApplication<Application>().getString(R.string.toast_audio_missing)
            }
        }
    }

    /**
     * Убрать текущую сессию из истории (JSON + аудио).
     * После вызова сессия в ViewModel сбрасывается.
     */
    fun removeCurrentFromHistory() {
        val id = _currentSessionId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = SessionStore.delete(getApplication(), id)
            withContext(Dispatchers.Main) {
                if (ok) {
                    _toast.value = getApplication<Application>().getString(R.string.toast_session_deleted)
                    resetSession()
                } else {
                    _toast.value = getApplication<Application>().getString(R.string.toast_session_delete_failed)
                }
            }
        }
    }

    /**
     * Загружает аудио в плеер. Приоритет M4A > WAV.
     * Нужно вызвать перед первым вызовом playSegment/play.
     * Идемпотентен — безопасно вызывать повторно.
     */
    fun ensurePlayerLoaded(): Boolean {
        val src = _m4aFile.value ?: _wavFile.value ?: return false
        return player.loadFromFile(src)
    }

    /** Проиграть один сегмент и остановиться на его конце. */
    fun playSegment(startMs: Long, endMs: Long) {
        if (!ensurePlayerLoaded()) {
            _toast.value = getApplication<Application>().getString(R.string.toast_audio_unavailable)
            return
        }
        player.playSegment(startMs, endMs)
    }

    /** Play/Pause для глобального мини-плеера. */
    fun togglePlayer() {
        if (!ensurePlayerLoaded()) {
            _toast.value = getApplication<Application>().getString(R.string.toast_audio_unavailable)
            return
        }
        player.toggle()
    }

    fun seekPlayer(ms: Long) {
        if (ensurePlayerLoaded()) player.seekTo(ms)
    }

    /** true если хоть какой-то аудио-файл живой и можно играть. */
    fun hasAudio(): Boolean = (_m4aFile.value ?: _wavFile.value) != null

    // -----------------------------------------------------------------
    // Метаданные / участники
    // -----------------------------------------------------------------

    fun updateMetadata(update: (InterviewMetadata) -> InterviewMetadata) {
        _metadata.value = update(_metadata.value)
        // Если сессия уже в истории — пересохраняем
        if (_currentSessionId.value != null) saveCurrentSession()
    }

    fun setParticipantName(speakerId: Int, name: String) {
        val map = _participants.value.names.toMutableMap()
        if (name.isBlank()) map.remove(speakerId) else map[speakerId] = name.trim()
        _participants.value = Participants(map)
        if (_currentSessionId.value != null) saveCurrentSession()
    }

    /** Список идентификаторов спикеров для редактора. До распознавания — пусто.
     *  После — реальные id из transcript. */
    fun knownSpeakerIds(): List<Int> {
        val t = _transcript.value ?: return _participants.value.names.keys.sorted()
        return t.segments.map { it.speakerId }.distinct().sorted()
    }

    // -----------------------------------------------------------------
    // Экспорт DOCX
    // -----------------------------------------------------------------

    /**
     * Сохранить DOCX + аудио в Downloads/MK-Интервью/.  Возвращает URI для шаринга.
     * Аудио копируется предпочтительно M4A (если конвертация уже закончилась),
     * иначе fallback на исходный WAV.
     */
    fun exportDocx() {
        val t = _transcript.value ?: return
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { writeDocx(t) }
            if (uri == null) {
                _toast.value = getApplication<Application>().getString(R.string.toast_docx_save_failed)
                return@launch
            }
            _exportedDocx.value = uri

            // Копируем аудио-файл рядом с DOCX. Приоритет M4A > WAV.
            val audioSrc = _m4aFile.value ?: _wavFile.value
            val audioCopied = if (audioSrc != null) {
                withContext(Dispatchers.IO) { copyAudioToDownloads(audioSrc) }
            } else null

            _toast.value = when {
                audioCopied != null -> getApplication<Application>().getString(R.string.toast_export_done_with_audio)
                _isCompressing.value -> getApplication<Application>().getString(R.string.toast_export_done_compressing)
                else -> getApplication<Application>().getString(R.string.toast_export_done)
            }
        }
    }

    /**
     * Копирует аудио в Downloads/MK-Интервью/. Имя файла — то же что у DOCX,
     * только расширение соответствует исходнику (.m4a / .wav).
     */
    private fun copyAudioToDownloads(audioSrc: File): Uri? {
        if (!audioSrc.exists() || audioSrc.length() == 0L) return null
        val ctx = getApplication<Application>()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(audioSrc.lastModified()))
        val baseName = (_metadata.value.title.ifBlank { "Interview" })
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(80)
        val ext = audioSrc.extension.lowercase().ifBlank { "m4a" }
        val fileName = "${baseName}_$ts.$ext"
        val mime = when (ext) {
            "m4a", "mp4", "aac" -> "audio/mp4"
            "wav"               -> "audio/wav"
            else                -> "audio/*"
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/ProtocolVoice")
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: return null
                resolver.openOutputStream(uri)?.use { os ->
                    audioSrc.inputStream().use { it.copyTo(os) }
                }
                uri
            } else {
                val out = File(ctx.filesDir, "exports/$fileName")
                out.parentFile?.mkdirs()
                audioSrc.inputStream().use { src -> out.outputStream().use { src.copyTo(it) } }
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
            }
        } catch (e: Exception) {
            android.util.Log.e("InterviewViewModel", "copyAudioToDownloads failed", e)
            null
        }
    }

    private fun writeDocx(t: InterviewTranscript): Uri? {
        val ctx = getApplication<Application>()
        val builder = DocxBuilder(t, _metadata.value, _participants.value)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val baseName = (_metadata.value.title.ifBlank { "Interview" })
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(80)
        val fileName = "${baseName}_$ts.docx"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // MediaStore Downloads — современный путь для Android 10+
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(
                        MediaStore.Downloads.MIME_TYPE,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    )
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/ProtocolVoice")
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: return null
                resolver.openOutputStream(uri)?.use { os -> builder.writeTo(os) }
                uri
            } else {
                // Fallback — пишем в app-private + FileProvider
                val out = File(ctx.filesDir, "exports/$fileName")
                out.parentFile?.mkdirs()
                builder.writeTo(out)
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
            }
        } catch (e: Exception) {
            _toast.value = getApplication<Application>().getString(R.string.toast_docx_save_error, e.message ?: "")
            null
        }
    }

    /** Intent для шаринга последнего экспортированного DOCX. */
    fun shareDocxIntent(uri: Uri): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // -----------------------------------------------------------------

    fun consumeToast() { _toast.value = null }

    /** Показать toast из UI-кода (например после clipboard copy). */
    fun showToast(message: String) {
        _toast.value = message
    }

    fun resetSession() {
        player.stop()
        player.release()
        _transcript.value = null
        _wavFile.value = null
        _m4aFile.value = null
        _exportedDocx.value = null
        _summaryResult.value = null
        _summaryError.value = null
        _exportedSummaryUri.value = null
        _currentSessionId.value = null
        _phase.value = Phase.IDLE
    }

    fun deleteAllModelsAndRestart(ctx: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val storage = ModelStorage(ctx)
            storage.deleteAll()
            // Взводим флаг force_downloader — это заставит isFirstRunComplete пропустить
            // assets fallback при следующем старте Activity. Без этого модели сразу же
            // скопируются из APK assets обратно в filesDir, и Downloader не покажется.
            storage.setForceDownloaderFlag()
            // И рестарт Activity — это заставит MainActivity заново проверить
            // ModelStorage.isFirstRunComplete() → false → покажет Downloader
            withContext(Dispatchers.Main) {
                _toast.value = ctx.getString(R.string.toast_models_deleted)
                // Простейший рестарт — перезапускаем MainActivity через Intent
                val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                if (intent != null) ctx.startActivity(intent)
            }
        }
    }

    override fun onCleared() {
        recorder.release()
        asr.release()
        player.release()
        try { enAsr?.release() } catch (_: Throwable) {}
        enAsr = null
        super.onCleared()
    }

    /** Прочитать WAV (16kHz mono PCM16) с диска в FloatArray. */
    private fun readWavFromFile(file: File): FloatArray? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 44) return null
            val data = bytes.copyOfRange(44, bytes.size)
            val numSamples = data.size / 2
            val out = FloatArray(numSamples)
            val bb = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                out[i] = bb.short.toFloat() / 32768f
            }
            out
        } catch (e: Throwable) {
            android.util.Log.e("InterviewViewModel", "readWavFromFile failed", e)
            null
        }
    }

    // =============================
}


