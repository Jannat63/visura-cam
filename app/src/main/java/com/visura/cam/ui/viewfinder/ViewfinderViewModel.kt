package com.visura.cam.ui.viewfinder

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visura.cam.camera.CamState
import com.visura.cam.camera.CameraManager
import com.visura.cam.camera.FlashSetting
import com.visura.cam.correction.ColorCorrectionEngine
import com.visura.cam.utils.ImageSaver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ViewfinderViewModel @Inject constructor(
    val cameraManager: CameraManager,
    private val colorEngine: ColorCorrectionEngine,
    private val imageSaver: ImageSaver
) : ViewModel() {

    private val _uiState = MutableStateFlow(VfState())
    val uiState: StateFlow<VfState> = _uiState.asStateFlow()

    val camState: StateFlow<CamState> = cameraManager.state

    // Shot info shown after capture: map of label→value
    private val _lastShotInfo = MutableStateFlow<Map<String, String>?>(null)
    val lastShotInfo: StateFlow<Map<String, String>?> = _lastShotInfo

    private val _lastPhotoUri = MutableStateFlow<Uri?>(null)
    val lastPhotoUri: StateFlow<Uri?> = _lastPhotoUri

    private var currentLensId = ColorCorrectionEngine.LENS_MAIN_108MP

    // ── Camera ────────────────────────────────────────────────────

    fun startCamera(owner: LifecycleOwner, previewView: PreviewView) {
        cameraManager.startCamera(owner, previewView)
    }

    fun flipCamera(owner: LifecycleOwner, previewView: PreviewView) {
        cameraManager.switchCamera(owner, previewView)
        currentLensId = if (cameraManager.currentSelector ==
            androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA)
            ColorCorrectionEngine.LENS_SELFIE
        else ColorCorrectionEngine.LENS_MAIN_108MP
    }

    // ── Capture ───────────────────────────────────────────────────

    fun capture(context: Context) {
        if (_uiState.value.isCapturing) return
        _uiState.update { it.copy(isCapturing = true) }

        val filename = "AJCAM_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_DCIM}/AJCam")
        }
        val options = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .build()

        val scene = when (_uiState.value.mode) {
            ShootMode.MACRO    -> "macro"
            ShootMode.PORTRAIT -> "portrait"
            ShootMode.NIGHT    -> "night"
            ShootMode.PRO      -> "general"
            else               -> "general"
        }

        cameraManager.takePicture(
            options,
            onSaved = { result: ImageCapture.OutputFileResults ->
                val uri = result.savedUri
                Log.d("AJCam", "Captured: $uri")
                _uiState.update { it.copy(isCapturing = false, isProcessing = true) }

                viewModelScope.launch {
                    uri?.let { savedUri ->
                        _lastPhotoUri.value = savedUri

                        // Fix 7+8: Background AI processing - doesn't freeze UI
                        if (_uiState.value.wbFixActive) {
                            imageSaver.processExistingPhoto(context, savedUri, scene, currentLensId)
                        }

                        // Build shot info for overlay
                        _lastShotInfo.value = buildShotInfo(scene)
                        _uiState.update { it.copy(isProcessing = false) }
                    } ?: _uiState.update { it.copy(isProcessing = false) }
                }
            },
            onError = { e: ImageCaptureException ->
                Log.e("AJCam", "Capture failed", e)
                _uiState.update { it.copy(isCapturing = false, isProcessing = false) }
            }
        )
    }

    private fun buildShotInfo(scene: String): Map<String, String> = mapOf(
        "ISO"     to "Auto",
        "Shutter" to "Auto",
        "f/"      to when (currentLensId) {
            "0" -> "1.88"; "1" -> "2.25"; "2" -> "2.4"; else -> "2.45"
        },
        "Lens"    to when (currentLensId) {
            "0" -> "Main 108MP f/1.88"; "1" -> "Ultrawide f/2.25"
            "2" -> "Macro f/2.4 4cm";  else -> "Selfie f/2.45"
        },
        "Scene"   to scene.replaceFirstChar { it.uppercase() },
        "Render"  to "AI Pipeline · Ahsan Jannat"
    )

    fun dismissShotInfo() { _lastShotInfo.value = null }

    // ── Gallery ───────────────────────────────────────────────────

    fun openGallery(context: Context) {
        val uri = _lastPhotoUri.value
        val intent = if (uri != null)
            Intent(Intent.ACTION_VIEW, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
        else
            Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"; flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        try { context.startActivity(intent) } catch (e: Exception) {
            Log.e("AJCam", "Gallery open failed", e)
        }
    }

    // ── Settings ──────────────────────────────────────────────────

    fun toggleSettings()     = _uiState.update { it.copy(showSettings  = !it.showSettings) }
    fun toggleGrid()         = _uiState.update { it.copy(gridEnabled   = !it.gridEnabled) }
    fun toggleWbFix()        = _uiState.update { it.copy(wbFixActive   = !it.wbFixActive) }
    fun toggleLocation()     = _uiState.update { it.copy(saveLocation  = !it.saveLocation) }
    fun toggleShutterSound() = _uiState.update { it.copy(shutterSound  = !it.shutterSound) }

    // Fix 4: Flash toggle cycles through all modes
    fun toggleFlash() {
        val next = when (_uiState.value.flash) {
            FlashSetting.AUTO  -> FlashSetting.ON
            FlashSetting.ON    -> FlashSetting.OFF
            FlashSetting.OFF   -> FlashSetting.TORCH
            FlashSetting.TORCH -> FlashSetting.AUTO
        }
        _uiState.update { it.copy(flash = next) }
        cameraManager.setFlash(next)
    }

    fun toggleTimer() {
        _uiState.update {
            it.copy(timerLabel = when (it.timerLabel) { "OFF" -> "3s"; "3s" -> "10s"; else -> "OFF" })
        }
    }

    fun cycleAspectRatio() {
        _uiState.update {
            it.copy(aspectRatio = when (it.aspectRatio) { "4:3" -> "16:9"; "16:9" -> "1:1"; else -> "4:3" })
        }
    }

    fun setMode(mode: ShootMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    // Fix 2: Proper zoom using CameraX ratio
    fun setZoom(value: Float) {
        _uiState.update { it.copy(zoom = value) }
        cameraManager.setZoomRatio(value)
    }

    fun onPinchZoom(scale: Float) {
        val new = (_uiState.value.zoom * scale).coerceIn(1f, cameraManager.getMaxZoom())
        setZoom(new)
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.shutdown()
    }
}
