package com.schmmd.drumroll.backup

import android.net.Uri
import com.schmmd.drumroll.data.Reading
import com.schmmd.drumroll.data.Repository
import com.schmmd.drumroll.data.SettingsStore
import com.schmmd.drumroll.data.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Writes the log to the file the user picked, replacing the Apps Script endpoint.
 *
 * The whole file is rewritten every time rather than a row appended, which is what makes
 * the shared secret unnecessary: there is no server to authenticate to, and a retry is
 * just the same bytes again instead of something that has to be de-duplicated by id.
 */
class ExportCoordinator(
    private val scope: CoroutineScope,
    private val repository: Repository,
    private val settings: SettingsStore,
    private val saf: SafFile,
) {
    private val labelFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d 'at' h:mm a").withZone(ZoneId.systemDefault())

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            repository.readings
                .debounce(3_000)
                .collect { readings ->
                    val current = settings.settings.first()
                    if (!current.autoExport || current.exportUri.isBlank()) return@collect
                    // An empty log overwriting a file full of readings is far more likely
                    // to be a bad restore or a wiped database than a deliberate erase.
                    if (readings.isEmpty()) return@collect
                    write(Uri.parse(current.exportUri), readings, auto = true)
                }
        }
    }

    /**
      * Reads a spreadsheet into the log. Used to seed a new phone from the old one's file.
      *
      * Nothing is deleted: rows already held are skipped rather than replaced, so this is
      * safe to run twice and safe to run against a file that only partly overlaps.
      */
    suspend fun importFrom(uri: Uri): String {
        val text = saf.readText(uri)
        // Parsed before anything is written, so an unrecognised file fails with the log
        // untouched instead of half-importing.
        val parsed = withContext(Dispatchers.Default) { ReadingsCsv.parse(text) }
        val added = repository.importReadings(parsed)
        val skipped = parsed.size - added
        val tail = if (skipped > 0) " ($skipped already held)" else ""
        return "Imported $added of ${parsed.size} readings$tail · ${now()}"
            .also { settings.setLastExport(it, hash = null) }
    }

    /** One-off write to [uri], used by "Save now" and when a target is first chosen. */
    suspend fun exportNow(uri: Uri): String {
        val readings = repository.allOnce()
        return write(uri, readings, auto = false)
    }

    /** Adopts [uri] as the recurring target, persists access, and writes immediately. */
    suspend fun setTarget(uri: Uri): String {
        val persisted = saf.persist(uri)
        settings.setExportTarget(uri.toString(), saf.displayName(uri))
        settings.setAutoExport(true)
        val status = exportNow(uri)
        // Without a persisted grant the file works now but not after a restart. Better to
        // say so than to leave the switch looking healthy until writes silently stop.
        if (persisted) return status
        return "$status · may need picking again after a restart"
            .also { settings.setLastExport(it, hash = null) }
    }

    private suspend fun write(uri: Uri, readings: List<Reading>, auto: Boolean): String {
        // Building the CSV is CPU work and the one-off path is called from the main
        // thread; only the file write itself was already off it.
        val csv = withContext(Dispatchers.Default) { ReadingsCsv.toCsv(readings) }

        // Hashed on the CSV, not on the readings: stamping rows SYNCED below changes the
        // rows and re-fires this flow, but it does not change the file, so hashing the
        // rows would spend a second pointless write settling every save. A manual "Save
        // now" still writes regardless — the user asked for it.
        val hash = csv.hashCode()
        if (auto && hash == settings.settings.first().lastExportHash) return ""

        return runCatching { saf.writeText(uri, csv) }
            .fold(
                onSuccess = {
                    repository.markAll(SyncState.SYNCED)
                    val verb = if (auto) "Saved" else "Exported"
                    "$verb ${readings.size} readings · ${now()}"
                        .also { settings.setLastExport(it, hash) }
                },
                onFailure = { t ->
                    // Otherwise the screen shows the last success forever while every
                    // write fails — the file moved in Drive, or the grant expired.
                    repository.markAll(SyncState.FAILED)
                    "Export failed · ${now()}: ${t.message ?: t.javaClass.simpleName}"
                        .also { settings.setLastExport(it, hash = null) }
                },
            )
    }

    private fun now(): String = labelFormatter.format(Instant.now())
}
