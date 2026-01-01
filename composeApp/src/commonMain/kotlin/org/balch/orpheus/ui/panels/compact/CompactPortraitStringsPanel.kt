package org.balch.orpheus.ui.panels.compact

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.LiquidState
import org.balch.orpheus.core.audio.VoiceState
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.features.voice.VoicePanelActions
import org.balch.orpheus.features.voice.VoiceUiState
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.abs

/**
 * Compact string panel for the bottom panel navigation in portrait mode.
 *
 * Layout:
 * - Left: Quad 0 pitch and hold controls
 * - Center: 4 strummable vertical strings (each triggers a duo of voices)
 * - Right: Quad 1 pitch and hold controls
 *
 * Interactions:
 * - Tap or drag across strings to "strum" and trigger voices
 * - String deflection provides visual feedback
 */
@Composable
fun CompactStringPanel(
    voiceState: VoiceUiState,
    actions: VoicePanelActions,
    modifier: Modifier = Modifier,
    liquidState: LiquidState? = LocalLiquidState.current,
    effects: VisualizationLiquidEffects = LocalLiquidEffects.current
) {
    val shape = RoundedCornerShape(12.dp)

    // String colors for the 4 duos
    val stringColors = listOf(
        OrpheusColors.neonMagenta,
        OrpheusColors.electricBlue,
        OrpheusColors.warmGlow,
        OrpheusColors.synthGreen
    )

    val baseModifier = modifier.fillMaxSize()

    val panelModifier = if (liquidState != null) {
        baseModifier
            .liquidVizEffects(
                liquidState = liquidState,
                scope = effects.bottom,
                frostAmount = effects.frostMedium.dp,
                color = OrpheusColors.softPurple,
                tintAlpha = effects.tintAlpha,
                shape = shape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
            .padding(8.dp)
    } else {
        baseModifier.padding(8.dp)
    }

    Row(modifier = panelModifier) {
        // Left controls column
        Column(
            modifier = Modifier
                .width(80.dp)
                .fillMaxHeight()
                .padding(end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Quad 0 Pitch
            RotaryKnob(
                value = voiceState.quadGroupPitches.getOrElse(0) { 0.5f },
                onValueChange = { actions.onQuadPitchChange(0, it) },
                label = "PITCH",
                controlId = ControlIds.quadPitch(0),
                size = 40.dp,
                progressColor = OrpheusColors.neonMagenta
            )
            // Quad 0 Hold
            RotaryKnob(
                value = voiceState.quadGroupHolds.getOrElse(0) { 0f },
                onValueChange = { actions.onQuadHoldChange(0, it) },
                label = "HOLD",
                controlId = ControlIds.quadHold(0),
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
        }

        // Center: Strummable strings
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            StringsCanvas(
                colors = stringColors,
                voiceStates = voiceState.voiceStates,
                onStringPlucked = { stringIndex, amplitude ->
                    // Each string triggers a duo (2 voices)
                    val voiceA = stringIndex * 2
                    val voiceB = stringIndex * 2 + 1
                    if (amplitude > 0.1f) {
                        actions.onPulseStart(voiceA)
                        actions.onPulseStart(voiceB)
                    }
                },
                onStringReleased = { stringIndex ->
                    val voiceA = stringIndex * 2
                    val voiceB = stringIndex * 2 + 1
                    actions.onPulseEnd(voiceA)
                    actions.onPulseEnd(voiceB)
                }
            )
        }

        // Right controls column
        Column(
            modifier = Modifier
                .width(80.dp)
                .fillMaxHeight()
                .padding(start = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Quad 1 Pitch
            RotaryKnob(
                value = voiceState.quadGroupPitches.getOrElse(1) { 0.5f },
                onValueChange = { actions.onQuadPitchChange(1, it) },
                label = "PITCH",
                controlId = ControlIds.quadPitch(1),
                size = 40.dp,
                progressColor = OrpheusColors.synthGreen
            )
            // Quad 1 Hold
            RotaryKnob(
                value = voiceState.quadGroupHolds.getOrElse(1) { 0f },
                onValueChange = { actions.onQuadHoldChange(1, it) },
                label = "HOLD",
                controlId = ControlIds.quadHold(1),
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
        }
    }
}

/**
 * Canvas for drawing and interacting with the 4 strummable strings.
 * Strings are vertical lines that can be "plucked" by dragging across them.
 */
@Composable
private fun StringsCanvas(
    colors: List<Color>,
    voiceStates: List<VoiceState>,
    onStringPlucked: (stringIndex: Int, amplitude: Float) -> Unit,
    onStringReleased: (stringIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Track which strings are currently being touched
    var activeStrings by remember { mutableStateOf(setOf<Int>()) }
    // Track deflection amount for each string (for visual feedback)
    var stringDeflections by remember { mutableStateOf(listOf(0f, 0f, 0f, 0f)) }
    val path = remember { Path() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        // Determine which string was touched
                        val stringWidth = size.width / 4f
                        val stringIndex = (offset.x / stringWidth).toInt().coerceIn(0, 3)
                        activeStrings = activeStrings + stringIndex
                        
                        // Calculate deflection based on distance from string center
                        val stringCenterX = stringWidth * (stringIndex + 0.5f)
                        val deflection = (offset.x - stringCenterX) / stringWidth
                        stringDeflections = stringDeflections.mapIndexed { i, d ->
                            if (i == stringIndex) deflection else d
                        }
                        
                        onStringPlucked(stringIndex, abs(deflection).coerceIn(0f, 1f))
                    },
                    onDrag = { change, _ ->
                        val stringWidth = size.width / 4f
                        val stringIndex = (change.position.x / stringWidth).toInt().coerceIn(0, 3)
                        
                        // Update deflection
                        val stringCenterX = stringWidth * (stringIndex + 0.5f)
                        val deflection = (change.position.x - stringCenterX) / stringWidth
                        stringDeflections = stringDeflections.mapIndexed { i, d ->
                            if (i == stringIndex) deflection else d
                        }
                        
                        // Trigger if crossed into a new string
                        if (stringIndex !in activeStrings) {
                            activeStrings = activeStrings + stringIndex
                            onStringPlucked(stringIndex, abs(deflection).coerceIn(0f, 1f))
                        }
                    },
                    onDragEnd = {
                        activeStrings.forEach { onStringReleased(it) }
                        activeStrings = emptySet()
                        stringDeflections = listOf(0f, 0f, 0f, 0f)
                    },
                    onDragCancel = {
                        activeStrings.forEach { onStringReleased(it) }
                        activeStrings = emptySet()
                        stringDeflections = listOf(0f, 0f, 0f, 0f)
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val stringWidth = size.width / 4f
                    val stringIndex = (offset.x / stringWidth).toInt().coerceIn(0, 3)
                    onStringPlucked(stringIndex, 0.7f)
                    // Quick release after tap
                    onStringReleased(stringIndex)
                }
            }
    ) {
        val stringWidth = size.width / 4f
        val stringHeight = size.height

        for (i in 0 until 4) {
            val stringCenterX = stringWidth * (i + 0.5f)
            val isActive = i in activeStrings || voiceStates.getOrNull(i * 2)?.pulse == true
            val deflection = stringDeflections.getOrElse(i) { 0f }
            
            val color = colors.getOrElse(i) { OrpheusColors.neonCyan }
            val strokeWidth = if (isActive) 6f else 4f
            val alpha = if (isActive) 1f else 0.7f
            val glowAlpha = if (isActive) 0.4f else 0.1f

            // Draw string glow (wider, more transparent)
            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        color.copy(alpha = glowAlpha * 0.5f),
                        color.copy(alpha = glowAlpha),
                        color.copy(alpha = glowAlpha * 0.5f)
                    )
                ),
                start = Offset(stringCenterX, 0f),
                end = Offset(stringCenterX, stringHeight),
                strokeWidth = strokeWidth * 4,
                cap = StrokeCap.Round
            )

            // Draw string with deflection curve
            if (abs(deflection) > 0.01f) {
                path.reset()
                path.moveTo(stringCenterX, 0f)
                val midY = stringHeight / 2f
                val deflectionX = deflection * stringWidth * 0.8f
                path.quadraticTo(
                    stringCenterX + deflectionX, midY,
                    stringCenterX, stringHeight
                )
                drawPath(
                    path = path,
                    color = color.copy(alpha = alpha),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            } else {
                // Straight string
                drawLine(
                    color = color.copy(alpha = alpha),
                    start = Offset(stringCenterX, 0f),
                    end = Offset(stringCenterX, stringHeight),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }

            // Draw string label at top
            // (Labels are drawn as simple circles at the top for touch indication)
            drawCircle(
                color = color.copy(alpha = if (isActive) 1f else 0.5f),
                radius = 12f,
                center = Offset(stringCenterX, 24f)
            )
        }
    }
}

// ==================== PREVIEWS ====================

@Preview(widthDp = 360, heightDp = 300)
@Composable
private fun CompactStringPanelPreview() {
    OrpheusTheme {
        CompactStringPanel(
            voiceState = VoiceUiState(
                voiceStates = List(8) { index -> VoiceState(index = index) },
                quadGroupPitches = listOf(0.5f, 0.5f),
                quadGroupHolds = listOf(0f, 0f)
            ),
            actions = VoicePanelActions.EMPTY
        )
    }
}
