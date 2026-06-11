package com.visura.cam.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AJCam"

@Singleton
class CameraManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imgCapture: ImageCapture? = null
    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // Store references for re-binding after switches
    private var savedOwner: LifecycleOwner? = null
    private var savedPreview: PreviewView? = null

    private val _state = MutableStateFlow<CamState>(CamState.Idle)
    val state: StateFlow<CamState> = _state

    var currentSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        private set

    // Fix 4: Flash state
    private var torchEnabled = false

    fun startCamera(
        owner: LifecycleOwner,
        previewView: PreviewView,
        selector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    ) {
        savedOwner = owner
        savedPreview = previewView
        currentSelector = selector

        ProcessCameraProvider.getInstance(context).addListener({
            try {
                val p = ProcessCameraProvider.getInstance(context).get()
                provider = p
                bind(p, owner, previewView, selector)
            } catch (e: Exception) {
                Log.e(TAG, "Provider failed", e)
                _state.value = CamState.Error(e.message ?: "Init failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(
        p: ProcessCameraProvider,
        owner: LifecycleOwner,
        previewView: PreviewView,
        selector: CameraSelector
    ) {
        p.unbindAll()

        val preview = Preview.Builder().build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imgCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(97)
            // Fix 7: Set target rotation so images save correctly
            .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)
            .build()

        try {
            camera = p.bindToLifecycle(owner, selector, preview, imgCapture!!)
            currentSelector = selector

            // Fix 2: Log actual zoom range for debugging
            val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
            Log.d(TAG, "Camera bound. Max zoom: ${maxZoom}x, front=${selector == CameraSelector.DEFAULT_FRONT_CAMERA}")

            _state.value = CamState.Ready(
                isFront = selector == CameraSelector.DEFAULT_FRONT_CAMERA,
                maxZoom = maxZoom
            )
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed", e)
            _state.value = CamState.Error(e.message ?: "Bind failed")
        }
    }

    // Fix 3: Camera switch uses stored references
    fun switchCamera() {
        val owner = savedOwner ?: return
        val preview = savedPreview ?: return
        val next = if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA)
            CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA
        provider?.let { bind(it, owner, preview, next) }
    }

    // Overload for explicit switch from UI
    fun switchCamera(owner: LifecycleOwner, previewView: PreviewView) {
        savedOwner = owner
        savedPreview = previewView
        switchCamera()
    }

    fun takePicture(
        options: ImageCapture.OutputFileOptions,
        onSaved: (ImageCapture.OutputFileResults) -> Unit,
        onError: (ImageCaptureException) -> Unit
    ) {
        imgCapture?.takePicture(options, executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(out: ImageCapture.OutputFileResults) = onSaved(out)
                override fun onError(e: ImageCaptureException) { Log.e(TAG, "Capture error", e); onError(e) }
            }
        ) ?: Log.e(TAG, "ImageCapture null - camera not ready")
    }

    // Fix 4: Proper flash + torch
    fun setFlash(mode: FlashSetting) {
        when (mode) {
            FlashSetting.OFF   -> {
                imgCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
                camera?.cameraControl?.enableTorch(false)
                torchEnabled = false
            }
            FlashSetting.AUTO  -> {
                imgCapture?.flashMode = ImageCapture.FLASH_MODE_AUTO
                camera?.cameraControl?.enableTorch(false)
                torchEnabled = false
            }
            FlashSetting.ON    -> {
                imgCapture?.flashMode = ImageCapture.FLASH_MODE_ON
                camera?.cameraControl?.enableTorch(false)
                torchEnabled = false
            }
            FlashSetting.TORCH -> {
                imgCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
                camera?.cameraControl?.enableTorch(true)
                torchEnabled = true
            }
        }
    }

    // Fix 2: Proper zoom using CameraX zoom ratio
    fun setZoomRatio(ratio: Float) {
        val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10f
        val clamped = ratio.coerceIn(1f, maxZoom)
        camera?.cameraControl?.setZoomRatio(clamped)
        Log.d(TAG, "Zoom set: ${clamped}x (max: ${maxZoom}x)")
    }

    fun getMaxZoom(): Float =
        camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10f

    fun tapFocus(x: Float, y: Float, w: Float, h: Float) {
        val factory = SurfaceOrientedMeteringPointFactory(w, h)
        val pt = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(pt)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    fun getZoomState() = camera?.cameraInfo?.zoomState

    fun shutdown() {
        if (torchEnabled) camera?.cameraControl?.enableTorch(false)
        provider?.unbindAll()
        executor.shutdown()
    }
}

enum class FlashSetting { OFF, AUTO, ON, TORCH }

sealed class CamState {
    object Idle : CamState()
    data class Ready(val isFront: Boolean, val maxZoom: Float = 10f) : CamState()
    data class Error(val msg: String) : CamState()
}
