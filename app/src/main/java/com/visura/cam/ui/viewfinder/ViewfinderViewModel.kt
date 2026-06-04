package com.visura.cam.ui.viewfinder

import android.view.Surface
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import com.visura.cam.camera.*
import com.visura.cam.correction.ColorCorrectionEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ViewfinderViewModel @Inject constructor(
    private val cameraController: VisuraCameraController,
    private val colorEngine: ColorCorrectionEngine,
    private val macroController: MacroController
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewfinderUiState())
    val uiState: StateFlow<ViewfinderUiState> = _uiState.asStateFlow()

    init {
        // Observe macro distance guide
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
                        sharpness = guide.sharpnessScore
                    )
                )}
            }
        }

        // Observe stabilisation state
        viewModelScope.launch {
            macroController.stabilisationReady.collect { ready ->
                _uiState.update { it.copy(isHandSteady = ready) }
            }
        }
    }

    fun onSurfaceCreated(surface: android.view.Surface) {
        cameraController.openCamera(ColorCorrectionEngine.LENS_MAIN_108MP)
        // Wait for camera to open, then start preview
        viewModelScope.launch {
            cameraController.cameraState
                .first { it is CameraState.Open }
                .let { cameraController.startPreview(surface) }
        }
    }

    fun capture() {
        val mode = _uiState.value.shootMode
        _uiState.update { it.copy(isCapturing = true) }

        if (mode == ShootModeUI.MACRO && _uiState.value.stabilisationAssistActive) {
            macroController.captureWhenStable {
                performCapture()
            }
            _uiState.update { it.copy(isWaitingForStable = true) }
        } else {
            performCapture()
        }
    }

    private fun performCapture() {
        cameraController.capturePhoto { imageCapture ->
            viewModelScope.launch {
                _uiState.update { it.copy(isCapturing = false, isWaitingForStable = false) }
            }
        }
    }

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
            "OFF" -> "3s"
            "3s"  -> "10s"
            else  -> "OFF"
        }
        _uiState.update { it.copy(timerLabel = next) }
    }

    fun setAspectRatio() {
        val next = when (_uiState.value.aspectRatioLabel) {
            "4:3"  -> "16:9"
            "16:9" -> "1:1"
            "1:1"  -> "Full"
            else   -> "4:3"
        }
        _uiState.update { it.copy(aspectRatioLabel = next) }
    }

    fun setShootMode(mode: ShootModeUI) {
        _uiState.update { it.copy(shootMode = mode) }
        // Switch physical lens if needed
        val lensId = when (mode) {
            ShootModeUI.MACRO -> ColorCorrectionEngine.LENS_MACRO
            else              -> ColorCorrectionEngine.LENS_MAIN_108MP
        }
        cameraController.switchLens(lensId)
    }

    fun setZoom(zoom: Float) {
        _uiState.update { it.copy(currentZoom = zoom) }
        // 0.6x = ultrawide lens, others = main with digital crop
        if (zoom == 0.6f) {
            cameraController.switchLens(ColorCorrectionEngine.LENS_ULTRAWIDE)
        } else {
            cameraController.switchLens(ColorCorrectionEngine.LENS_MAIN_108MP)
        }
    }

    fun toggleColorCorrection() {
        val newState = !_uiState.value.colorCorrectionActive
        _uiState.update { it.copy(colorCorrectionActive = newState) }
        colorEngine.toggleCorrection(cameraController.currentLensId, newState)
    }

    fun setISO(iso: Int) {
        _uiState.update { state ->
            state.copy(proSettings = state.proSettings.copy(isoValue = iso))
        }
        cameraController.updateSettings { copy(iso = iso, isProMode = true) }
    }

    fun setShutter(shutterNs: Long) {
        _uiState.update { state ->
            state.copy(proSettings = state.proSettings.copy(shutterNs = shutterNs))
        }
        cameraController.updateSettings { copy(shutterSpeedNs = shutterNs, isProMode = true) }
    }

    fun setEV(ev: Int) {
        _uiState.update { state ->
            state.copy(proSettings = state.proSettings.copy(evValue = ev))
        }
        cameraController.updateSettings { copy(evCompensation = ev) }
    }

    fun setWhiteBalance(kelvin: Int) {
        _uiState.update { state ->
            state.copy(proSettings = state.proSettings.copy(wbKelvin = kelvin))
        }
        cameraController.updateSettings { copy(whiteBalanceKelvin = kelvin, isProMode = true) }
    }

    fun setFocusDistance(distance: Float) {
        _uiState.update { state ->
            state.copy(proSettings = state.proSettings.copy(focusDistance = distance))
        }
        cameraController.updateSettings { copy(focusDistance = distance, isProMode = true) }
    }

    fun flipCamera() {
        val frontId = cameraController.getFrontCameraId()
        val isFront = cameraController.currentLensId == frontId
        cameraController.switchLens(
            if (isFront) ColorCorrectionEngine.LENS_MAIN_108MP else frontId
        )
    }

    fun openGallery() { /* navigate to gallery screen */ }
    fun openSettings() { /* navigate to settings screen */ }

    override fun onCleared() {
        super.onCleared()
        cameraController.close()
    }
}
