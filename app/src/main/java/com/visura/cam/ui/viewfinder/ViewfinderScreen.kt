package com.visura.cam.ui.viewfinder

import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.visura.cam.camera.CamState
import com.visura.cam.camera.FlashSetting
import kotlinx.coroutines.delay

// ── Color palette ─────────────────────────────────────────────
object VC {
    val Bg       = Color(0xFF0A0A0A)
    val Surface  = Color(0xFF181818)
    val Accent   = Color(0xFFB8F026)
    val White    = Color(0xFFFFFFFF)
    val Grey     = Color(0xFFAAAAAA)
    val DarkGrey = Color(0xFF444444)
    val Glass    = Color(0xCC000000)
    val Red      = Color(0xFFE53935)
    val Green    = Color(0xFF4CAF50)
    val Yellow   = Color(0xFFFFB300)
}

// ── State & Enums ──────────────────────────────────────────────
enum class ShootMode(val label: String) {
    NIGHT("NIGHT"), PORTRAIT("PORTRAIT"), PHOTO("PHOTO"),
    VIDEO("VIDEO"), PRO("PRO"), MACRO("MACRO")
}

data class VfState(
    val mode: ShootMode = ShootMode.PHOTO,
    val flash: FlashSetting = FlashSetting.AUTO,
    val zoom: Float = 1.0f,
    val wbFixActive: Boolean = true,
    val gridEnabled: Boolean = false,
    val timerLabel: String = "OFF",
    val aspectRatio: String = "4:3",
    val isCapturing: Boolean = false,
    val isProcessing: Boolean = false,
    val showSettings: Boolean = false,
    val saveLocation: Boolean = false,
    val shutterSound: Boolean = true
)

