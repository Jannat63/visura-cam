package com.visura.cam.ui.viewfinder

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * CameraPreviewSurface — Native SurfaceView for Camera2 preview output.
 *
 * Camera2 requires a real Android Surface (not a Compose Canvas).
 * We embed a SurfaceView via AndroidView interop.
 *
 * The surface is passed to VisuraCameraController.startPreview()
 * once it's ready (surfaceCreated callback).
 */
@Composable
fun CameraPreviewSurface(
    modifier: Modifier = Modifier,
    onSurfaceCreated: (android.view.Surface) -> Unit
) {
    val surfaceView = remember { SurfaceViewHolder() }

    DisposableEffect(Unit) {
        onDispose {
            surfaceView.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceCreated(holder.surface)
                    }
                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                    ) { /* handled by Camera2 session */ }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { }
                })
                surfaceView.view = this
            }
        }
    )
}

private class SurfaceViewHolder {
    var view: SurfaceView? = null
    fun release() { view = null }
}
