package com.visura.cam.utils

import android.Manifest
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.*
import com.visura.cam.ui.viewfinder.VisuraColors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WithCameraPermission(content: @Composable () -> Unit) {
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
    if (state.allPermissionsGranted) {
        content()
    } else {
        PermissionScreen(
            allDenied = state.revokedPermissions.size == permissions.size,
            onRequest  = { state.launchMultiplePermissionRequest() }
        )
    }
}

@Composable
private fun PermissionScreen(allDenied: Boolean, onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VisuraColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // App logo placeholder
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(VisuraColors.Accent),
                contentAlignment = Alignment.Center
            ) {
                Text("VC", color = VisuraColors.Background,
                    fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                text = "Visura Cam",
                color = VisuraColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "by Ahsan Jannat",
                color = VisuraColors.Accent,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (allDenied)
                    "Camera, microphone and storage permissions are needed to take photos."
                else
                    "Some permissions are missing. Please grant all permissions to continue.",
                color = VisuraColors.TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            if (!allDenied) {
                Text(
                    text = "Go to Settings → Apps → Visura Cam → Permissions",
                    color = VisuraColors.TextTertiary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = VisuraColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .height(52.dp)
            ) {
                Text(
                    text = "Grant Permissions",
                    color = VisuraColors.Background,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }
        }
    }
}
