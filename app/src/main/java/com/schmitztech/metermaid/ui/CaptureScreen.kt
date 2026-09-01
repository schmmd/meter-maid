package com.schmitztech.metermaid.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.schmitztech.metermaid.data.Roi
import com.schmitztech.metermaid.ocr.FrameBuffer
import com.schmitztech.metermaid.ocr.FrameCapture
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The app's front door. Opens straight to the viewfinder — no home screen, no menu —
 * because every extra tap here is a reason not to take the reading at all.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CaptureScreen(
    roi: Roi,
    onFlipGuide: () -> Unit,
    onCaptured: (frames: List<Bitmap>) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val frameBuffer = remember { FrameBuffer() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    // Read from the analyzer thread every frame, so it is a holder rather than state.
    val viewAspect = remember { AtomicReference(0f) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            frameBuffer.clear()
        }
    }

    LaunchedEffect(previewView, roi) {
        val view = previewView ?: return@LaunchedEffect
        val provider = suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                { cont.resume(future.get()) },
                ContextCompat.getMainExecutor(context),
            )
        }

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(view.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .apply {
                setAnalyzer(executor) { proxy ->
                    try {
                        val full = proxy.toBitmap()
                        FrameCapture.cropToRoi(
                            source = full,
                            roi = roi,
                            sensorRotationDegrees = proxy.imageInfo.rotationDegrees,
                            viewAspect = viewAspect.get(),
                        )?.let(frameBuffer::offer)
                        if (!full.isRecycled) full.recycle()
                    } catch (_: Exception) {
                        // A dropped frame is not worth surfacing; the burst has four more.
                    } finally {
                        proxy.close()
                    }
                }
            }

        provider.unbindAll()
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
        zoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewView = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    viewAspect.set(if (it.height > 0) it.width.toFloat() / it.height else 0f)
                }
                // On the preview rather than the whole screen, so the controls keep their
                // own taps. Filling the guide box with the register is what the recognizer
                // is most sensitive to, and in a crawlspace moving closer is not always an
                // option.
                .pointerInput(camera) {
                    detectTransformGestures { _, _, pinch, _ ->
                        val active = camera ?: return@detectTransformGestures
                        val state = active.cameraInfo.zoomState.value
                            ?: return@detectTransformGestures
                        val next = (state.zoomRatio * pinch)
                            .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                        active.cameraControl.setZoomRatio(next)
                        zoom = next
                    }
                },
        )

        // The guide rectangle. Everything outside it is discarded before OCR, which is
        // the single highest-value step in the whole pipeline.
        Canvas(Modifier.fillMaxSize()) {
            val rect = androidx.compose.ui.geometry.Rect(
                offset = Offset(roi.left * size.width, roi.top * size.height),
                size = Size(roi.width * size.width, roi.height * size.height),
            )
            val scrim = Color(0x99000000)
            drawRect(scrim, size = Size(size.width, rect.top))
            drawRect(scrim, topLeft = Offset(0f, rect.bottom),
                size = Size(size.width, size.height - rect.bottom))
            drawRect(scrim, topLeft = Offset(0f, rect.top),
                size = Size(rect.left, rect.height))
            drawRect(scrim, topLeft = Offset(rect.right, rect.top),
                size = Size(size.width - rect.right, rect.height))
            drawRect(
                color = Color(0xFF55B7AD),
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = 4f),
            )
        }

        Text(
            "Line the drums up inside the frame",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 14.sp,
            // Clears the icon row (48dp) and the degrees readout tucked under it.
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 76.dp),
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onFlipGuide) {
                Icon(Icons.Default.FlipCameraAndroid, "Flip guide", tint = Color.White)
            }
            IconButton(onClick = onHistory) {
                Icon(Icons.Default.History, "History", tint = Color.White)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, "Settings", tint = Color.White)
            }
        }

        if (zoom > 1.01f) {
            Text(
                String.format("%.1f\u00d7", zoom),
                color = Color.White.copy(alpha = 0.75f),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 24.dp, bottom = 40.dp),
            )
        }

        Text(
            "${roi.rotationDegrees}°",
            color = Color.White.copy(alpha = 0.6f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 52.dp, end = 20.dp),
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .size(76.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                onClick = {
                    val burst = frameBuffer.snapshot()
                    android.util.Log.i("MeterMaidOcr", "shutter pressed: buffer holds ${burst.size}")
                    onCaptured(burst)
                },
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier.size(60.dp),
            ) {}
        }
    }
}
