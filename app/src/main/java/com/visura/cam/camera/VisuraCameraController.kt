package com.visura.cam.camera

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import com.visura.cam.correction.ColorCorrectionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

private const val TAG = "VisuraCamera"

@Singleton
class VisuraCameraController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val colorEngine: ColorCorrectionEngine
) {
    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private val cameraThread = HandlerThread("VisuraCamera").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
    val cameraState: StateFlow<CameraState> = _cameraState

    private val _captureSettings = MutableStateFlow(CaptureSettings())
    val captureSettings: StateFlow<CaptureSettings> = _captureSettings

    var currentLensId: String = ""
        private set

    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var lastPreviewSurface: Surface? = null

    // ── Camera ID Discovery ──────────────────────────────────────
    // Realme 8 Pro: discover real IDs at runtime instead of assuming "0","1","2","3"

    private val backCameraIds: Map<String, Int> by lazy { discoverCameras() }

    /**
     * Discover all cameras and map them to lens positions.
     * Returns map: lens_type → camera_id_string
     */
    private fun discoverCameras(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(LENS_FACING) ?: continue
                val focalLengths = chars.get(LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val pixelArraySize = chars.get(SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val mp = if (pixelArraySize != null)
                    (pixelArraySize.width * pixelArraySize.height) / 1_000_000 else 0

                when {
                    facing == LENS_FACING_FRONT ->
                        result[ColorCorrectionEngine.LENS_SELFIE] = id.toIntOrNull() ?: 0
                    facing == LENS_FACING_BACK && mp >= 80 ->
                        result[ColorCorrectionEngine.LENS_MAIN_108MP] = id.toIntOrNull() ?: 0
                    facing == LENS_FACING_BACK && mp in 6..20 ->
                        result[ColorCorrectionEngine.LENS_ULTRAWIDE] = id.toIntOrNull() ?: 0
                    facing == LENS_FACING_BACK && mp in 1..4 &&
                            !result.containsKey(ColorCorrectionEngine.LENS_MACRO) ->
                        result[ColorCorrectionEngine.LENS_MACRO] = id.toIntOrNull() ?: 0
                    facing == LENS_FACING_BACK && mp in 1..4 ->
                        result[ColorCorrectionEngine.LENS_DEPTH] = id.toIntOrNull() ?: 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera discovery failed", e)
        }
        // Fallback: if main not found, use first back camera
        if (!result.containsKey(ColorCorrectionEngine.LENS_MAIN_108MP)) {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(LENS_FACING) == LENS_FACING_BACK
            }?.let {
                result[ColorCorrectionEngine.LENS_MAIN_108MP] = it.toIntOrNull() ?: 0
            }
        }
        Log.d(TAG, "Discovered cameras: $result")
        return result
    }

    private fun lensIdToDeviceId(lensId: String): String {
        return backCameraIds[lensId]?.toString()
            ?: cameraManager.cameraIdList.firstOrNull() ?: "0"
    }

    fun getFrontCameraId(): String =
        backCameraIds[ColorCorrectionEngine.LENS_SELFIE]?.toString()
            ?: cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(LENS_FACING) == LENS_FACING_FRONT
            } ?: "1"

    // ── Open Camera ───────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.CAMERA)
    fun openCamera(lensId: String = ColorCorrectionEngine.LENS_MAIN_108MP) {
        currentLensId = lensId
        _cameraState.value = CameraState.Opening
        val deviceId = lensIdToDeviceId(lensId)
        Log.d(TAG, "Opening camera lensId=$lensId deviceId=$deviceId")

        try {
            cameraManager.openCamera(deviceId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "Camera opened: $deviceId")
                    cameraDevice = camera
                    _cameraState.value = CameraState.Open(lensId)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close(); cameraDevice = null
                    _cameraState.value = CameraState.Closed
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close(); cameraDevice = null
                    _cameraState.value = CameraState.Error("Camera error $error")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera failed", e)
            _cameraState.value = CameraState.Error(e.message ?: "Open failed")
        }
    }

    // ── Start Preview ─────────────────────────────────────────────

    fun startPreview(previewSurface: Surface) {
        val device = cameraDevice
        if (device == null) {
            Log.w(TAG, "startPreview: camera not open yet")
            lastPreviewSurface = previewSurface
            return
        }
        lastPreviewSurface = previewSurface

        // Close existing session
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null

        // Setup image readers
        setupImageReaders()

        val surfaces = buildList {
            add(previewSurface)
            jpegReader?.surface?.let { add(it) }
        }

        try {
            val outputConfigs = surfaces.map { OutputConfiguration(it) }
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                { cmd -> cameraHandler.post(cmd) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "Session configured")
                        captureSession = session
                        startRepeatingPreview(session, previewSurface)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Session config FAILED")
                        _cameraState.value = CameraState.Error("Session config failed")
                    }
                }
            )
            device.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession failed", e)
            _cameraState.value = CameraState.Error(e.message ?: "Session failed")
        }
    }

    private fun setupImageReaders() {
        val settings = _captureSettings.value
        val outputSize = if (settings.is108MpMode)
            Size(4000, 3000) else Size(4000, 3000)  // 12MP binned — sweet spot for HM2

        jpegReader?.close()
        jpegReader = ImageReader.newInstance(
            outputSize.width, outputSize.height, ImageFormat.JPEG, 3)

        if (settings.captureRaw) {
            rawReader?.close()
            rawReader = ImageReader.newInstance(
                4000, 3000, ImageFormat.RAW_SENSOR, 2)
        }
    }

    private fun startRepeatingPreview(session: CameraCaptureSession, surface: Surface) {
        val device = cameraDevice ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                // *** YELLOW CAST FIX on every preview frame ***
                colorEngine.applyToCapture(this, currentLensId)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE,
                    if (currentLensId == ColorCorrectionEngine.LENS_MACRO)
                        CaptureRequest.CONTROL_AF_MODE_OFF
                    else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                applyManualSettings(this, _captureSettings.value)
            }
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
            Log.d(TAG, "Preview started")
        } catch (e: Exception) {
            Log.e(TAG, "startRepeatingPreview failed", e)
        }
    }

    // ── Capture Photo ─────────────────────────────────────────────

    fun capturePhoto(onCaptured: (ImageCapture) -> Unit) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                jpegReader?.surface?.let { addTarget(it) }
                rawReader?.surface?.let { addTarget(it) }
                // *** YELLOW CAST FIX on still capture ***
                colorEngine.applyToCapture(this, currentLensId)
                set(CaptureRequest.JPEG_QUALITY, 97)
                set(CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
                set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
                set(CaptureRequest.HOT_PIXEL_MODE,
                    CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
                applyManualSettings(this, _captureSettings.value)
            }
            session.capture(builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val jpeg = jpegReader?.acquireLatestImage()
                        val raw  = rawReader?.acquireLatestImage()
                        onCaptured(ImageCapture(jpeg, raw, result, currentLensId))
                    }
                }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "capturePhoto failed", e)
        }
    }

    private fun applyManualSettings(builder: CaptureRequest.Builder, settings: CaptureSettings) {
        if (!settings.isProMode) return
        settings.iso?.let {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, it)
        }
        settings.shutterSpeedNs?.let {
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
        }
        settings.focusDistance?.let {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
        settings.evCompensation?.let {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it)
        }
    }

    fun updateSettings(block: CaptureSettings.() -> CaptureSettings) {
        _captureSettings.value = block(_captureSettings.value)
    }

    fun switchLens(lensId: String) {
        Log.d(TAG, "Switching lens to $lensId")
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        captureSession = null
        cameraDevice = null
        try {
            @Suppress("MissingPermission")
            openCamera(lensId)
        } catch (e: Exception) {
            Log.e(TAG, "switchLens failed", e)
        }
    }

    fun close() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close()   } catch (_: Exception) {}
        try { jpegReader?.close()      } catch (_: Exception) {}
        try { rawReader?.close()       } catch (_: Exception) {}
        try { cameraThread.quitSafely() } catch (_: Exception) {}
    }
}

