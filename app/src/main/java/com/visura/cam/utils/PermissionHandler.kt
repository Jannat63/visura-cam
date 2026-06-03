package com.visura.cam.utils

import android.Manifest
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.*
import com.visura.cam.ui.viewfinder.VisuraColors

/**
 * PermissionHandler — Manages all runtime permissions for Visura Cam.
 *
 * Required permissions:
 *   - CAMERA               → take photos / video
 *   - RECORD_AUDIO         → video recording
 *   - READ_MEDIA_IMAGES    → gallery access (Android 13+)
 *   - READ_EXTERNAL_STORAGE → gallery access (Android 10–12)
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WithCameraPermission(
    content: @Composable () -> Unit
) {
    val permissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    val state = rememberMultiplePermissionsState(permissions)

    when {
        state.allPermissionsGranted -> content()
        else -> PermissionRationale(
            onRequest = { state.launchMultiplePermissionRequest() },
            deniedPermissions = state.revokedPermissions.map { it.permission }
        )
    }
}

@Composable
private fun PermissionRationale(
    onRequest: () -> Unit,
    deniedPermissions: List<String>
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VisuraColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .background(VisuraColors.Surface, RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Permissions needed",
                color = VisuraColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Visura Cam needs camera, microphone and storage access to work.",
                color = VisuraColors.TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            if (deniedPermissions.isNotEmpty()) {
                Text(
                    text = "If you denied permissions, go to:\nSettings → Apps → Visura Cam → Permissions",
                    color = VisuraColors.TextTertiary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = VisuraColors.Accent),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Grant permissions",
                    color = VisuraColors.Background,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
