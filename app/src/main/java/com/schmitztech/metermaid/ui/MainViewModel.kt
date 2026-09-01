package com.schmitztech.metermaid.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.schmitztech.metermaid.MeterMaidApp
import com.schmitztech.metermaid.data.MeterConfig
import com.schmitztech.metermaid.data.Reading
import com.schmitztech.metermaid.data.Roi
import com.schmitztech.metermaid.data.Settings
import com.schmitztech.metermaid.backup.ExportStatus
import com.schmitztech.metermaid.ocr.PpOcrRecognizer
import com.schmitztech.metermaid.ocr.PREFILL_THRESHOLD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app as MeterMaidApp
    private val repository = application.repository
    private val settingsStore = application.settings
    private val recognizer = PpOcrRecognizer(app)

    val readings = repository.readings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<Settings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    /** Whether the chosen file currently reflects the log — one state for the whole log. */
    val exportStatus: StateFlow<ExportStatus> = application.export.status

    private val _confirm = MutableStateFlow<ConfirmState?>(null)
    val confirm: StateFlow<ConfirmState?> = _confirm.asStateFlow()

    private var pendingPhoto: File? = null
    private var busy = false

    /**
      * Flips the crop end-for-end. A register read from the far side of the pit comes out
      * upside down; it never comes out on its side, so 90 and 270 were only ever ways to
      * get an unreadable crop.
      */
    fun flipGuide() = viewModelScope.launch {
        val roi = settings.value.roi
        settingsStore.setRoi(roi.copy(rotationDegrees = if (roi.rotationDegrees == 180) 0 else 180))
    }

    /**
     * Called on shutter. Runs the burst through the recognizer, then opens the confirm
     * screen with whatever earned a prefill — and blanks whatever didn't.
     */
    fun onCaptured(frames: List<Bitmap>) {
        if (busy) return
        busy = true
        val current = settings.value
        val config = current.meter

        // snapshot() already handed us copies the analyzer cannot recycle underneath us,
        // so the photo only needs one of its own because persist() consumes what it saves.
        val keepsake = frames.lastOrNull()
            ?.takeIf { !it.isRecycled }
            ?.let { runCatching { it.copy(it.config ?: Bitmap.Config.ARGB_8888, false) }.getOrNull() }

        viewModelScope.launch {
            val today = LocalDate.now()
            pendingPhoto = keepsake?.let { persist(it) }

            android.util.Log.i(
                "MeterMaidOcr",
                "shutter: frames=${frames.size} ocrEnabled=${current.ocrEnabled} " +
                    "sizes=${frames.map { "${it.width}x${it.height}" }}",
            )
            val recognized = if (current.ocrEnabled && frames.isNotEmpty()) {
                runCatching { recognizer.read(frames, config.drumCount, config.trailingZeros) }
                    .onFailure { android.util.Log.w("MeterMaidOcr", "recognition threw", it) }
                    .getOrNull()
            } else null

            // The burst is ours to free: snapshot() copied it for exactly this reason.
            frames.forEach { if (!it.isRecycled) it.recycle() }

            // Only positions the burst actually agreed on are prefilled. A blank box is
            // an honest "I don't know"; a guessed digit is not.
            val prefill = buildString {
                for (i in 0 until config.drumCount) {
                    val agree = recognized?.agreement?.getOrNull(i) ?: 0f
                    val digit = recognized?.digits?.getOrNull(i)
                    append(if (digit != null && agree >= PREFILL_THRESHOLD) digit else ' ')
                }
            }

            val previous = repository.previousFor(config.meterId, today)

            _confirm.value = ConfirmState(
                drums = prefill,
                agreement = recognized?.agreement ?: List(config.drumCount) { 0f },
                readingDate = today,
                capturedAt = today,
                previous = previous,
                ocrSummary = summarise(recognized, config),
                photoPath = pendingPhoto?.absolutePath.orEmpty(),
            )
            busy = false
        }
    }

    private fun summarise(
        r: com.schmitztech.metermaid.ocr.RecognizedReading?,
        config: MeterConfig,
    ): String = when {
        r == null -> "Couldn't read the register — type all ${config.drumCount} drums."
        r.agreement.count { it >= PREFILL_THRESHOLD } == config.drumCount ->
            "All ${config.drumCount} drums agreed across ${r.framesUsable} of ${r.framesAttempted} frames."
        else -> {
            val unsure = r.agreement.count { it < PREFILL_THRESHOLD }
            "Read ${config.drumCount - unsure} of ${config.drumCount} drums from " +
                "${r.framesUsable} of ${r.framesAttempted} frames. The rest are yours."
        }
    }

    /**
      * Opens the confirm screen with nothing filled in and no photo behind it.
      *
      * A reading taken while the phone was in the house, or one found written on the
      * back of an envelope, still belongs in the log — and since the app owns the data
      * and rewrites the whole spreadsheet, typing it into the sheet by hand would be
      * overwritten on the next save.
      */
    fun startManualEntry() = viewModelScope.launch {
        if (busy) return@launch
        val config = settings.value.meter
        val today = LocalDate.now()
        pendingPhoto = null
        _confirm.value = ConfirmState(
            drums = " ".repeat(config.drumCount),
            agreement = List(config.drumCount) { 0f },
            readingDate = today,
            capturedAt = null,
            previous = repository.previousFor(config.meterId, today),
            ocrSummary = "Entered by hand.",
            photoPath = "",
        )
    }

    /**
      * Opens a saved reading for correction.
      *
      * A digit typed wrong is not worth deleting and re-entering, and deleting is the
      * only thing history offered. The same confirm screen is reused so the same
      * validation applies — the row keeps its identity, its capture time and its photo,
      * and only the register, the date and the note can move.
      */
    fun startEdit(id: String) = viewModelScope.launch {
        if (busy) return@launch
        val reading = repository.byId(id) ?: return@launch
        val config = settings.value.meter
        val date = LocalDate.parse(reading.readingDate)
        _confirm.value = ConfirmState(
            drums = config.toDrums(reading.value),
            // Every digit is known: they came from a saved reading, not a guess.
            agreement = List(config.drumCount) { 1f },
            readingDate = date,
            // A photo-backed reading keeps its shutter date, so dragging the date far
            // from the photo still warns on edit. A hand-entered row has no photo and
            // nothing to drift from.
            capturedAt = reading.photoPath.takeIf { it.isNotBlank() }?.let {
                Instant.ofEpochMilli(reading.capturedAtUtc).atZone(ZoneId.systemDefault()).toLocalDate()
            },
            note = reading.note,
            previous = repository.previousFor(config.meterId, date, excluding = id),
            ocrSummary = null,
            photoPath = reading.photoPath,
            editingId = id,
        )
    }

    fun updateDrums(drums: String) {
        _confirm.value = _confirm.value?.copy(drums = drums)
    }

    fun updateNote(note: String) {
        _confirm.value = _confirm.value?.copy(note = note)
    }

    fun updateDate(date: LocalDate) = viewModelScope.launch {
        val state = _confirm.value ?: return@launch
        val config = settings.value.meter
        _confirm.value = state.copy(
            readingDate = date,
            previous = repository.previousFor(config.meterId, date, excluding = state.editingId),
        )
    }

    fun cancel() {
        pendingPhoto?.delete()
        pendingPhoto = null
        _confirm.value = null
    }

    fun save() = viewModelScope.launch {
        val state = _confirm.value ?: return@launch
        val config = settings.value.meter
        val drums = state.drums.filter(Char::isDigit)
        if (drums.length != config.drumCount) return@launch

        val editing = state.editingId?.let { repository.byId(it) }
        if (editing != null) {
            val value = config.toValue(drums)
            repository.save(
                editing.copy(
                    readingDate = state.readingDate.toString(),
                    value = value,
                    note = state.note,
                    // A hand-corrected register is a corrected one, whatever produced
                    // the original. An untouched value keeps whatever it already said.
                    corrected = if (value != editing.value) true else editing.corrected,
                )
            )
            _confirm.value = null
            return@launch
        }

        // "Corrected" means the user changed or supplied a digit the OCR did not.
        val corrected = state.agreement.any { it < PREFILL_THRESHOLD }

        repository.save(
            Reading(
                id = UUID.randomUUID().toString(),
                readingDate = state.readingDate.toString(),
                capturedAtUtc = Instant.now().toEpochMilli(),
                value = config.toValue(drums),
                meterId = config.meterId,
                ocrRaw = state.ocrSummary,
                minAgreement = state.agreement.minOrNull(),
                corrected = corrected,
                photoPath = pendingPhoto?.absolutePath.orEmpty(),
                note = state.note,
            )
        )
        pendingPhoto = null
        _confirm.value = null
    }

    fun delete(id: String) = viewModelScope.launch { repository.delete(id) }

    /** Writes the log to the chosen file right now. No-op until a file has been picked. */
    fun exportNow() = viewModelScope.launch {
        val target = settings.value.exportUri.takeIf { it.isNotBlank() } ?: return@launch
        application.export.exportNow(Uri.parse(target))
    }

    /** Seeds the log from a spreadsheet. Existing readings are left alone. */
    fun importFrom(uri: Uri) = viewModelScope.launch {
        runCatching { application.export.importFrom(uri) }
            .onFailure { settingsStore.setLastImport("Import failed: ${it.message}") }
    }

    /** The user picked a file in the system picker; adopt it and write to it immediately. */
    fun chooseExportTarget(uri: Uri, writeNow: Boolean) = viewModelScope.launch {
        application.export.setTarget(uri, writeNow)
    }

    fun setAutoExport(enabled: Boolean) = viewModelScope.launch {
        settingsStore.setAutoExport(enabled)
        if (enabled) exportNow()
    }

    fun saveMeter(config: MeterConfig) = viewModelScope.launch { settingsStore.setMeter(config) }

    fun toggleOcr(enabled: Boolean) = viewModelScope.launch { settingsStore.setOcrEnabled(enabled) }

    /** Photos stay in app-private storage; they are the audit trail, not gallery items. */
    private suspend fun persist(bitmap: Bitmap): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(getApplication<Application>().filesDir, "photos").apply { mkdirs() }
            val file = File(dir, "${Instant.now().toEpochMilli()}.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file
        }.getOrNull().also { bitmap.recycle() }
    }

    override fun onCleared() {
        recognizer.close()
        super.onCleared()
    }
}
