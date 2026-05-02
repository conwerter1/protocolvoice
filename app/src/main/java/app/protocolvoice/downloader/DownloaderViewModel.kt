package app.protocolvoice.downloader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * ViewModel для экрана скачивания моделей.
 *
 * Архитектура:
 *   ViewModel НЕ скачивает сама — только проксирует команды в DownloadService
 *   и подписывается на observable state из его companion'а.
 *
 * Зачем сервис:
 *   - Скачивание 332 МБ занимает 5-15 минут.
 *   - Если пользователь свернёт приложение или нажмёт Home — Activity
 *     уничтожится, ViewModel onCleared(), coroutine отменится → скачивание
 *     прервётся. Это плохо.
 *   - DownloadService = foreground service, защищён от kill'а пока показывает
 *     notification → скачивание продолжается даже когда приложение свёрнуто.
 *
 * Жизненный цикл:
 *   - При создании ViewModel → checkInitialState() (быстрая проверка filesDir)
 *   - startFirstRunDownload() → DownloadService.start(ctx)
 *   - cancel() → DownloadService.cancel(ctx)
 *   - retry() → cancel + start
 *
 * Phase отражает что показывать на UI:
 *   CHECKING       → spinner "Проверяем модели..."
 *   NEEDS_DOWNLOAD → welcome-экран с кнопкой Скачать
 *   DOWNLOADING    → progress-bar + цифры
 *   SUCCESS        → "Готово, переходим..."
 *   ERROR          → описание ошибки + кнопка Повторить
 */
class DownloaderViewModel(app: Application) : AndroidViewModel(app) {

    enum class Phase {
        CHECKING,
        NEEDS_DOWNLOAD,
        DOWNLOADING,
        SUCCESS,
        ERROR,
    }

    private val storage = ModelStorage(app)

    private val _phase = MutableStateFlow(Phase.CHECKING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Прогресс скачивания [0..1] для UI. */
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    // Прокси на companion-flow'ы из DownloadService
    val currentModelId: StateFlow<String?> = DownloadService.currentModelId
    val errorMessage: StateFlow<String?> = DownloadService.errorMessage
    val bytesDownloaded: StateFlow<Long> = DownloadService.bytesDownloaded
    val bytesTotal: StateFlow<Long> = DownloadService.bytesTotal
    val speedBps: StateFlow<Long> = DownloadService.speedBps

    init {
        checkInitialState()
        observeServiceState()
        observeProgress()
    }

    /** Чек начального состояния — есть ли модели на диске (включая fallback из assets). */
    private fun checkInitialState() {
        viewModelScope.launch {
            _phase.value = Phase.CHECKING
            val ok = storage.isFirstRunComplete(getApplication())
            _phase.value = if (ok) Phase.SUCCESS else Phase.NEEDS_DOWNLOAD
        }
    }

    /** Подписка на ServiceState — обновляем Phase соответственно. */
    private fun observeServiceState() {
        viewModelScope.launch {
            DownloadService.serviceState.collect { state ->
                _phase.value = when (state) {
                    DownloadService.ServiceState.IDLE -> {
                        // Если сервис ещё не стартовал, не меняем фазу
                        // (определяется через checkInitialState)
                        if (storage.isFirstRunComplete(getApplication())) Phase.SUCCESS
                        else Phase.NEEDS_DOWNLOAD
                    }
                    DownloadService.ServiceState.DOWNLOADING -> Phase.DOWNLOADING
                    DownloadService.ServiceState.SUCCESS -> Phase.SUCCESS
                    DownloadService.ServiceState.ERROR -> Phase.ERROR
                    DownloadService.ServiceState.CANCELLED -> Phase.NEEDS_DOWNLOAD
                }
            }
        }
    }

    /** Расчёт прогресса из bytesDownloaded / bytesTotal. */
    private fun observeProgress() {
        viewModelScope.launch {
            combine(bytesDownloaded, bytesTotal) { down, total ->
                if (total > 0) (down.toFloat() / total).coerceIn(0f, 1f) else 0f
            }.collect { _progress.value = it }
        }
    }

    /**
     * Запустить first-run загрузку. Делегируется в DownloadService.
     */
    fun startFirstRunDownload() {
        DownloadService.start(getApplication())
    }

    /**
     * Повторная попытка после ошибки.
     */
    fun retry() {
        DownloadService.cancel(getApplication())
        // Маленькая пауза не нужна — start обработает корректно
        DownloadService.start(getApplication())
    }

    /**
     * Отменить скачивание.
     */
    fun cancel() {
        DownloadService.cancel(getApplication())
    }
}
