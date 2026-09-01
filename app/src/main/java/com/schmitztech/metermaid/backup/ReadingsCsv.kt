package com.schmitztech.metermaid.backup

import com.schmitztech.metermaid.data.Reading
import com.schmitztech.metermaid.data.intervals
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The whole log as one spreadsheet-ready CSV.
 *
 * The Apps Script version left `change`, `days` and `gal/day` to ARRAYFORMULAs living in
 * the sheet. A file dropped in Drive is not a live sheet and has nowhere to put them, so
 * they are computed here instead — the columns are the same ones, just filled in before
 * the file is written rather than after.
 */
/** One row from an imported file, before it is given an id and stored. */
data class ParsedReading(
    val readingDate: String,
    val capturedAtUtc: Long,
    val value: Long,
    val meterId: String,
    val corrected: Boolean,
    val note: String,
    val photoName: String,
)

object ReadingsCsv {

    const val MIME = "text/csv"
    const val FILE_NAME = "well-readings.csv"

    // No id column: it was the Apps Script idempotency key, and rewriting the whole
    // file leaves nothing to de-duplicate against. It stays the Room primary key.
    private val HEADERS = listOf(
        "meter", "timestamp", "date", "reading", "source", "note",
        "change", "days", "gal/day", "photo",
    )

    // Locale.ROOT throughout: the file is data shared between devices, not prose for
    // this one. Without it a comma-decimal phone writes "104,3" and an Eastern-Arabic
    // digit phone writes timestamps no other phone can parse back.
    private val TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    // The old Google Sheet's date cell was a real Date, and Sheets' "Download as CSV"
    // renders it in display format — "10/24/2021 12:00:00", not ISO. Those downloads
    // are the only route for pre-app history, so both shapes have to parse.
    private val LEGACY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d/yyyy", Locale.ROOT)

    /**
     * [zone] is injectable so the column can be asserted on: it renders the capture
     * instant in local time, which otherwise makes the output depend on where it runs.
     */
    fun toCsv(readings: List<Reading>, zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder()
        sb.append(HEADERS.joinToString(",")).append("\r\n")

        // Chronological with a per-meter baseline, same walk as every other surface.
        for (interval in readings.intervals()) {
            val r = interval.reading
            val perDay = interval.perDay?.let { String.format(Locale.ROOT, "%.1f", it) } ?: ""

            sb.append(
                listOf(
                    r.meterId,
                    // When the shutter actually fired. readingDate is editorial and can
                    // be nudged; this one is forensic and never edited, and it is what
                    // separates two readings taken on the same day.
                    TIMESTAMP.format(Instant.ofEpochMilli(r.capturedAtUtc).atZone(zone)),
                    r.readingDate,
                    r.value.toString(),
                    if (r.corrected) "corrected" else "ocr",
                    r.note,
                    interval.delta?.toString() ?: "new meter",
                    interval.days?.toString() ?: "",
                    perDay,
                    // Filename only. The absolute path is app-private and meaningless
                    // off the device; the name is what ties a row to its JPEG.
                    r.photoPath.substringAfterLast('/'),
                ).joinToString(",", transform = ::escape),
            ).append("\r\n")
        }
        return sb.toString()
    }

    /**
     * Reads a file this object wrote, so a new phone can be seeded from the spreadsheet.
     *
     * Columns are matched by name rather than position: the derived columns are the
     * app's own output and a hand-sorted or hand-edited sheet should still import. Rows
     * are rebuilt with fresh ids — the id column was dropped when the export stopped
     * needing one — so identity on the way back in comes from the meter and the capture
     * timestamp instead.
     *
     * @throws IllegalArgumentException if the file is not recognisably a readings export.
     */
    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): List<ParsedReading> {
        // Some editors and cloud providers prepend a BOM, which is not whitespace and
        // would otherwise become part of the first column's name.
        val rows = parseRows(text.removePrefix("\uFEFF"))
        require(rows.isNotEmpty()) { "That file is empty." }

        val header = rows.first().map { it.trim().lowercase() }
        require(header.contains("reading") && header.contains("date")) {
            "That file has no reading/date columns — it does not look like a readings export."
        }
        fun cell(row: List<String>, name: String): String {
            val index = header.indexOf(name)
            return if (index in row.indices) row[index].trim() else ""
        }

        val parsed = rows.drop(1)
            .filter { row -> row.any { it.isNotBlank() } }
            .mapNotNull { row ->
                val value = cell(row, "reading").toLongOrNull() ?: return@mapNotNull null
                val date = dateOf(cell(row, "date")) ?: return@mapNotNull null
                ParsedReading(
                    readingDate = date.toString(),
                    // Noon, when the row predates the timestamp column or carries a
                    // date the app never wrote — it sidesteps the DST edges.
                    capturedAtUtc = instantOf(cell(row, "timestamp"), date, zone),
                    value = value,
                    meterId = cell(row, "meter"),
                    corrected = cell(row, "source").equals("corrected", ignoreCase = true),
                    note = cell(row, "note"),
                    photoName = cell(row, "photo"),
                )
            }
        require(parsed.isNotEmpty()) { "That file contains no readings." }
        return parsed
    }

    /** ISO first (the app's own output), then the legacy Sheets shape, time part and all. */
    private fun dateOf(raw: String): LocalDate? {
        val head = raw.trim().substringBefore(' ')
        return runCatching { LocalDate.parse(head) }.getOrNull()
            ?: runCatching { LocalDate.parse(head, LEGACY_DATE) }.getOrNull()
    }

    private fun instantOf(raw: String, date: LocalDate, zone: ZoneId): Long {
        val parsed = raw.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDateTime.parse(it, TIMESTAMP) }.getOrNull()
        }
        return (parsed ?: date.atTime(12, 0)).atZone(zone).toInstant().toEpochMilli()
    }

    /** RFC-4180-ish: quoted fields, doubled quotes, newlines inside quotes. */
    private fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> field.append(c)
                }
                // A quote only opens a quoted run at the start of a field. Anywhere else
                // it is literal, so a stray one in a hand-typed note cannot swallow the
                // rest of the file.
                c == '"' && field.isEmpty() -> quoted = true
                c == ',' -> { row.add(field.toString()); field.setLength(0) }
                c == '\r' -> Unit
                c == '\n' -> {
                    row.add(field.toString()); field.setLength(0)
                    rows.add(row.toList()); row.clear()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row.toList())
        }
        return rows
    }

    private fun escape(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
