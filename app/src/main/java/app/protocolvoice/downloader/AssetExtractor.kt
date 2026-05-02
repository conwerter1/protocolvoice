package app.protocolvoice.downloader

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Извлекает маленькие модели из APK-assets в filesDir.
 *
 * Зачем: sherpa-onnx Kotlin API требует чтобы все модели в одном
 * OfflineRecognizer / OfflineSpeakerDiarization грузились ОДНИМ способом —
 * либо все через assetManager, либо все по абсолютным путям.
 *
 * Большие модели (ASR, embedding) скачиваются по сети и лежат в filesDir.
 * Чтобы можно было использовать абсолютные пути ВЕЗДЕ, маленькие модели
 * (segmentation, vad, tokens.txt) тоже копируем из assets в filesDir
 * при первом запуске.
 *
 * Маленькие модели остаются в APK — это минимально (~2 МБ). Их копирование
 * выполняется один раз при старте AsrService.
 */
object AssetExtractor {

    private const val TAG = "AssetExtractor"

    /** Имена ассет-моделей которые нужно извлечь в filesDir. */
    private val SMALL_ASSETS = listOf(
        "asr/segmentation.onnx",
        "asr/silero_vad.onnx",
        "asr/gigaam_v3_e2e_ctc_tokens.txt",
    )

    /**
     * Извлекает все маленькие модели в filesDir/models/<name>.
     * Идемпотентно: если файл уже есть и тот же размер — пропускает.
     */
    fun extractSmallAssets(ctx: Context, storage: ModelStorage) {
        for (assetPath in SMALL_ASSETS) {
            extractOne(ctx, assetPath, storage.rootDir)
        }
    }

    private fun extractOne(ctx: Context, assetPath: String, targetDir: File) {
        // Имя файла без префикса "asr/"
        val filename = assetPath.substringAfterLast("/")
        val target = File(targetDir, filename)

        // Получаем размер из assets (через open)
        val expectedSize = try {
            ctx.assets.openFd(assetPath).use { it.length }
        } catch (e: Throwable) {
            // Не все assets можно через openFd — у больших файлов будет fail
            // Тогда определим размер по фактическому inputStream copy
            -1L
        }

        // Если уже есть и размер совпадает — пропускаем
        if (target.exists() && (expectedSize < 0 || target.length() == expectedSize)) {
            Log.d(TAG, "Asset already extracted: $filename (${target.length()} bytes)")
            return
        }

        // Копируем
        Log.i(TAG, "Extracting asset $assetPath → $target")
        target.parentFile?.mkdirs()
        ctx.assets.open(assetPath).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Log.i(TAG, "Extracted $filename: ${target.length()} bytes")
    }

    /**
     * Полные абсолютные пути для использования в sherpa-onnx config'ах.
     */
    object Paths {
        fun segmentation(storage: ModelStorage): String =
            File(storage.rootDir, "segmentation.onnx").absolutePath

        fun sileroVad(storage: ModelStorage): String =
            File(storage.rootDir, "silero_vad.onnx").absolutePath

        fun asrTokens(storage: ModelStorage): String =
            File(storage.rootDir, "gigaam_v3_e2e_ctc_tokens.txt").absolutePath

        fun asrModel(storage: ModelStorage): String =
            storage.fileFor(ModelRegistry.ASR_MAIN).absolutePath

        fun embeddingFor(storage: ModelStorage, model: app.protocolvoice.asr.EmbeddingModel): String {
            val regModel = when (model) {
                app.protocolvoice.asr.EmbeddingModel.CAMPlus -> ModelRegistry.EMBEDDING_CAMPLUS
                app.protocolvoice.asr.EmbeddingModel.ERes2Net -> ModelRegistry.EMBEDDING_V1
                app.protocolvoice.asr.EmbeddingModel.ERes2NetV2 -> ModelRegistry.EMBEDDING_V2
            }
            return storage.fileFor(regModel).absolutePath
        }
    }
}
