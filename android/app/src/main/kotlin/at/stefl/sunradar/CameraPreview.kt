package at.stefl.sunradar

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.SizeF
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

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
fun CameraPreview(modifier: Modifier, onCamera: (ViewCamera?) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var calibration: SensorCalibration? = null
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
            {
                val provider = future.get()
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
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
                    builder.build().also { it.surfaceProvider = previewView.surfaceProvider }
                calibration = readCalibration(characteristics, distorted = canSwitchOff)
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview)
                update()
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            previewView.removeOnLayoutChangeListener(layoutListener)
            previewView.previewStreamState.removeObservers(lifecycleOwner)
            if (future.isDone) future.get().unbindAll()
            onCamera(null)
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
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
