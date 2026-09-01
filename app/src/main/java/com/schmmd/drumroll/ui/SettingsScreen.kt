package com.schmmd.drumroll.ui

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
import com.schmmd.drumroll.data.MeterConfig
import com.schmmd.drumroll.data.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onSaveEndpoint: (String, String) -> Unit,
    onSaveMeter: (MeterConfig) -> Unit,
    onToggleOcr: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var endpoint by remember(settings.endpoint) { mutableStateOf(settings.endpoint) }
    var secret by remember(settings.secret) { mutableStateOf(settings.secret) }
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
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text("Apps Script web app URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("Shared secret") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = { onSaveEndpoint(endpoint.trim(), secret.trim()) },
            ) { Text("Save destination") }

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
                        meterId = meterId.trim().ifBlank { MeterConfig.ROCKWELL },
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
