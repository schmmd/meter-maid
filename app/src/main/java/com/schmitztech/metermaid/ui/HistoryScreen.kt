package com.schmitztech.metermaid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.schmitztech.metermaid.backup.ExportStatus
import com.schmitztech.metermaid.data.Interval
import com.schmitztech.metermaid.data.MeterConfig
import com.schmitztech.metermaid.data.Reading
import com.schmitztech.metermaid.data.intervals
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    readings: List<Reading>,
    config: MeterConfig,
    /** Null when no file is linked, which hides the sync control entirely. */
    syncStatus: ExportStatus?,
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
                    // silently ignores you is worse than one that is not there. The tint
                    // is the old per-row dot moved to where the one fact actually lives:
                    // the whole log is saved, pending, or failed together.
                    if (syncStatus != null) {
                        IconButton(onClick = onRetrySync) {
                            Icon(Icons.Default.Refresh, "Sync now", tint = syncStatus.color())
                        }
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

        // The same chronological walk every other surface uses, keyed for the
        // newest-first display order this list arrives in.
        val intervalById = remember(readings) {
            readings.intervals().associateBy { it.reading.id }
        }
        LazyColumn(Modifier.padding(padding)) {
            items(readings, key = { it.id }) { reading ->
                ReadingRow(intervalById.getValue(reading.id), config, onEdit, onDelete)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ReadingRow(
    interval: Interval,
    config: MeterConfig,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val reading = interval.reading
    val subtitle = when {
        interval.delta == null -> null // no predecessor, nothing to say
        interval.perDay != null -> "+${interval.delta} · ${interval.perDay.toLong()} gal/day"
        else -> "+${interval.delta} · same day"
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
        Column(Modifier.weight(1f)) {
            Text(
                "${reading.readingDate}  ${reading.timeOfDay()}",
                style = MaterialTheme.typography.bodyLarge,
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
private fun ExportStatus.color(): Color = when (this) {
    ExportStatus.SAVED -> Color(0xFF2E6B4A)
    ExportStatus.PENDING, ExportStatus.WRITING -> WarnColor
    ExportStatus.FAILED -> MaterialTheme.colorScheme.error
    ExportStatus.IDLE -> LocalContentColor.current
}
