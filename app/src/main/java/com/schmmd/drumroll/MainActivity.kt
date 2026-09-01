package com.schmmd.drumroll

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.schmmd.drumroll.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DrumrollTheme { Root() } }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun Root() {
    val vm: MainViewModel = viewModel()
    val settings by vm.settings.collectAsState()
    val confirm by vm.confirm.collectAsState()
    val readings by vm.readings.collectAsState()
    val nav = rememberNavController()
    val camera = rememberPermissionState(Manifest.permission.CAMERA)

    if (!camera.status.isGranted) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Button(onClick = { camera.launchPermissionRequest() }, Modifier.padding(24.dp)) {
                Text("Allow camera access")
            }
        }
        return
    }

    // The confirm screen is modal over everything: once a burst has been taken, the
    // only ways out are Save or Cancel.
    confirm?.let { state ->
        ConfirmScreen(
            state = state,
            config = settings.meter,
            onDrumsChange = vm::updateDrums,
            onNoteChange = vm::updateNote,
            onDateChange = vm::updateDate,
            onSave = { vm.save() },
            onCancel = vm::cancel,
        )
        return
    }

    NavHost(nav, startDestination = "capture") {
        composable("capture") {
            CaptureScreen(
                roi = settings.roi,
                onRotateGuide = { vm.rotateGuide() },
                onCaptured = vm::onCaptured,
                onHistory = { nav.navigate("history") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("history") {
            HistoryScreen(
                readings = readings,
                config = settings.meter,
                onBack = { nav.popBackStack() },
                onRetrySync = vm::retrySync,
                onDelete = vm::delete,
            )
        }
        composable("settings") {
            SettingsScreen(
                settings = settings,
                onSaveEndpoint = vm::saveEndpoint,
                onSaveMeter = vm::saveMeter,
                onToggleOcr = vm::toggleOcr,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
