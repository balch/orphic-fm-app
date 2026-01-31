package org.balch.orpheus.features.looper

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.preview.LiquidEffectsProvider
import org.balch.orpheus.ui.preview.LiquidPreviewContainerWithGradient
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.compositeOver
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Fire Orange Looper Color Scheme
private val LooperColor = OrpheusColors.looperFireOrange
private val RecordColor = OrpheusColors.looperFlame
private val PlayColor = OrpheusColors.looperGreen

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

    val recordGlowAlpha by animateFloatAsState(if (state.isRecording) 1f else 0.2f)
    val playGlowAlpha by animateFloatAsState(if (state.isPlaying) 1f else 0.2f)

    val textColor by animateColorAsState(
        targetValue = when {
            state.isRecording -> RecordColor
            state.isPlaying -> PlayColor
            else -> OrpheusColors.greyText
        }
    )

    CollapsibleColumnPanel(
        title = "LOOP",
        color = LooperColor,
        expandedTitle = "Circular Buffer",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {

        // Central Looper Display
        Box(
            modifier = Modifier
                .size(140.dp)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularLooperDisplay(state, recordGlowAlpha, playGlowAlpha)

            // Duration Text in center
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (state.isRecording) "REC" else if (state.isPlaying) "PLAY" else "IDLE",
                    color = textColor,
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Record Button (Primary action)
            LooperActionButton(
                icon = Icons.Default.Refresh,
                label = "RECORD",
                active = state.isRecording,
                activeColor = RecordColor,
                glowAlpha = recordGlowAlpha,
                onClick = { actions.setRecord(!state.isRecording) },
            )

            // Play Button
            LooperActionButton(
                icon = Icons.Default.PlayArrow,
                label = "PLAY",
                active = state.isPlaying,
                activeColor = PlayColor,
                enabled = state.loopDuration > 0,
                glowAlpha = playGlowAlpha,
                onClick = { actions.setPlay(!state.isPlaying) },
            )

            // Clear Button
            LooperActionButton(
                icon = Icons.Default.Clear,
                label = "CLEAR",
                active = false,
                activeColor = OrpheusColors.looperBurnt,
                onClick = { actions.clear() },
            )
        }
    }
}

@Composable
fun CircularLooperDisplay(
    state: LooperUiState,
    recordGlowAlpha: Float,
    playGlowAlpha: Float
) {
    val progress by animateFloatAsState(
        targetValue = state.position,
        animationSpec = if (state.position == 0f) tween(0) else tween(100, easing = LinearEasing)
    )
    
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
        // Use recordGlowAlpha for smooth fade
        val arcSize = Size(radius * 2, radius * 2)
        val arcTopLeft = Offset(centerX - radius, centerY - radius)
        
        val recordAlpha = (recordGlowAlpha - 0.2f) / 0.8f
        if (recordAlpha > 0f) {
            drawArc(
                color = OrpheusColors.looperChestnut.copy(alpha = 0.5f * recordAlpha),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Progress Arc
        if (state.isRecording || state.isPlaying || recordAlpha > 0f || playGlowAlpha > 0.2f) {
            val isActiveRecord = state.isRecording || recordAlpha > 0.01f
            val color = if (isActiveRecord) RecordColor else PlayColor
            val glowAlpha = if (isActiveRecord) recordAlpha else (playGlowAlpha - 0.2f) / 0.8f
            
            if (glowAlpha > 0f) {
                // Glow effect for arc
                drawArc(
                    color = color.copy(alpha = glowAlpha * 0.3f),
                    startAngle = -90f,
                    sweepAngle = progress * 360f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                )

                drawArc(
                    color = color.copy(alpha = glowAlpha),
                    startAngle = -90f,
                    sweepAngle = progress * 360f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                )

                // Glow effect at tip
                val angle = (progress * 360f - 90f) * PI.toFloat() / 180f
                val tipX = centerX + radius * cos(angle)
                val tipY = centerY + radius * sin(angle)

                // Outer tip glow
                drawCircle(
                    color = color.copy(alpha = glowAlpha * 0.4f),
                    radius = 12.dp.toPx(),
                    center = Offset(tipX, tipY)
                )

                drawCircle(
                    color = color.copy(alpha = glowAlpha),
                    radius = 6.dp.toPx(),
                    center = Offset(tipX, tipY)
                )
            }
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
    glowAlpha: Float = 1f,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val baseColor = if (enabled) OrpheusColors.looperAsh else OrpheusColors.looperCoal
    val activeBg = activeColor.copy(alpha = 0.25f).compositeOver(baseColor)
    val backgroundColor by animateColorAsState(if (active) activeBg else baseColor)
    val tintColor by animateColorAsState(if (active) activeColor else if (enabled) OrpheusColors.looperEmber else OrpheusColors.looperBrown)
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .shadow(
                    elevation = if (active || glowAlpha > 0.2f) (8 * (glowAlpha - 0.2f) / 0.8f).dp else 0.dp,
                    shape = CircleShape,
                    ambientColor = activeColor,
                    spotColor = activeColor
                )
                .clip(CircleShape)
                .background(backgroundColor)
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