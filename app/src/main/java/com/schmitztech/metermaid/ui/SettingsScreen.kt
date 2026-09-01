package com.schmitztech.metermaid.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.schmitztech.metermaid.data.MeterConfig
import com.schmitztech.metermaid.backup.ReadingsCsv
import com.schmitztech.metermaid.data.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onCreateTarget: (Uri) -> Unit,
    onUseExistingTarget: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onToggleAutoExport: (Boolean) -> Unit,
    onExportNow: () -> Unit,
    onSaveMeter: (MeterConfig) -> Unit,
    onToggleOcr: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    // Creates a new spreadsheet file wherever the picker offers — Drive, local, Dropbox.
    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ReadingsCsv.MIME),
    ) { uri -> uri?.let(onCreateTarget) }

    // Anything, rather than text/csv: Drive lists files with unhelpful MIME types and a
    // filtered picker hides the very file the user exported last time.
    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onUseExistingTarget) }

    val importFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImport) }

    var drums by remember(settings.meter) { mutableStateOf(settings.meter.drumCount.toString()) }
    var zeros by remember(settings.meter) { mutableStateOf(settings.meter.trailingZeros.toString()) }
    var minDay by remember(settings.meter) { mutableStateOf(settings.meter.minPerDay.toInt().toString()) }
    var maxDay by remember(settings.meter) { mutableStateOf(settings.meter.maxPerDay.toInt().toString()) }
    var meterId by remember(settings.meter) { mutableStateOf(settings.meter.meterId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Spreadsheet", style = MaterialTheme.typography.titleSmall)
            Text(
                "Readings are written to one CSV file. Pick Google Drive in the system " +
                    "picker to keep it in the cloud — no sign-in, no setup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (settings.exportUri.isNotBlank()) {
                LabelledValue("File", settings.exportName.ifBlank { "chosen" })
            }
            if (settings.lastExport.isNotBlank()) {
                Text(
                    settings.lastExport,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { createFile.launch(ReadingsCsv.FILE_NAME) }) {
                    Text(if (settings.exportUri.isBlank()) "Choose a file…" else "New file…")
                }
                OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }) {
                    Text("Use existing…")
                }
            }
            Text(
                "\u201cUse existing\u201d only links the file — it is not read or " +
                    "overwritten until you import or save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Save after every reading", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Rewrites the whole file a few seconds after anything changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.autoExport,
                    onCheckedChange = onToggleAutoExport,
                    enabled = settings.exportUri.isNotBlank(),
                )
            }

            OutlinedButton(
                onClick = onExportNow,
                enabled = settings.exportUri.isNotBlank(),
            ) { Text("Save now") }

            HorizontalDivider()

            Text("Import", style = MaterialTheme.typography.titleSmall)
            Text(
                "Reads a spreadsheet back in to seed a new phone. Readings already held " +
                    "are skipped, so it is safe to run twice, and nothing is deleted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { importFile.launch(arrayOf("*/*")) }) {
                Text("Import…")
            }
            // Beside the button that caused it: this used to land in the export status
            // above, where an import failure read as a spreadsheet problem.
            if (settings.lastImport.isNotBlank()) {
                Text(
                    settings.lastImport,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (settings.lastImport.startsWith("Import failed")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            HorizontalDivider()

            Text("Meter", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = meterId,
                onValueChange = { meterId = it },
                label = { Text("Meter ID") },
                supportingText = { Text("Change this when the meter is replaced — it resets the backwards check.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = drums, onValueChange = { drums = it },
                    label = { Text("Rotating drums") }, modifier = Modifier.weight(1f), singleLine = true,
                )
                OutlinedTextField(
                    value = zeros, onValueChange = { zeros = it },
                    label = { Text("Painted zeros") }, modifier = Modifier.weight(1f), singleLine = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = minDay, onValueChange = { minDay = it },
                    label = { Text("Min gal/day") }, modifier = Modifier.weight(1f), singleLine = true,
                )
                OutlinedTextField(
                    value = maxDay, onValueChange = { maxDay = it },
                    label = { Text("Max gal/day") }, modifier = Modifier.weight(1f), singleLine = true,
                )
            }
            Button(onClick = {
                onSaveMeter(
                    MeterConfig(
                        meterId = meterId.trim(),
                        drumCount = drums.toIntOrNull() ?: 6,
                        trailingZeros = zeros.toIntOrNull()?.coerceIn(0, 3) ?: 1,
                        minPerDay = minDay.toDoubleOrNull() ?: 50.0,
                        maxPerDay = maxDay.toDoubleOrNull() ?: 5000.0,
                    )
                )
            }) { Text("Save meter") }

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Read digits automatically", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Prefills the drums the camera is confident about. The moving drum is always yours.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.ocrEnabled, onCheckedChange = onToggleOcr)
            }
        }
    }
}
