package app.protocolvoice.docx

import app.protocolvoice.asr.ConfidenceLevel
import app.protocolvoice.asr.InterviewMetadata
import app.protocolvoice.asr.InterviewTranscript
import app.protocolvoice.asr.Participants
import app.protocolvoice.asr.TranscriptSegment
import app.protocolvoice.asr.TranscriptWord
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Сборка DOCX-протокола интервью без внешних библиотек (Apache POI весит >15 МБ
 * и тащит за собой XML-стек который ломает minSdk 26 и сильно раздувает APK).
 *
 * DOCX это просто ZIP с фиксированной структурой OOXML:
 *
 *   /[Content_Types].xml          — типы файлов внутри пакета
 *   /_rels/.rels                  — relationship: «document.xml — главный»
 *   /word/document.xml            — сам текст протокола
 *   /word/_rels/document.xml.rels — пустые relationships документа
 *   /word/styles.xml              — стили (Normal, Heading1, TableGrid…)
 *
 * Этого достаточно для Word/LibreOffice/WPS — все они переваривают файл без
 * жалоб. Шрифт в стилях задан Times New Roman 11/14 — стандартный документный
 * формат, привычный для официальных протоколов и аудиторских отчётов.
 *
 * Структура выходного протокола:
 *   1. Заголовок «ПРОТОКОЛ ИНТЕРВЬЮ»
 *   2. Шапка: тема, место, дата, аудитор
 *   3. Список участников
 *   4. Основная часть — таблица «Время | Спикер | Реплика»,
 *      слова с low-confidence окрашены красным, с medium — оранжевым.
 *   5. Заметки аудитора (если заполнены)
 *   6. Блок подписей
 */
