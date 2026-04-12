package org.balch.orpheus.features.dj

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.plugin.symbols.DjSource
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.SignalTrace
import org.balch.orpheus.ui.widgets.BenderFaderWidget
import org.balch.orpheus.ui.widgets.RotaryKnob
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Cleveland Guardians palette for DJ panel
private data class DjColors(
    val panelColor: Color = OrpheusColors.djRed,
    val knobTrackColor: Color = OrpheusColors.djNavy,
    val knobProgressColor: Color = OrpheusColors.djRed,
    val knobColor: Color = OrpheusColors.djRedLight,
    val labelColor: Color = OrpheusColors.djRed,
    val deckAColor: Color = OrpheusColors.djRedLight,
    val deckBColor: Color = OrpheusColors.djCream,
    val frozenColor: Color = OrpheusColors.djIceBlue,
)

@Composable
fun DjPanel(
    feature: DjFeature,
    vizFlowA: StateFlow<FloatArray>,
    vizFlowB: StateFlow<FloatArray>,
    outVizFlow: StateFlow<FloatArray>,
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
    showExpandedTitle: Boolean = true,
) {
    val djColors = remember { DjColors() }
    val state by feature.stateFlow.collectAsState()
    val actions = feature.actions
    val vizA by vizFlowA.collectAsState()
    val vizB by vizFlowB.collectAsState()
    val outViz by outVizFlow.collectAsState()

    CollapsibleColumnPanel(
        title = "DJ",
        color = djColors.panelColor,
        expandedTitle = if (showExpandedTitle) "Itchy & Scratchy" else null,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader,
        backgroundContent = {
            SignalTrace(data = outViz, color = djColors.deckAColor, alpha = 0.25f)
        },
    ) {
        // ── Deck A | Fader A | knobs | Fader B | Deck B ──────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Deck A platter + source
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                TurntablePlatter(
                    vizData = vizA,
                    frozen = state.frozenA,
                    locked = state.lockedA,
                    velocity = state.velocityA,
                    wet = state.wetA,
                    deckColor = djColors.deckAColor,
                    frozenColor = djColors.frozenColor,
                    deckLabel = "A",
                    onDrag = { velocity -> actions.setPlatterDrag(0, velocity) },
                    onRelease = { actions.setPlatterRelease(0) },
                    onToggleLock = { actions.toggleLock(0) },
                    modifier = Modifier.size(100.dp),
                )
                SourceDropdown(
                    source = state.sourceA,
                    onSourceChange = actions.setSourceA,
                    color = djColors.panelColor,
                )
            }

            // Center: Fader A | DELAY/REVERB knobs | Fader B
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Fader A
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("A", color = djColors.deckAColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    BenderFaderWidget(
                        value = state.wetA * 2f - 1f,
                        onValueChange = { v -> actions.setWetA((v + 1f) / 2f) },
                        trackHeight = 120,
                        trackWidth = 8,
                        thumbWidth = 32,
                        thumbHeight = 18,
                        accentColor = djColors.deckAColor,
                        springBack = false,
                    )
                }

                // Knobs stacked vertically between faders
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RotaryKnob(
                        value = state.delaySend,
                        onValueChange = actions.setDelaySend,
                        label = "DLY",
                        size = 28.dp,
                        trackColor = djColors.knobTrackColor,
                        progressColor = djColors.knobProgressColor,
                        knobColor = djColors.knobColor,
                        labelColor = djColors.labelColor,
                        controlId = "dj_delay_send",
                    )
                    RotaryKnob(
                        value = state.reverbSend,
                        onValueChange = actions.setReverbSend,
                        label = "RVB",
                        size = 28.dp,
                        trackColor = djColors.knobTrackColor,
                        progressColor = djColors.knobProgressColor,
                        knobColor = djColors.knobColor,
                        labelColor = djColors.labelColor,
                        controlId = "dj_reverb_send",
                    )
                }

                // Fader B
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("B", color = djColors.deckBColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    BenderFaderWidget(
                        value = state.wetB * 2f - 1f,
                        onValueChange = { v -> actions.setWetB((v + 1f) / 2f) },
                        trackHeight = 120,
                        trackWidth = 8,
                        thumbWidth = 32,
                        thumbHeight = 18,
                        accentColor = djColors.deckBColor,
                        springBack = false,
                    )
                }
            }

            // Deck B platter + source
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                TurntablePlatter(
                    vizData = vizB,
                    frozen = state.frozenB,
                    locked = state.lockedB,
                    velocity = state.velocityB,
                    wet = state.wetB,
                    deckColor = djColors.deckBColor,
                    frozenColor = djColors.frozenColor,
                    deckLabel = "B",
                    onDrag = { velocity -> actions.setPlatterDrag(1, velocity) },
                    onRelease = { actions.setPlatterRelease(1) },
                    onToggleLock = { actions.toggleLock(1) },
                    modifier = Modifier.size(100.dp),
                )
                SourceDropdown(
                    source = state.sourceB,
                    onSourceChange = actions.setSourceB,
                    color = djColors.panelColor,
                )
            }
        }

    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Private composables
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Circular turntable platter with radial waveform visualization and drag-to-scratch.
 *
 * vizData layout: [0..127] = 128 waveform samples, [128] = normalized playhead position (0-1).
 * Touch drag computes angular velocity from the drag delta relative to platter center.
 */
