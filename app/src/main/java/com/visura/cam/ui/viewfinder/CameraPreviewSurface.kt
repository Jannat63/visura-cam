package com.visura.cam.ui.viewfinder

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * CameraPreviewSurface — Real Android SurfaceView for Camera2 preview.
 *
 * Camera2 requires a native Surface, not a Compose Canvas.
 * We embed SurfaceView via AndroidView Compose interop.
 *
 * Lifecycle:
 *   surfaceCreated  → surface ready → open camera
 *   surfaceDestroyed→ camera session should be closed
 */
@Composable
fun CameraPreviewSurface(
    modifier: Modifier = Modifier,
    onSurfaceCreated: (Surface) -> Unit
) {
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }

    DisposableEffect(Unit) {
        onDispose { surfaceView = null }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).also { sv ->
                surfaceView = sv
                sv.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceCreated(holder.surface)
                    }
                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                    ) { /* Camera2 handles resize via session */ }
                    override fun surfaceDestroyed(holder: SurfaceHolder) { }
                })
            }
        }
    )
}
