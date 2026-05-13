package app.protocolvoice.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Singleton-исполнитель длинных задач: импорт аудио, распознавание, генерация резюме.
 *
 * Зачем: ViewModel и его [viewModelScope] разрушаются вместе с Activity при
 * звонке, сворачивании, повороте экрана и т.п. Если запустить долгий импорт
 * через viewModelScope, входящий звонок разрушит Activity → отменит scope →
 * MediaCodec прервётся → пользователь потеряет работу.
 *
 * Решение: тяжёлая работа выполняется в [taskScope] — собственном
 * application-scope CoroutineScope. ViewModel только публикует _команды_
 * (startImport, startRecognition) и подписывается на StateFlow прогресса/
 * результата. При пересоздании Activity новый ViewModel пересоединяется
 * к тем же потокам.
 *
 * Время жизни корутины:
 *  - стартует [ProcessingService] (foreground, тип dataSync) — Android не
 *    убивает процесс пока сервис активен
 *  - в начале работы — `ProcessingService.start()`, в `finally` — `stop()`
 *  - если задача уже идёт, повторный вызов игнорируется
 *
 * Все методы потокобезопасны (StateFlow + SupervisorJob).
 */
object LongTaskRunner {

    private const val TAG = "LongTaskRunner"

    /** Application-scope: переживает разрушение Activity/ViewModel. */
    private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------- IMPORT ----------

    private val _importInProgress = MutableStateFlow(false)
    val importInProgress: StateFlow<Boolean> = _importInProgress.asStateFlow()

    private val _importProgress = MutableStateFlow(0f)
    val importProgress: StateFlow<Float> = _importProgress.asStateFlow()

    /**
     * Результат последнего успешного импорта.
     * ViewModel читает это значение при пересоздании и переходит в RECORDED.
     */
    private val _importResult = MutableStateFlow<ImportOutcome?>(null)
    val importResult: StateFlow<ImportOutcome?> = _importResult.asStateFlow()

    private var importJob: Job? = null

    data class ImportOutcome(
        val wavFile: File,
        val durationMs: Long,
        val sourceSampleRate: Int,
        val sourceChannels: Int,
        val error: String? = null,
    )

    /**
     * Запустить импорт аудио. Если импорт уже идёт — игнорировать.
     *
     * @return true если запущен, false если уже выполняется
     */
    fun startImport(ctx: Context, uri: Uri): Boolean {
        if (_importInProgress.value) {
            Log.i(TAG, "startImport ignored: import already in progress")
            return false
        }
        val appCtx = ctx.applicationContext
        _importInProgress.value = true
        _importProgress.value = 0f
        _importResult.value = null

        importJob = taskScope.launch {
            ProcessingService.start(appCtx, appCtx.getString(app.protocolvoice.R.string.processing_importing))
            try {
                val result = AudioImporter.importToWav(
                    ctx = appCtx,
                    uri = uri,
                    onProgress = { p -> _importProgress.value = p },
                )
                _importResult.value = ImportOutcome(
                    wavFile = result.wavFile,
                    durationMs = result.durationMs,
                    sourceSampleRate = result.sourceSampleRate,
                    sourceChannels = result.sourceChannels,
                )
                Log.i(TAG, "Import done: ${result.wavFile.absolutePath} (${result.durationMs}ms)")
            } catch (e: Throwable) {
                Log.e(TAG, "Import failed", e)
                _importResult.value = ImportOutcome(
                    wavFile = File(""),
                    durationMs = 0,
                    sourceSampleRate = 0,
                    sourceChannels = 0,
                    error = e.message ?: "Unknown error",
                )
            } finally {
                _importInProgress.value = false
                _importProgress.value = 0f
                ProcessingService.stop(appCtx)
            }
        }
        return true
    }

    // ---------- RECOGNITION ----------

    /**
     * ViewModel должен вызвать это после того, как обработал [importResult],
     * чтобы следующий импорт стартовал с чистого состояния.
     */
    fun clearImportResult() {
        _importResult.value = null
    }

    /**
     * Принудительная отмена импорта (пользователь нажал Cancel).
     * Освобождает ресурсы MediaCodec.
     */
    fun cancelImport() {
        importJob?.cancel()
        _importInProgress.value = false
        _importProgress.value = 0f
    }

    private val _recognitionInProgress = MutableStateFlow(false)
    val recognitionInProgress: StateFlow<Boolean> = _recognitionInProgress.asStateFlow()

