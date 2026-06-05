package com.visura.cam.ui.viewfinder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.visura.cam.utils.ProRenderEngine

// ── Colour Palette ─────────────────────────────────────────────
object VC {
    val Bg          = Color(0xFF0A0A0A)
    val Surface     = Color(0xFF181818)
    val Accent      = Color(0xFFB8F026)
    val AccentDark  = Color(0xFF8AB81C)
    val White       = Color(0xFFFFFFFF)
    val Grey        = Color(0xFFAAAAAA)
    val DarkGrey    = Color(0xFF555555)
    val Glass       = Color(0xCC000000)
    val GlassMid    = Color(0x88000000)
    val Red         = Color(0xFFE53935)
    val Green       = Color(0xFF4CAF50)
    val Yellow      = Color(0xFFFFB300)
}

@Composable
fun ViewfinderScreen(vm: ViewfinderViewModel = hiltViewModel()) {
    val context       = LocalContext.current
    val lifecycle     = LocalLifecycleOwner.current
    val state         by vm.uiState.collectAsState()
    val camState      by vm.camState.collectAsState()
    val lastShotInfo  by vm.lastShotInfo.collectAsState()
    val lastPhotoUri  by vm.lastPhotoUri.collectAsState()
    val zoomState     by vm.cameraManager.getZoomState()
        ?.observeAsState() ?: remember { mutableStateOf(null) }

    // PreviewView reference passed into ViewModel
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Start camera when composable is ready
    LaunchedEffect(Unit) {
        vm.startCamera(lifecycle, previewView)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VC.Bg)
            // Pinch-to-zoom
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    vm.onPinchZoom(zoom)
                }
            }
    ) {

        // ── 1. Camera Preview ─────────────────────────────────────
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
                .then(
                    if (state.gridEnabled)
                        Modifier
                    else Modifier
                )
        )

        // ── 2. Grid overlay ───────────────────────────────────────
        if (state.gridEnabled) {
            GridOverlay(modifier = Modifier.fillMaxSize())
        }

        // ── 3. Top Bar ────────────────────────────────────────────
        TopBar(
            state    = state,
            onFlash  = vm::toggleFlash,
            onTimer  = vm::toggleTimer,
            onRatio  = vm::cycleAspectRatio,
            onSettings = vm::toggleSettings,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )

        // ── 4. WB Fix badge ───────────────────────────────────────
        WbFixBadge(
            active   = state.wbFixActive,
            onToggle = vm::toggleWbFix,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 60.dp)
        )

        // ── 5. Bottom Controls ────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            // Zoom row
            ZoomBar(
                current  = state.zoom,
                onChange = vm::setZoom,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 12.dp)
            )

            // Mode selector
            ModeRow(
                selected = state.mode,
                onSelect = vm::setMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )

            // Shutter row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Last photo thumbnail
                LastPhotoThumb(
                    uri     = lastPhotoUri,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { vm.openGallery(context) }
                )

                // Shutter
                ShutterBtn(
                    mode        = state.mode,
                    isCapturing = state.isCapturing,
                    onClick     = { vm.capture(context) },
                    modifier    = Modifier.size(80.dp)
                )

                // Flip camera
                IconBtn(
                    icon    = Icons.Default.Cameraswitch,
                    size    = 60.dp,
                    onClick = { vm.flipCamera(lifecycle, previewView) }
                )
            }
        }

        // ── 6. Shot info overlay ──────────────────────────────────
        lastShotInfo?.let { info ->
            ShotInfoCard(
                info     = info,
                onDismiss = vm::dismissShotInfo,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 200.dp, start = 12.dp, end = 12.dp)
            )
        }

        // ── 7. Settings panel ─────────────────────────────────────
        if (state.showSettings) {
            SettingsSheet(
                state    = state,
                onClose  = vm::toggleSettings,
                onGrid   = vm::toggleGrid,
                vm       = vm,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // ── 8. Camera error ───────────────────────────────────────
        (camState as? CamState.Error)?.let { err ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VC.Bg.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text("Camera Error", color = VC.White,
                        fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text(err.msg, color = VC.Grey, fontSize = 13.sp,
                        textAlign = TextAlign.Center)
                    Button(onClick = { vm.startCamera(lifecycle, previewView) },
                        colors = ButtonDefaults.buttonColors(containerColor = VC.Accent)
                    ) {
                        Text("Retry", color = VC.Bg, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ── Top Bar ────────────────────────────────────────────────────

@Composable
fun TopBar(
    state: VfState,
    onFlash: () -> Unit,
    onTimer: () -> Unit,
    onRatio: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onFlash) {
            Icon(
                imageVector = when (state.flash) {
                    FlashMode.OFF   -> Icons.Default.FlashOff
                    FlashMode.AUTO  -> Icons.Default.FlashAuto
                    FlashMode.ON    -> Icons.Default.FlashOn
                    FlashMode.TORCH -> Icons.Default.Lightbulb
                },
                contentDescription = "Flash",
                tint = if (state.flash == FlashMode.ON || state.flash == FlashMode.TORCH)
                    VC.Accent else VC.Grey,
                modifier = Modifier.size(26.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(text = state.timerLabel, onClick = onTimer)
            Chip(text = state.aspectRatio, onClick = onRatio)
        }

        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, "Settings",
                tint = VC.Grey, modifier = Modifier.size(26.dp))
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
        Text(text, color = VC.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ── WB Fix Badge ──────────────────────────────────────────────

@Composable
fun WbFixBadge(active: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(VC.Glass)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(7.dp).clip(CircleShape)
                .background(if (active) VC.Green else VC.Yellow)
        )
        Text(
            text = if (active) "WB Fix" else "WB Off",
            color = if (active) VC.Green else VC.Yellow,
            fontSize = 12.sp, fontWeight = FontWeight.Medium
        )
    }
}

// ── Zoom Bar ──────────────────────────────────────────────────

@Composable
fun ZoomBar(current: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val levels = listOf(
        Pair(0.5f, "0.6"),
        Pair(1.0f, "1×"),
        Pair(2.0f, "2"),
        Pair(5.0f, "5")
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(VC.Glass)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        levels.forEach { (value, label) ->
            val sel = kotlin.math.abs(current - value) < 0.1f
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (sel) VC.Accent else Color.Transparent)
                    .clickable { onChange(value) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (sel) VC.Bg else VC.White,
                    fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// ── Mode Selector ─────────────────────────────────────────────

@Composable
fun ModeRow(selected: ShootMode, onSelect: (ShootMode) -> Unit, modifier: Modifier = Modifier) {
    val modes = listOf(ShootMode.NIGHT, ShootMode.PORTRAIT, ShootMode.PHOTO,
        ShootMode.VIDEO, ShootMode.PRO, ShootMode.MACRO)
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        items(modes) { mode ->
            val sel = mode == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(mode) }
            ) {
                Text(
                    text = mode.label,
                    color = if (sel) VC.Accent else VC.Grey,
                    fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                )
                if (sel) Box(
                    modifier = Modifier.padding(top = 3.dp)
                        .size(4.dp).clip(CircleShape).background(VC.Accent)
                )
            }
        }
    }
}

// ── Shutter Button ────────────────────────────────────────────

@Composable
fun ShutterBtn(
    mode: ShootMode,
    isCapturing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVideo = mode == ShootMode.VIDEO
    Box(
        modifier = modifier
            .clip(CircleShape)
            .border(3.dp, VC.White, CircleShape)
            .clickable(enabled = !isCapturing, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isCapturing) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = VC.Accent, strokeWidth = 3.dp
            )
        } else {
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape)
                    .background(if (isVideo) VC.Red else VC.White)
            )
        }
    }
}

// ── Icon Button ───────────────────────────────────────────────

@Composable
fun IconBtn(
    icon: ImageVector,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    tint: Color = VC.White
) {
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Icon(icon, contentDescription = null, tint = tint,
            modifier = Modifier.size(size * 0.55f))
    }
}

// ── Last Photo Thumbnail ──────────────────────────────────────

@Composable
fun LastPhotoThumb(uri: Uri?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(VC.Surface, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (uri != null) {
            AsyncImage(
                model = uri, contentDescription = "Last photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
            )
        } else {
            Icon(Icons.Default.PhotoLibrary, null,
                tint = VC.DarkGrey, modifier = Modifier.size(28.dp))
        }
    }
}

// ── Grid Overlay ──────────────────────────────────────────────

@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width; val h = size.height
        val paint = androidx.compose.ui.graphics.Paint().apply {
            color = Color.White.copy(alpha = 0.25f)
            strokeWidth = 1f
        }
        drawIntoCanvas { canvas ->
            canvas.drawLine(
                androidx.compose.ui.geometry.Offset(w / 3f, 0f),
                androidx.compose.ui.geometry.Offset(w / 3f, h), paint)
            canvas.drawLine(
                androidx.compose.ui.geometry.Offset(w * 2f / 3f, 0f),
                androidx.compose.ui.geometry.Offset(w * 2f / 3f, h), paint)
            canvas.drawLine(
                androidx.compose.ui.geometry.Offset(0f, h / 3f),
                androidx.compose.ui.geometry.Offset(w, h / 3f), paint)
            canvas.drawLine(
                androidx.compose.ui.geometry.Offset(0f, h * 2f / 3f),
                androidx.compose.ui.geometry.Offset(w, h * 2f / 3f), paint)
        }
    }
}

