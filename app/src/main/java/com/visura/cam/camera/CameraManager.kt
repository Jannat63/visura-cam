package com.visura.cam.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    private var savedOwner: LifecycleOwner? = null
    private var savedPreview: PreviewView? = null

    private val _state = MutableStateFlow<CamState>(CamState.Idle)
    val state: StateFlow<CamState> = _state

    var currentSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        private set

    private var torchEnabled = false

    fun startCamera(
        owner: LifecycleOwner,
        previewView: PreviewView,
        selector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    ) {
        savedOwner = owner
        savedPreview = previewView
        currentSelector = selector

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
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
            .build()

        try {
            camera = p.bindToLifecycle(owner, selector, preview, imgCapture!!)
            currentSelector = selector
            val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
            Log.d(TAG, "Camera bound. Max zoom: ${maxZoom}x")
            _state.value = CamState.Ready(
                isFront = selector == CameraSelector.DEFAULT_FRONT_CAMERA,
                maxZoom = maxZoom
            )
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed", e)
            _state.value = CamState.Error(e.message ?: "Bind failed")
        }
    }

    fun switchCamera(owner: LifecycleOwner, previewView: PreviewView) {
        savedOwner = owner
        savedPreview = previewView
        val next = if (currentSelector == CameraSelector.DEFAULT_BACK_CAMERA)
            CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA
        provider?.let { bind(it, owner, previewView, next) }
    }

    fun takePicture(
        options: ImageCapture.OutputFileOptions,
        onSaved: (ImageCapture.OutputFileResults) -> Unit,
        onError: (ImageCaptureException) -> Unit
    ) {
        imgCapture?.takePicture(options, executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(out: ImageCapture.OutputFileResults) = onSaved(out)
                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "Capture error", e)
                    onError(e)
                }
            }
        ) ?: Log.e(TAG, "ImageCapture null - camera not ready")
    }

    fun setFlash(mode: FlashSetting) {
        when (mode) {
            FlashSetting.OFF -> {
                imgCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
                camera?.cameraControl?.enableTorch(false)
                torchEnabled = false
            }
            FlashSetting.AUTO -> {
                imgCapture?.flashMode = ImageCapture.FLASH_MODE_AUTO
                camera?.cameraControl?.enableTorch(false)
                torchEnabled = false
            }
            FlashSetting.ON -> {
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

    fun setZoomRatio(ratio: Float) {
        val maxZoom = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10f
        val clamped = ratio.coerceIn(1f, maxZoom)
        camera?.cameraControl?.setZoomRatio(clamped)
    }

    fun getMaxZoom(): Float =
        camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10f

    fun tapFocus(x: Float, y: Float, w: Float, h: Float) {
        val factory = SurfaceOrientedMeteringPointFactory(w, h)
        val pt = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(pt)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
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
