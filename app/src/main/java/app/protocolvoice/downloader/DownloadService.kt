package app.protocolvoice.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.protocolvoice.MainActivity
import app.protocolvoice.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service for downloading models.
 *
 * Зачем сервис, а не просто coroutine во ViewModel:
 *   - Скачивание занимает 5-15 минут (332 МБ на ~500 КБ/с)
 *   - Если пользователь сворачивает приложение → Activity уничтожается → coroutine
 *     отменяется → скачивание прерывается. Это плохо UX.
 *   - Foreground service защищён от kill'а системой (пока показывает notification)
 *   - Notification даёт пользователю прогресс в шторке + кнопку Cancel
 *
 * Lifecycle:
 *   1. UI вызывает start(ctx, models) → стартует service в foreground режиме
 *   2. Service показывает notification с прогрессом (обновляется каждые ~500ms)
 *   3. По завершении (success или error) — обновляет notification + останавливается
 *   4. Cancel: pendingIntent на ACTION_CANCEL → service отменяет job + останавливается
 *
 * Прогресс наблюдается через companion object'ные StateFlow'ы — DownloaderViewModel
 * подписывается на них и рендерит UI.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private lateinit var storage: ModelStorage
    private lateinit var downloader: ModelDownloader
    private var progressUpdateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        storage = ModelStorage(this)
        downloader = ModelDownloader(storage)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDownload()
            ACTION_CANCEL -> cancelDownload()
        }
        return START_NOT_STICKY  // не перезапускаем после kill — пользователь решит сам
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        progressUpdateJob?.cancel()
        scope.cancel()
        _serviceState.value = ServiceState.IDLE
    }

    // ────────────────────────────────────────────────────────────────────
    // Download orchestration
    // ────────────────────────────────────────────────────────────────────

    private fun startDownload() {
        if (downloadJob?.isActive == true) {
            Log.i(TAG, "Download already in progress, ignoring start request")
            return
        }
        Log.i(TAG, "Starting download of first-run models")
        _serviceState.value = ServiceState.DOWNLOADING

        // Поднимаем foreground с initial notification
        startForeground(NOTIFICATION_ID, buildProgressNotification(0f, 0L, 0L, 0L, "starting"))

        // Запускаем периодическое обновление notification + StateFlow проксирование
        progressUpdateJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                _bytesDownloaded.value = downloader.bytesDownloaded.value
                _bytesTotal.value      = downloader.bytesTotal.value
                _speedBps.value        = downloader.speedBps.value
                _currentModelId.value  = downloader.currentModelId.value

                // Обновляем notification
                val total = downloader.bytesTotal.value
                val done  = downloader.bytesDownloaded.value
                val progress = if (total > 0) done.toFloat() / total else 0f
                val notif = buildProgressNotification(
                    progress = progress,
                    bytesDownloaded = done,
                    bytesTotal = total,
                    speedBps = downloader.speedBps.value,
                    modelId = downloader.currentModelId.value ?: "",
                )
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notif)
            }
        }

        downloadJob = scope.launch {
            try {
                // Читаем выбранный язык из prefs (RU/EN) и определяем какие модели качать.
                // Существующие модели другого языка НЕ удаляются.
                val language = storage.readSelectedLanguage()
                val modelsToDownload = ModelRegistry.firstRunModelsFor(language)
                Log.i(TAG, "Downloading ${modelsToDownload.size} models for language=$language")
                val ok = downloader.downloadAll(modelsToDownload)
                if (ok) {
                    Log.i(TAG, "Download completed successfully")
                    _serviceState.value = ServiceState.SUCCESS
                    // Сбрасываем debug-флаг force_downloader — больше не нужен, модели скачаны.
                    storage.clearForceDownloaderFlag()
                    showCompletionNotification(success = true)
                } else {
                    Log.w(TAG, "Download failed: ${downloader.errorMessage.value}")
                    _serviceState.value = ServiceState.ERROR
                    _errorMessage.value = downloader.errorMessage.value
                    showCompletionNotification(success = false)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Download crashed", e)
                _serviceState.value = ServiceState.ERROR
                _errorMessage.value = e.message
                showCompletionNotification(success = false)
            } finally {
                progressUpdateJob?.cancel()
                stopForeground(STOP_FOREGROUND_DETACH) // notification остаётся
                stopSelf()
            }
        }
    }

    private fun cancelDownload() {
        Log.i(TAG, "Download cancellation requested by user")
        downloadJob?.cancel()
        progressUpdateJob?.cancel()
        _serviceState.value = ServiceState.CANCELLED
        // Удаляем notification
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ────────────────────────────────────────────────────────────────────
    // Notification building
    // ────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_download),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_download_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL
        }
        return PendingIntent.getService(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildProgressNotification(
        progress: Float,
        bytesDownloaded: Long,
        bytesTotal: Long,
        speedBps: Long,
        modelId: String,
    ): Notification {
        val pct = (progress * 100).toInt().coerceIn(0, 100)
        val downMb = bytesDownloaded / (1024 * 1024)
        val totalMb = bytesTotal / (1024 * 1024)
        val speedKbs = speedBps / 1024

        val title = getString(R.string.notification_download_title)
        val text = if (bytesTotal > 0) {
            getString(R.string.notification_download_text, downMb, totalMb, speedKbs)
        } else {
            getString(R.string.notification_download_starting)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_processing)
            .setProgress(100, pct, bytesTotal == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_download_cancel),
                cancelPendingIntent(),
            )
            .build()
    }

    private fun showCompletionNotification(success: Boolean) {
        val title = if (success) getString(R.string.notification_download_done_title)
                    else getString(R.string.notification_download_error_title)
        val text = if (success) getString(R.string.notification_download_done_text)
                   else getString(R.string.notification_download_error_text)
        val icon = if (success) R.drawable.ic_notification_ready
                   else R.drawable.ic_notification

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppPendingIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notif)
    }

    // ────────────────────────────────────────────────────────────────────
    // Companion: API + state observability
    // ────────────────────────────────────────────────────────────────────

    enum class ServiceState { IDLE, DOWNLOADING, SUCCESS, ERROR, CANCELLED }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "app.protocolvoice.action.START_DOWNLOAD"
        const val ACTION_CANCEL = "app.protocolvoice.action.CANCEL_DOWNLOAD"

        // Observable state — DownloaderViewModel подписывается на эти flow'ы
        private val _serviceState = MutableStateFlow(ServiceState.IDLE)
        val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

        private val _bytesDownloaded = MutableStateFlow(0L)
        val bytesDownloaded: StateFlow<Long> = _bytesDownloaded.asStateFlow()

        private val _bytesTotal = MutableStateFlow(0L)
        val bytesTotal: StateFlow<Long> = _bytesTotal.asStateFlow()

        private val _speedBps = MutableStateFlow(0L)
        val speedBps: StateFlow<Long> = _speedBps.asStateFlow()

        private val _currentModelId = MutableStateFlow<String?>(null)
        val currentModelId: StateFlow<String?> = _currentModelId.asStateFlow()

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        /** Запустить скачивание моделей через сервис (из UI). */
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /** Отменить (из UI или notification action). */
        fun cancel(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
