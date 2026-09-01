package com.schmmd.drumroll.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.schmmd.drumroll.DrumrollApp
import com.schmmd.drumroll.data.MeterConfig
import com.schmmd.drumroll.data.Reading
import com.schmmd.drumroll.data.Roi
import com.schmmd.drumroll.data.Settings
import com.schmmd.drumroll.data.SyncState
import com.schmmd.drumroll.ocr.MlKitRecognizer
import com.schmmd.drumroll.ocr.PREFILL_THRESHOLD
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

    private val application = app as DrumrollApp
    private val repository = application.repository
    private val settingsStore = application.settings
    private val recognizer = MlKitRecognizer()

    val readings = repository.readings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<Settings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    private val _confirm = MutableStateFlow<ConfirmState?>(null)
    val confirm: StateFlow<ConfirmState?> = _confirm.asStateFlow()

    private var pendingPhoto: File? = null
    private var busy = false

    fun rotateGuide() = viewModelScope.launch {
        val roi = settings.value.roi
        settingsStore.setRoi(roi.copy(rotationDegrees = (roi.rotationDegrees + 90) % 360))
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

        // Copied here, on the caller's thread: the analyzer keeps filling the ring
        // buffer after the shutter and recycles what it evicts, so the frame has to be
        // taken out of harm's way before the coroutine gets a chance to run.
        val keepsake = frames.lastOrNull()
            ?.takeIf { !it.isRecycled }
            ?.let { runCatching { it.copy(it.config ?: Bitmap.Config.ARGB_8888, false) }.getOrNull() }

        viewModelScope.launch {
            val today = LocalDate.now()
            pendingPhoto = keepsake?.let { persist(it) }

            val recognized = if (current.ocrEnabled && frames.isNotEmpty()) {
                runCatching { recognizer.read(frames, config.drumCount) }.getOrNull()
            } else null

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
            val duplicate = repository.existingOn(today, config.meterId) != null

            _confirm.value = ConfirmState(
                drums = prefill,
                agreement = recognized?.agreement ?: List(config.drumCount) { 0f },
                readingDate = today,
                capturedAt = today,
                previous = previous,
                duplicate = duplicate,
                ocrSummary = summarise(recognized, config),
                photoPath = pendingPhoto?.absolutePath.orEmpty(),
            )
            busy = false
        }
    }

    private fun summarise(
        r: com.schmmd.drumroll.ocr.RecognizedReading?,
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
            previous = repository.previousFor(config.meterId, date),
            duplicate = repository.existingOn(date, config.meterId) != null,
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

        val prefilled = buildString {
            for (i in 0 until config.drumCount) {
                val agree = state.agreement.getOrNull(i) ?: 0f
                append(if (agree >= PREFILL_THRESHOLD) '1' else '0')
            }
        }
        // "Corrected" means the user changed or supplied a digit the OCR did not.
        val corrected = prefilled.contains('0')

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
                sync = SyncState.QUEUED,
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

    /** The user picked a file in the system picker; adopt it and write to it immediately. */
    fun chooseExportTarget(uri: Uri) = viewModelScope.launch {
        application.export.setTarget(uri)
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
