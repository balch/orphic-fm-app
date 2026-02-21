package org.balch.orpheus.features.mediapipe

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.gestures.AslCategory
import org.balch.orpheus.core.gestures.AslSign
import org.balch.orpheus.core.gestures.GestureMode
import org.balch.orpheus.core.gestures.InteractionPhase
import org.balch.orpheus.core.gestures.duoDisplayLabel
import org.balch.orpheus.core.gestures.paramDisplayLabel
import org.balch.orpheus.core.gestures.quadDisplayLabel
import org.balch.orpheus.core.gestures.targetDisplayLabel
import org.balch.orpheus.ui.theme.OrpheusColors

private enum class SlotState { EMPTY, ACTIVE, LOCKED }

/**
 * 3-slot breadcrumb bar showing ASL selection progress: Target > Param > Control.
 * Overlaid at the bottom of the camera view.
 */
@Composable
fun AslSelectionBar(
    selectedTarget: AslSign?,
    selectedParam: AslSign?,
    modePrefix: AslSign?,
    interactionPhase: InteractionPhase,
    gestureMode: GestureMode = GestureMode.ASL,
    isTracking: Boolean,
    remoteAdjustArmed: Boolean = false,
    selectedDuoIndex: Int? = null,
    selectedQuadIndex: Int? = null,
    modifier: Modifier = Modifier,
) {
    if (!isTracking) return

    // Maestro Mode: show a single full-width indicator instead of breadcrumbs
    if (gestureMode == GestureMode.CONDUCTOR) {
        ConductorModeBar(modifier)
        return
    }

    // Derive slot states
    val hasTarget = selectedTarget != null
    val isSystemTarget = selectedTarget?.category == AslCategory.SYSTEM
    val hasParam = selectedParam != null || isSystemTarget
    val isControlling = interactionPhase == InteractionPhase.CONTROLLING
    val hasModePrefix = modePrefix != null && !hasTarget

    val targetState = when {
        hasTarget -> SlotState.LOCKED
        hasModePrefix -> SlotState.ACTIVE
        else -> SlotState.ACTIVE
    }
    val paramState = when {
        hasParam -> SlotState.LOCKED
        hasTarget -> SlotState.ACTIVE
        else -> SlotState.EMPTY
    }
    val controlState = when {
        remoteAdjustArmed -> SlotState.LOCKED
        isControlling -> SlotState.LOCKED
        hasParam -> SlotState.ACTIVE
        else -> SlotState.EMPTY
    }

    // Derive labels
    val targetLabel = when {
        selectedDuoIndex != null -> AslSign.duoDisplayLabel(selectedDuoIndex)
        selectedQuadIndex != null -> AslSign.quadDisplayLabel(selectedQuadIndex)
        selectedTarget != null -> selectedTarget.targetDisplayLabel
        hasModePrefix -> if (modePrefix == AslSign.LETTER_D) "DUO ?" else "QUAD ?"
        else -> "Target"
    }
    val paramLabel = when {
        isSystemTarget -> "\u2014" // em dash
        hasParam && selectedParam != null -> selectedParam.paramDisplayLabel ?: selectedParam.label
        else -> "Param"
    }
    val controlLabel = when {
        remoteAdjustArmed -> "R"
        isControlling -> "\u25CF" // filled circle when active
        else -> "Pinch"
    }

    val showCancel = hasTarget || hasModePrefix

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                ),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SlotChip(targetLabel, targetState, modifier = Modifier.weight(1f))
            ArrowSeparator(locked = targetState == SlotState.LOCKED)
            SlotChip(paramLabel, paramState, modifier = Modifier.weight(1f))
            ArrowSeparator(locked = paramState == SlotState.LOCKED)
            SlotChip(controlLabel, controlState, modifier = Modifier.weight(1f))

            if (showCancel) {
                Text(
                    text = "A=\u2715",
                    color = Color.Gray,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SlotChip(
    label: String,
    state: SlotState,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    val green = OrpheusColors.synthGreen

    // Pulse animation for ACTIVE state
    val pulseAlpha = if (state == SlotState.ACTIVE) {
        val transition = rememberInfiniteTransition(label = "slotPulse")
        val alpha by transition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
            label = "pulseAlpha",
        )
        alpha
    } else {
        1f
    }

    val background = when (state) {
        SlotState.EMPTY -> Color.Gray.copy(alpha = 0.3f)
        SlotState.ACTIVE -> Color.Transparent
        SlotState.LOCKED -> green.copy(alpha = 0.8f)
    }
    val textColor = when (state) {
        SlotState.EMPTY -> Color.Gray
        SlotState.ACTIVE -> green
        SlotState.LOCKED -> Color.White
    }
    val borderMod = if (state == SlotState.ACTIVE) {
        Modifier.border(1.dp, green.copy(alpha = pulseAlpha), shape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .height(28.dp)
            .then(borderMod)
            .background(background, shape)
            .alpha(if (state == SlotState.ACTIVE) pulseAlpha else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (state == SlotState.LOCKED) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun ArrowSeparator(locked: Boolean) {
    Text(
        text = "\u25B8", // right-pointing triangle
        color = if (locked) OrpheusColors.synthGreen else Color.Gray.copy(alpha = 0.5f),
        fontSize = 12.sp,
    )
}

@Preview
@Composable
private fun AslSelectionBarIdlePreview() {
    AslSelectionBar(
        selectedTarget = null,
        selectedParam = null,
        modePrefix = null,
        interactionPhase = InteractionPhase.IDLE,
        isTracking = true,
    )
}

@Preview
@Composable
private fun AslSelectionBarTargetLockedPreview() {
    AslSelectionBar(
        selectedTarget = AslSign.NUM_3,
        selectedParam = null,
        modePrefix = null,
        interactionPhase = InteractionPhase.SELECTED,
        isTracking = true,
    )
}

@Preview
@Composable
private fun AslSelectionBarFullyLockedPreview() {
    AslSelectionBar(
        selectedTarget = AslSign.NUM_3,
        selectedParam = AslSign.LETTER_M,
        modePrefix = null,
        interactionPhase = InteractionPhase.CONTROLLING,
        isTracking = true,
    )
}

@Preview
@Composable
private fun AslSelectionBarSystemTargetPreview() {
    AslSelectionBar(
        selectedTarget = AslSign.LETTER_V,
        selectedParam = null,
        modePrefix = null,
        interactionPhase = InteractionPhase.SELECTED,
        isTracking = true,
    )
}

@Preview
@Composable
private fun AslSelectionBarModePrefixPreview() {
    AslSelectionBar(
        selectedTarget = null,
        selectedParam = null,
        modePrefix = AslSign.LETTER_D,
        interactionPhase = InteractionPhase.IDLE,
        isTracking = true,
    )
}

@Composable
private fun ConductorModeBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "conductorPulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "conductorAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                ),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .alpha(pulseAlpha)
                .border(1.dp, OrpheusColors.neonOrange, RoundedCornerShape(6.dp))
                .background(OrpheusColors.neonOrange.copy(alpha = 0.2f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "ORCHESTRATE",
                color = OrpheusColors.neonOrange,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
        }
    }
}

@Preview
@Composable
private fun AslSelectionBarConductorModePreview() {
    AslSelectionBar(
        selectedTarget = null,
        selectedParam = null,
        modePrefix = null,
        interactionPhase = InteractionPhase.IDLE,
        gestureMode = GestureMode.CONDUCTOR,
        isTracking = true,
    )
}
