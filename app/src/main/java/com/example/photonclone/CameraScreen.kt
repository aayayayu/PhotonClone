package com.example.photonclone

import android.content.ContentValues
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var videoMode by remember { mutableStateOf(false) }
    var flashOn by remember { mutableStateOf(false) }
    var night by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var exposure by remember { mutableStateOf(0f) }
    var recording by remember { mutableStateOf<Recording?>(null) }

    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    val videoCapture = remember {
        VideoCapture.withOutput(
            Recorder.Builder().setQualitySelector(
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
            ).build()
        )
    }

    // (Re)bind camera whenever lens, mode, or night mode changes
    LaunchedEffect(lensFacing, videoMode, night) {
        val provider = ProcessCameraProvider.getInstance(context).await()
        var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (night && !videoMode) {
            val em = ExtensionsManager.getInstanceAsync(context, provider).await()
            if (em.isExtensionAvailable(selector, ExtensionMode.NIGHT)) {
                selector = em.getExtensionEnabledCameraSelector(selector, ExtensionMode.NIGHT)
            } else {
                Toast.makeText(context, "Night mode not supported on this device", Toast.LENGTH_SHORT).show()
                night = false
            }
        }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        provider.unbindAll()
        camera = provider.bindToLifecycle(
            lifecycleOwner, selector, preview, if (videoMode) videoCapture else imageCapture
        )
        exposure = 0f
    }

    LaunchedEffect(flashOn, camera, videoMode) {
        imageCapture.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        if (videoMode) camera?.cameraControl?.enableTorch(flashOn)
    }

    val stamp = { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) }

    fun takePhoto() {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PhotonClone")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        imageCapture.takePicture(opts, ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    Toast.makeText(context, "Photo saved", Toast.LENGTH_SHORT).show()
                }
                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    fun toggleRecording() {
        val active = recording
        if (active != null) { active.stop(); recording = null; return }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/PhotonClone")
        }
        val opts = MediaStoreOutputOptions.Builder(
            context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()
        recording = videoCapture.output.prepareRecording(context, opts)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { ev ->
                if (ev is VideoRecordEvent.Finalize) {
                    recording = null
                    Toast.makeText(context, "Video saved", Toast.LENGTH_SHORT).show()
                }
            }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    detectTapGestures { offset ->
                        val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    }
                }
                .pointerInput(camera) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val cur = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                        cam.cameraControl.setZoomRatio(cur * zoom)
                    }
                }
        )

        // Top bar
        Row(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(onClick = { flashOn = !flashOn }) {
                Icon(if (flashOn) Icons.Default.FlashOn else Icons.Default.FlashOff, "Flash", tint = Color.White)
            }
            FilterChip(
                selected = night, enabled = !videoMode,
                onClick = { night = !night }, label = { Text("Night") }
            )
        }

        // Bottom controls
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
            if (range != null && range.upper > 0) {
                Slider(
                    value = exposure,
                    onValueChange = {
                        exposure = it
                        camera?.cameraControl?.setExposureCompensationIndex(it.toInt())
                    },
                    valueRange = range.lower.toFloat()..range.upper.toFloat(),
                    modifier = Modifier.width(240.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("PHOTO", color = if (!videoMode) Color.Yellow else Color.White,
                    modifier = Modifier.clickable(enabled = recording == null) { videoMode = false })
                Text("VIDEO", color = if (videoMode) Color.Yellow else Color.White,
                    modifier = Modifier.clickable(enabled = recording == null) { videoMode = true })
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Spacer(Modifier.size(48.dp))
                Box(
                    Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                        .padding(8.dp).clip(CircleShape)
                        .background(if (videoMode) Color.Red else Color.White)
                        .clickable { if (videoMode) toggleRecording() else takePhoto() }
                )
                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    },
                    enabled = recording == null, modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Default.FlipCameraAndroid, "Flip", tint = Color.White) }
            }
        }
    }
}
