package app.protocolvoice

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.protocolvoice.audio.RecordingService
import app.protocolvoice.downloader.DownloaderScreen
import app.protocolvoice.downloader.ModelStorage
import app.protocolvoice.ui.AboutScreen
import app.protocolvoice.ui.InterviewScreen
import app.protocolvoice.ui.InterviewViewModel
import app.protocolvoice.ui.history.HistoryScreen
import app.protocolvoice.ui.theme.InterviewTheme

/**
 * Точка входа в приложение ProtocolVoice.
 *
 * Маршрутизация экранов:
 *   - DOWNLOADER : первый запуск, скачиваем модели (если ещё не скачаны)
 *   - MAIN       : основной экран (запись/распознавание/экспорт)
 *   - HISTORY    : список сохранённых сессий
 *
 * Логика стартового экрана:
 *   - При запуске проверяем ModelStorage.isFirstRunComplete()
 *   - Если все REQUIRED модели на диске → MAIN
 *   - Иначе → DOWNLOADER (после успеха автоматически переход на MAIN)
 *
 * Foreground-запись: при переходе ViewModel в RECORDING/PAUSED стартуем
 * RecordingService, иначе останавливаем. Это держит приложение «живым»
 * в фоне на всё время записи (для часовых интервью обязательно).
 */
class MainActivity : AppCompatActivity() {

    private enum class Screen { DOWNLOADER, MAIN, HISTORY, ABOUT }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ВАЖНО: installSplashScreen() ДОЛЖен быть вызван ДО super.onCreate()
        // Иначе сплаш не подхватится и будет системная белая заглушка.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Решаем стартовый экран один раз при создании Activity:
        // если модели на месте (в filesDir или копируются из assets) — сразу MAIN, иначе DOWNLOADER.
        // ctx=this подключает fallback на APK assets (для debug-сборок с bundled моделями).
        val storage = ModelStorage(this)
        val initialScreen = if (storage.isFirstRunComplete(this)) Screen.MAIN else Screen.DOWNLOADER

        setContent {
            InterviewTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    val ctx = this@MainActivity
                    var screen by rememberSaveable { mutableStateOf(initialScreen) }

                    when (screen) {
                        Screen.DOWNLOADER -> DownloaderScreen(
                            onComplete = { screen = Screen.MAIN },
                            onExit = { finish() },
                        )
                        Screen.MAIN -> {
                            // Создаём InterviewViewModel только когда нужен — после downloader'а.
                            val vm: InterviewViewModel = viewModel()
                            val phase by vm.phase.collectAsState()

                            // Управление foreground service'ом по фазе
                            LaunchedEffect(phase) {
                                val intent = Intent(ctx, RecordingService::class.java)
                                when (phase) {
                                    InterviewViewModel.Phase.RECORDING,
                                    InterviewViewModel.Phase.PAUSED -> {
                                        ContextCompat.startForegroundService(ctx, intent)
                                    }
                                    else -> {
                                        ctx.stopService(intent)
                                    }
                                }
                            }

                            InterviewScreen(
                                vm = vm,
                                onOpenHistory = { screen = Screen.HISTORY },
                                onOpenAbout = { screen = Screen.ABOUT },
                            )
                        }
                        Screen.HISTORY -> {
                            val vm: InterviewViewModel = viewModel()
                            HistoryScreen(
                                onBack = { screen = Screen.MAIN },
                                onOpen = { id ->
                                    vm.loadFromHistory(id)
                                    screen = Screen.MAIN
                                },
                            )
                        }
                        Screen.ABOUT -> {
                            AboutScreen(
                                onBack = { screen = Screen.MAIN },
                            )
                        }
                    }
                }
            }
        }
    }
}