@Composable
private fun TurntablePlatter(
    vizData: FloatArray,
    frozen: Boolean,
    locked: Boolean,
    velocity: Float,
    wet: Float,
    deckColor: Color,
    frozenColor: Color,
    deckLabel: String,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
    onToggleLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentOnToggleLock by rememberUpdatedState(onToggleLock)
    val currentVelocity by rememberUpdatedState(velocity)
    val currentWet by rememberUpdatedState(wet)

    // Continuous rotation animation — only spins when this deck's wet > 0
    var rotationAngle by remember { mutableStateOf(0f) }
    // Pulsing alpha for frozen overlay / locked spindle
    var pulseAlpha by remember { mutableStateOf(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            if (currentWet > 0.001f) {
                rotationAngle += currentVelocity * 0.05f
            }
            val pulse = kotlin.math.abs(kotlin.math.sin(System.nanoTime() / 166_000_000f))
            pulseAlpha = 0.4f + 0.6f * pulse
        }
    }

    val isFrozen = frozen || locked
    val borderColor = if (isFrozen) frozenColor else deckColor

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    // Long-press center spindle to toggle lock
                    detectTapGestures(
                        onLongPress = { offset ->
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val dx = offset.x - cx
                            val dy = offset.y - cy
                            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                            val spindleRadius = minOf(size.width, size.height) / 2f * 0.35f
                            if (dist < spindleRadius) {
                                currentOnToggleLock()
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    var lastY = 0f
                    // Vertical drag: up = forward (positive), down = backward (negative).
                    // High sensitivity so small wiggles produce audible scratch.
                    // A 10px mouse move should yield ~1.0 velocity.
                    val pxToVelocity = -0.1f

                    detectDragGestures(
                        onDragStart = { offset ->
                            lastY = offset.y
                            currentOnDrag(0f) // touching, stopped
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val deltaY = change.position.y - lastY
                            val scratchVelocity = (deltaY * pxToVelocity).coerceIn(-5f, 5f)
                            currentOnDrag(scratchVelocity)
                            lastY = change.position.y
                        },
                        onDragEnd = { currentOnRelease() },
                        onDragCancel = { currentOnRelease() },
                    )
                },
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val outerRadius = (size.minDimension / 2f) - 4f
            val innerRadius = outerRadius * 0.3f
            val waveRadius = outerRadius * 0.7f

            // Frozen: filled ice overlay that pulses
            if (isFrozen) {
                drawCircle(
                    color = frozenColor.copy(alpha = pulseAlpha * 0.15f),
                    radius = outerRadius,
                    center = Offset(cx, cy),
                )
            }

            // Outer ring border — thicker when frozen
            drawCircle(
                color = borderColor,
                radius = outerRadius,
                center = Offset(cx, cy),
                style = Stroke(width = if (isFrozen) 4f else 2f),
            )

            // Inner ring
            drawCircle(
                color = borderColor.copy(alpha = 0.3f),
                radius = waveRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 1f),
            )

            // Radial waveform ring — etched grooves with highlight/shadow
            val sampleCount = vizData.size.coerceAtMost(128)
            val etchOffset = 0.012f // angular offset for highlight/shadow
            val highlightColor = Color.White.copy(alpha = 0.35f)
            val shadowColor = Color.Black.copy(alpha = 0.4f)
            val grooveColor = deckColor.copy(alpha = 0.5f)
            if (sampleCount > 0) {
                for (i in 0 until sampleCount) {
                    val baseAngle = (i.toFloat() / sampleCount) * 2f * PI.toFloat() - (PI.toFloat() / 2f) + rotationAngle
                    val sample = vizData[i].coerceIn(-1f, 1f)
                    val r0 = waveRadius
                    val r1 = waveRadius + sample * (outerRadius - waveRadius) * 0.8f

                    // Shadow (clockwise offset)
                    val shadowAngle = baseAngle + etchOffset
                    drawLine(
                        color = shadowColor,
                        start = Offset(cx + cos(shadowAngle) * r0, cy + sin(shadowAngle) * r0),
                        end = Offset(cx + cos(shadowAngle) * r1, cy + sin(shadowAngle) * r1),
                        strokeWidth = 1.5f,
                    )
                    // Main groove
                    drawLine(
                        color = grooveColor,
                        start = Offset(cx + cos(baseAngle) * r0, cy + sin(baseAngle) * r0),
                        end = Offset(cx + cos(baseAngle) * r1, cy + sin(baseAngle) * r1),
                        strokeWidth = 1.5f,
                    )
                    // Highlight (counter-clockwise offset)
                    val hlAngle = baseAngle - etchOffset
                    drawLine(
                        color = highlightColor,
                        start = Offset(cx + cos(hlAngle) * r0, cy + sin(hlAngle) * r0),
                        end = Offset(cx + cos(hlAngle) * r1, cy + sin(hlAngle) * r1),
                        strokeWidth = 1f,
                    )
                }
            }

            // Playhead line — vizData[128] if available
            val playheadPos = if (vizData.size > 128) vizData[128] else 0f
            val playheadAngle = playheadPos * 2f * PI.toFloat() - (PI.toFloat() / 2f) + rotationAngle
            drawLine(
                color = borderColor,
                start = Offset(
                    cx + cos(playheadAngle) * innerRadius,
                    cy + sin(playheadAngle) * innerRadius,
                ),
                end = Offset(
                    cx + cos(playheadAngle) * outerRadius,
                    cy + sin(playheadAngle) * outerRadius,
                ),
                strokeWidth = 2f,
            )

            // Center spindle — pulses when frozen/locked
            drawCircle(
                color = borderColor.copy(alpha = if (isFrozen) pulseAlpha else 1f),
                radius = innerRadius * 0.4f,
                center = Offset(cx, cy),
            )

            // Glass lens overlay — top specular highlight fading to transparent
            drawCircle(
                brush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = 0.18f),
                    0.35f to Color.White.copy(alpha = 0.06f),
                    0.5f to Color.Transparent,
                    0.85f to Color.Transparent,
                    1.0f to Color.White.copy(alpha = 0.04f),
                    startY = cy - outerRadius,
                    endY = cy + outerRadius,
                ),
                radius = outerRadius,
                center = Offset(cx, cy),
            )
        }

        // Deck label overlay at center
        Text(
            text = deckLabel,
            color = borderColor.copy(alpha = 0.5f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }

    // Velocity readout below platter
    Text(
        text = ((velocity * 100).roundToInt() / 100.0).toString(),
        color = deckColor.copy(alpha = 0.6f),
        fontSize = 9.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Compact source selector dropdown for a deck.
 */
@Composable
private fun SourceDropdown(
    source: DjSource,
    onSourceChange: (Int) -> Unit,
    color: Color,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "SOURCE",
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.7f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )

        Spacer(Modifier.height(2.dp))

        Box(
            modifier = Modifier
                .clickable { expanded = true }
                .clip(RoundedCornerShape(6.dp))
                .background(OrpheusColors.darkVoid.copy(alpha = 0.6f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = source.label,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select source",
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(OrpheusColors.panelSurface),
            ) {
                DjSource.entries.forEach { entry ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = entry.label,
                                color = if (entry == source) color else Color.White,
                            )
                        },
                        onClick = {
                            onSourceChange(entry.sourceId)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}