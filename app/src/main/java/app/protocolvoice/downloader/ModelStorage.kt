package app.protocolvoice.downloader

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Управление физическими файлами моделей на диске.
 *
 * Структура filesDir/models/:
 *   gigaam_v3_e2e_ctc_int8.onnx
 *   speaker_embedding_camplus.onnx
 *   speaker_embedding.onnx          (опц.)
 *   speaker_embedding_v2.onnx       (опц.)
 *   .partial/                       — частично скачанные файлы (resume support)
 *     gigaam_v3_e2e_ctc_int8.onnx.partial
 *
 * При установленном приложении filesDir = /data/data/app.protocolvoice/files/
 * Этот путь приватный — никто кроме приложения туда не залезет.
 */
class ModelStorage(private val ctx: Context) {

    /** Корневая папка для всех моделей. */
    val rootDir: File by lazy {
        File(ctx.filesDir, "models").apply { mkdirs() }
    }

    /** Папка для частичных файлов (для resume support). */
    val partialDir: File by lazy {
        File(rootDir, ".partial").apply { mkdirs() }
    }

    /**
     * Полный путь до файла модели на диске.
     * Возвращает File независимо от того, существует он или нет.
     */
    fun fileFor(model: ModelRegistry.Model): File {
        val parent = if (model.subdir != null) File(rootDir, model.subdir).apply { mkdirs() } else rootDir
        return File(parent, model.filename)
    }

    /**
     * Путь до .partial-файла (для resume).
     * Если модель имеет subdir — создаёт соответствующую подпапку внутри .partial/.
     */
    fun partialFileFor(model: ModelRegistry.Model): File {
        val parent = if (model.subdir != null) {
            File(partialDir, model.subdir).apply { mkdirs() }
        } else {
            partialDir
        }
        return File(parent, "${model.filename}.partial")
    }

    /**
     * Существует ли модель на диске.
     */
    fun exists(model: ModelRegistry.Model): Boolean = fileFor(model).exists()

    /**
     * Корректность модели на диске:
     *  - файл существует
     *  - размер совпадает
     *  - SHA-256 совпадает (если sha256 не PLACEHOLDER_*)
     */
    fun isValid(model: ModelRegistry.Model, checkHash: Boolean = true): Boolean {
        val f = fileFor(model)
        if (!f.exists()) return false
        if (f.length() != model.sizeBytes) {
            Log.w(TAG, "Size mismatch for ${model.id}: expected ${model.sizeBytes}, got ${f.length()}")
            return false
        }
        if (checkHash && !model.sha256.startsWith("PLACEHOLDER")) {
            val actual = sha256(f)
            if (actual != model.sha256) {
                Log.w(TAG, "Hash mismatch for ${model.id}: expected ${model.sha256}, got $actual")
                return false
            }
        }
        return true
    }

    /**
     * Все ли REQUIRED + DEFAULT_EMBEDDING модели есть на диске.
     * Это критерий для решения "показать onboarding или нет".
     *
     * Если модели лежат в APK assets (debug-сборка с bundled моделями) и нет в filesDir,
     * их нужно скопировать в filesDir и вернуть true — так Downloader не покажется.
     * Это «fallback» логика для F-Droid и дев-сборок.
     *
     * Флаг "force_downloader" в SharedPreferences отключает fallback на один запуск
     * (используется debug-действием "Перезагрузить модели" для тестирования скачивания).
     * Флаг сбрасывается после проверки — второй рестарт вернётся к обычному поведению.
     */
    fun isFirstRunComplete(ctx: Context? = null): Boolean {
        val allInFilesDir = ModelRegistry.FIRST_RUN_REQUIRED.all { isValid(it, checkHash = false) }
        if (allInFilesDir) return true

        // Fallback: проверяем есть ли модели в APK assets и при наличии — копируем в filesDir.
        // Нужен Context для assetManager. Если ctx == null — возвращаем результат без fallback.
        if (ctx == null) return false

        // Дебажный флаг: если «force_downloader» взведён — пропускаем assets fallback.
        // НЕ сбрасываем флаг здесь — эта функция может вызываться несколько раз
        // при старте (MainActivity + DownloaderViewModel.checkInitialState).
        // Сброс флага делаем явно через clearForceDownloaderFlag() после успеха скачивания.
        val prefs = ctx.getSharedPreferences("downloader_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("force_downloader", false)) {
            Log.i(TAG, "force_downloader flag set — skipping assets fallback")
            return false
        }

        val assetMgr = ctx.assets
        val available = try { assetMgr.list("asr")?.toSet() ?: emptySet() } catch (_: Throwable) { emptySet() }
        val missing = ModelRegistry.FIRST_RUN_REQUIRED.filter { !isValid(it, checkHash = false) }
        // Проверяем что ВСЕ недостающие есть в assets — иначе не смысла копировать часть.
        if (!missing.all { it.filename in available }) return false

        // Копируем. На девайсе это ~30 сек для 332 MB — приемлемо для одноразовой операции.
        try {
            for (model in missing) {
                val target = fileFor(model)
                Log.i(TAG, "Copying ${model.filename} from APK assets to filesDir...")
                assetMgr.open("asr/${model.filename}").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Copied ${model.filename}: ${target.length()} bytes")
            }
            return ModelRegistry.FIRST_RUN_REQUIRED.all { isValid(it, checkHash = false) }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to copy models from assets: ${e.message}", e)
            return false
        }
    }

