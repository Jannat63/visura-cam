package com.visura.cam.camera

import android.content.Context
import android.util.Log
import android.util.Rational
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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

private const val TAG = "VisuraCam"

@Singleton
class CameraManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow<CamState>(CamState.Idle)
    val state: StateFlow<CamState> = _state

    var currentSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        private set
    var isFlashOn = false

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        selector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    ) {
        currentSelector = selector
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bindUseCases(provider, lifecycleOwner, previewView, selector)
            } catch (e: Exception) {
                Log.e(TAG, "CameraProvider failed", e)
                _state.value = CamState.Error(e.message ?: "Camera init failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindUseCases(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        previewView: PreviewView,
        selector: CameraSelector
    ) {
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(4000, 3000),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    ).build()
            )
            .setJpegQuality(97)
            .build()

        try {
            camera = provider.bindToLifecycle(owner, selector, preview, imageCapture)
            _state.value = CamState.Ready(selector == CameraSelector.DEFAULT_FRONT_CAMERA)
            Log.d(TAG, "Camera bound: front=${selector == CameraSelector.DEFAULT_FRONT_CAMERA}")
        } catch (e: Exception) {
            Log.e(TAG, "bindToLifecycle failed", e)
            _state.value = CamState.Error(e.message ?: "Bind failed")
        }
    }

    fun switchCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val newSelector = if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA)
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA
        cameraProvider?.let { bindUseCases(it, lifecycleOwner, previewView, newSelector) }
    }

    fun takePicture(
        outputOptions: ImageCapture.OutputFileOptions,
        onSaved: (ImageCapture.OutputFileResults) -> Unit,
        onError: (ImageCaptureException) -> Unit
    ) {
        imageCapture?.takePicture(outputOptions, executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onSaved(output)
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture error", exception)
                    onError(exception)
                }
            }
        ) ?: Log.e(TAG, "ImageCapture not ready")
    }

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    fun setLinearZoom(zoom: Float) {
        camera?.cameraControl?.setLinearZoom(zoom)
    }

    fun setZoomRatio(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    fun setFlash(enabled: Boolean) {
        isFlashOn = enabled
        imageCapture?.flashMode = if (enabled) ImageCapture.FLASH_MODE_ON
                                   else ImageCapture.FLASH_MODE_OFF
    }

    fun tapToFocus(x: Float, y: Float, width: Float, height: Float) {
        val meteringPoint = SurfaceOrientedMeteringPointFactory(width, height)
            .createPoint(x, y)
        val action = FocusMeteringAction.Builder(meteringPoint).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    fun getZoomState() = camera?.cameraInfo?.zoomState

    fun shutdown() {
        cameraProvider?.unbindAll()
        executor.shutdown()
    }
}

sealed class CamState {
    object Idle : CamState()
    data class Ready(val isFront: Boolean) : CamState()
    data class Error(val msg: String) : CamState()
}