// ── Shot Info Card ────────────────────────────────────────────

@Composable
fun ShotInfoCard(
    info: ProRenderEngine.ShotInfo,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(info) {
        kotlinx.coroutines.delay(4000)
        onDismiss()
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(VC.Glass)
            .border(0.5.dp, VC.Accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .clickable(onClick = onDismiss)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("VISURA CAM", color = VC.Accent, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Ahsan Jannat", color = VC.Grey, fontSize = 11.sp)
        }
        Divider(color = VC.Accent.copy(alpha = 0.2f), thickness = 0.5.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ExposureBlock("ISO", info.iso.toString())
            ExposureBlock("SHUTTER", info.shutterFraction)
            ExposureBlock("f/", info.aperture.toString())
        }
        Divider(color = Color.White.copy(alpha = 0.06f), thickness = 0.5.dp)
        InfoRow("📷", "Sensor", info.sensorName)
        InfoRow("🔭", "Lens",   info.lensName)
        InfoRow("🎨", "Render", "Pro pipeline · Ahsan Jannat")
        Text("Tap to dismiss", color = VC.DarkGrey, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun ExposureBlock(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = VC.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(label, color = VC.Grey, fontSize = 10.sp, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun InfoRow(icon: String, label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 13.sp)
        Text(label, color = VC.DarkGrey, fontSize = 11.sp,
            modifier = Modifier.width(56.dp))
        Text(value, color = VC.Grey, fontSize = 11.sp)
    }
}

// ── Settings Sheet ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    state: VfState,
    onClose: () -> Unit,
    onGrid: () -> Unit,
    vm: ViewfinderViewModel,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onClose)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(VC.Surface)
                .padding(20.dp)
                .align(Alignment.BottomCenter)
                .clickable(enabled = false) {}
        ) {
            // Handle
            Box(modifier = Modifier
                .width(40.dp).height(4.dp)
                .clip(CircleShape).background(VC.DarkGrey)
                .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(16.dp))
            Text("Settings", color = VC.White, fontSize = 17.sp,
                fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))

            SettingToggle("Grid lines", state.gridEnabled, onGrid)
            SettingToggle("WB Color Fix", state.wbFixActive, vm::toggleWbFix)
            SettingToggle("Save location", state.saveLocation, vm::toggleLocation)
            SettingToggle("Shutter sound", state.shutterSound, vm::toggleShutterSound)

            Spacer(Modifier.height(12.dp))
            Text("By Ahsan Jannat · Visura Cam v1.0",
                color = VC.DarkGrey, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun SettingToggle(label: String, value: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = VC.White, fontSize = 14.sp)
        Switch(
            checked = value,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = VC.Bg,
                checkedTrackColor = VC.Accent
            )
        )
    }
}

// ── State & Enums ─────────────────────────────────────────────

enum class ShootMode(val label: String) {
    NIGHT("NIGHT"), PORTRAIT("PORTRAIT"), PHOTO("PHOTO"),
    VIDEO("VIDEO"), PRO("PRO"), MACRO("MACRO")
}

enum class FlashMode { OFF, AUTO, ON, TORCH }

data class VfState(
    val mode: ShootMode = ShootMode.PHOTO,
    val flash: FlashMode = FlashMode.AUTO,
    val zoom: Float = 1.0f,
    val wbFixActive: Boolean = true,
    val gridEnabled: Boolean = false,
    val timerLabel: String = "OFF",
    val aspectRatio: String = "4:3",
    val isCapturing: Boolean = false,
    val showSettings: Boolean = false,
    val saveLocation: Boolean = false,
    val shutterSound: Boolean = true
)
