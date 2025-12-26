package org.balch.orpheus.ui.compact

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.balch.orpheus.core.midi.MidiMappingState.Companion.ControlIds
import org.balch.orpheus.features.presets.PresetsViewModel
import org.balch.orpheus.features.voice.SynthKeyboardHandler
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.ui.panels.LocalLiquidEffects
import org.balch.orpheus.ui.panels.LocalLiquidState
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.liquidVizEffects
import org.balch.orpheus.ui.widgets.RotaryKnob
import kotlin.math.abs

/**
 * Compact Portrait Layout: Designed for mobile portrait orientation.
 * Displays 4 strummable "strings" that trigger voices when plucked.
 * Each string represents a pair of voices (duo).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactPortraitScreen(
    modifier: Modifier = Modifier,
    voiceViewModel: VoiceViewModel = metroViewModel(),
    presetViewModel: PresetsViewModel = metroViewModel(),
) {
    val voiceState by voiceViewModel.uiState.collectAsState()
    val presetState by presetViewModel.uiState.collectAsState()
    val liquidState = LocalLiquidState.current
    val effects = LocalLiquidEffects.current
    val shape = RoundedCornerShape(12.dp)

    // Focus handling for keyboard input
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // Preset dropdown state
    var presetDropdownExpanded by remember { mutableStateOf(false) }

    // String colors for the 4 duos
    val stringColors = listOf(
        OrpheusColors.neonMagenta,
        OrpheusColors.electricBlue,
        OrpheusColors.warmGlow,
        OrpheusColors.synthGreen
    )

    Row(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                SynthKeyboardHandler.handleKeyEvent(
                    keyEvent = event,
                    voiceState = voiceState,
                    voiceViewModel = voiceViewModel,
                    isDialogActive = presetDropdownExpanded
                )
            }
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
    ) {
        // Left controls column
        Column(
            modifier = Modifier
                .width(80.dp)
                .fillMaxHeight()
                .padding(end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Quad 1 Pitch
            RotaryKnob(
                value = voiceState.quadGroupPitches[0],
                onValueChange = { voiceViewModel.onQuadPitchChange(0, it) },
                label = "PITCH",
                controlId = ControlIds.quadPitch(0),
                size = 40.dp,
                progressColor = OrpheusColors.neonMagenta
            )
            // Quad 1 Hold
            RotaryKnob(
                value = voiceState.quadGroupHolds[0],
                onValueChange = { voiceViewModel.onQuadHoldChange(0, it) },
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
                        voiceViewModel.onPulseStart(voiceA)
                        voiceViewModel.onPulseStart(voiceB)
                    }
                },
                onStringReleased = { stringIndex ->
                    val voiceA = stringIndex * 2
                    val voiceB = stringIndex * 2 + 1
                    voiceViewModel.onPulseEnd(voiceA)
                    voiceViewModel.onPulseEnd(voiceB)
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
            // Quad 2 Pitch
            RotaryKnob(
                value = voiceState.quadGroupPitches[1],
                onValueChange = { voiceViewModel.onQuadPitchChange(1, it) },
                label = "PITCH",
                controlId = ControlIds.quadPitch(1),
                size = 40.dp,
                progressColor = OrpheusColors.synthGreen
            )
            // Quad 2 Hold
            RotaryKnob(
                value = voiceState.quadGroupHolds[1],
                onValueChange = { voiceViewModel.onQuadHoldChange(1, it) },
                label = "HOLD",
                controlId = ControlIds.quadHold(1),
                size = 40.dp,
                progressColor = OrpheusColors.warmGlow
            )
        }
    }

    // Bottom: Patch selector (minimal row at bottom)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        ExposedDropdownMenuBox(
            expanded = presetDropdownExpanded,
            onExpandedChange = { presetDropdownExpanded = it }
        ) {
            TextField(
                value = presetState.selectedPreset?.name ?: "Select Patch",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetDropdownExpanded) },
                modifier = Modifier
                    .menuAnchor()
                    .width(200.dp),
                textStyle = MaterialTheme.typography.bodySmall
            )
            ExposedDropdownMenu(
                expanded = presetDropdownExpanded,
                onDismissRequest = { presetDropdownExpanded = false }
            ) {
                presetState.presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name) },
                        onClick = {
                            presetViewModel.applyPreset(preset)
                            presetDropdownExpanded = false
                        }
                    )
                }
            }
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
    voiceStates: List<org.balch.orpheus.core.audio.VoiceState>,
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
