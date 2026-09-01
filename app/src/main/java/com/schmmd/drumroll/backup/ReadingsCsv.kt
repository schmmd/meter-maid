package com.schmmd.drumroll.backup

import com.schmmd.drumroll.data.Reading
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The whole log as one spreadsheet-ready CSV.
 *
 * The Apps Script version left `change`, `days` and `gal/day` to ARRAYFORMULAs living in
 * the sheet. A file dropped in Drive is not a live sheet and has nowhere to put them, so
 * they are computed here instead — the columns are the same ones, just filled in before
 * the file is written rather than after.
 */
object ReadingsCsv {

    const val MIME = "text/csv"
    const val FILE_NAME = "well-readings.csv"

    // No id column: it was the Apps Script idempotency key, and rewriting the whole
    // file leaves nothing to de-duplicate against. It stays the Room primary key.
    private val HEADERS = listOf(
        "meter", "timestamp", "date", "reading", "source", "note",
        "change", "days", "gal/day", "photo",
    )

    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * [zone] is injectable so the column can be asserted on: it renders the capture
     * instant in local time, which otherwise makes the output depend on where it runs.
     */
    fun toCsv(readings: List<Reading>, zone: ZoneId = ZoneId.systemDefault()): String {
        // Chronological, because every derived column is a comparison with the reading
        // before it. Ties on the same day fall back to when the photo was taken.
        val ordered = readings.sortedWith(compareBy({ it.readingDate }, { it.capturedAtUtc }))
        val previousByMeter = HashMap<String, Reading>()

        val sb = StringBuilder()
        sb.append(HEADERS.joinToString(",")).append("\r\n")

        for (r in ordered) {
            // Per meter, so swapping the meter out starts a fresh series instead of
            // rendering as one enormous negative jump.
            val prev = previousByMeter[r.meterId]
            val days = prev?.let {
                ChronoUnit.DAYS.between(LocalDate.parse(it.readingDate), LocalDate.parse(r.readingDate))
            }
            val change = prev?.let { r.value - it.value }
            val perDay = if (change != null && days != null && days > 0L) {
                String.format("%.1f", change.toDouble() / days)
            } else {
                ""
            }

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
                    change?.toString() ?: "new meter",
                    days?.toString() ?: "",
                    perDay,
                    // Filename only. The absolute path is app-private and meaningless
                    // off the device; the name is what ties a row to its JPEG.
                    r.photoPath.substringAfterLast('/'),
                ).joinToString(",", transform = ::escape),
            ).append("\r\n")

            previousByMeter[r.meterId] = r
        }
        return sb.toString()
    }

    private fun escape(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
