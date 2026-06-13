package com.visura.cam.utils

import android.Manifest
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.*

// Colours defined inline — no cross-package dependency
private val BG     = Color(0xFF0A0A0A)
private val ACCENT = Color(0xFFB8F026)
private val WHITE  = Color(0xFFFFFFFF)
private val GREY   = Color(0xFFAAAAAA)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WithCameraPermission(content: @Composable () -> Unit) {
    val perms = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val state = rememberMultiplePermissionsState(perms)
    if (state.allPermissionsGranted) content()
    else PermissionUI { state.launchMultiplePermissionRequest() }
}

@Composable
private fun PermissionUI(onRequest: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(BG),
        Alignment.Center
    ) {
        Column(
            Modifier.padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                Modifier.size(80.dp).clip(CircleShape).background(ACCENT),
                Alignment.Center
            ) {
                Text("AJ", color = BG, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Text("AJ Cam", color = WHITE, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Text("by Ahsan Jannat", color = ACCENT, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Camera, microphone and storage\npermissions are needed to continue.",
                color = GREY, fontSize = 14.sp,
                textAlign = TextAlign.Center, lineHeight = 22.sp
            )
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = ACCENT),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Grant Permissions", color = BG,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}
