package com.visura.cam.ui.viewfinder

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.visura.cam.camera.*
import com.visura.cam.correction.ColorCorrectionEngine
import com.visura.cam.utils.ImageSaver
import com.visura.cam.utils.ProRenderEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ViewfinderViewModel @Inject constructor(
    private val cameraController: VisuraCameraController,
    private val colorEngine: ColorCorrectionEngine,
    private val macroController: MacroController,
    private val imageSaver: ImageSaver
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewfinderUiState())
    val uiState: StateFlow<ViewfinderUiState> = _uiState.asStateFlow()

    // Last shot info for overlay display
    private val _lastShotInfo = MutableStateFlow<ProRenderEngine.ShotInfo?>(null)
    val lastShotInfo: StateFlow<ProRenderEngine.ShotInfo?> = _lastShotInfo.asStateFlow()

    private var lastPreviewSurface: Surface? = null
    private var currentScene: String = "general"

    init {
        // Observe macro distance
        viewModelScope.launch {
            macroController.distanceGuide.collect { guide ->
                _uiState.update { it.copy(
                    macroDistanceState = MacroDistanceState(
                        status = when (guide.status) {
                            MacroController.DistanceStatus.SWEET_SPOT -> MacroStatus.SWEET_SPOT
                            MacroController.DistanceStatus.TOO_CLOSE  -> MacroStatus.TOO_CLOSE
                            MacroController.DistanceStatus.TOO_FAR    -> MacroStatus.TOO_FAR
                            else -> MacroStatus.UNKNOWN
                        },
                        distanceCm = guide.estimatedDistanceCm,
                        sharpness  = guide.sharpnessScore
                    )
                )}
            }
        }

        // Observe stabilisation
        viewModelScope.launch {
            macroController.stabilisationReady.collect { ready ->
                _uiState.update { it.copy(isHandSteady = ready) }
            }
        }

        // Auto-restart preview when lens opens
        viewModelScope.launch {
            cameraController.cameraState.collect { state ->
                if (state is CameraState.Open) {
                    lastPreviewSurface?.let { cameraController.startPreview(it) }
                }
            }
        }
    }

    fun onSurfaceCreated(surface: android.view.Surface) {
        lastPreviewSurface = surface
        // Permission already granted by WithCameraPermission in MainActivity
        @Suppress("MissingPermission")
        cameraController.openCamera(ColorCorrectionEngine.LENS_MAIN_108MP)
    }

    fun capture() {
        val mode = _uiState.value.shootMode
        _uiState.update { it.copy(isCapturing = true) }
        if (mode == ShootModeUI.MACRO && _uiState.value.stabilisationAssistActive) {
            macroController.captureWhenStable { performCapture() }
            _uiState.update { it.copy(isWaitingForStable = true) }
        } else {
            performCapture()
        }
    }

    private fun performCapture() {
        currentScene = when (_uiState.value.shootMode) {
            ShootModeUI.MACRO     -> "macro"
            ShootModeUI.PORTRAIT  -> "portrait"
            ShootModeUI.NIGHT     -> "night"
            else                  -> "general"
        }

        cameraController.capturePhoto { imageCapture ->
            viewModelScope.launch {

                // Build ShotInfo from actual capture result
                val profile = colorEngine.getProfile(imageCapture.lensId)
                val shotInfo = ProRenderEngine.ShotInfo(
                    lensId        = imageCapture.lensId,
                    scene         = currentScene,
                    iso           = imageCapture.actualIso,
                    shutterSpeedNs = imageCapture.actualShutterNs,
                    aperture      = imageCapture.actualAperture,
                    focalLengthMm = imageCapture.focalLengthMm,
                    rGain         = profile.rGain,
                    gGain         = profile.gEvenGain,
                    bGain         = profile.bGain
                )

                // Save with full professional pipeline
                imageCapture.jpeg?.let { jpeg ->
                    imageSaver.saveJpeg(jpeg, imageCapture.lensId, shotInfo)
                }
                imageCapture.raw?.let { raw ->
                    imageSaver.saveRawDng(raw, imageCapture.lensId)
                }

                // Show shot info overlay
                _lastShotInfo.value = shotInfo

                _uiState.update { it.copy(
                    isCapturing = false,
                    isWaitingForStable = false
                )}
            }
        }
    }

    fun dismissShotInfo() { _lastShotInfo.value = null }

    fun toggleFlash() {
        val next = when (_uiState.value.flashMode) {
            FlashModeUI.AUTO  -> FlashModeUI.ON
            FlashModeUI.ON    -> FlashModeUI.OFF
            FlashModeUI.OFF   -> FlashModeUI.TORCH
            FlashModeUI.TORCH -> FlashModeUI.AUTO
        }
        _uiState.update { it.copy(flashMode = next) }
    }

    fun toggleTimer() {
        val next = when (_uiState.value.timerLabel) {
            "OFF" -> "3s"; "3s" -> "10s"; else -> "OFF"
        }
        _uiState.update { it.copy(timerLabel = next) }
    }

    fun setAspectRatio() {
        val next = when (_uiState.value.aspectRatioLabel) {
            "4:3" -> "16:9"; "16:9" -> "1:1"; "1:1" -> "Full"; else -> "4:3"
        }
        _uiState.update { it.copy(aspectRatioLabel = next) }
    }

    fun setShootMode(mode: ShootModeUI) {
        _uiState.update { it.copy(shootMode = mode) }
        val lensId = when (mode) {
            ShootModeUI.MACRO -> ColorCorrectionEngine.LENS_MACRO
            else              -> ColorCorrectionEngine.LENS_MAIN_108MP
        }
        switchLens(lensId)
    }

    fun setZoom(zoom: Float) {
        _uiState.update { it.copy(currentZoom = zoom) }
        switchLens(
            if (zoom == 0.6f) ColorCorrectionEngine.LENS_ULTRAWIDE
            else ColorCorrectionEngine.LENS_MAIN_108MP
        )
    }

    private fun switchLens(lensId: String) {
        cameraController.switchLens(lensId)
        // Preview restarts automatically via cameraState watcher in init{}
    }

    fun toggleColorCorrection() {
        val newState = !_uiState.value.colorCorrectionActive
        _uiState.update { it.copy(colorCorrectionActive = newState) }
        colorEngine.toggleCorrection(cameraController.currentLensId, newState)
        lastPreviewSurface?.let { cameraController.startPreview(it) }
    }

    fun setISO(iso: Int) {
        _uiState.update { s -> s.copy(proSettings = s.proSettings.copy(isoValue = iso)) }
        cameraController.updateSettings { copy(iso = iso, isProMode = true) }
        lastPreviewSurface?.let { cameraController.startPreview(it) }
    }

    fun setShutter(shutterNs: Long) {
        _uiState.update { s -> s.copy(proSettings = s.proSettings.copy(shutterNs = shutterNs)) }
        cameraController.updateSettings { copy(shutterSpeedNs = shutterNs, isProMode = true) }
        lastPreviewSurface?.let { cameraController.startPreview(it) }
    }

    fun setEV(ev: Int) {
        _uiState.update { s -> s.copy(proSettings = s.proSettings.copy(evValue = ev)) }
        cameraController.updateSettings { copy(evCompensation = ev) }
        lastPreviewSurface?.let { cameraController.startPreview(it) }
    }

    fun setWhiteBalance(kelvin: Int) {
        _uiState.update { s -> s.copy(proSettings = s.proSettings.copy(wbKelvin = kelvin)) }
        cameraController.updateSettings { copy(whiteBalanceKelvin = kelvin, isProMode = true) }
        lastPreviewSurface?.let { cameraController.startPreview(it) }
    }

    fun setFocusDistance(distance: Float) {
        _uiState.update { s -> s.copy(proSettings = s.proSettings.copy(focusDistance = distance)) }
        cameraController.updateSettings { copy(focusDistance = distance, isProMode = true) }
        lastPreviewSurface?.let { cameraController.startPreview(it) }
    }

    fun flipCamera() {
        val frontId = cameraController.getFrontCameraId()
        val isFront = cameraController.currentLensId == frontId
        switchLens(if (isFront) ColorCorrectionEngine.LENS_MAIN_108MP else frontId)
    }

    fun openGallery()  { /* navigate */ }
    fun openSettings() { /* navigate */ }

    override fun onCleared() {
        super.onCleared()
        cameraController.close()
    }
}
