package com.visura.cam.ui.viewfinder

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visura.cam.utils.ProRenderEngine
import kotlinx.coroutines.delay

/**
 * ShotInfoOverlay — Pro photographer shot data panel.
 *
 * Appears after every capture showing:
 *   • Sensor used + megapixels
 *   • ISO, shutter speed, aperture
 *   • Lens name + scene type
 *   • Render profile applied
 *   • Owner: Ahsan Jannat
 *
 * Auto-dismisses after 4 seconds.
 * Tap to dismiss early.
 */
@Composable
fun ShotInfoOverlay(
    shotInfo: ProRenderEngine.ShotInfo?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = shotInfo != null,
        enter = slideInVertically { it } + fadeIn(),
        exit  = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        shotInfo ?: return@AnimatedVisibility

        // Auto-dismiss after 4 seconds
        LaunchedEffect(shotInfo) {
            delay(4000)
            onDismiss()
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onDismiss() }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xE8101010))
                .border(0.5.dp, VisuraColors.Accent.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                // Header — Owner branding
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "VISURA CAM",
                        color = VisuraColors.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.12.sp
                    )
                    Text(
                        text = "Ahsan Jannat",
                        color = VisuraColors.TextTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(VisuraColors.Accent.copy(alpha = 0.25f))
                )

                // Sensor + MP row
                ShotDataRow(
                    icon = "📷",
                    label = "Sensor",
                    value = shotInfo.sensorName
                )
                ShotDataRow(
                    icon = "🔬",
                    label = "Resolution",
                    value = shotInfo.megapixels
                )
                ShotDataRow(
                    icon = "🔭",
                    label = "Lens",
                    value = shotInfo.lensName
                )

                // Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(Color.White.copy(alpha = 0.06f))
                )

                // Exposure data — what pro photographers care about
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ExposureBlock(label = "ISO",     value = shotInfo.iso.toString())
                    ExposureBlock(label = "SHUTTER", value = shotInfo.shutterFraction)
                    ExposureBlock(label = "f/",      value = shotInfo.aperture.toString())
                }

                // Divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(Color.White.copy(alpha = 0.06f))
                )

                // Scene + render info
                ShotDataRow(
                    icon = "🎨",
                    label = "Scene",
                    value = shotInfo.scene.replaceFirstChar { it.uppercase() }
                )
                ShotDataRow(
                    icon = "✨",
                    label = "Render",
                    value = "Pro pipeline — Hasselblad colour science"
                )
                if (shotInfo.scene == "macro") {
                    ShotDataRow(
                        icon = "🔬",
                        label = "Macro render",
                        value = "Deep vignette · Clarity +60 · Colour pop"
                    )
                }

                // Tap to dismiss hint
                Text(
                    text = "Tap to dismiss",
                    color = VisuraColors.TextTertiary,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
private fun ShotDataRow(icon: String, label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 13.sp)
        Text(
            text = label,
            color = VisuraColors.TextTertiary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            color = VisuraColors.TextSecondary,
            fontSize = 11.sp,
            lineHeight = 14.sp
        )
    }
}

@Composable
private fun ExposureBlock(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            color = VisuraColors.TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = label,
            color = VisuraColors.TextTertiary,
            fontSize = 10.sp,
            letterSpacing = 0.08.sp
        )
    }
}