// ── Main Screen ────────────────────────────────────────────────
@Composable
fun ViewfinderScreen(vm: ViewfinderViewModel = hiltViewModel()) {
    val ctx       = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val state     by vm.uiState.collectAsState()
    val camState  by vm.camState.collectAsState()
    val shotInfo  by vm.lastShotInfo.collectAsState()
    val lastUri   by vm.lastPhotoUri.collectAsState()

    val zoomLiveData = vm.cameraManager.getZoomState()
    val zoomStateValue by (zoomLiveData?.observeAsState() ?: remember { mutableStateOf(null) })

    val previewView = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(Unit) {
        vm.startCamera(lifecycle, previewView)
    }

    val maxZoom = (camState as? CamState.Ready)?.maxZoom ?: 10f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VC.Bg)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    vm.onPinchZoom(zoom)
                }
            }
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        if (state.gridEnabled) {
            GridOverlay(modifier = Modifier.fillMaxSize())
        }

        TopBar(
            state    = state,
            vm       = vm,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )

        WbBadge(
            active   = state.wbFixActive,
            onToggle = vm::toggleWbFix,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 60.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            ZoomBar(
                current  = state.zoom,
                maxZoom  = maxZoom,
                onChange = vm::setZoom,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 12.dp)
            )

            ModeRow(
                selected = state.mode,
                onSelect = vm::setMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GalleryThumb(
                    uri      = lastUri,
                    onClick  = { vm.openGallery(ctx) },
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(10.dp))
                )

                ShutterBtn(
                    isCapturing = state.isCapturing,
                    isVideo     = state.mode == ShootMode.VIDEO,
                    onClick     = { vm.capture(ctx) },
                    modifier    = Modifier.size(80.dp)
                )

                FlipBtn(
                    modifier = Modifier.size(60.dp),
                    onClick  = { vm.flipCamera(lifecycle, previewView) }
                )
            }
        }

        if (shotInfo != null) {
            ShotInfoCard(
                info      = shotInfo!!,
                onDismiss = vm::dismissShotInfo,
                modifier  = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 200.dp)
                    .padding(horizontal = 12.dp)
            )
        }

        if (state.isProcessing) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(VC.Glass)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        color    = VC.Accent,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(text = "AI Rendering…", color = VC.White, fontSize = 13.sp)
                }
            }
        }

        if (state.showSettings) {
            SettingsSheet(
                state    = state,
                vm       = vm,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        if (camState is CamState.Error) {
            val err = camState as CamState.Error
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VC.Bg.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(text = "Camera Error", color = VC.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text(text = err.msg, color = VC.Grey, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Button(
                        onClick = { vm.startCamera(lifecycle, previewView) },
                        colors  = ButtonDefaults.buttonColors(containerColor = VC.Accent)
                    ) {
                        Text(text = "Retry", color = VC.Bg, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ── Top Bar ────────────────────────────────────────────────────
@Composable
fun TopBar(state: VfState, vm: ViewfinderViewModel, modifier: Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = vm::toggleFlash) {
            val icon = when (state.flash) {
                FlashSetting.OFF   -> Icons.Default.FlashOff
                FlashSetting.AUTO  -> Icons.Default.FlashAuto
                FlashSetting.ON    -> Icons.Default.FlashOn
                FlashSetting.TORCH -> Icons.Default.Lightbulb
            }
            val tint = if (state.flash == FlashSetting.OFF) VC.Grey else VC.Accent
            Icon(
                imageVector        = icon,
                contentDescription = "Flash",
                tint               = tint,
                modifier           = Modifier.size(26.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(text = state.timerLabel, onClick = vm::toggleTimer)
            Chip(text = state.aspectRatio, onClick = vm::cycleAspectRatio)
        }

        IconButton(onClick = vm::toggleSettings) {
            Icon(
                imageVector        = Icons.Default.Settings,
                contentDescription = "Settings",
                tint               = VC.Grey,
                modifier           = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
fun Chip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(VC.Glass)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 5.dp)
    ) {
        Text(text = text, color = VC.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun WbBadge(active: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(VC.Glass)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) VC.Green else VC.Yellow)
        )
        Text(
            text       = if (active) "WB Fix" else "WB Off",
            color      = if (active) VC.Green else VC.Yellow,
            fontSize   = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ZoomBar(current: Float, maxZoom: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    val levels = mutableListOf(Pair(1.0f, "1×"))
    if (maxZoom >= 2f)  levels.add(Pair(2.0f,  "2×"))
    if (maxZoom >= 5f)  levels.add(Pair(5.0f,  "5×"))
    if (maxZoom >= 10f) levels.add(Pair(10.0f, "10×"))
    if (maxZoom >= 30f) levels.add(Pair(30.0f, "30×"))

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(VC.Glass)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        levels.forEach { pair ->
            val value = pair.first
            val label = pair.second
            val selected = kotlin.math.abs(current - value) < 0.15f
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) VC.Accent else Color.Transparent)
                    .clickable { onChange(value) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text       = label,
                    color      = if (selected) VC.Bg else VC.White,
                    fontSize   = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun ModeRow(selected: ShootMode, onSelect: (ShootMode) -> Unit, modifier: Modifier) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        items(ShootMode.entries) { mode ->
            Column(
                modifier = Modifier.clickable { onSelect(mode) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text       = mode.label,
                    color      = if (mode == selected) VC.Accent else VC.Grey,
                    fontSize   = 13.sp,
                    fontWeight = if (mode == selected) FontWeight.Bold else FontWeight.Normal
                )
                if (mode == selected) {
                    Box(
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(VC.Accent)
                    )
                }
            }
        }
    }
}

@Composable
fun ShutterBtn(isCapturing: Boolean, isVideo: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .border(width = 3.dp, color = VC.White, shape = CircleShape)
            .clickable(enabled = !isCapturing, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isCapturing) {
            CircularProgressIndicator(
                color       = VC.Accent,
                modifier    = Modifier.size(48.dp),
                strokeWidth = 3.dp
            )
        } else {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(if (isVideo) VC.Red else VC.White)
            )
        }
    }
}

@Composable
fun FlipBtn(modifier: Modifier, onClick: () -> Unit) {
    IconButton(
        onClick  = onClick,
        modifier = modifier
    ) {
        Icon(
            imageVector        = Icons.Default.Cameraswitch,
            contentDescription = "Switch camera",
            tint               = VC.White,
            modifier           = Modifier.size(34.dp)
        )
    }
}

@Composable
fun GalleryThumb(uri: Uri?, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier         = modifier
            .background(VC.Surface, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (uri != null) {
            AsyncImage(
                model              = uri,
                contentDescription = "Last photo",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Icon(
                imageVector        = Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint               = VC.DarkGrey,
                modifier           = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
fun GridOverlay(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val color = Color.White.copy(alpha = 0.22f)
        val w = size.width
        val h = size.height
        drawLine(color = color, start = Offset(w / 3f, 0f), end = Offset(w / 3f, h), strokeWidth = 1f)
        drawLine(color = color, start = Offset(w * 2f / 3f, 0f), end = Offset(w * 2f / 3f, h), strokeWidth = 1f)
        drawLine(color = color, start = Offset(0f, h / 3f), end = Offset(w, h / 3f), strokeWidth = 1f)
        drawLine(color = color, start = Offset(0f, h * 2f / 3f), end = Offset(w, h * 2f / 3f), strokeWidth = 1f)
    }
}

@Composable
fun ShotInfoCard(info: Map<String, String>, onDismiss: () -> Unit, modifier: Modifier) {
    LaunchedEffect(info) {
        delay(5000)
        onDismiss()
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(VC.Glass)
            .border(width = 0.5.dp, color = VC.Accent.copy(alpha = 0.4f), shape = RoundedCornerShape(14.dp))
            .clickable(onClick = onDismiss)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "AJ CAM", color = VC.Accent, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(text = "Ahsan Jannat", color = VC.Grey, fontSize = 11.sp)
        }
        Divider(color = VC.Accent.copy(alpha = 0.2f), thickness = 0.5.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val iso = info["ISO"]
            val shutter = info["Shutter"]
            val aperture = info["f/"]
            if (iso != null) ExpBlock(label = "ISO", value = iso)
            if (shutter != null) ExpBlock(label = "SHUTTER", value = shutter)
            if (aperture != null) ExpBlock(label = "f/", value = aperture)
        }
        Divider(color = Color.White.copy(alpha = 0.06f), thickness = 0.5.dp)
        val lens = info["Lens"]
        val scene = info["Scene"]
        val render = info["Render"]
        if (lens != null) InfoRow(icon = "🔭", label = "Lens", value = lens)
        if (scene != null) InfoRow(icon = "🎨", label = "Scene", value = scene)
        if (render != null) InfoRow(icon = "✨", label = "Render", value = render)
        Text(
            text     = "Tap to dismiss",
            color    = VC.DarkGrey,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
private fun ExpBlock(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = VC.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(text = label, color = VC.Grey, fontSize = 10.sp)
    }
}

@Composable
private fun InfoRow(icon: String, label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 13.sp)
        Text(text = label, color = VC.DarkGrey, fontSize = 11.sp, modifier = Modifier.width(50.dp))
        Text(text = value, color = VC.Grey, fontSize = 11.sp)
    }
}

@Composable
fun SettingsSheet(state: VfState, vm: ViewfinderViewModel, modifier: Modifier) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = vm::toggleSettings)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(VC.Surface)
                .align(Alignment.BottomCenter)
                .clickable(enabled = false) {}
                .padding(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(VC.DarkGrey)
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Settings", color = VC.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(12.dp))
            SettingRow(label = "Grid lines",    value = state.gridEnabled,  onToggle = vm::toggleGrid)
            SettingRow(label = "AI WB Fix",     value = state.wbFixActive,  onToggle = vm::toggleWbFix)
            SettingRow(label = "Location tag",  value = state.saveLocation, onToggle = vm::toggleLocation)
            SettingRow(label = "Shutter sound", value = state.shutterSound, onToggle = vm::toggleShutterSound)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text     = "AJ Cam v1.0 · Ahsan Jannat",
                color    = VC.DarkGrey,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun SettingRow(label: String, value: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = VC.White, fontSize = 14.sp)
        Switch(
            checked         = value,
            onCheckedChange = { onToggle() },
            colors          = SwitchDefaults.colors(
                checkedThumbColor = VC.Bg,
                checkedTrackColor = VC.Accent
            )
        )
    }
}
