package app.protocolvoice.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.protocolvoice.R

/**
 * Foreground service который держит процесс приложения живым во время
 * ДОЛГИХ ВЫЧИСЛЕНИЙ: импорт аудио, распознавание (ASR), диаризация,
 * генерация резюме с NER.
 *
 * Отличие от [RecordingService]:
 *  - RecordingService держит процесс во время ЗАПИСИ (тип microphone)
 *  - ProcessingService держит процесс во время ОБРАБОТКИ (тип dataSync)
 *
 * Без этого сервиса Android может убить процесс при сворачивании или
 * входящем звонке, и пользователь потеряет всю работу (импортированный
 * файл, частично распознанные сегменты, готовое резюме).
 *
 * Использование из ViewModel/Activity:
 *   ProcessingService.start(ctx, "Распознаю запись...")
 *   try {
 *     // длительная работа
 *   } finally {
 *     ProcessingService.stop(ctx)
 *   }
 *
 * Notification использует тот же канал что и RecordingService для единообразия.
 * Если оба сервиса работают одновременно — пользователь видит два уведомления.
 */
class ProcessingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val statusText = intent?.getStringExtra(EXTRA_STATUS)
            ?: getString(R.string.processing_default_text)
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // dataSync — для долгих фоновых вычислений, не привязанных к микрофону
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // START_NOT_STICKY — если процесс убит вопреки foreground, не пересоздавать
        // (это означало бы потерю состояния ViewModel, нет смысла рестартить пустой сервис)
        return START_NOT_STICKY
    }

    private fun buildNotification(statusText: String): Notification {
        val launcherIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = launcherIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.processing_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification_recording)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_processing),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.processing_default_text) }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "interview_processing"
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_STATUS = "status"

        /**
         * Запустить сервис с указанным текстом статуса.
         * Можно вызвать повторно с другим текстом — Android update'нёт notification.
         */
        fun start(ctx: Context, statusText: String) {
            val intent = Intent(ctx, ProcessingService::class.java).apply {
                putExtra(EXTRA_STATUS, statusText)
            }
            ContextCompat.startForegroundService(ctx, intent)
        }

        /** Остановить сервис. Можно вызывать многократно — лишние вызовы безопасны. */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ProcessingService::class.java))
        }
    }
}
