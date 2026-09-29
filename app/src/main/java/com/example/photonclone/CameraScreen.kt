// Manual exposure logic (preview exposure cap, full-length capture exposure with a low
// target FPS range, 1/3-stop steps, MANUAL_SENSOR capability check) is adapted from
// bjzhou/PhotonCamera (Camera2Controller.applyExposureSettings / CameraState), Apache-2.0.
// See NOTICE.
package com.example.photonclone

import android.content.ContentValues
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.provider.MediaStore
import android.util.Range
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.roundToInt

// Values taken from Photon Camera's CameraState
private const val MAX_PHOTO_PREVIEW_NS = 1_000_000_000L / 15   // preview exposure cap (photo)
private const val MAX_VIDEO_SHUTTER_NS = 5_000_000_000L        // manual shutter cap (video), raised from Photon's 1/6s
private const val MAX_PHOTO_SHUTTER_NS = 10_000_000_000L       // safety cap for still capture

/** 1/3-stop steps between lo and hi (inclusive of hi). */
private fun stopValues(lo: Double, hi: Double): List<Double> {
    val out = mutableListOf<Double>()
    val f = 2.0.pow(1.0 / 3.0)
    var v = lo
    while (v < hi * 0.999) { out += v; v *= f }
    out += hi
    return out
}
private fun nearestT(stops: List<Double>, target: Double): Float {
    val i = stops.indices.minByOrNull { abs(stops[it] - target) } ?: 0
    return if (stops.size <= 1) 0f else i / (stops.size - 1).toFloat()
}
private fun shutterLabel(ns: Long): String =
    if (ns < 500_000_000L) "1/${(1e9 / ns).roundToInt()}" else "%.1fs".format(ns / 1e9)

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val scope = rememberCoroutineScope()

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var videoMode by remember { mutableStateOf(false) }
    var flashOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }

    // Manual controls
    var manual by remember { mutableStateOf(false) }
    var night by remember { mutableStateOf(false) }
    var isoT by remember { mutableStateOf(0.3f) }
    var shutterT by remember { mutableStateOf(0.5f) }
    var evIndex by remember { mutableStateOf(0f) }

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

    // Sensor limits reported by the device (Camera2 characteristics)
    val isoRange: Range<Int> = remember(camera) {
        camera?.let {
            Camera2CameraInfo.from(it.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        } ?: Range(100, 3200)
    }
    val maxFrameDur: Long? = remember(camera) {
        camera?.let {
            Camera2CameraInfo.from(it.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
        }
    }
    val expRange: Range<Long> = remember(camera, videoMode, maxFrameDur) {
        val r = camera?.let {
            Camera2CameraInfo.from(it.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        } ?: Range(1_000_000L, 1_000_000_000L)
        val cap = minOf(if (videoMode) MAX_VIDEO_SHUTTER_NS else MAX_PHOTO_SHUTTER_NS, maxFrameDur ?: Long.MAX_VALUE)
        Range(r.lower, maxOf(r.lower, minOf(r.upper, cap)))
    }
    val lowestFps: Range<Int>? = remember(camera) {
        camera?.let {
            Camera2CameraInfo.from(it.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.minByOrNull { r -> r.upper }
        }
    }
    val manualSupported: Boolean = remember(camera) {
        camera?.let {
            Camera2CameraInfo.from(it.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        } ?: true
    }

    val isoStops = remember(isoRange) { stopValues(isoRange.lower.toDouble(), isoRange.upper.toDouble()) }
    val expStops = remember(expRange) { stopValues(expRange.lower.toDouble(), expRange.upper.toDouble()) }
    val iso = isoStops[(isoT * (isoStops.size - 1)).roundToInt().coerceIn(0, isoStops.size - 1)].roundToInt()
    val expNs = expStops[(shutterT * (expStops.size - 1)).roundToInt().coerceIn(0, expStops.size - 1)].toLong()
    val previewCap = if (videoMode) MAX_VIDEO_SHUTTER_NS else MAX_PHOTO_PREVIEW_NS
    val previewLimited = manual && expNs > previewCap

    // Photon-style: repeating preview is capped; the still shot uses the full exposure
    fun applyManualOptions(cam: Camera, exposureNs: Long, lowFps: Boolean) =
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(exposureNs, 33_333_333L))
                .apply {
                    if (lowFps && lowestFps != null)
                        setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, lowestFps)
                }.build()
        )

    // Bind camera when lens or mode changes
    LaunchedEffect(lensFacing, videoMode) {
        val provider = ProcessCameraProvider.getInstance(context).await()
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        provider.unbindAll()
        camera = provider.bindToLifecycle(
            lifecycleOwner, selector, preview, if (videoMode) videoCapture else imageCapture
        )
        evIndex = 0f
    }

    LaunchedEffect(camera, manual, iso, expNs, videoMode) {
        val cam = camera ?: return@LaunchedEffect
        if (manual && manualSupported) {
            applyManualOptions(cam, minOf(expNs, previewCap), false)
        } else {
            Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions()
        }
    }

    LaunchedEffect(flashOn, camera, videoMode) {
        imageCapture.flashMode = if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        if (videoMode) camera?.cameraControl?.enableTorch(flashOn)
    }

    val stamp = { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) }

    fun enableManual(on: Boolean) {
        if (on && !manualSupported) {
            Toast.makeText(context, "This camera doesn't expose manual sensor control", Toast.LENGTH_SHORT).show()
            return
        }
        manual = on
        if (!on) night = false
    }

    fun toggleNight() {
        if (!manualSupported) {
            Toast.makeText(context, "Night mode needs manual sensor control", Toast.LENGTH_SHORT).show()
            return
        }
        night = !night
        if (night) {
            // Night preset: ~1/4s shutter with ISO capped at ~1600. Use a tripod.
            manual = true
            shutterT = nearestT(expStops, 250_000_000.0)
            isoT = nearestT(isoStops, 1600.0)
        } else {
            manual = false
        }
    }

    fun takePhoto() {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PhotonClone")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        scope.launch {
            val cam = camera
            val longShot = manual && manualSupported && expNs > previewCap && cam != null
            if (longShot) applyManualOptions(cam!!, expNs, true).await()
            val restore = {
                if (longShot) applyManualOptions(cam!!, minOf(expNs, previewCap), false)
            }
            imageCapture.takePicture(opts, ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                        restore()
                        Toast.makeText(context, "Photo saved", Toast.LENGTH_SHORT).show()
                    }
                    override fun onError(e: ImageCaptureException) {
                        restore()
                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                })
        }
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
                        if (!manual) {
                            val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                        }
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
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { flashOn = !flashOn }) {
                Icon(if (flashOn) Icons.Default.FlashOn else Icons.Default.FlashOff, "Flash", tint = Color.White)
            }
            FilterChip(selected = manual, onClick = { enableManual(!manual) }, label = { Text("Manual") })
            FilterChip(selected = night, enabled = !videoMode, onClick = { toggleNight() }, label = { Text("Night") })
        }

        // Bottom controls
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (manual) {
                Column(
                    Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ISO $iso", color = Color.White, modifier = Modifier.width(84.dp))
                        Slider(value = isoT, onValueChange = { isoT = it; night = false }, steps = (isoStops.size - 2).coerceAtLeast(0), modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shutterLabel(expNs), color = Color.White, modifier = Modifier.width(84.dp))
                        Slider(value = shutterT, onValueChange = { shutterT = it; night = false }, steps = (expStops.size - 2).coerceAtLeast(0), modifier = Modifier.weight(1f))
                    }
                    if (videoMode && expNs > 40_000_000L) {
                        Text(
                            "Video will record at ~%.1f fps at this shutter (slow-shutter look)".format(1e9 / expNs),
                            color = Color(0xFFFFCC66), style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (previewLimited) {
                        Text(
                            "Preview capped at 1/${(1e9 / previewCap).roundToInt()}s — the photo uses the full exposure",
                            color = Color(0xFFFFCC66), style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            } else {
                val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
                if (range != null && range.upper > 0) {
                    Slider(
                        value = evIndex,
                        onValueChange = {
                            evIndex = it
                            camera?.cameraControl?.setExposureCompensationIndex(it.toInt())
                        },
                        valueRange = range.lower.toFloat()..range.upper.toFloat(),
                        modifier = Modifier.width(240.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
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
