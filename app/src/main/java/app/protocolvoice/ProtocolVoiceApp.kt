package app.protocolvoice

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Application class — точка инициализации до первого Activity.
 *
 * Главные задачи:
 *   1. На СВЕЖЕЙ установке (нет asr_language в prefs) — выбрать язык по умолчанию
 *      исходя из системной локали:
 *        - системный язык "ru" → asr_language = "RU" (Whisper не нужен, качаем GigaAM)
 *        - всё остальное (en, fr, de, ...) → asr_language = "EN" (универсальный, качаем Whisper)
 *      Это значит: русскому пользователю — русский UI и русская модель ASR
 *      англоязычному (или любому другому) — английский UI и Whisper.
 *      Пользователь всё равно может переключить через 🌐 на Downloader экране.
 *
 *   2. Применить сохранённый язык интерфейса до создания MainActivity
 *      (через AppCompatDelegate.setApplicationLocales). Без этого свежеустановленное
 *      приложение могло бы показать смесь системного и приложенческого языка.
 *
 * Persistence:
 *   - asr_language хранится в SharedPreferences "interview_prefs" (тот же что в InterviewViewModel)
 *   - UI locale хранится либо в SharedPreferences AppCompat (Android <13)
 *     либо в системе через PerAppLanguagesManager (Android 13+)
 */
class ProtocolVoiceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        applyLanguagePreference()
    }

    private fun applyLanguagePreference() {
        val prefs = getSharedPreferences("interview_prefs", MODE_PRIVATE)
        var langName = prefs.getString("asr_language", null)

        // Свежая установка → определяем по системному языку.
        if (langName == null) {
            langName = detectDefaultAsrLanguage()
            prefs.edit().putString("asr_language", langName).apply()
            Log.i(TAG, "First-run: set asr_language=$langName based on system locale")
        }

        // Если AppCompat уже знает локаль (Android 13+ запомнил/пользователь поменял через системные Settings)
        // — не перезаписываем, его выбор приоритетнее.
        val current = AppCompatDelegate.getApplicationLocales()
        if (!current.isEmpty) return

        val localeTag = when (langName) {
            "EN" -> "en"
            "RU" -> "ru"
            else -> return
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTag))
    }

    /**
     * Определяет язык по умолчанию исходя из системной локали Android.
     * Только русский → RU, всё остальное → EN.
     *
     * Логика: GigaAM умеет только русский, Whisper умеет почти всё что нужно
     * не-русскоговорящим. Если в Android системе выставлен русский — пользователь
     * с большой вероятностью русскоговорящий. Иначе — даём Whisper.
     */
    private fun detectDefaultAsrLanguage(): String {
        val systemLocale = resources.configuration.locales.get(0) ?: Locale.getDefault()
        val lang = systemLocale.language.lowercase()
        return if (lang == "ru") "RU" else "EN"
    }

    companion object {
        private const val TAG = "ProtocolVoiceApp"
    }
}
