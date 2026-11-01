package at.stefl.sunradar

import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Size
import android.util.SizeF
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SunradarCamera"

/**
 * The preview of the back camera. [onCamera] gets the camera model in the pixels of the view each
 * time the transform from the sensor to the view changes, or null if it is not known.
 *
 * The preview asks for `DISTORTION_CORRECTION_MODE_OFF`, so the image has the distortion that
 * `LENS_DISTORTION` describes. If the device cannot switch the correction off, the model has no
 * distortion.
 */
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreview(
    modifier: Modifier,
    displayRotation: Int,
    detectSun: Boolean,
    onBlobs: (List<ViewBlob>) -> Unit,
    onCamera: (ViewCamera?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    // The use cases, so a rotation can update them without a rebind. The activity is not recreated
    // on a rotation (configChanges), so nobody else sets the new target rotation.
    val useCases = remember { mutableStateOf<Pair<Preview, ImageAnalysis>?>(null) }
    LaunchedEffect(displayRotation, useCases.value) {
        useCases.value?.let { (preview, analysis) ->
            preview.targetRotation = displayRotation
            analysis.targetRotation = displayRotation
        }
    }
    // Read by the analyzer thread, so the detection can switch on and off without a rebind.
    val detect = remember { AtomicBoolean(false) }
    detect.set(detectSun)
    LaunchedEffect(detectSun) { if (!detectSun) onBlobs(emptyList()) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var calibration: SensorCalibration? = null
        // The provider can arrive after the dispose (Camera, then Sky at once): then do nothing.
        var disposed = false
        val update = {
            val matrix = previewView.sensorToViewTransform
            val c = calibration
            if (matrix == null || c == null || previewView.width == 0) {
                onCamera(null)
            } else {
                val values = FloatArray(9).also { matrix.getValues(it) }
                val camera =
                    viewCamera(
                        c,
                        values,
                        previewView.width.toDouble(),
                        previewView.height.toDouble(),
                    )
                if (camera == null)
                    Log.w(TAG, "unexpected sensor-to-view transform: ${values.toList()}")
                onCamera(camera)
            }
        }
        val layoutListener =
            android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        previewView.addOnLayoutChangeListener(layoutListener)
        previewView.previewStreamState.observe(lifecycleOwner) {
            if (it == PreviewView.StreamState.STREAMING) update()
        }

        future.addListener(
            listener@{
                if (disposed) return@listener
                val provider = future.get()
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(selector)) {
                    Log.w(TAG, "the device has no back camera")
                    return@listener
                }
                val info = provider.getCameraInfo(selector)
                val characteristics = Camera2CameraInfo.from(info)
                val modes =
                    characteristics.getCameraCharacteristic(
                        CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES
                    )
                val canSwitchOff =
                    modes?.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF) == true

                val builder = Preview.Builder()
                if (canSwitchOff) {
                    Camera2Interop.Extender(builder)
                        .setCaptureRequestOption(
                            CaptureRequest.DISTORTION_CORRECTION_MODE,
                            CameraMetadata.DISTORTION_CORRECTION_MODE_OFF,
                        )
                }
                val preview =
                    builder.setTargetRotation(displayRotation).build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                val analysis = sunAnalysis(previewView, detect, onBlobs)
                analysis.targetRotation = displayRotation
                calibration = readCalibration(characteristics, distorted = canSwitchOff)
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                useCases.value = preview to analysis
                update()
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            disposed = true
            previewView.removeOnLayoutChangeListener(layoutListener)
            previewView.previewStreamState.removeObservers(lifecycleOwner)
            if (future.isDone) future.get().unbindAll()
            onCamera(null)
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** A bright region of the camera image, in the pixels of the preview view. */
data class ViewBlob(val center: Offset, val radius: Float)

private val analysisExecutor = Executors.newSingleThreadExecutor()

/**
 * Small frames for the sun detection. The analyzer finds the saturated regions in the Y plane, and
 * maps them to the view: view = sensorToView * (sensorToBuffer)^-1 * buffer. The matrices contain
 * the crop, the scale, and the rotation of both streams.
 */
private fun sunAnalysis(
    previewView: PreviewView,
    detect: AtomicBoolean,
    onBlobs: (List<ViewBlob>) -> Unit,
): ImageAnalysis {
    val analysis =
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    var bytes = ByteArray(0)
    val main = ContextCompat.getMainExecutor(previewView.context)
    analysis.setAnalyzer(analysisExecutor) { image ->
        try {
            if (detect.get()) {
                val plane = image.planes[0]
                val buffer = plane.buffer
                if (bytes.size < buffer.remaining()) bytes = ByteArray(buffer.remaining())
                val length = buffer.remaining()
                buffer.get(bytes, 0, length)
                val blobs = SunDetector.findBlobs(bytes, image.width, image.height, plane.rowStride)
                val sensorToBuffer = Matrix(image.imageInfo.sensorToBufferTransformMatrix)
                main.execute {
                    val sensorToView = previewView.sensorToViewTransform ?: return@execute
                    val bufferToView = Matrix()
                    if (!sensorToBuffer.invert(bufferToView)) return@execute
                    bufferToView.postConcat(sensorToView)
                    onBlobs(
                        blobs.map {
                            val p = floatArrayOf(it.centerX.toFloat(), it.centerY.toFloat())
                            bufferToView.mapPoints(p)
                            ViewBlob(
                                Offset(p[0], p[1]),
                                bufferToView.mapRadius(it.radius.toFloat()),
                            )
                        }
                    )
                }
            }
        } finally {
            image.close()
        }
    }
    return analysis
}

@OptIn(ExperimentalCamera2Interop::class)
private fun readCalibration(info: Camera2CameraInfo, distorted: Boolean): SensorCalibration {
    fun <T> get(key: CameraCharacteristics.Key<T>): T? = info.getCameraCharacteristic(key)
    val active = get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect()
    val preCorrection =
        get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: active
    val physical = get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: SizeF(0f, 0f)
    val pixelArray = get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    val calibration =
        SensorCalibration(
            intrinsics =
                get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                    ?.map { it.toDouble() }
                    ?.toDoubleArray(),
            distortion =
                get(CameraCharacteristics.LENS_DISTORTION)?.map { it.toDouble() }?.toDoubleArray(),
            distorted = distorted,
            preCorrectionLeft = preCorrection.left.toDouble(),
            preCorrectionTop = preCorrection.top.toDouble(),
            activeLeft = active.left.toDouble(),
            activeTop = active.top.toDouble(),
            activeWidth = active.width().toDouble(),
            activeHeight = active.height().toDouble(),
            focalLengthMm =
                get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull()
                    ?.toDouble() ?: 0.0,
            physicalWidthMm = physical.width.toDouble(),
            physicalHeightMm = physical.height.toDouble(),
            pixelArrayWidth = pixelArray?.width?.toDouble() ?: active.width().toDouble(),
            pixelArrayHeight = pixelArray?.height?.toDouble() ?: active.height().toDouble(),
        )
    Log.i(
        TAG,
        "intrinsics=${calibration.intrinsics?.toList()} distortion=${calibration.distortion?.toList()} " +
            "distorted=$distorted active=$active preCorrection=$preCorrection focal=${calibration.focalLengthMm} " +
            "physical=$physical pixelArray=$pixelArray",
    )
    return calibration
}
