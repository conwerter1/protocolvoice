package app.protocolvoice.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.protocolvoice.R
import java.io.File

/**
 * Простой плеер для прослушивания записанного интервью поверх
 * `android.media.MediaPlayer`. Поддерживает M4A и WAV нативно, без extra-зависимостей.
 *
 * Состояния публикуются через StateFlow — UI наблюдает их.
 *
 * Особенности:
 *   - playSegment(start, end): играет ровно отрезок и автоматически останавливается на end.
 *     Используется для тапа по карточке сегмента.
 *   - play() / pause() / seekTo(ms): глобальные операции для мини-плеера.
 *   - currentMs обновляется каждые 100 мс через корутину-poller, пока играет.
 *   - При смене источника (loadFromFile) предыдущий источник освобождается.
 */
class AudioPlayer(private val ctx: Context) {

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentMs = MutableStateFlow(0L)
    val currentMs: StateFlow<Long> = _currentMs.asStateFlow()

    private val _totalMs = MutableStateFlow(0L)
    val totalMs: StateFlow<Long> = _totalMs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Если играем сегмент целевой границы — здесь её endMs. null = играем без границы. */
    private val _segmentEndMs = MutableStateFlow<Long?>(null)
    val segmentEndMs: StateFlow<Long?> = _segmentEndMs.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var loadedFile: File? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollerJob: Job? = null

    /**
     * Загрузить аудио-файл в плеер. Если уже загружен этот же — ничего не делаем.
     * Возвращает true при успехе.
     */
    fun loadFromFile(file: File): Boolean {
        if (loadedFile?.absolutePath == file.absolutePath && mediaPlayer != null) {
            return true
        }
        if (!file.exists() || file.length() == 0L) {
            _error.value = ctx.getString(R.string.player_error_audio_missing)
            return false
        }
        // Закрываем предыдущий
        releaseInternal()
        return try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    _isPlaying.value = false
                    _currentMs.value = 0L
                    _segmentEndMs.value = null
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    _error.value = ctx.getString(R.string.player_error_code, what)
                    _isPlaying.value = false
                    true
                }
            }
            mediaPlayer = mp
            loadedFile = file
            _totalMs.value = mp.duration.toLong().coerceAtLeast(0L)
            _currentMs.value = 0L
            _error.value = null
            Log.i(TAG, "Loaded ${file.name}, duration=${_totalMs.value}ms")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load ${file.absolutePath}", e)
            _error.value = ctx.getString(R.string.player_error_load)
            mediaPlayer = null
            loadedFile = null
            false
        }
    }

    /** Проиграть всё с текущей позиции. */
    fun play() {
        val mp = mediaPlayer ?: return
        try {
            _segmentEndMs.value = null
            mp.start()
            _isPlaying.value = true
            startPoller()
        } catch (e: Throwable) {
            Log.e(TAG, "play() failed", e)
            _error.value = ctx.getString(R.string.player_error_play)
        }
    }

    /** Проиграть отрезок [startMs, endMs] и остановиться на конце. */
    fun playSegment(startMs: Long, endMs: Long) {
        val mp = mediaPlayer ?: return
        try {
            mp.seekTo(startMs.toInt())
            _currentMs.value = startMs
            _segmentEndMs.value = endMs
            mp.start()
            _isPlaying.value = true
            startPoller()
        } catch (e: Throwable) {
            Log.e(TAG, "playSegment() failed", e)
            _error.value = ctx.getString(R.string.player_error_play_segment)
        }
    }

    fun pause() {
        val mp = mediaPlayer ?: return
        try {
            if (mp.isPlaying) mp.pause()
            _isPlaying.value = false
            // segmentEndMs оставляем — если возобновят, продолжим до конца сегмента
        } catch (_: Throwable) {}
    }

    /** Toggle play/pause — удобно для одной кнопки в мини-плеере. */
    fun toggle() {
        if (_isPlaying.value) pause() else play()
    }

    fun seekTo(ms: Long) {
        val mp = mediaPlayer ?: return
        try {
            val clamped = ms.coerceIn(0L, _totalMs.value)
            mp.seekTo(clamped.toInt())
            _currentMs.value = clamped
            // Свободный seek сбрасывает границу сегмента
            _segmentEndMs.value = null
        } catch (_: Throwable) {}
    }

    fun stop() {
        val mp = mediaPlayer ?: return
        try {
            if (mp.isPlaying) mp.pause()
            mp.seekTo(0)
        } catch (_: Throwable) {}
        _isPlaying.value = false
        _currentMs.value = 0L
        _segmentEndMs.value = null
    }

    fun consumeError() { _error.value = null }

    fun release() {
        releaseInternal()
        loadedFile = null
        _totalMs.value = 0L
        _currentMs.value = 0L
        _isPlaying.value = false
        _segmentEndMs.value = null
    }

    private fun releaseInternal() {
        pollerJob?.cancel()
        pollerJob = null
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        mediaPlayer = null
    }

    /** Корутина-poller: обновляет _currentMs пока играет, и тормозит на segmentEndMs. */
    private fun startPoller() {
        pollerJob?.cancel()
        pollerJob = scope.launch {
            while (isActive) {
                val mp = mediaPlayer ?: break
                if (!_isPlaying.value) break
                val pos = try { mp.currentPosition.toLong() } catch (_: Throwable) { break }
                _currentMs.value = pos
                val end = _segmentEndMs.value
                if (end != null && pos >= end) {
                    try { mp.pause() } catch (_: Throwable) {}
                    _isPlaying.value = false
                    _segmentEndMs.value = null
                    break
                }
                delay(100)
            }
        }
    }

    companion object {
        private const val TAG = "AudioPlayer"
    }
}
