package com.schmitztech.metermaid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import com.schmitztech.metermaid.data.MeterConfig
import com.schmitztech.metermaid.data.Reading
import com.schmitztech.metermaid.data.SyncState
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    readings: List<Reading>,
    config: MeterConfig,
    canSync: Boolean,
    onBack: () -> Unit,
    onRetrySync: () -> Unit,
    onAddManual: () -> Unit,
    onChart: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Readings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onChart) {
                        Icon(Icons.Default.ShowChart, "Water use over time")
                    }
                    IconButton(onClick = onAddManual) { Icon(Icons.Default.Add, "Add a reading") }
                    // With no file linked the tap would do nothing at all; a button that
                    // silently ignores you is worse than one that is not there.
                    if (canSync) {
                        IconButton(onClick = onRetrySync) { Icon(Icons.Default.Refresh, "Sync now") }
                    }
                },
            )
        },
    ) { padding ->
        if (readings.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text("No readings yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        LazyColumn(Modifier.padding(padding)) {
            itemsIndexed(readings, key = { _, r -> r.id }) { index, reading ->
                // Ordered newest first, so the comparison row is the next one down on
                // the same meter — scanned past any interleaved rows of another meter,
                // which sat adjacent before and blanked the delta.
                val previous = (index + 1 until readings.size)
                    .firstOrNull { readings[it].meterId == reading.meterId }
                    ?.let(readings::get)
                ReadingRow(reading, previous, config, onEdit, onDelete)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ReadingRow(
    reading: Reading,
    previous: Reading?,
    config: MeterConfig,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val subtitle = if (previous != null) {
        val days = ChronoUnit.DAYS.between(
            LocalDate.parse(previous.readingDate), LocalDate.parse(reading.readingDate)
        )
        val delta = reading.value - previous.value
        if (days > 0) "+$delta · ${delta / days} gal/day" else "+$delta · same day"
    } else {
        if (reading.corrected) "corrected" else "ocr"
    }

    Row(
        Modifier
            .fillMaxWidth()
            // The whole row, not a pencil hiding in the corner: correcting a mistyped
            // drum is the common reason to come back to this screen.
            .clickable { onEdit(reading.id) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(reading.sync.color())
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${reading.readingDate}  ${reading.timeOfDay()}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            config.display(reading.value),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyLarge,
        )
        IconButton(onClick = { onDelete(reading.id) }) {
            Text("×", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Local clock time the shutter fired, so same-day readings are distinguishable. */
private fun Reading.timeOfDay(): String =
    DateTimeFormatter.ofPattern("HH:mm")
        .format(Instant.ofEpochMilli(capturedAtUtc).atZone(ZoneId.systemDefault()))

@Composable
private fun SyncState.color(): Color = when (this) {
    SyncState.SYNCED -> Color(0xFF2E6B4A)
    SyncState.QUEUED, SyncState.SYNCING -> WarnColor
    SyncState.FAILED -> MaterialTheme.colorScheme.error
}
