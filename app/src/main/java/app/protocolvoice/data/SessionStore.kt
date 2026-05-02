package app.protocolvoice.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import app.protocolvoice.asr.ConfidenceLevel
import app.protocolvoice.asr.InterviewMetadata
import app.protocolvoice.asr.InterviewTranscript
import app.protocolvoice.asr.Participants
import app.protocolvoice.asr.TranscriptSegment
import app.protocolvoice.asr.TranscriptWord
import java.io.File

/**
 * Хранилище сессий интервью — JSON-файлы в `app/files/sessions/`.
 *
 * Каждая сессия = один файл `session_<ts>.json`. Внутри:
 *   - id            — уникальный ID (timestamp-строка)
 *   - createdAt     — System.currentTimeMillis() создания
 *   - audioFileName — имя аудио в `app/files/recordings/` (.m4a или .wav), или null если удалено
 *   - metadata      — InterviewMetadata
 *   - participants  — Participants (Map<Int, String>)
 *   - transcript    — InterviewTranscript
 *
 * Не использует SQL — для аудитора десятки записей в год, listFiles() справится.
 *
 * Все операции синхронные blocking (быстрые: парсинг JSON 1–10 МБ).
 * Вызывать из viewModelScope в Dispatchers.IO.
 */
object SessionStore {

    private const val TAG = "SessionStore"
    private const val SESSIONS_DIR = "sessions"
    private const val FILE_PREFIX = "session_"
    private const val FILE_SUFFIX = ".json"

    /** Краткое описание сессии для списка истории — без полного транскрипта. */
    data class SessionSummary(
        val id: String,
        val createdAt: Long,
        val title: String,
        val location: String,
        val auditorName: String,
        val durationMs: Long,
        val numSpeakers: Int,
        val numSegments: Int,
        val totalWords: Int,
        val audioFileName: String?,
        val audioExists: Boolean,
    )

    /** Полная загруженная сессия. */
    data class LoadedSession(
        val id: String,
        val metadata: InterviewMetadata,
        val participants: Participants,
        val transcript: InterviewTranscript,
        /** Файл аудио в app/files/recordings/, null если удалено. */
        val audioFile: File?,
    )

    // -----------------------------------------------------------------
    // Запись / чтение / удаление
    // -----------------------------------------------------------------

    /**
     * Сохранить сессию в JSON. Возвращает ID сессии (для последующего обновления).
     * Если id уже существует — перезаписывается (поэтому можно сохранять
     * после правки имён участников / метаданных).
     */
    fun save(
        ctx: Context,
        id: String,
        metadata: InterviewMetadata,
        participants: Participants,
        transcript: InterviewTranscript,
        audioFile: File?,
    ): Boolean {
        return try {
            val dir = sessionsDir(ctx)
            dir.mkdirs()
            val file = File(dir, "$FILE_PREFIX$id$FILE_SUFFIX")
            val json = serialize(id, metadata, participants, transcript, audioFile)
            file.writeText(json.toString(2), Charsets.UTF_8)
            Log.i(TAG, "Saved session $id (${file.length() / 1024} KB)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "save() failed for id=$id", e)
            false
        }
    }

    /** Сгенерировать новый id (timestamp). */
    fun newId(): String =
        "${System.currentTimeMillis()}"

    /**
     * Загрузить сессию по id. null если файл отсутствует или повреждён.
     */
    fun load(ctx: Context, id: String): LoadedSession? {
        return try {
            val file = File(sessionsDir(ctx), "$FILE_PREFIX$id$FILE_SUFFIX")
            if (!file.exists()) return null
            val json = JSONObject(file.readText(Charsets.UTF_8))
            deserialize(ctx, json)
        } catch (e: Throwable) {
            Log.e(TAG, "load() failed for id=$id", e)
            null
        }
    }

    /**
     * Список всех сессий — только summary, без полного транскрипта (быстро).
     * Сортировка: свежие сверху.
     */
    fun listSummaries(ctx: Context): List<SessionSummary> {
        val dir = sessionsDir(ctx)
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
        } ?: return emptyList()
        val out = mutableListOf<SessionSummary>()
        for (f in files) {
            try {
                val json = JSONObject(f.readText(Charsets.UTF_8))
                out.add(parseSummary(ctx, json))
            } catch (e: Throwable) {
                Log.w(TAG, "Skipping corrupted session file: ${f.name}", e)
            }
        }
        out.sortByDescending { it.createdAt }
        return out
    }