class DocxBuilder(
    private val transcript: InterviewTranscript,
    private val metadata: InterviewMetadata,
    private val participants: Participants,
) {

    /** Записать DOCX в файл. Возвращает file. */
    fun writeTo(file: File): File {
        file.parentFile?.mkdirs()
        file.outputStream().use { writeTo(it) }
        return file
    }

    /** Записать DOCX в произвольный поток (для SAF / share intent). */
    fun writeTo(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putEntry("[Content_Types].xml", CONTENT_TYPES_XML)
            zip.putEntry("_rels/.rels", ROOT_RELS_XML)
            zip.putEntry("word/_rels/document.xml.rels", DOCUMENT_RELS_XML)
            zip.putEntry("word/styles.xml", stylesXml())
            zip.putEntry("word/document.xml", documentXml())
        }
    }

    // ===================================================================
    // document.xml — главное содержимое
    // ===================================================================

    private fun documentXml(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
        append("<w:body>")

        // ---- Заголовок ----
        appendParagraph(
            text = "ПРОТОКОЛ ИНТЕРВЬЮ",
            style = "TitleStyle",
            alignment = "center",
        )

        // ---- Тема (если задана) ----
        if (metadata.title.isNotBlank()) {
            appendParagraph(
                text = metadata.title,
                style = "SubtitleStyle",
                alignment = "center",
            )
        }

        appendEmptyParagraph()

        // ---- Шапка: метаданные ----
        appendMetadataTable()
        appendEmptyParagraph()

        // ---- Участники ----
        appendParagraph(text = "Участники интервью", style = "Heading1Style")
        appendParticipantsList()
        appendEmptyParagraph()

        // ---- Основная часть ----
        appendParagraph(text = "Содержание интервью", style = "Heading1Style")
        appendTranscriptTable()
        appendEmptyParagraph()

        // ---- Заметки ----
        if (metadata.notes.isNotBlank()) {
            appendParagraph(text = "Примечания аудитора", style = "Heading1Style")
            for (line in metadata.notes.split("\n")) {
                appendParagraph(text = line.ifBlank { " " })
            }
            appendEmptyParagraph()
        }

        // ---- Статистика распознавания ----
        appendStatsParagraph()
        appendEmptyParagraph()

        // ---- Подписи ----
        appendSignaturesBlock()

        // sectPr — настройки страницы (A4, поля 25/15/20/20 мм)
        append(
            """<w:sectPr>""" +
                """<w:pgSz w:w="11906" w:h="16838"/>""" +
                """<w:pgMar w:top="1134" w:right="850" w:bottom="1134" w:left="1418" """ +
                """w:header="708" w:footer="708" w:gutter="0"/>""" +
                """<w:cols w:space="708"/>""" +
                """<w:docGrid w:linePitch="360"/>""" +
            """</w:sectPr>""",
        )

        append("</w:body>")
        append("</w:document>")
    }

    // ---- Шапка: таблица «поле — значение» ----
    private fun StringBuilder.appendMetadataTable() {
        val rows = mutableListOf<Pair<String, String>>()
        rows.add("Дата проведения" to formatDate(metadata.date))
        if (metadata.location.isNotBlank()) rows.add("Место проведения" to metadata.location)
        if (metadata.auditorName.isNotBlank()) rows.add("Аудитор / интервьюер" to metadata.auditorName)
        rows.add("Длительность записи" to formatDuration(transcript.totalDurationMs))

        append("""<w:tbl>""")
        // table layout
        append(
            """<w:tblPr>""" +
                """<w:tblStyle w:val="TableGrid"/>""" +
                """<w:tblW w:w="5000" w:type="pct"/>""" +
                """<w:tblBorders>""" +
                    tableBorder("top", "single", 4) +
                    tableBorder("left", "single", 4) +
                    tableBorder("bottom", "single", 4) +
                    tableBorder("right", "single", 4) +
                    tableBorder("insideH", "single", 4) +
                    tableBorder("insideV", "single", 4) +
                """</w:tblBorders>""" +
            """</w:tblPr>""",
        )
        append("""<w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="6000"/></w:tblGrid>""")
        for ((label, value) in rows) {
            append("""<w:tr>""")
            append(tableCell(label, widthPct = 33, bold = true, shadeHex = "EEEEEE"))
            append(tableCell(value, widthPct = 67))
            append("""</w:tr>""")
        }
        append("""</w:tbl>""")
    }

    // ---- Список участников ----
    private fun StringBuilder.appendParticipantsList() {
        val ids = transcript.segments.map { it.speakerId }.distinct().sorted()
        if (ids.isEmpty()) {
            appendParagraph(text = "Участники не определены", italic = true)
            return
        }
        for ((idx, id) in ids.withIndex()) {
            val name = participants.displayName(id)
            val tag = "[SPK_$id]"
            appendParagraph(text = "${idx + 1}. $name  $tag")
        }
    }

    // ---- Главная таблица: тайм-код | спикер | реплика ----
    private fun StringBuilder.appendTranscriptTable() {
        append("""<w:tbl>""")
        append(
            """<w:tblPr>""" +
                """<w:tblStyle w:val="TableGrid"/>""" +
                """<w:tblW w:w="5000" w:type="pct"/>""" +
                """<w:tblBorders>""" +
                    tableBorder("top", "single", 4) +
                    tableBorder("left", "single", 4) +
                    tableBorder("bottom", "single", 4) +
                    tableBorder("right", "single", 4) +
                    tableBorder("insideH", "single", 4) +
                    tableBorder("insideV", "single", 4) +
                """</w:tblBorders>""" +
            """</w:tblPr>""",
        )
        append(
            """<w:tblGrid>""" +
                """<w:gridCol w:w="1300"/>""" +
                """<w:gridCol w:w="2200"/>""" +
                """<w:gridCol w:w="5500"/>""" +
            """</w:tblGrid>""",
        )

        // Заголовок таблицы
        append("""<w:tr>""")
        append(tableCell("Время", widthPct = 13, bold = true, shadeHex = "DDDDDD", alignment = "center"))
        append(tableCell("Спикер", widthPct = 22, bold = true, shadeHex = "DDDDDD", alignment = "center"))
        append(tableCell("Реплика", widthPct = 65, bold = true, shadeHex = "DDDDDD", alignment = "center"))
        append("""</w:tr>""")

        // Объединяем подряд идущие сегменты одного спикера в одну реплику —
        // так протокол читается естественно, без рваных строк по 5 секунд.
        val merged = mergeConsecutiveSegments(transcript.segments)

        for (seg in merged) {
            append("""<w:tr>""")
            append(tableCell(formatTime(seg.startMs), widthPct = 13, alignment = "center"))
            append(
                tableCell(
                    text = participants.displayName(seg.speakerId),
                    widthPct = 22,
                    bold = true,
                ),
            )
            append(transcriptCell(seg.words, widthPct = 65))
            append("""</w:tr>""")
        }
        append("""</w:tbl>""")
    }

    /**
     * Ячейка таблицы для реплики — слова с разной уверенностью покрашены
     * в разные цвета. low → красный, medium → оранжевый, high → чёрный.
     */
    private fun transcriptCell(words: List<TranscriptWord>, widthPct: Int): String = buildString {
        val pctTwentieths = (widthPct * 50)
        append("""<w:tc>""")
        append("""<w:tcPr><w:tcW w:w="$pctTwentieths" w:type="pct"/></w:tcPr>""")
        append("""<w:p>""")
        append("""<w:pPr><w:pStyle w:val="Normal"/><w:jc w:val="both"/></w:pPr>""")
        for ((idx, w) in words.withIndex()) {
            val color = when (w.confidenceLevel) {
                ConfidenceLevel.LOW    -> "C62828"
                ConfidenceLevel.MEDIUM -> "E65100"
                ConfidenceLevel.HIGH   -> "000000"
            }
            val text = if (idx == 0) w.text else " " + w.text
            append("""<w:r>""")
            append("""<w:rPr><w:color w:val="$color"/><w:sz w:val="22"/></w:rPr>""")
            append("""<w:t xml:space="preserve">""")
            append(escapeXml(text))
            append("""</w:t>""")
            append("""</w:r>""")
        }
        append("""</w:p>""")
        append("""</w:tc>""")
    }

    private fun StringBuilder.appendStatsParagraph() {
        val s = transcript.stats
        val text = buildString {
            append("Распознано сегментов: ${s.totalSegments}, ")
            append("слов: ${s.totalWords}")
            if (s.lowConfidenceWords > 0 || s.mediumConfidenceWords > 0) {
                append(" (требуют проверки: ")
                if (s.lowConfidenceWords > 0) append("красным — ${s.lowConfidenceWords}")
                if (s.lowConfidenceWords > 0 && s.mediumConfidenceWords > 0) append(", ")
                if (s.mediumConfidenceWords > 0) append("оранжевым — ${s.mediumConfidenceWords}")
                append(")")
            }
            append(".")
        }
        appendParagraph(text = text, italic = true, sizeHalfPt = 20)
    }

    private fun StringBuilder.appendSignaturesBlock() {
        appendParagraph(text = "Подписи участников:", style = "Heading1Style")
        appendEmptyParagraph()
        val ids = transcript.segments.map { it.speakerId }.distinct().sorted()
        for (id in ids) {
            val name = participants.displayName(id)
            // строка вида: «Иванов И.И. ____________________ /__________/»
            appendParagraph(text = "$name    ____________________    /__________________/")
            appendEmptyParagraph()
        }
        if (metadata.auditorName.isNotBlank()) {
            appendParagraph(
                text = "Аудитор / интервьюер: ${metadata.auditorName}    " +
                        "____________________    /__________________/",
            )
            appendEmptyParagraph()
        }
        appendParagraph(text = "Дата подписания: «____» ________________ 20____ г.")
    }

    // ===================================================================
    // Низкоуровневые билдеры абзацев и ячеек
    // ===================================================================

    private fun StringBuilder.appendParagraph(
        text: String,
        style: String = "Normal",
        alignment: String? = null,
        bold: Boolean = false,
        italic: Boolean = false,
        sizeHalfPt: Int? = null,
    ) {
        append("""<w:p>""")
        append("""<w:pPr><w:pStyle w:val="$style"/>""")
        if (alignment != null) append("""<w:jc w:val="$alignment"/>""")
        append("""</w:pPr>""")
        append("""<w:r>""")
        append("""<w:rPr>""")
        if (bold) append("""<w:b/>""")
        if (italic) append("""<w:i/>""")
        if (sizeHalfPt != null) append("""<w:sz w:val="$sizeHalfPt"/>""")
        append("""</w:rPr>""")
        append("""<w:t xml:space="preserve">""")
        append(escapeXml(text))
        append("""</w:t>""")
        append("""</w:r>""")
        append("""</w:p>""")
    }

    private fun StringBuilder.appendEmptyParagraph() {
        append("""<w:p><w:pPr><w:pStyle w:val="Normal"/></w:pPr></w:p>""")
    }

    /**
     * Ячейка таблицы. widthPct — процент от ширины таблицы (целое 1..100).
     * shadeHex — заливка ячейки в hex без #, или null если без заливки.
     */
    private fun tableCell(
        text: String,
        widthPct: Int,
        bold: Boolean = false,
        italic: Boolean = false,
        shadeHex: String? = null,
        alignment: String = "left",
    ): String = buildString {
        // Проценты в DOCX задаются в "пятидесятых долях процента" (5000 = 100%)
        val pctTwentieths = (widthPct * 50)
        append("""<w:tc>""")
        append("""<w:tcPr>""")
        append("""<w:tcW w:w="$pctTwentieths" w:type="pct"/>""")
        if (shadeHex != null) {
            append("""<w:shd w:val="clear" w:color="auto" w:fill="$shadeHex"/>""")
        }
        append("""</w:tcPr>""")
        append("""<w:p>""")
        append("""<w:pPr><w:pStyle w:val="Normal"/>""")
        append("""<w:jc w:val="$alignment"/>""")
        append("""</w:pPr>""")
        append("""<w:r>""")
        append("""<w:rPr>""")
        if (bold) append("""<w:b/>""")
        if (italic) append("""<w:i/>""")
        append("""<w:sz w:val="22"/>""")
        append("""</w:rPr>""")
        append("""<w:t xml:space="preserve">""")
        append(escapeXml(text))
        append("""</w:t>""")
        append("""</w:r>""")
        append("""</w:p>""")
        append("""</w:tc>""")
    }

    private fun tableBorder(side: String, type: String, sizeEighthPt: Int): String =
        """<w:$side w:val="$type" w:sz="$sizeEighthPt" w:space="0" w:color="666666"/>"""

    // ===================================================================
    // styles.xml — Times New Roman, заголовки, таблицы
    // ===================================================================

    private fun stylesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault>
      <w:rPr>
        <w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:cs="Times New Roman"/>
        <w:sz w:val="22"/>
        <w:szCs w:val="22"/>
        <w:lang w:val="ru-RU" w:eastAsia="ru-RU" w:bidi="ar-SA"/>
      </w:rPr>
    </w:rPrDefault>
    <w:pPrDefault>
      <w:pPr>
        <w:spacing w:after="120" w:line="276" w:lineRule="auto"/>
      </w:pPr>
    </w:pPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:styleId="Normal" w:default="1">
    <w:name w:val="Normal"/>
    <w:rPr><w:sz w:val="22"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="TitleStyle">
    <w:name w:val="Title"/>
    <w:basedOn w:val="Normal"/>
    <w:pPr>
      <w:spacing w:before="240" w:after="240"/>
      <w:jc w:val="center"/>
    </w:pPr>
    <w:rPr><w:b/><w:sz w:val="32"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="SubtitleStyle">
    <w:name w:val="Subtitle"/>
    <w:basedOn w:val="Normal"/>
    <w:pPr>
      <w:spacing w:before="120" w:after="240"/>
      <w:jc w:val="center"/>
    </w:pPr>
    <w:rPr><w:i/><w:sz w:val="26"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading1Style">
    <w:name w:val="heading 1"/>
    <w:basedOn w:val="Normal"/>
    <w:pPr>
      <w:spacing w:before="240" w:after="120"/>
    </w:pPr>
    <w:rPr><w:b/><w:sz w:val="26"/></w:rPr>
  </w:style>
  <w:style w:type="table" w:styleId="TableGrid">
    <w:name w:val="Table Grid"/>
    <w:basedOn w:val="TableNormal"/>
    <w:tblPr>
      <w:tblBorders>
        <w:top w:val="single" w:sz="4" w:space="0" w:color="666666"/>
        <w:left w:val="single" w:sz="4" w:space="0" w:color="666666"/>
        <w:bottom w:val="single" w:sz="4" w:space="0" w:color="666666"/>
        <w:right w:val="single" w:sz="4" w:space="0" w:color="666666"/>
        <w:insideH w:val="single" w:sz="4" w:space="0" w:color="666666"/>
        <w:insideV w:val="single" w:sz="4" w:space="0" w:color="666666"/>
      </w:tblBorders>
    </w:tblPr>
  </w:style>
  <w:style w:type="table" w:default="1" w:styleId="TableNormal">
    <w:name w:val="Normal Table"/>
    <w:tblPr><w:tblInd w:w="0" w:type="dxa"/></w:tblPr>
  </w:style>
