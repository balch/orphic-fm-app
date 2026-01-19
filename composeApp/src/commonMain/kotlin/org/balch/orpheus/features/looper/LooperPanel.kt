package org.balch.orpheus.features.looper

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.compose.ui.tooling.preview.PreviewParameter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Fire Orange Looper Color Scheme
private val LooperColor = OrpheusColors.looperFireOrange
private val RecordColor = OrpheusColors.looperFlame
private val PlayColor = OrpheusColors.looperAmber

@Composable
fun LooperPanel(
    feature: LooperFeature = LooperViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true
) {
    val state by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "LOOP",
        color = LooperColor,
        expandedTitle = "Circle Back",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {

        // Central Looper Display
        Box(
            modifier = Modifier
                .size(160.dp)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularLooperDisplay(state)

            // Duration Text in center
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (state.isRecording) "REC" else if (state.isPlaying) "PLAY" else "IDLE",
                    color = if (state.isRecording) RecordColor else if (state.isPlaying) PlayColor else OrpheusColors.greyText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall
                )
                if (state.loopDuration > 0 || state.isRecording) {
                    val seconds =
                        if (state.isRecording) (state.position * 60.0) else state.loopDuration
                    val displaySecs = (seconds * 100).roundToInt() / 100.0
                    Text(
                        text = "${displaySecs}s",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Controls
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Record Button (Primary action)
            LooperActionButton(
                icon = Icons.Default.Refresh,
                label = "RECORD",
                active = state.isRecording,
                activeColor = RecordColor,
                onClick = { actions.setRecord(!state.isRecording) },
                modifier = Modifier.scale(1.2f)
            )

            // Play Button
            LooperActionButton(
                icon = Icons.Default.PlayArrow,
                label = "PLAY",
                active = state.isPlaying,
                activeColor = PlayColor,
                enabled = state.loopDuration > 0,
                onClick = { actions.setPlay(!state.isPlaying) }
            )

            // Clear Button
            LooperActionButton(
                icon = Icons.Default.Clear,
                label = "CLEAR",
                active = false,
                activeColor = OrpheusColors.looperBurnt,
                onClick = { actions.clear() }
            )
        }
    }
}

@Composable
fun CircularLooperDisplay(state: LooperUiState) {
    val progress by animateFloatAsState(
        targetValue = state.position,
        animationSpec = if (state.position == 0f) tween(0) else tween(100, easing = LinearEasing)
    )
    
    val recordGlowAlpha by animateFloatAsState(if (state.isRecording) 1f else 0.2f)
    val playGlowAlpha by animateFloatAsState(if (state.isPlaying) 1f else 0.2f)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = size.minDimension / 2f - 4.dp.toPx()
        
        // Background track - coal brown for fire theme
        drawCircle(
            color = OrpheusColors.looperCoal,
            radius = radius,
            style = Stroke(width = 8.dp.toPx())
        )
        
        // Recording Track (Full circle if recording started) - chestnut brown
        if (state.isRecording) {
            drawArc(
                color = OrpheusColors.looperChestnut.copy(alpha = 0.5f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Progress Arc
        if (state.isRecording || state.isPlaying) {
            val color = if (state.isRecording) RecordColor else PlayColor
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = progress * 360f,
                useCenter = false,
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            )
            
            // Glow effect at tip
            val angle = (progress * 360f - 90f) * PI.toFloat() / 180f
            val tipX = centerX + radius * cos(angle)
            val tipY = centerY + radius * sin(angle)
            
            drawCircle(
                color = color,
                radius = 6.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(tipX, tipY)
            )
        }
    }
}

@Composable
private fun LooperActionButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
    val pulseScale by if (active && activeColor == RecordColor) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.1f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            )
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(1f) }
    }

    val backgroundColor by animateColorAsState(if (active) activeColor.copy(alpha = 0.25f) else if (enabled) OrpheusColors.looperAsh else OrpheusColors.looperCoal)
    val tintColor by animateColorAsState(if (active) activeColor else if (enabled) OrpheusColors.looperEmber else OrpheusColors.looperBrown)
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(60.dp)
    ) {
        Box(
            modifier = Modifier
                .scale(pulseScale)
                .size(48.dp)
                .shadow(if (active) 8.dp else 0.dp, CircleShape, ambientColor = activeColor, spotColor = activeColor)
                .clip(CircleShape)
                .background(backgroundColor)
                .then(if (active) Modifier.border(2.dp, activeColor, CircleShape) else Modifier)
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tintColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) activeColor else if (enabled) OrpheusColors.looperEmber else OrpheusColors.looperBrown,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

// Preview support
@Preview(widthDp = 400, heightDp = 400)
@Composable
fun LooperPanelPreview(
    @PreviewParameter(LiquidEffectsProvider::class) effects: VisualizationLiquidEffects,
) {
    LiquidPreviewContainerWithGradient(effects = effects) {
        LooperPanel(
            isExpanded = true,
            feature = LooperViewModel.previewFeature(),
        )
    }
}