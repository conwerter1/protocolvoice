package app.protocolvoice.downloader

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Скачивание моделей по HTTP с поддержкой resume.
 *
 * Поток скачивания одной модели:
 *   1. Проверяем что файл уже не скачан полностью + sha256 совпадает → return SUCCESS
 *   2. Если есть .partial с меньшим размером — отправляем Range: bytes=N- запрос
 *   3. Иначе — обычный GET с Content-Length
 *   4. Стримим в .partial/ кусками 64 KB, обновляем прогресс
 *   5. Проверяем размер на матч с ожидаемым
 *   6. Считаем sha256 → если matches → переименовываем в финальный путь
 *   7. Если sha256 не совпадает → удаляем и retry (макс 3 раза)
 *
 * Все методы suspend — поддерживают cancellation. При отмене .partial остаётся
 * на диске, при следующем запуске продолжит с того же места.
 *
 * Прогресс через StateFlow:
 *   - currentModelId : id текущей качаемой модели (null = idle)
 *   - bytesDownloaded: всего скачано байт (по всем моделям в текущей сессии)
 *   - bytesTotal     : целевой объём
 *   - speedBps       : скорость скачивания, байт в секунду (за последние 2 сек)
 *   - errorMessage   : описание последней ошибки или null
 */
class ModelDownloader(
    private val storage: ModelStorage,
) {

    /** Идентификатор модели которая сейчас скачивается, или null. */
    private val _currentModelId = MutableStateFlow<String?>(null)
    val currentModelId: StateFlow<String?> = _currentModelId.asStateFlow()

    /** Скачано байт суммарно в текущей сессии. */
    private val _bytesDownloaded = MutableStateFlow(0L)
    val bytesDownloaded: StateFlow<Long> = _bytesDownloaded.asStateFlow()

    /** Целевой объём в текущей сессии. */
    private val _bytesTotal = MutableStateFlow(0L)
    val bytesTotal: StateFlow<Long> = _bytesTotal.asStateFlow()

    /** Скорость скачивания в байтах/сек (среднее за последние 2 сек). */
    private val _speedBps = MutableStateFlow(0L)
    val speedBps: StateFlow<Long> = _speedBps.asStateFlow()

    /** Сообщение об ошибке последней неудачной попытки, или null. */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Текущее состояние. */
    enum class Status {
        IDLE,
        DOWNLOADING,
        VERIFYING,
        SUCCESS,
        ERROR,
        CANCELLED,
    }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    // ────────────────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────────────────

    /**
     * Скачать набор моделей по списку. Если модель уже на диске и валидна —
     * пропускаем. Если есть .partial — резюмируем.
     *
     * @return true если всё успешно, false если хоть одна модель не скачалась.
     *         Подробности — в errorMessage и логах.
     */
    suspend fun downloadAll(models: List<ModelRegistry.Model>): Boolean = withContext(Dispatchers.IO) {
        _errorMessage.value = null
        _status.value = Status.DOWNLOADING

        // Считаем общий объём (только то что ещё не скачано)
        val pending = models.filter { !storage.isValid(it, checkHash = false) }
        if (pending.isEmpty()) {
            _status.value = Status.SUCCESS
            return@withContext true
        }
        _bytesTotal.value = pending.sumOf { it.sizeBytes }
        _bytesDownloaded.value = 0L

        for (model in pending) {
            if (!coroutineContext.isActive) {
                _status.value = Status.CANCELLED
                return@withContext false
            }
            // Учтём то что уже было скачано в .partial — стартуем с этого
            val partial = storage.partialFileFor(model)
            val alreadyDownloaded = if (partial.exists()) partial.length() else 0L

            _currentModelId.value = model.id
            val ok = downloadOneWithRetry(model, maxAttempts = 3)
            if (!ok) {
                _status.value = Status.ERROR
                _currentModelId.value = null
                return@withContext false
            }
        }

        _currentModelId.value = null
        _status.value = Status.SUCCESS
        true
    }

    /**
     * Скачать одну модель с retry (3 попытки).
     */
    private suspend fun downloadOneWithRetry(
        model: ModelRegistry.Model,
        maxAttempts: Int,
    ): Boolean {
        var lastError: String? = null
        for (attempt in 1..maxAttempts) {
            if (!coroutineContext.isActive) return false
            try {
                downloadOne(model)
                // Успех — переходим к верификации
                if (verifyAndPromote(model)) {
                    Log.i(TAG, "Model ${model.id} downloaded and verified successfully")
                    return true
                } else {
                    lastError = "Verification failed (attempt $attempt)"
                    // удалим .partial и попробуем ещё раз
                    storage.partialFileFor(model).delete()
                }
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "Download attempt $attempt failed for ${model.id}: $lastError", e)
                // Сохраняем .partial для следующей попытки (resume)
            }
            // Между попытками — пауза 2 сек
            if (attempt < maxAttempts && coroutineContext.isActive) {
                kotlinx.coroutines.delay(2000)
            }
        }
        _errorMessage.value = "Failed to download ${model.filename}: $lastError"
        return false
    }

    /**
     * Скачать одну модель с поддержкой resume.
     * Поднимает HTTP-исключение при ошибке — обрабатывается в downloadOneWithRetry.
     */
    private suspend fun downloadOne(model: ModelRegistry.Model) {
        val url = ModelRegistry.BASE_URL + model.urlSuffix
        val partial = storage.partialFileFor(model)
        val startFrom = if (partial.exists()) partial.length() else 0L

        Log.i(TAG, "Downloading ${model.id} from $url (resume from $startFrom)")
        _status.value = Status.DOWNLOADING

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            // Range header для resume
            if (startFrom > 0) {
                setRequestProperty("Range", "bytes=$startFrom-")
            }
            setRequestProperty("User-Agent", "ProtocolVoice/1.0 (Android)")
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }

        try {
            val responseCode = conn.responseCode
            // 200 = полная загрузка с нуля; 206 = частичная (Range)
            if (responseCode != 200 && responseCode != 206) {
                throw IOException("HTTP $responseCode: ${conn.responseMessage}")
            }
            // Если сервер не поддерживает Range и вернул 200 — стартуем заново
            val actualStartFrom = if (responseCode == 206) startFrom else 0L
            if (actualStartFrom == 0L && partial.exists()) {
                partial.delete()
            }

            val expectedTotal = if (responseCode == 206) {
                // Content-Range: bytes 1024-2047/2048
                val range = conn.getHeaderField("Content-Range")
                range?.substringAfter("/")?.toLongOrNull() ?: model.sizeBytes
            } else {
                conn.contentLengthLong.takeIf { it > 0 } ?: model.sizeBytes
            }

            // Открываем .partial в режиме append (если есть start), иначе с нуля
            val output = FileOutputStream(partial, /* append = */ actualStartFrom > 0)
            val input = conn.inputStream

            try {
                val buf = ByteArray(64 * 1024)
                var modelDownloaded = actualStartFrom
                var totalReadInSession = 0L
                var lastSpeedSampleAt = System.currentTimeMillis()
                var lastSpeedSampleBytes = 0L

                while (coroutineContext.isActive) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    modelDownloaded += n
                    totalReadInSession += n
                    _bytesDownloaded.value += n

                    // Обновляем скорость раз в ~500ms
                    val now = System.currentTimeMillis()
                    if (now - lastSpeedSampleAt > 500) {
                        val deltaBytes = totalReadInSession - lastSpeedSampleBytes
                        val deltaMs = (now - lastSpeedSampleAt).coerceAtLeast(1)
                        _speedBps.value = (deltaBytes * 1000L) / deltaMs
                        lastSpeedSampleAt = now
                        lastSpeedSampleBytes = totalReadInSession
                    }
                }
                output.flush()
            } finally {
                try { input.close() } catch (_: Throwable) {}
                try { output.close() } catch (_: Throwable) {}
            }

            if (!coroutineContext.isActive) {
                throw kotlinx.coroutines.CancellationException("Download cancelled")
            }

            Log.i(TAG, "Downloaded ${partial.length()} bytes for ${model.id}")
        } catch (e: SocketTimeoutException) {
            throw IOException("Network timeout — check connection", e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Проверить размер + sha256 + переместить в final.
     */
    private fun verifyAndPromote(model: ModelRegistry.Model): Boolean {
        _status.value = Status.VERIFYING
        val partial = storage.partialFileFor(model)
        if (!partial.exists()) return false

        // Проверка размера (быстро)
        if (partial.length() != model.sizeBytes) {
            Log.w(TAG, "Size mismatch for ${model.id}: ${partial.length()} != ${model.sizeBytes}")
            // Если меньше ожидаемого — это incomplete download, .partial оставляем
            // для следующей попытки. Если больше — ошибка, удаляем.
            if (partial.length() > model.sizeBytes) {
                partial.delete()
            }
            return false
        }

        // SHA-256 (медленно, ~2-3 сек на 300 MB модель)
        if (!model.sha256.startsWith("PLACEHOLDER")) {
            val actual = storage.sha256(partial)
            if (actual != model.sha256) {
                Log.e(TAG, "Hash mismatch for ${model.id}: expected ${model.sha256}, got $actual")
                partial.delete()
                return false
            }
        } else {
            Log.w(TAG, "Skipping hash check for ${model.id} (placeholder)")
        }

        // Переименовываем в финальное имя
        return storage.promotePartialToFinal(model)
    }

    companion object {
        private const val TAG = "ModelDownloader"
    }
}