</w:styles>"""

    // ===================================================================
    // Утилиты
    // ===================================================================

    private fun ZipOutputStream.putEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun escapeXml(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&'  -> sb.append("&amp;")
                '<'  -> sb.append("&lt;")
                '>'  -> sb.append("&gt;")
                '"'  -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> String.format(Locale.ROOT, "%d ч %02d мин %02d сек", h, m, s)
            m > 0 -> String.format(Locale.ROOT, "%d мин %02d сек", m, s)
            else  -> String.format(Locale.ROOT, "%d сек", s)
        }
    }

    private fun formatDate(epochMs: Long): String {
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru-RU"))
        return fmt.format(Date(epochMs))
    }

    /**
     * Объединяет подряд идущие сегменты одного и того же спикера в один,
     * чтобы протокол читался реплика-за-репликой, а не по 3-секундным кускам.
     * Граница: если между сегментами одного спикера < 2 секунд — клеим.
     */
    private fun mergeConsecutiveSegments(
        segs: List<TranscriptSegment>,
    ): List<TranscriptSegment> {
        if (segs.size <= 1) return segs
        val out = mutableListOf<TranscriptSegment>()
        var current = segs[0]
        for (i in 1 until segs.size) {
            val next = segs[i]
            val gap = next.startMs - current.endMs
            if (next.speakerId == current.speakerId && gap in 0..2000) {
                current = current.copy(
                    endMs = next.endMs,
                    words = current.words + next.words,
                )
            } else {
                out.add(current)
                current = next
            }
        }
        out.add(current)
        return out
    }

    // ===================================================================
    // Статические артефакты OOXML — одинаковые для любого DOCX
    // ===================================================================

    companion object {
        private const val CONTENT_TYPES_XML =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

        private const val ROOT_RELS_XML =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

        private const val DOCUMENT_RELS_XML =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
    }
}
