package com.visura.cam.ui.viewfinder

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visura.cam.camera.CamState
import com.visura.cam.camera.CameraManager
import com.visura.cam.correction.ColorCorrectionEngine
import com.visura.cam.utils.ImageSaver
import com.visura.cam.utils.ProRenderEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

private const val TAG = "ViewfinderVM"

@HiltViewModel
class ViewfinderViewModel @Inject constructor(
    val cameraManager: CameraManager,
    private val colorEngine: ColorCorrectionEngine,
    private val imageSaver: ImageSaver
) : ViewModel() {

    private val _uiState = MutableStateFlow(VfState())
    val uiState: StateFlow<VfState> = _uiState.asStateFlow()

    val camState: StateFlow<CamState> = cameraManager.state

    private val _lastShotInfo = MutableStateFlow<ProRenderEngine.ShotInfo?>(null)
    val lastShotInfo: StateFlow<ProRenderEngine.ShotInfo?> = _lastShotInfo

    private val _lastPhotoUri = MutableStateFlow<Uri?>(null)
    val lastPhotoUri: StateFlow<Uri?> = _lastPhotoUri

    // ── Camera lifecycle ──────────────────────────────────────────

    fun startCamera(owner: LifecycleOwner, previewView: PreviewView) {
        cameraManager.startCamera(owner, previewView)
    }

    fun flipCamera(owner: LifecycleOwner, previewView: PreviewView) {
        cameraManager.switchCamera(owner, previewView)
    }

    // ── Capture ───────────────────────────────────────────────────

    fun capture(context: Context) {
        if (_uiState.value.isCapturing) return
        _uiState.update { it.copy(isCapturing = true) }

        val filename = "VISURA_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_DCIM}/VisuraCam")
        }
        val outputOptions = androidx.camera.core.ImageCapture.OutputFileOptions
            .Builder(context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        val scene = when (_uiState.value.mode) {
            ShootMode.MACRO    -> "macro"
            ShootMode.PORTRAIT -> "portrait"
            ShootMode.NIGHT    -> "night"
            ShootMode.PRO      -> "general"
            else               -> "general"
        }

        cameraManager.takePicture(
            outputOptions,
            onSaved = { result: androidx.camera.core.ImageCapture.OutputFileResults ->
                viewModelScope.launch {
                    val uri = result.savedUri
                    if (uri != null) {
                        Log.d(TAG, "Photo saved: $uri")

                        // Post-process the saved image
                        val profile = colorEngine.getProfile(ColorCorrectionEngine.LENS_MAIN_108MP)
                        val shotInfo = ProRenderEngine.ShotInfo(
                            lensId        = ColorCorrectionEngine.LENS_MAIN_108MP,
                            scene         = scene,
                            iso           = 100,
                            shutterSpeedNs = 33_000_000L,
                            aperture      = 1.88f,
                            focalLengthMm = 4.74f,
                            rGain         = profile.rGain,
                            gGain         = profile.gEvenGain,
                            bGain         = profile.bGain
                        )

                        // Apply pro render pipeline to saved JPEG
                        if (_uiState.value.wbFixActive) {
                            imageSaver.processExistingPhoto(context, uri, shotInfo)
                        }

                        _lastPhotoUri.value = uri
                        _lastShotInfo.value = shotInfo
                    }
                    _uiState.update { it.copy(isCapturing = false) }
                }
            },
            onError = { e: androidx.camera.core.ImageCaptureException ->
                Log.e(TAG, "Capture failed", e)
                viewModelScope.launch {
                    _uiState.update { it.copy(isCapturing = false) }
                }
            }
        )
    }

    fun dismissShotInfo() { _lastShotInfo.value = null }

    // ── Gallery ───────────────────────────────────────────────────

    fun openGallery(context: Context) {
        val uri = _lastPhotoUri.value ?: run {
            // Open system gallery
            val intent = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    // ── Settings ──────────────────────────────────────────────────

    fun toggleSettings() = _uiState.update { it.copy(showSettings = !it.showSettings) }
    fun toggleGrid()     = _uiState.update { it.copy(gridEnabled  = !it.gridEnabled) }
    fun toggleWbFix()    = _uiState.update { it.copy(wbFixActive  = !it.wbFixActive) }
    fun toggleLocation() = _uiState.update { it.copy(saveLocation = !it.saveLocation) }
    fun toggleShutterSound() = _uiState.update { it.copy(shutterSound = !it.shutterSound) }

    // ── Camera controls ───────────────────────────────────────────

    fun toggleFlash() {
        val next = when (_uiState.value.flash) {
            FlashMode.AUTO  -> FlashMode.ON
            FlashMode.ON    -> FlashMode.OFF
            FlashMode.OFF   -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.AUTO
        }
        _uiState.update { it.copy(flash = next) }
        cameraManager.setFlash(next == FlashMode.ON || next == FlashMode.TORCH)
    }

    fun toggleTimer() {
        val next = when (_uiState.value.timerLabel) {
            "OFF" -> "3s"; "3s" -> "10s"; else -> "OFF"
        }
        _uiState.update { it.copy(timerLabel = next) }
    }

    fun cycleAspectRatio() {
        val next = when (_uiState.value.aspectRatio) {
            "4:3" -> "16:9"; "16:9" -> "1:1"; else -> "4:3"
        }
        _uiState.update { it.copy(aspectRatio = next) }
    }

    fun setMode(mode: ShootMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    fun setZoom(value: Float) {
        _uiState.update { it.copy(zoom = value) }
        // CameraX zoom: 0.5=ultrawide, 1.0=1x, 2.0=2x, 5.0=5x
        cameraManager.setZoomRatio(value)
    }

    fun onPinchZoom(scale: Float) {
        val current = _uiState.value.zoom
        val newZoom = (current * scale).coerceIn(0.5f, 5.0f)
        setZoom(newZoom)
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.shutdown()
    }
}
