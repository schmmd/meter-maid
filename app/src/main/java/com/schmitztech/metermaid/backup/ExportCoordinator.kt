package com.schmitztech.metermaid.backup

import android.net.Uri
import com.schmitztech.metermaid.data.Reading
import com.schmitztech.metermaid.data.Repository
import com.schmitztech.metermaid.data.SettingsStore
import com.schmitztech.metermaid.data.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // One truncate-and-stream at a time: the debounced collector and a manual "Save now"
    // both write the same document, and two interleaved truncations corrupt it.
    private val writeMutex = Mutex()
    private var retryJob: Job? = null
    private var retryDelayMs = FIRST_RETRY_MS

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
        val current = settings.settings.first()
        // Parsed before anything is written, so an unrecognised file fails with the log
        // untouched instead of half-importing.
        // A file with no meter column is presumed to be this meter's — leaving those
        // rows blank would strand them outside the configured series.
        val parsed = withContext(Dispatchers.Default) { ReadingsCsv.parse(text) }
            .map { if (it.meterId.isBlank()) it.copy(meterId = current.meter.meterId) else it }
        val added = repository.importReadings(parsed)

        // The reverse gap: a blank config against a file that names its meter would
        // split every screen into two series and disarm the backwards/gal-day checks,
        // because new captures would never find the imported baseline.
        val meters = parsed.map { it.meterId }.filter { it.isNotBlank() }.distinct()
        if (current.meter.meterId.isBlank() && meters.size == 1) {
            settings.setMeter(current.meter.copy(meterId = meters.single()))
        }

        // "Use existing" links a file without arming auto-save, so the log cannot
        // overwrite readings the user has not loaded yet. Once this import has pulled
        // the linked file in, writing it back can no longer erase anything.
        if (!current.autoExport && uri.toString() == current.exportUri) {
            settings.setAutoExport(true)
        }

        val skipped = parsed.size - added
        val tail = if (skipped > 0) " ($skipped already held)" else ""
        return "Imported $added of ${parsed.size} readings$tail · ${now()}"
            .also { settings.setLastImport(it) }
    }

    /** One-off write to [uri], used by "Save now" and when a target is first chosen. */
    suspend fun exportNow(uri: Uri): String {
        val readings = repository.allOnce()
        return write(uri, readings, auto = false)
    }

    /**
      * Adopts [uri] as the recurring target.
      *
      * [writeNow] must be false for a file that already exists. Writing on adopt made
      * sense for a file this app had just created and is empty by definition; pointed at
      * an existing spreadsheet on a fresh install it published an empty log straight over
      * the readings the user was trying to reach — losing exactly what they came for.
      */
    suspend fun setTarget(uri: Uri, writeNow: Boolean): String {
        val persisted = saf.persist(uri)
        settings.setExportTarget(uri.toString(), saf.displayName(uri))
        val status = if (writeNow) {
            settings.setAutoExport(true)
            exportNow(uri)
        } else {
            // Auto-save stays OFF until the file has been imported. Arming it here let
            // the first captured reading truncate a linked historical file down to the
            // local log before the user ever imported it — the Settings copy promises
            // the file is untouched until they import or save, and now it is.
            "Linked to ${saf.displayName(uri) ?: "the file"}. " +
                "Import to load what it holds — saving starts after that."
                .also { settings.setLastExport(it, hash = null) }
        }
        // Without a persisted grant the file works now but not after a restart. Better to
        // say so than to leave the switch looking healthy until writes silently stop.
        if (persisted) return status
        return "$status · may need picking again after a restart"
            .also { settings.setLastExport(it, hash = null) }
    }

    private suspend fun write(uri: Uri, readings: List<Reading>, auto: Boolean): String = writeMutex.withLock {
        // Building the CSV is CPU work and the one-off path is called from the main
        // thread; only the file write itself was already off it.
        // An empty log is never worth publishing, and publishing it destroys whatever
        // the file held. There is no case where writing a header over someone's readings
        // is what they meant.
        if (readings.isEmpty()) {
            return@withLock "Nothing to save yet — no readings recorded."
                .also { settings.setLastExport(it, hash = null) }
        }

        val csv = withContext(Dispatchers.Default) { ReadingsCsv.toCsv(readings) }

        // Hashed on the CSV, not on the readings: stamping rows SYNCED below changes the
        // rows and re-fires this flow, but it does not change the file, so hashing the
        // rows would spend a second pointless write settling every save. A manual "Save
        // now" still writes regardless — the user asked for it.
        val hash = csv.hashCode()
        if (auto && hash == settings.settings.first().lastExportHash) {
            // The file already holds these bytes, so the rows are as saved as a write
            // would leave them. An edit that changes nothing re-stamps its row QUEUED,
            // and without this it would show unsynced forever. Settling an already
            // settled log updates no rows, so the flow does not re-fire.
            repository.markAll(SyncState.SYNCED)
            return@withLock ""
        }

        // A copy the app owns before touching the one it doesn't: SAF gives no rename
        // step for a single-document grant, so the remote write truncates in place and
        // can tear. The mirror keeps the last full log reachable if it does.
        saf.mirror(csv)

        return@withLock runCatching { saf.writeText(uri, csv) }
            .fold(
                onSuccess = {
                    retryDelayMs = FIRST_RETRY_MS
                    repository.markAll(SyncState.SYNCED)
                    val verb = if (auto) "Saved" else "Exported"
                    "$verb ${readings.size} readings · ${now()}"
                        .also { settings.setLastExport(it, hash) }
                },
                onFailure = { t ->
                    // Otherwise the screen shows the last success forever while every
                    // write fails — the file moved in Drive, or the grant expired.
                    repository.markAll(SyncState.FAILED)
                    if (auto) scheduleRetry()
                    "Export failed · ${now()}: ${t.message ?: t.javaClass.simpleName}"
                        .also { settings.setLastExport(it, hash = null) }
                },
            )
    }

    /**
      * A failed write left to Room's invalidation retries exactly once: the second
      * markAll(FAILED) updates no rows, fires no trigger, and the flow goes silent until
      * the next edit. This keeps trying on its own clock instead, backing off so an
      * expired grant does not spin. A retry that finds the log already saved falls into
      * the hash-skip above and does nothing.
      */
    private suspend fun scheduleRetry() {
        // A failing retry lands back here; cancelling the job we are running inside
        // would kill the status update still to come in the caller.
        val self = currentCoroutineContext()[Job]
        retryJob?.takeIf { it !== self }?.cancel()
        val backoff = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        retryJob = scope.launch {
            delay(backoff)
            val current = settings.settings.first()
            if (!current.autoExport || current.exportUri.isBlank()) return@launch
            val readings = repository.allOnce()
            if (readings.isEmpty()) return@launch
            write(Uri.parse(current.exportUri), readings, auto = true)
        }
    }

    private fun now(): String = labelFormatter.format(Instant.now())

    private companion object {
        const val FIRST_RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 30L * 60_000L
    }
}
