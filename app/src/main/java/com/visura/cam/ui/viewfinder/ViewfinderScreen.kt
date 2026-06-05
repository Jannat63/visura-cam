package com.visura.cam.ui.viewfinder

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings

// ─── Visura Cam Color Palette ────────────────────────────────────
object VisuraColors {
    val Background     = Color(0xFF0A0A0A)   // Near-black — battery saving on AMOLED
    val Surface        = Color(0xFF141414)
    val SurfaceHigh    = Color(0xFF1E1E1E)
    val Accent         = Color(0xFFB8F026)   // Yellow-green — high contrast on dark
    val AccentDim      = Color(0xFF7BAC14)
    val TextPrimary    = Color(0xFFFFFFFF)
    val TextSecondary  = Color(0xFFAAAAAA)
    val TextTertiary   = Color(0xFF666666)
    val Warning        = Color(0xFFFF6B35)
    val Success        = Color(0xFF4CAF50)
    val Danger         = Color(0xFFE53935)
    val GlassWhite     = Color(0x22FFFFFF)
    val GlassBlack     = Color(0xAA000000)
    val CorrectionOn   = Color(0xFF4CAF50)   // Green dot = fix active
    val CorrectionOff  = Color(0xFFFF6B35)   // Orange dot = fix disabled
}

