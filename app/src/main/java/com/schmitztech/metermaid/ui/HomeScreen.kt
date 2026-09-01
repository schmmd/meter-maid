package com.schmitztech.metermaid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The front door. The viewfinder used to open first on the theory that every extra tap
 * is a reason not to take the reading — but half of the visits are to look something
 * up, not to record, and those all started with a detour through the camera.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRecord: () -> Unit,
    onReadings: () -> Unit,
    onCharts: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Meter Maid") }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HomeCard(Icons.Default.PhotoCamera, "Record", onRecord)
                HomeCard(Icons.Default.Receipt, "Readings", onReadings)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HomeCard(Icons.AutoMirrored.Filled.ShowChart, "Charts", onCharts)
                HomeCard(Icons.Default.Settings, "Settings", onSettings)
            }
        }
    }
}

@Composable
private fun RowScope.HomeCard(icon: ImageVector, label: String, onClick: () -> Unit) {
    Card(onClick = onClick, Modifier.weight(1f).aspectRatio(1f)) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon, contentDescription = null, modifier = Modifier.size(44.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