    /**
     * Удалить сессию (JSON + аудио-файл). Возвращает true если удалось хотя бы JSON.
     */
    fun delete(ctx: Context, id: String): Boolean {
        var anyDeleted = false
        try {
            // Сначала смотрим audioFileName в JSON — чтобы узнать что удалять
            val jsonFile = File(sessionsDir(ctx), "$FILE_PREFIX$id$FILE_SUFFIX")
            if (jsonFile.exists()) {
                try {
                    val json = JSONObject(jsonFile.readText(Charsets.UTF_8))
                    val audioName = json.optString("audioFileName", "").takeIf { it.isNotBlank() }
                    if (audioName != null) {
                        val audio = File(recordingsDir(ctx), audioName)
                        if (audio.exists()) {
                            if (audio.delete()) Log.i(TAG, "Deleted audio: $audioName")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to read JSON for audio cleanup", e)
                }
                if (jsonFile.delete()) anyDeleted = true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "delete() failed for id=$id", e)
        }
        return anyDeleted
    }

    // -----------------------------------------------------------------
    // Сериализация / десериализация
    // -----------------------------------------------------------------

    private fun serialize(
        id: String,
        metadata: InterviewMetadata,
        participants: Participants,
        transcript: InterviewTranscript,
        audioFile: File?,
    ): JSONObject {
        val root = JSONObject()
        root.put("id", id)
        root.put("createdAt", System.currentTimeMillis())
        root.put("audioFileName", audioFile?.name ?: JSONObject.NULL)

        // metadata
        root.put("metadata", JSONObject().apply {
            put("title", metadata.title)
            put("location", metadata.location)
            put("auditorName", metadata.auditorName)
            put("date", metadata.date)
            put("notes", metadata.notes)
        })

        // participants
        val partsObj = JSONObject()
        for ((spkId, name) in participants.names) {
            partsObj.put(spkId.toString(), name)
        }
        root.put("participants", partsObj)

        // transcript
        root.put("transcript", JSONObject().apply {
            put("totalDurationMs", transcript.totalDurationMs)
            put("recordedAt", transcript.recordedAt)
            put("sourceWavPath", transcript.sourceWavPath)
            put("numSpeakers", transcript.numSpeakers)
            val segArr = JSONArray()
            for (seg in transcript.segments) {
                val segObj = JSONObject()
                segObj.put("speakerId", seg.speakerId)
                segObj.put("startMs", seg.startMs)
                segObj.put("endMs", seg.endMs)
                val wordsArr = JSONArray()
                for (w in seg.words) {
                    val wObj = JSONObject()
                    wObj.put("text", w.text)
                    wObj.put("startMs", w.startMs)
                    wObj.put("endMs", w.endMs)
                    wObj.put("confidence", w.confidence.toDouble())
                    wordsArr.put(wObj)
                }
                segObj.put("words", wordsArr)
                segArr.put(segObj)
            }
            put("segments", segArr)
        })

        return root
    }

    private fun deserialize(ctx: Context, root: JSONObject): LoadedSession {
        val id = root.getString("id")
        val audioFileName = if (root.isNull("audioFileName")) null
                            else root.optString("audioFileName", "").takeIf { it.isNotBlank() }
        val audioFile = audioFileName?.let { File(recordingsDir(ctx), it) }
            ?.takeIf { it.exists() && it.length() > 0 }

        val mdObj = root.getJSONObject("metadata")
        val metadata = InterviewMetadata(
            title = mdObj.optString("title", ""),
            location = mdObj.optString("location", ""),
            auditorName = mdObj.optString("auditorName", ""),
            date = mdObj.optLong("date", System.currentTimeMillis()),
            notes = mdObj.optString("notes", ""),
        )

        val partsObj = root.getJSONObject("participants")
        val partsMap = mutableMapOf<Int, String>()
        val partsKeys = partsObj.keys()
        while (partsKeys.hasNext()) {
            val k = partsKeys.next()
            val spkId = k.toIntOrNull() ?: continue
            partsMap[spkId] = partsObj.getString(k)
        }
        val participants = Participants(partsMap)

        val tObj = root.getJSONObject("transcript")
        val segArr = tObj.getJSONArray("segments")
        val segments = mutableListOf<TranscriptSegment>()
        for (i in 0 until segArr.length()) {
            val segObj = segArr.getJSONObject(i)
            val wordsArr = segObj.getJSONArray("words")
            val words = mutableListOf<TranscriptWord>()
            for (j in 0 until wordsArr.length()) {
                val wObj = wordsArr.getJSONObject(j)
                words.add(TranscriptWord(
                    text = wObj.getString("text"),
                    startMs = wObj.getLong("startMs"),
                    endMs = wObj.getLong("endMs"),
                    confidence = wObj.getDouble("confidence").toFloat(),
                ))
            }
            segments.add(TranscriptSegment(
                speakerId = segObj.getInt("speakerId"),
                startMs = segObj.getLong("startMs"),
                endMs = segObj.getLong("endMs"),
                words = words,
            ))
        }
        val transcript = InterviewTranscript(
            segments = segments,
            totalDurationMs = tObj.getLong("totalDurationMs"),
            recordedAt = tObj.optLong("recordedAt", System.currentTimeMillis()),
            sourceWavPath = tObj.optString("sourceWavPath", ""),
            numSpeakers = tObj.optInt("numSpeakers", 0),
        )

        return LoadedSession(
            id = id,
            metadata = metadata,
            participants = participants,
            transcript = transcript,
            audioFile = audioFile,
        )
    }

    private fun parseSummary(ctx: Context, root: JSONObject): SessionSummary {
        val id = root.getString("id")
        val createdAt = root.optLong("createdAt", 0L)
        val mdObj = root.getJSONObject("metadata")
        val tObj = root.getJSONObject("transcript")
        val segArr = tObj.getJSONArray("segments")
        var totalWords = 0
        for (i in 0 until segArr.length()) {
            totalWords += segArr.getJSONObject(i).getJSONArray("words").length()
        }
        val audioFileName = if (root.isNull("audioFileName")) null
                            else root.optString("audioFileName", "").takeIf { it.isNotBlank() }
        val audioExists = audioFileName?.let {
            File(recordingsDir(ctx), it).exists()
        } ?: false
        return SessionSummary(
            id = id,
            createdAt = createdAt,
            title = mdObj.optString("title", ""),
            location = mdObj.optString("location", ""),
            auditorName = mdObj.optString("auditorName", ""),
            durationMs = tObj.optLong("totalDurationMs", 0L),
            numSpeakers = tObj.optInt("numSpeakers", 0),
            numSegments = segArr.length(),
            totalWords = totalWords,
            audioFileName = audioFileName,
            audioExists = audioExists,
        )
    }

    private fun sessionsDir(ctx: Context) = File(ctx.filesDir, SESSIONS_DIR)
    private fun recordingsDir(ctx: Context) = File(ctx.filesDir, "recordings")
}
