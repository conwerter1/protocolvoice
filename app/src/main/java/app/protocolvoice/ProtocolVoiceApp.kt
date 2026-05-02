package app.protocolvoice

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Application class — точка инициализации до первого Activity.
 *
 * Главная задача здесь: применить сохранённый язык интерфейса (RU/EN)
 * до того как создастся MainActivity. Без этого первый запуск после
 * смены языка показал бы UI на старом языке.
 *
 * AppCompat хранит выбор пользователя в системных настройках начиная с
 * Android 13 (через PerAppLanguagesManager) — там он переживёт даже
 * переустановку приложения. На более старых Android — в SharedPreferences
 * AppCompat-а. В обоих случаях нужно вызвать setApplicationLocales() здесь
 * чтобы Activity создались уже с правильной локалью.
 */
class ProtocolVoiceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyStoredAsrLanguage()
    }

    private fun applyStoredAsrLanguage() {
        // Берём сохранённый выбор из interview_prefs (тот же что использует InterviewViewModel)
        val prefs = getSharedPreferences("interview_prefs", MODE_PRIVATE)
        val langName = prefs.getString("asr_language", null) ?: return

        // Если AppCompat уже знает локаль (Android 13+ запомнил) — не перезаписываем,
        // его выбор приоритетнее (пользователь мог поменять через системные Settings).
        val current = AppCompatDelegate.getApplicationLocales()
        if (!current.isEmpty) return

        val localeTag = when (langName) {
            "EN" -> "en"
            "RU" -> "ru"
            else -> return
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTag))
    }
}
