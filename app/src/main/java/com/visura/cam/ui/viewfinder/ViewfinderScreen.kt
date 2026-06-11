package com.visura.cam.ui.viewfinder

import android.net.Uri
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.visura.cam.camera.CamState
import com.visura.cam.camera.FlashSetting

object VC {
    val Bg         = Color(0xFF0A0A0A)
    val Surface    = Color(0xFF181818)
    val Accent     = Color(0xFFB8F026)
    val White      = Color(0xFFFFFFFF)
    val Grey       = Color(0xFFAAAAAA)
    val DarkGrey   = Color(0xFF444444)
    val Glass      = Color(0xCC000000)
    val Red        = Color(0xFFE53935)
    val Green      = Color(0xFF4CAF50)
    val Yellow     = Color(0xFFFFB300)
}

@Composable
fun ViewfinderScreen(vm: ViewfinderViewModel = hiltViewModel()) {
    val ctx       = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val state     by vm.uiState.collectAsState()
    val camState  by vm.camState.collectAsState()
    val shotInfo  by vm.lastShotInfo.collectAsState()
    val lastUri   by vm.lastPhotoUri.collectAsState()
    val zoomState by vm.cameraManager.getZoomState()?.observeAsState() ?: remember { mutableStateOf(null) }

    val previewView = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(Unit) { vm.startCamera(lifecycle, previewView) }

    // Fix 2: Get actual max zoom from camera
    val maxZoom = (camState as? CamState.Ready)?.maxZoom ?: 10f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VC.Bg)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ -> vm.onPinchZoom(zoom) }
            }
    ) {
        // Camera preview
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Grid
        if (state.gridEnabled) GridOverlay(Modifier.fillMaxSize())

        // Top bar
        TopBar(state, vm, Modifier
            .fillMaxWidth().align(Alignment.TopCenter)
            .statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp))

        // WB badge
        WbBadge(state.wbFixActive, vm::toggleWbFix,
            Modifier.align(Alignment.TopStart).statusBarsPadding()
                .padding(start = 12.dp, top = 60.dp))

        // Bottom controls
        Column(
            modifier = Modifier.fillMaxWidth()
                .align(Alignment.BottomCenter).navigationBarsPadding()
        ) {
            // Fix 2: Dynamic zoom bar based on actual max zoom
            ZoomBar(
                current  = state.zoom,
                maxZoom  = maxZoom,
                onChange = vm::setZoom,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)
            )
            ModeRow(state.mode, vm::setMode,
                Modifier.fillMaxWidth().padding(bottom = 16.dp))
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 28.dp).padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GalleryThumb(lastUri, Modifier.size(60.dp).clip(RoundedCornerShape(10.dp))
                    .clickable { vm.openGallery(ctx) })
                ShutterBtn(state.mode, state.isCapturing, { vm.capture(ctx) }, Modifier.size(80.dp))
                // Fix 3: Pass lifecycle and previewView for camera switch
                IconBtn(Icons.Default.Cameraswitch, 60.dp) {
                    vm.flipCamera(lifecycle, previewView)
                }
            }
        }

        // Shot info overlay
        shotInfo?.let { info ->
            ShotInfoCard(info, vm::dismissShotInfo,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(bottom = 200.dp, start = 12.dp, end = 12.dp))
        }

        // Processing indicator
        if (state.isProcessing) {
            Box(Modifier.align(Alignment.Center)
                .clip(RoundedCornerShape(12.dp)).background(VC.Glass).padding(20.dp),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(color = VC.Accent, modifier = Modifier.size(36.dp))
                    Text("AI Rendering…", color = VC.White, fontSize = 13.sp)
                }
            }
        }

        // Settings
        if (state.showSettings) {
            SettingsSheet(state, vm, Modifier.align(Alignment.BottomCenter))
        }

        // Error
        (camState as? CamState.Error)?.let { err ->
            Box(Modifier.fillMaxSize().background(VC.Bg.copy(0.92f)),
                Alignment.Center) {
                Column(Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Camera Error", color = VC.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text(err.msg, color = VC.Grey, fontSize = 13.sp, textAlign = TextAlign.Center)
                    Button(onClick = { vm.startCamera(lifecycle, previewView) },
                        colors = ButtonDefaults.buttonColors(containerColor = VC.Accent)) {
                        Text("Retry", color = VC.Bg, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun TopBar(state: VfState, vm: ViewfinderViewModel, modifier: Modifier) {
    Row(modifier, Arrangement.SpaceBetween, Alignment.CenterVertically) {
        // Fix 4: Flash button with all modes
        IconButton(onClick = vm::toggleFlash) {
            Icon(
                imageVector = when (state.flash) {
                    FlashSetting.OFF   -> Icons.Default.FlashOff
                    FlashSetting.AUTO  -> Icons.Default.FlashAuto
                    FlashSetting.ON    -> Icons.Default.FlashOn
                    FlashSetting.TORCH -> Icons.Default.Lightbulb
                },
                contentDescription = "Flash",
                tint = when (state.flash) {
                    FlashSetting.OFF  -> VC.Grey
                    else              -> VC.Accent
                },
                modifier = Modifier.size(26.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(state.timerLabel, vm::toggleTimer)
            Chip(state.aspectRatio, vm::cycleAspectRatio)
        }
        IconButton(onClick = vm::toggleSettings) {
            Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings",
                tint = VC.Grey, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
fun WbBadge(active: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(VC.Glass)
            .clickable(onClick = onToggle).padding(horizontal = 10.dp, vertical = 5.dp),
        Alignment.CenterVertically, Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape)
            .background(if (active) VC.Green else VC.Yellow))
        Text(
            if (active) "WB Fix" else "WB Off",
            color = if (active) VC.Green else VC.Yellow,
            fontSize = 12.sp, fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun Chip(text: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(VC.Glass)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 5.dp)) {
        Text(text, color = VC.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// Fix 2: Dynamic zoom bar - shows real zoom levels based on camera capability
@Composable
fun ZoomBar(current: Float, maxZoom: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    // Build zoom levels dynamically based on what the camera supports
    val levels = buildList {
        add(Pair(1.0f, "1×"))
        if (maxZoom >= 2f)  add(Pair(2.0f,  "2×"))
        if (maxZoom >= 5f)  add(Pair(5.0f,  "5×"))
        if (maxZoom >= 10f) add(Pair(10.0f, "10×"))
        if (maxZoom >= 30f) add(Pair(30.0f, "30×"))
        if (maxZoom >= 60f) add(Pair(60.0f, "60×"))
    }
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(VC.Glass)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        levels.forEach { (value, label) ->
            val sel = kotlin.math.abs(current - value) < 0.15f
            Box(
                Modifier.clip(CircleShape)
                    .background(if (sel) VC.Accent else Color.Transparent)
                    .clickable { onChange(value) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                Alignment.Center
            ) {
                Text(label,
                    color = if (sel) VC.Bg else VC.White,
                    fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun ModeRow(selected: ShootMode, onSelect: (ShootMode) -> Unit, modifier: Modifier) {
    val modes = ShootMode.values().toList()
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(horizontal = 24.dp)) {
        items(modes) { mode ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(mode) }
            ) {
                Text(mode.label,
                    color = if (mode == selected) VC.Accent else VC.Grey,
                    fontSize = 13.sp,
                    fontWeight = if (mode == selected) FontWeight.Bold else FontWeight.Normal)
                if (mode == selected)
                    Box(Modifier.padding(top = 3.dp).size(4.dp).clip(CircleShape).background(VC.Accent))
            }
        }
    }
}

@Composable
fun ShutterBtn(mode: ShootMode, isCapturing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.clip(CircleShape).border(3.dp, VC.White, CircleShape)
        .clickable(enabled = !isCapturing, onClick = onClick),
        Alignment.Center) {
        if (isCapturing) {
            CircularProgressIndicator(Modifier.size(48.dp), color = VC.Accent, strokeWidth = 3.dp)
        } else {
            Box(Modifier.size(64.dp).clip(CircleShape)
                .background(if (mode == ShootMode.VIDEO) VC.Red else VC.White))
        }
    }
}

@Composable
fun IconBtn(icon: ImageVector, size: Dp, onClick: () -> Unit, tint: Color = VC.White) {
    IconButton(onClick, Modifier.size(size)) {
        Icon(imageVector = icon, contentDescription = null, tint = tint,
            modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun GalleryThumb(uri: Uri?, modifier: Modifier) {
    Box(modifier.background(VC.Surface, RoundedCornerShape(10.dp)), Alignment.Center) {
        if (uri != null)
            AsyncImage(uri, "Last photo", Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop)
        else
            Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = null,
                tint = VC.DarkGrey, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun GridOverlay(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val c = Color.White.copy(alpha = 0.22f)
        drawLine(c, androidx.compose.ui.geometry.Offset(size.width/3f, 0f),
            androidx.compose.ui.geometry.Offset(size.width/3f, size.height), 1f)
        drawLine(c, androidx.compose.ui.geometry.Offset(size.width*2f/3f, 0f),
            androidx.compose.ui.geometry.Offset(size.width*2f/3f, size.height), 1f)
        drawLine(c, androidx.compose.ui.geometry.Offset(0f, size.height/3f),
            androidx.compose.ui.geometry.Offset(size.width, size.height/3f), 1f)
        drawLine(c, androidx.compose.ui.geometry.Offset(0f, size.height*2f/3f),
            androidx.compose.ui.geometry.Offset(size.width, size.height*2f/3f), 1f)
    }
}

@Composable
fun ShotInfoCard(info: Map<String, String>, onDismiss: () -> Unit, modifier: Modifier) {
    LaunchedEffect(info) { kotlinx.coroutines.delay(5000); onDismiss() }
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(VC.Glass)
            .border(0.5.dp, VC.Accent.copy(0.4f), RoundedCornerShape(14.dp))
            .clickable(onClick = onDismiss).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text("AJ CAM", color = VC.Accent, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Ahsan Jannat", color = VC.Grey, fontSize = 11.sp)
        }
        Divider(color = VC.Accent.copy(0.2f), thickness = 0.5.dp)
        // Exposure row
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            info["ISO"]?.let     { ExpBlock("ISO", it) }
            info["Shutter"]?.let { ExpBlock("SHUTTER", it) }
            info["f/"]?.let      { ExpBlock("f/", it) }
        }
        Divider(color = Color.White.copy(0.06f), thickness = 0.5.dp)
        info["Lens"]?.let    { InfoRow("🔭", "Lens", it) }
        info["Scene"]?.let   { InfoRow("🎨", "Scene", it) }
        info["Render"]?.let  { InfoRow("✨", "Render", it) }
        Text("Tap to dismiss", color = VC.DarkGrey, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable private fun ExpBlock(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = VC.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(label, color = VC.Grey, fontSize = 10.sp, letterSpacing = 0.5.sp)
    }
}
@Composable private fun InfoRow(icon: String, label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 13.sp)
        Text(label, color = VC.DarkGrey, fontSize = 11.sp, modifier = Modifier.width(50.dp))
        Text(value, color = VC.Grey, fontSize = 11.sp)
    }
}

@Composable
fun SettingsSheet(state: VfState, vm: ViewfinderViewModel, modifier: Modifier) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.6f)).clickable(onClick = vm::toggleSettings)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(VC.Surface).padding(20.dp).align(Alignment.BottomCenter)
                .clickable(enabled = false) {}
        ) {
            Box(Modifier.width(40.dp).height(4.dp).clip(CircleShape)
                .background(VC.DarkGrey).align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(16.dp))
            Text("Settings", color = VC.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            SettingRow("Grid lines",  state.gridEnabled,    vm::toggleGrid)
            SettingRow("AI WB Fix",   state.wbFixActive,    vm::toggleWbFix)
            SettingRow("Location tag", state.saveLocation,  vm::toggleLocation)
            SettingRow("Shutter sound", state.shutterSound, vm::toggleShutterSound)
            Spacer(Modifier.height(12.dp))
            Text("AJ Cam v1.0 · Ahsan Jannat", color = VC.DarkGrey, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun SettingRow(label: String, value: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(label, color = VC.White, fontSize = 14.sp)
        Switch(value, { onToggle() }, colors = SwitchDefaults.colors(
            checkedThumbColor = VC.Bg, checkedTrackColor = VC.Accent))
    }
}

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