@Composable
fun ViewfinderScreen(
    viewModel: ViewfinderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val lastShotInfo by viewModel.lastShotInfo.collectAsState()
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VisuraColors.Background)
    ) {
        // ── 1. Camera Preview Surface ─────────────────────────────
        CameraPreviewSurface(
            modifier = Modifier.fillMaxSize(),
            onSurfaceCreated = { surface -> viewModel.onSurfaceCreated(surface) }
        )

        // ── 2. Top Bar ────────────────────────────────────────────
        TopControlBar(
            uiState = uiState,
            onFlashToggle = viewModel::toggleFlash,
            onTimerToggle = viewModel::toggleTimer,
            onRatioChange = viewModel::setAspectRatio,
            onSettingsTap = viewModel::openSettings,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // ── 3. Grid overlay (rule of thirds) ──────────────────────
        if (uiState.gridEnabled) {
            GridOverlay(modifier = Modifier.fillMaxSize())
        }

        // ── 4. Macro distance guide (only in macro mode) ──────────
        if (uiState.shootMode == ShootModeUI.MACRO) {
            MacroDistanceGuide(
                distanceState = uiState.macroDistanceState,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // ── 5. Histogram (pro mode) ───────────────────────────────
        if (uiState.histogramEnabled) {
            LiveHistogram(
                histogramData = uiState.histogramData,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 80.dp, end = 12.dp)
            )
        }

        // ── 6. Focus indicator ────────────────────────────────────
        uiState.focusPoint?.let { point ->
            FocusIndicator(
                x = point.x,
                y = point.y,
                state = uiState.focusState
            )
        }

        // ── 7. PRO mode controls bar ──────────────────────────────
        AnimatedVisibility(
            visible = uiState.shootMode == ShootModeUI.PRO,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ProControlStrip(
                settings = uiState.proSettings,
                onIsoChange = viewModel::setISO,
                onShutterChange = viewModel::setShutter,
                onEvChange = viewModel::setEV,
                onWbChange = viewModel::setWhiteBalance,
                onFocusChange = viewModel::setFocusDistance,
                modifier = Modifier.padding(bottom = 220.dp)
            )
        }

        // ── 8. Bottom controls ────────────────────────────────────
        BottomControlArea(
            uiState = uiState,
            onCaptureTap = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.capture()
            },
            onGalleryTap = viewModel::openGallery,
            onFlipCamera = viewModel::flipCamera,
            onModeChange = viewModel::setShootMode,
            onZoomChange = viewModel::setZoom,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )

        // ── 9. Color correction status indicator ──────────────────
        ColorCorrectionBadge(
            isActive = uiState.colorCorrectionActive,
            onToggle = viewModel::toggleColorCorrection,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 60.dp)
        )

        // ── 10. Stabilisation overlay (macro mode) ────────────────
        if (uiState.shootMode == ShootModeUI.MACRO && uiState.stabilisationAssistActive) {
            StabilisationOverlay(
                isReady = uiState.isHandSteady,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

// ─── Top Control Bar ─────────────────────────────────────────────

@Composable
fun TopControlBar(
    uiState: ViewfinderUiState,
    onFlashToggle: () -> Unit,
    onTimerToggle: () -> Unit,
    onRatioChange: () -> Unit,
    onSettingsTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Flash
        IconButton(onClick = onFlashToggle) {
            Icon(
                imageVector = when (uiState.flashMode) {
                        FlashModeUI.OFF   -> rememberFlashOffIcon()
                        FlashModeUI.AUTO  -> rememberFlashAutoIcon()
                        FlashModeUI.ON    -> rememberFlashOnIcon()
                        FlashModeUI.TORCH -> rememberTorchIcon()
                    },
                contentDescription = "Flash",
                tint = if (uiState.flashMode == FlashModeUI.ON) VisuraColors.Accent
                       else VisuraColors.TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }

        // Center row: Timer | Ratio | Settings
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopChip(text = uiState.timerLabel, onClick = onTimerToggle)
            TopChip(text = uiState.aspectRatioLabel, onClick = onRatioChange)
        }

        // Settings
        IconButton(onClick = onSettingsTap) {
            Icon(
                imageVector = rememberSettingsIcon(),
                contentDescription = "Settings",
                tint = VisuraColors.TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun TopChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(VisuraColors.GlassBlack)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = VisuraColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// ─── Color Correction Badge ───────────────────────────────────────

@Composable
fun ColorCorrectionBadge(
    isActive: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(VisuraColors.GlassBlack)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) VisuraColors.CorrectionOn else VisuraColors.CorrectionOff
                )
        )
        Text(
            text = if (isActive) "WB Fix" else "WB Off",
            color = if (isActive) VisuraColors.CorrectionOn else VisuraColors.CorrectionOff,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ─── Bottom Control Area ──────────────────────────────────────────

@Composable
fun BottomControlArea(
    uiState: ViewfinderUiState,
    onCaptureTap: () -> Unit,
    onGalleryTap: () -> Unit,
    onFlipCamera: () -> Unit,
    onModeChange: (ShootModeUI) -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Zoom selector
        ZoomSelector(
            currentZoom = uiState.currentZoom,
            availableZooms = uiState.availableZooms,
            onZoomChange = onZoomChange,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 16.dp)
        )

        // Mode selector
        ModeSelector(
            selectedMode = uiState.shootMode,
            onModeChange = onModeChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        )

        // Capture row: Gallery | Shutter | Flip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Last photo thumbnail
            GalleryThumb(
                thumbnail = uiState.lastCaptureThumbnail,
                onClick = onGalleryTap,
                modifier = Modifier.size(56.dp)
            )

            // Shutter button
            ShutterButton(
                mode = uiState.shootMode,
                isCapturing = uiState.isCapturing,
                isRecording = uiState.isRecording,
                isWaitingStable = uiState.isWaitingForStable,
                onClick = onCaptureTap,
                modifier = Modifier.size(80.dp)
            )

            // Flip / switch camera
            IconButton(
                onClick = onFlipCamera,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = rememberFlipIcon(),
                    contentDescription = "Switch camera",
                    tint = VisuraColors.TextPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

// ─── Shutter Button ───────────────────────────────────────────────

@Composable
fun ShutterButton(
    mode: ShootModeUI,
    isCapturing: Boolean,
    isRecording: Boolean,
    isWaitingStable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVideo = mode == ShootModeUI.VIDEO || mode == ShootModeUI.SLOW_MO

    Box(
        modifier = modifier
            .clip(CircleShape)
            .border(3.dp, VisuraColors.TextPrimary, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            isWaitingStable -> {
                // Pulsing ring for stabilisation assist
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    color = VisuraColors.Accent,
                    strokeWidth = 3.dp
                )
            }
            isRecording -> {
                // Red square = stop recording
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(VisuraColors.Danger)
                )
            }
            isVideo -> {
                // Red circle = start video
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(VisuraColors.Danger)
                )
            }
            else -> {
                // White circle = capture photo
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            if (isCapturing) VisuraColors.Accent.copy(alpha = 0.7f)
                            else VisuraColors.TextPrimary
                        )
                )
            }
        }
    }
}

// ─── Zoom Selector ────────────────────────────────────────────────

@Composable
fun ZoomSelector(
    currentZoom: Float,
    availableZooms: List<ZoomLevel>,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(VisuraColors.GlassBlack)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        availableZooms.forEach { zoom ->
            val isSelected = kotlin.math.abs(currentZoom - zoom.value) < 0.05f
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (isSelected) VisuraColors.Accent else Color.Transparent
                    )
                    .clickable { onZoomChange(zoom.value) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = zoom.label,
                    color = if (isSelected) VisuraColors.Background else VisuraColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

// ─── Mode Selector ────────────────────────────────────────────────

@Composable
fun ModeSelector(
    selectedMode: ShootModeUI,
    onModeChange: (ShootModeUI) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf(
        ShootModeUI.NIGHT, ShootModeUI.PORTRAIT, ShootModeUI.PHOTO,
        ShootModeUI.VIDEO, ShootModeUI.MORE
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        modes.forEach { mode ->
            val isSelected = mode == selectedMode
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onModeChange(mode) }
            ) {
                Text(
                    text = mode.label,
                    color = if (isSelected) VisuraColors.Accent else VisuraColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(VisuraColors.Accent)
                    )
                }
            }
        }
    }
}

