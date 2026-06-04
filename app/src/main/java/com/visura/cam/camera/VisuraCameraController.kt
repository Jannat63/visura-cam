package com.visura.cam.camera

import android.Manifest
import kotlin.math.ln
import kotlin.math.pow
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import com.visura.cam.correction.ColorCorrectionEngine
import com.visura.cam.correction.ColorCorrectionEngine.Companion.LENS_MAIN_108MP
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VisuraCameraController — Full Camera2 API controller for Realme 8 Pro.
 *
 * Cameras available:
 *   ID "0" → Samsung HM2 108MP main (f/1.88, PDAF)
 *   ID "1" → 8MP ultrawide (f/2.25, 119°)
 *   ID "2" → 2MP macro (f/2.4, 4cm fixed)
 *   ID "3" → 2MP B&W depth
 * Front:
 *   ID "1" → Sony IMX471 16MP (f/2.45)
 *
 * Key capabilities unlocked via Camera2:
 *   - RAW DNG capture (bypasses ISP color pipeline entirely)
 *   - Manual AWB override (core yellow cast fix)
 *   - ISO 50–3200, Shutter 1/4000s–30s
 *   - Manual focus + focus peaking data
 *   - Per-frame color correction gains
 */
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
    private var previewRequest: CaptureRequest? = null

    private val cameraThread = HandlerThread("VisuraCamera").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    // Current state
    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
    val cameraState: StateFlow<CameraState> = _cameraState

    private val _captureSettings = MutableStateFlow(CaptureSettings())
    val captureSettings: StateFlow<CaptureSettings> = _captureSettings

    var currentLensId: String = LENS_MAIN_108MP
        private set

    // Image readers
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    // Realme 8 Pro Samsung HM2 output sizes
    object HM2Sizes {
        val FULL_108MP    = Size(12000, 9000)   // 108MP mode
        val BINNED_12MP   = Size(4000, 3000)    // Default 9-in-1 binning
        val PREVIEW_4K    = Size(3840, 2160)
        val PREVIEW_FHD   = Size(1920, 1080)
        val MACRO_MAX     = Size(1600, 1200)    // 2MP macro
        val ULTRAWIDE_MAX = Size(3264, 2448)    // 8MP
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun openCamera(lensId: String = LENS_MAIN_108MP) {
        currentLensId = lensId
        _cameraState.value = CameraState.Opening

        cameraManager.openCamera(lensId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                _cameraState.value = CameraState.Open(lensId)
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                _cameraState.value = CameraState.Closed
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                _cameraState.value = CameraState.Error("Camera error: $error")
            }
        }, cameraHandler)
    }

    /**
     * Start preview session with color correction applied from first frame.
     */
    fun startPreview(previewSurface: Surface, glCorrectionSurface: Surface? = null) {
        val device = cameraDevice ?: return
        val settings = _captureSettings.value

        // Setup JPEG reader
        val outputSize = if (settings.is108MpMode) HM2Sizes.FULL_108MP else HM2Sizes.BINNED_12MP
        jpegReader = ImageReader.newInstance(
            outputSize.width, outputSize.height,
            ImageFormat.JPEG, 3
        )

        // Setup RAW DNG reader (bypasses damaged ISP — best quality fix)
        if (settings.captureRaw) {
            rawReader = ImageReader.newInstance(
                HM2Sizes.BINNED_12MP.width, HM2Sizes.BINNED_12MP.height,
                ImageFormat.RAW_SENSOR, 2
            )
        }

        val surfaces = buildList {
            add(previewSurface)
            glCorrectionSurface?.let { add(it) }
            jpegReader?.surface?.let { add(it) }
            rawReader?.surface?.let { add(it) }
        }

        val outputConfigs = surfaces.map { OutputConfiguration(it) }
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigs,
            { cmd -> cameraHandler.post(cmd) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    startRepeatingPreview(session, previewSurface)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    _cameraState.value = CameraState.Error("Session config failed")
                }
            }
        )
        device.createCaptureSession(sessionConfig)
    }

    private fun startRepeatingPreview(session: CameraCaptureSession, surface: Surface) {
        val device = cameraDevice ?: return
        val settings = _captureSettings.value

        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)

            // *** CORE FIX: Apply yellow cast correction on every preview frame ***
            colorEngine.applyToCapture(this, currentLensId)

            // Auto exposure
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

            // Auto focus
            set(CaptureRequest.CONTROL_AF_MODE,
                if (currentLensId == ColorCorrectionEngine.LENS_MACRO)
                    CaptureRequest.CONTROL_AF_MODE_OFF   // Macro is fixed focus
                else
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )

            // Apply manual overrides if pro mode is on
            applyManualSettings(this, settings)

            // Noise reduction
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)

            // Edge enhancement
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        }

        previewRequest = builder.build()
        session.setRepeatingRequest(previewRequest!!, null, cameraHandler)
    }

    /**
     * Capture a photo — JPEG + optional RAW DNG simultaneously.
     */
    fun capturePhoto(onCaptured: (ImageCapture) -> Unit) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val settings = _captureSettings.value

        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            jpegReader?.surface?.let { addTarget(it) }
            rawReader?.surface?.let { addTarget(it) }

            // *** CORE FIX: Apply color correction to still capture ***
            colorEngine.applyToCapture(this, currentLensId)

            // Highest quality settings for still
            set(CaptureRequest.JPEG_QUALITY, 97)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
            set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
            set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)

            // Lock AE/AF before capture
            set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            set(CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_START)

            applyManualSettings(this, settings)
        }

        session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                val jpeg = jpegReader?.acquireLatestImage()
                val raw = rawReader?.acquireLatestImage()
                onCaptured(ImageCapture(jpeg, raw, result, currentLensId))
            }
        }, cameraHandler)
    }

    /**
     * Apply manual pro settings if active (ISO, shutter, WB, focus).
     */
    private fun applyManualSettings(builder: CaptureRequest.Builder, settings: CaptureSettings) {
        if (settings.isProMode) {
            // Manual ISO
            settings.iso?.let {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, it)
            }
            // Manual shutter speed (in nanoseconds)
            settings.shutterSpeedNs?.let {
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
            }
            // Manual focus distance
            settings.focusDistance?.let {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
            }
            // Manual white balance (Kelvin)
            settings.whiteBalanceKelvin?.let { kelvin ->
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                // Convert Kelvin to RGB gains
                val (r, g, b) = kelvinToRgbGains(kelvin, currentLensId)
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS,
                    android.hardware.camera2.params.RggbChannelVector(r, g, g, b))
            }
        }

        // EV compensation
        settings.evCompensation?.let {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it)
        }
    }

    /**
     * Convert color temperature in Kelvin to Camera2 RGGB channel gains.
     * Combines with yellow cast correction.
     */
    private fun kelvinToRgbGains(kelvin: Int, lensId: String): Triple<Float, Float, Float> {
        val profile = colorEngine.getProfile(lensId)
        val t = kelvin / 100f
        val r = when {
            t <= 66 -> 1.0f
            else -> (329.698727446f * (t - 60.0).pow(-0.1332047592).toFloat())
                .div(255f).coerceIn(0f, 1f)
        }
        val g = when {
            t <= 66 -> (99.4708025861f * ln(t.toDouble()) - 161.1195681661f)
                .toFloat().div(255f).coerceIn(0f, 1f)
            else -> (288.1221695283f * (t - 60.0).pow(-0.0755148492).toFloat())
                .div(255f).coerceIn(0f, 1f)
        }
        val b = when {
            t >= 66 -> 1.0f
            t <= 19 -> 0f
            else -> (138.5177312231f * ln((t - 10).toDouble()) - 305.0447927307f)
                .toFloat().div(255f).coerceIn(0f, 1f)
        }
        // Multiply with yellow cast correction gains
        return Triple(r * profile.rGain, g * profile.gEvenGain, b * profile.bGain)
    }

    fun updateSettings(block: CaptureSettings.() -> CaptureSettings) {
        _captureSettings.value = block(_captureSettings.value)
        // Settings updated — caller (ViewModel) will call startPreview() to apply them
    }

    fun switchLens(lensId: String) {
        captureSession?.close()
        cameraDevice?.close()
        openCamera(lensId)
    }

    /**
     * Find the front-facing camera ID at runtime.
     * Front camera ID is not fixed — resolved via CameraCharacteristics.
     */
    fun getFrontCameraId(): String {
        return cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        } ?: "1"  // fallback
    }

    fun close() {
        captureSession?.close()
        cameraDevice?.close()
        jpegReader?.close()
        rawReader?.close()
        cameraThread.quitSafely()
    }
}