    /**
     * Сбросить флаг force_downloader. Вызывается после успешного скачивания моделей
     * — чтобы при следующем старте приложения isFirstRunComplete вернулся к обычному поведению.
     */
    fun clearForceDownloaderFlag() {
        val prefs = ctx.getSharedPreferences("downloader_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("force_downloader", false).apply()
        Log.i(TAG, "force_downloader flag cleared")
    }

    /**
     * Взвести флаг «force_downloader» — при следующем вызове isFirstRunComplete()
     * пропустит assets fallback и вернёт false. Используется debug-кнопкой
     * "Перезагрузить модели" чтобы принудительно показать Downloader-экран
     * даже если модели всё ещё лежат в APK assets.
     */
    fun setForceDownloaderFlag() {
        val prefs = ctx.getSharedPreferences("downloader_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("force_downloader", true).apply()
        Log.i(TAG, "force_downloader flag set")
    }

    /**
     * Сколько байт уже скачано из first-run набора (для прогресса при возобновлении).
     */
    fun firstRunBytesDownloaded(): Long {
        return ModelRegistry.FIRST_RUN_REQUIRED.sumOf { model ->
            val f = fileFor(model)
            val partial = partialFileFor(model)
            when {
                f.exists() && f.length() == model.sizeBytes -> model.sizeBytes
                f.exists() -> f.length()
                partial.exists() -> partial.length()
                else -> 0L
            }
        }
    }

    /**
     * Удалить модель с диска (включая partial).
     */
    fun delete(model: ModelRegistry.Model): Boolean {
        var ok = true
        val f = fileFor(model)
        val p = partialFileFor(model)
        if (f.exists() && !f.delete()) ok = false
        if (p.exists() && !p.delete()) ok = false
        return ok
    }

    /**
     * Удалить все скачанные модели (для troubleshooting).
     */
    fun deleteAll(): Boolean {
        var ok = true
        ModelRegistry.ALL.forEach { if (!delete(it)) ok = false }
        return ok
    }

    /**
     * Сколько места занимают все скачанные модели на диске (байт).
     */
    fun totalDiskUsage(): Long {
        return ModelRegistry.ALL.sumOf { model ->
            val f = fileFor(model)
            if (f.exists()) f.length() else 0L
        } + (partialDir.listFiles()?.sumOf { it.length() } ?: 0L)
    }

    /**
     * Завершение скачивания: переместить из .partial/ в rootDir.
     * Атомарная операция (rename).
     */
    fun promotePartialToFinal(model: ModelRegistry.Model): Boolean {
        val partial = partialFileFor(model)
        val final = fileFor(model)
        if (!partial.exists()) return false
        if (final.exists()) final.delete()
        return partial.renameTo(final)
    }

    /**
     * SHA-256 файла. Используется для верификации целостности.
     */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ModelStorage"
    }
}