// ── Data classes ─────────────────────────────────────────────────

data class CaptureSettings(
    val isProMode: Boolean = false,
    val is108MpMode: Boolean = false,
    val captureRaw: Boolean = false,
    val iso: Int? = null,
    val shutterSpeedNs: Long? = null,
    val focusDistance: Float? = null,
    val whiteBalanceKelvin: Int? = null,
    val evCompensation: Int? = null,
    val shootMode: ShootMode = ShootMode.PHOTO,
    val flashMode: FlashMode = FlashMode.AUTO,
    val hdrEnabled: Boolean = false,
    val nightModeEnabled: Boolean = false,
    val macroAssistEnabled: Boolean = true
)

enum class ShootMode { PHOTO, VIDEO, MACRO, PORTRAIT, NIGHT, PRO, RAW, PANORAMA, SLOW_MO, TIME_LAPSE }
enum class FlashMode { OFF, AUTO, ON, TORCH }

sealed class CameraState {
    object Idle    : CameraState()
    object Opening : CameraState()
    data class Open(val lensId: String) : CameraState()
    object Closed  : CameraState()
    data class Error(val message: String) : CameraState()
}

data class ImageCapture(
    val jpeg: android.media.Image?,
    val raw:  android.media.Image?,
    val captureResult: TotalCaptureResult,
    val lensId: String
) {
    val actualIso: Int get() =
        captureResult.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
    val actualShutterNs: Long get() =
        captureResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 33_000_000L
    val actualAperture: Float get() = when (lensId) {
        "0" -> 1.88f; "1" -> 2.25f; "2" -> 2.4f
        "3" -> 2.4f;  else -> 2.45f
    }
    val focalLengthMm: Float get() = when (lensId) {
        "0" -> 4.74f; "1" -> 1.86f; "2" -> 4.74f; else -> 3.47f
    }
}