// ── Data classes ──────────────────────────────────────────────────

data class CaptureSettings(
    val isProMode: Boolean = false,
    val is108MpMode: Boolean = false,
    val captureRaw: Boolean = false,
    val iso: Int? = null,                    // null = auto. Range: 50–3200 on HM2
    val shutterSpeedNs: Long? = null,        // null = auto. 250_000 (1/4000) to 30_000_000_000 (30s)
    val focusDistance: Float? = null,        // null = AF. 0.0 = infinity, 1/cm value
    val whiteBalanceKelvin: Int? = null,     // null = uses correction engine
    val evCompensation: Int? = null,         // -12 to +12 (steps of 1/6 EV on 720G)
    val shootMode: ShootMode = ShootMode.PHOTO,
    val flashMode: FlashMode = FlashMode.AUTO,
    val hdrEnabled: Boolean = false,
    val nightModeEnabled: Boolean = false,
    val macroAssistEnabled: Boolean = true
)

enum class ShootMode { PHOTO, VIDEO, MACRO, PORTRAIT, NIGHT, PRO, RAW, PANORAMA, SLOW_MO, TIME_LAPSE }
enum class FlashMode { OFF, AUTO, ON, TORCH }

sealed class CameraState {
    object Idle : CameraState()
    object Opening : CameraState()
    data class Open(val lensId: String) : CameraState()
    object Closed : CameraState()
    data class Error(val message: String) : CameraState()
}

data class ImageCapture(
    val jpeg: android.media.Image?,
    val raw: android.media.Image?,
    val captureResult: TotalCaptureResult,
    val lensId: String
) {
    // Extract actual ISO and shutter from capture result for EXIF + shot info
    val actualIso: Int get() =
        captureResult.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 100
    val actualShutterNs: Long get() =
        captureResult.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 33_000_000L
    val actualAperture: Float get() = when (lensId) {
        "0" -> 1.88f   // Main 108MP f/1.88
        "1" -> 2.25f   // Ultrawide f/2.25
        "2" -> 2.4f    // Macro f/2.4
        "3" -> 2.4f    // Depth f/2.4
        else -> 2.45f  // Selfie f/2.45
    }
    val focalLengthMm: Float get() = when (lensId) {
        "0" -> 4.74f   // Main ~26mm equiv
        "1" -> 1.86f   // Ultrawide ~13mm equiv
        "2" -> 4.74f   // Macro ~29mm equiv
        else -> 3.47f  // Selfie ~22mm equiv
    }
}
