package app.protocolvoice.summary.pro_tier

import android.util.Log
import java.io.File

/**
 * JNI-обёртка над llama.cpp.
 *
 * Минимальный API:
 *   - load(modelPath) → handle
 *   - chat(handle, system, user, maxTokens, temperature) → text
 *   - free(handle)
 *
 * Native library: libllama-android.so (~3 MB, ARM64 only).
 *
 * Сборка нативной части (см. pro_tier/jni/README.md):
 *   1. Установить Android NDK r26+
 *   2. Скачать llama.cpp из github.com/ggml-org/llama.cpp
 *   3. Скопировать llama.cpp/examples/llama.android в app/src/main/cpp/llama
 *   4. Добавить externalNativeBuild в build.gradle.kts:
 *        ndk { abiFilters += setOf("arm64-v8a") }
 *        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
 *   5. ./gradlew assembleDebug
 *
 * До тех пор, пока native binary не собран, методы выбрасывают
 * UnsatisfiedLinkError — это нормально, PRO tier просто не доступен.
 *
 * Чтобы PRO tier стал доступен, нужно:
 *   1. Собрать libllama-android.so (см. выше)
 *   2. Скачать QVikhr-2.5-1.5B-Instruct-r.Q5_K_M.gguf (1.0 GB) в filesDir/models/qvikhr/
 *   3. SummaryFacade.isProTierAvailable() начнёт возвращать true
 */
class LlamaCppBridge {

    /**
     * Опаковый handle на нативный объект LlamaContext.
     * 0 означает не инициализирован.
     */
    private var nativeHandle: Long = 0L

    /**
     * Загрузить GGUF модель.
     *
     * @param modelPath абсолютный путь к .gguf файлу
     * @param contextSize контекст в токенах (4096 достаточно для наших промптов)
     * @param threads количество CPU потоков (4 — оптимально на Xiaomi 12T)
     * @return true если успешно
     */
    fun load(modelPath: String, contextSize: Int = 4096, threads: Int = 4): Boolean {
        if (nativeHandle != 0L) {
            Log.w(TAG, "Model already loaded; freeing previous")
            free()
        }
        require(File(modelPath).exists()) { "Model file not found: $modelPath" }
        try {
            nativeHandle = nativeLoad(modelPath, contextSize, threads)
            return nativeHandle != 0L
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native llama.cpp library not loaded — PRO tier unavailable", e)
            throw IllegalStateException(
                "Native library libllama-android.so не собрана. " +
                "Соберите через NDK по инструкции в pro_tier/jni/README.md",
                e,
            )
        }
    }

    /**
     * Выполнить chat completion с QVikhr.
     *
     * Использует Qwen 2.5 chat template:
     *   <|im_start|>system
     *   {system}<|im_end|>
     *   <|im_start|>user
     *   {user}<|im_end|>
     *   <|im_start|>assistant
     *
     * @param systemMessage system prompt (например "Ты — опытный аудитор...")
     * @param userMessage user prompt с контекстом и вопросом
     * @param maxTokens лимит токенов на ответ
     * @param temperature температура (0.0–1.0)
     * @return сгенерированный текст
     */
    fun chat(
        systemMessage: String,
        userMessage: String,
        maxTokens: Int = 500,
        temperature: Float = 0.2f,
        topP: Float = 0.9f,
        repeatPenalty: Float = 1.15f,
    ): String {
        check(nativeHandle != 0L) { "Model not loaded; call load() first" }
        return try {
            nativeChat(nativeHandle, systemMessage, userMessage, maxTokens, temperature, topP, repeatPenalty)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native chat failed — library not present", e)
            throw IllegalStateException("Native llama.cpp library missing", e)
        }
    }

    /**
     * Освободить ресурсы.
     */
    fun free() {
        if (nativeHandle != 0L) {
            try {
                nativeFree(nativeHandle)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Failed to call native free", e)
            } finally {
                nativeHandle = 0L
            }
        }
    }

    val isLoaded: Boolean get() = nativeHandle != 0L

    /**
     * Native methods. Native lib will be loaded lazily on first call.
     */
    private external fun nativeLoad(modelPath: String, contextSize: Int, threads: Int): Long
    private external fun nativeChat(
        handle: Long,
        systemMessage: String,
        userMessage: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
    ): String
    private external fun nativeFree(handle: Long)

    companion object {
        private const val TAG = "LlamaCppBridge"

        /**
         * Попытка загрузить native library. Возвращает true если получилось.
         * Используется в SummaryFacade.isProTierAvailable() для определения
         * доступности PRO tier.
         */
        fun isNativeLibraryAvailable(): Boolean {
            return try {
                System.loadLibrary("llama-android")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.i(TAG, "Native library 'llama-android' not present — PRO tier disabled")
                false
            }
        }
    }

    init {
        // Ленивая загрузка нативной библиотеки. Если её нет — все методы
        // будут падать с UnsatisfiedLinkError.
        try {
            System.loadLibrary("llama-android")
        } catch (e: UnsatisfiedLinkError) {
            Log.i(TAG, "Native library not loaded; instance will throw on use")
        }
    }
}