// ─── PRO Controls Strip ───────────────────────────────────────────

@Composable
fun ProControlStrip(
    settings: ProSettings,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Long) -> Unit,
    onEvChange: (Int) -> Unit,
    onWbChange: (Int) -> Unit,
    onFocusChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Labels row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            listOf("ISO", "S", "EV", "WB", "AF").forEach { label ->
                Text(text = label, color = VisuraColors.TextTertiary, fontSize = 11.sp,
                    fontWeight = FontWeight.Medium)
            }
        }
        // Values row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Text(text = settings.isoLabel, color = VisuraColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = settings.shutterLabel, color = VisuraColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = settings.evLabel, color = if (settings.evValue > 0) VisuraColors.Accent else VisuraColors.TextPrimary,
                fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = settings.wbLabel, color = VisuraColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = settings.afLabel, color = VisuraColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        // Scrollable dial
        ProDial(
            settings = settings,
            onIsoChange = onIsoChange,
            onShutterChange = onShutterChange,
            onEvChange = onEvChange,
            onWbChange = onWbChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(top = 6.dp)
        )
    }
}

// ─── Macro Distance Guide ─────────────────────────────────────────

@Composable
fun MacroDistanceGuide(
    distanceState: MacroDistanceState,
    modifier: Modifier = Modifier
) {
    val color = when (distanceState.status) {
        MacroStatus.SWEET_SPOT -> VisuraColors.Success
        MacroStatus.TOO_CLOSE  -> VisuraColors.Danger
        MacroStatus.TOO_FAR    -> VisuraColors.Warning
        else -> VisuraColors.TextTertiary
    }
    val label = when (distanceState.status) {
        MacroStatus.SWEET_SPOT -> "Perfect — ${distanceState.distanceCm}cm"
        MacroStatus.TOO_CLOSE  -> "Too close — move back"
        MacroStatus.TOO_FAR    -> "Too far — move closer"
        else -> "Move to 4cm"
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(VisuraColors.GlassBlack)
                .border(1.dp, color, RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(text = label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ─── UI State & Enums ────────────────────────────────────────────

enum class ShootModeUI(val label: String) {
    NIGHT("NIGHT"), PORTRAIT("PORTRAIT"), PHOTO("PHOTO"),
    VIDEO("VIDEO"), MORE("MORE"), PRO("PRO"), MACRO("MACRO"),
    RAW("RAW"), PANORAMA("PANO"), SLOW_MO("SLO-MO"), TIME_LAPSE("TIMELAPSE")
}

enum class FlashModeUI { OFF, AUTO, ON, TORCH }
enum class MacroStatus { SWEET_SPOT, TOO_CLOSE, TOO_FAR, UNKNOWN }

data class ZoomLevel(val value: Float, val label: String)

// Realme 8 Pro zoom levels — honest about optical vs digital
val REALME8PRO_ZOOM_LEVELS = listOf(
    ZoomLevel(0.6f, "0.6"),   // Ultrawide (optical switch)
    ZoomLevel(1.0f, "1×"),    // Main 108MP (optical)
    ZoomLevel(2.0f, "2"),     // Digital crop on 108MP (acceptable)
    ZoomLevel(5.0f, "5")      // Digital — marked as digital in UI
)

data class MacroDistanceState(
    val status: MacroStatus = MacroStatus.UNKNOWN,
    val distanceCm: Float = 0f,
    val sharpness: Float = 0f
)

data class ProSettings(
    val isoValue: Int? = null,
    val shutterNs: Long? = null,
    val evValue: Int = 0,
    val wbKelvin: Int? = null,
    val focusDistance: Float? = null
) {
    val isoLabel get() = isoValue?.toString() ?: "AUTO"
    val shutterLabel get() = shutterNs?.let { "1/${(1_000_000_000L / it).toInt()}" } ?: "AUTO"
    val evLabel get() = if (evValue >= 0) "+${evValue/6f}" else "${evValue/6f}"
    val wbLabel get() = wbKelvin?.let { "${it}K" } ?: "AWB"
    val afLabel get() = if (focusDistance == null) "AF-C" else "MF"
}

data class ViewfinderUiState(
    val shootMode: ShootModeUI = ShootModeUI.PHOTO,
    val flashMode: FlashModeUI = FlashModeUI.AUTO,
    val currentZoom: Float = 1.0f,
    val availableZooms: List<ZoomLevel> = REALME8PRO_ZOOM_LEVELS,
    val colorCorrectionActive: Boolean = true,
    val gridEnabled: Boolean = false,
    val histogramEnabled: Boolean = false,
    val isCapturing: Boolean = false,
    val isRecording: Boolean = false,
    val isWaitingForStable: Boolean = false,
    val isHandSteady: Boolean = false,
    val stabilisationAssistActive: Boolean = false,
    val macroDistanceState: MacroDistanceState = MacroDistanceState(),
    val focusPoint: FocusPoint? = null,
    val focusState: FocusState = FocusState.IDLE,
    val proSettings: ProSettings = ProSettings(),
    val histogramData: HistogramData? = null,
    val lastCaptureThumbnail: Any? = null,
    val timerLabel: String = "OFF",
    val aspectRatioLabel: String = "4:3"
) {
    // flashIcon resolved in TopControlBar composable directly
}

data class FocusPoint(val x: Float, val y: Float)
enum class FocusState { IDLE, SEARCHING, LOCKED, FAILED }
data class HistogramData(val r: FloatArray, val g: FloatArray, val b: FloatArray)

// Icon helpers — using Icons.Filled (material-icons-extended)
@Composable fun rememberSettingsIcon() = Icons.Filled.Settings
@Composable fun rememberFlipIcon() = Icons.Filled.Cameraswitch
@Composable fun rememberFlashOffIcon() = Icons.Filled.FlashOff
@Composable fun rememberFlashAutoIcon() = Icons.Filled.FlashAuto
@Composable fun rememberFlashOnIcon() = Icons.Filled.FlashOn
@Composable fun rememberTorchIcon() = Icons.Filled.Lightbulb
// abs() from kotlin.math used directly

// Placeholder composables — implement with actual logic
@Composable fun GridOverlay(modifier: Modifier) {}
@Composable fun LiveHistogram(histogramData: HistogramData?, modifier: Modifier) {}
@Composable fun FocusIndicator(x: Float, y: Float, state: FocusState) {}
@Composable fun ProDial(settings: ProSettings, onIsoChange: (Int)->Unit, onShutterChange: (Long)->Unit, onEvChange: (Int)->Unit, onWbChange: (Int)->Unit, modifier: Modifier) {}
@Composable fun StabilisationOverlay(isReady: Boolean, modifier: Modifier) {}
@Composable fun GalleryThumb(thumbnail: Any?, onClick: () -> Unit, modifier: Modifier) {}