    private val _recognitionStatus = MutableStateFlow("")
    val recognitionStatus: StateFlow<String> = _recognitionStatus.asStateFlow()

    /**
     * Результат распознавания. ViewModel читает это и переключается в Phase.READY.
     */
    private val _recognitionResult = MutableStateFlow<RecognitionOutcome?>(null)
    val recognitionResult: StateFlow<RecognitionOutcome?> = _recognitionResult.asStateFlow()

    private var recognitionJob: Job? = null

    /**
     * Результат распознавания: либо success с transcript, либо error.
     * Прячем в [transcript] реальный объект InterviewTranscript — поскольку LongTaskRunner
     * не имеет доступа к пакету asr, используем Any? и приводим в ViewModel.
     */
    data class RecognitionOutcome(
        val transcript: Any?,        // app.protocolvoice.asr.InterviewTranscript
        val wavFile: java.io.File,   // путь к исходнику — нужен для последующей конверсии в M4A
        val error: String? = null,
    )

    /**
     * Запустить распознавание в живучем контексте.
     *
     * Принимает lambda выполнения — так как все зависимости (AsrService, EnglishAsrService, etc.)
     * живут внутри ViewModel. LongTaskRunner лишь даёт application-scope.
     */
    fun startRecognition(
        ctx: Context,
        statusText: String,
        work: suspend (
            updateStatus: (String) -> Unit,
        ) -> RecognitionOutcome,
    ): Boolean {
        if (_recognitionInProgress.value) {
            Log.i(TAG, "startRecognition ignored: already in progress")
            return false
        }
        val appCtx = ctx.applicationContext
        _recognitionInProgress.value = true
        _recognitionStatus.value = statusText
        _recognitionResult.value = null

        recognitionJob = taskScope.launch {
            ProcessingService.start(appCtx, statusText)
            try {
                val outcome = work { newStatus ->
                    _recognitionStatus.value = newStatus
                }
                _recognitionResult.value = outcome
            } catch (e: Throwable) {
                Log.e(TAG, "Recognition failed", e)
                _recognitionResult.value = RecognitionOutcome(
                    transcript = null,
                    wavFile = java.io.File(""),
                    error = e.message ?: "Unknown error",
                )
            } finally {
                _recognitionInProgress.value = false
                _recognitionStatus.value = ""
                ProcessingService.stop(appCtx)
            }
        }
        return true
    }

    fun clearRecognitionResult() {
        _recognitionResult.value = null
    }

    fun cancelRecognition() {
        recognitionJob?.cancel()
        _recognitionInProgress.value = false
        _recognitionStatus.value = ""
    }

    // ---------- SUMMARY ----------

    private val _summaryInProgress = MutableStateFlow(false)
    val summaryInProgress: StateFlow<Boolean> = _summaryInProgress.asStateFlow()

    /**
     * Результат генерации резюме. ViewModel читает и прокидывает в _summaryResult.
     * Использует Any? чтобы не импортировать SummaryResult.
     */
    private val _summaryOutcome = MutableStateFlow<SummaryOutcome?>(null)
    val summaryOutcome: StateFlow<SummaryOutcome?> = _summaryOutcome.asStateFlow()

    private var summaryJob: Job? = null

    data class SummaryOutcome(
        val result: Any?,                // app.protocolvoice.summary.default_tier.SummaryResult
        val error: String? = null,
    )

    /**
     * Запустить генерацию резюме в живучем контексте.
     */
    fun startSummary(
        ctx: Context,
        statusText: String,
        work: suspend () -> Any?,
    ): Boolean {
        if (_summaryInProgress.value) {
            Log.i(TAG, "startSummary ignored: already in progress")
            return false
        }
        val appCtx = ctx.applicationContext
        _summaryInProgress.value = true
        _summaryOutcome.value = null

        summaryJob = taskScope.launch {
            ProcessingService.start(appCtx, statusText)
            try {
                val result = work()
                _summaryOutcome.value = SummaryOutcome(result = result, error = null)
            } catch (e: Throwable) {
                Log.e(TAG, "Summary failed", e)
                _summaryOutcome.value = SummaryOutcome(
                    result = null,
                    error = e.message ?: "Unknown error",
                )
            } finally {
                _summaryInProgress.value = false
                ProcessingService.stop(appCtx)
            }
        }
        return true
    }

    fun clearSummaryOutcome() {
        _summaryOutcome.value = null
    }
}
