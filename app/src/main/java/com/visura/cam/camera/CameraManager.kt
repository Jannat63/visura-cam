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

private const val TAG = "VisuraCam"

@Singleton
class CameraManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imgCapture: ImageCapture? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow<CamState>(CamState.Idle)
    val state: StateFlow<CamState> = _state

    var currentSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        private set

    fun startCamera(owner: LifecycleOwner, previewView: PreviewView,
                    selector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA) {
        currentSelector = selector
        ProcessCameraProvider.getInstance(context).also { future ->
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
    }

    private fun bind(p: ProcessCameraProvider, owner: LifecycleOwner,
                     previewView: PreviewView, selector: CameraSelector) {
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
            _state.value = CamState.Ready(selector == CameraSelector.DEFAULT_FRONT_CAMERA)
            Log.d(TAG, "Bound: front=${selector == CameraSelector.DEFAULT_FRONT_CAMERA}")
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed", e)
            _state.value = CamState.Error(e.message ?: "Bind failed")
        }
    }

    fun switchCamera(owner: LifecycleOwner, previewView: PreviewView) {
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
                override fun onError(e: ImageCaptureException) = onError(e)
            }
        ) ?: Log.e(TAG, "ImageCapture null")
    }

    fun setFlash(on: Boolean) {
        imgCapture?.flashMode = if (on) ImageCapture.FLASH_MODE_ON
                                 else ImageCapture.FLASH_MODE_OFF
    }

    fun setZoomRatio(ratio: Float) { camera?.cameraControl?.setZoomRatio(ratio) }
    fun setZoom(linear: Float)     { camera?.cameraControl?.setLinearZoom(linear) }
    fun tapFocus(x: Float, y: Float, w: Float, h: Float) {
        val pt = SurfaceOrientedMeteringPointFactory(w, h).createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
    }
    fun getZoomState() = camera?.cameraInfo?.zoomState
    fun shutdown() { provider?.unbindAll(); executor.shutdown() }
}

sealed class CamState {
    object Idle : CamState()
    data class Ready(val isFront: Boolean) : CamState()
    data class Error(val msg: String) : CamState()
}
