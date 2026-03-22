package org.balch.orpheus.features.dj

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
    val frozenColor: Color = OrpheusColors.djCream,
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
        expandedTitle = "Scratchers",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        backgroundContent = {
            // Override LocalSignalVizEnabled so trace draws regardless of Signal Monitor
            androidx.compose.runtime.CompositionLocalProvider(
                org.balch.orpheus.ui.viz.LocalSignalVizEnabled provides true,
            ) {
                SignalTrace(data = outViz, color = djColors.deckAColor, alpha = 0.25f)
            }
        },
    ) {
        // ── Top row: DELAY | REVERB | spacer | MIX ──────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RotaryKnob(
                value = state.delaySend,
                onValueChange = actions.setDelaySend,
                label = "DELAY",
                size = 30.dp,
                trackColor = djColors.knobTrackColor,
                progressColor = djColors.knobProgressColor,
                knobColor = djColors.knobColor,
                labelColor = djColors.labelColor,
                controlId = "dj_delay_send",
            )

            RotaryKnob(
                value = state.reverbSend,
                onValueChange = actions.setReverbSend,
                label = "REVERB",
                size = 30.dp,
                trackColor = djColors.knobTrackColor,
                progressColor = djColors.knobProgressColor,
                knobColor = djColors.knobColor,
                labelColor = djColors.labelColor,
                controlId = "dj_reverb_send",
            )

            Spacer(Modifier.width(16.dp))

            RotaryKnob(
                value = state.mix,
                onValueChange = actions.setMix,
                label = "MIX",
                size = 38.dp,
                trackColor = djColors.knobTrackColor,
                progressColor = djColors.knobProgressColor,
                knobColor = djColors.knobColor,
                labelColor = djColors.labelColor,
                controlId = "dj_mix",
            )
        }

        Spacer(Modifier.height(4.dp))

        // ── Main row: Deck A | Crossfader | Deck B ─────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Deck A
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                TurntablePlatter(
                    vizData = vizA,
                    frozen = state.frozenA,
                    velocity = state.velocityA,
                    mix = state.mix,
                    deckColor = djColors.deckAColor,
                    frozenColor = djColors.frozenColor,
                    deckLabel = "A",
                    onDrag = { velocity -> actions.setPlatterDrag(0, velocity) },
                    onRelease = { actions.setPlatterRelease(0) },
                    modifier = Modifier.size(100.dp),
                )
                SourceDropdown(
                    source = state.sourceA,
                    onSourceChange = actions.setSourceA,
                    color = djColors.panelColor,
                )
            }

            // Crossfader — vertical fader between platters
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                Text(
                    text = "A",
                    color = djColors.deckAColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
                BenderFaderWidget(
                    value = -(state.crossfader * 2f - 1f), // 0..1 → -1..+1, inverted (up=A)
                    onValueChange = { v ->
                        // -1..+1 → 0..1 (inverted: up=A=0, down=B=1)
                        actions.setCrossfader((-v + 1f) / 2f)
                    },
                    trackHeight = 100,
                    trackWidth = 8,
                    thumbWidth = 36,
                    thumbHeight = 20,
                    accentColor = djColors.panelColor,
                    springBack = false,
                )
                Text(
                    text = "B",
                    color = djColors.deckBColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Deck B
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                TurntablePlatter(
                    vizData = vizB,
                    frozen = state.frozenB,
                    velocity = state.velocityB,
                    mix = state.mix,
                    deckColor = djColors.deckBColor,
                    frozenColor = djColors.frozenColor,
                    deckLabel = "B",
                    onDrag = { velocity -> actions.setPlatterDrag(1, velocity) },
                    onRelease = { actions.setPlatterRelease(1) },
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
    velocity: Float,
    mix: Float,
    deckColor: Color,
    frozenColor: Color,
    deckLabel: String,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentVelocity by rememberUpdatedState(velocity)
    val currentMix by rememberUpdatedState(mix)

    // Continuous rotation animation — only spins when mix > 0
    var rotationAngle by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { }
            if (currentMix > 0.001f) {
                rotationAngle += currentVelocity * 0.05f
            }
        }
    }

    val borderColor = if (frozen) frozenColor else deckColor

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
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
                            val scratchVelocity = (deltaY * pxToVelocity).coerceIn(-3f, 3f)
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

            // Outer ring border
            drawCircle(
                color = borderColor,
                radius = outerRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 2f),
            )

            // Inner ring
            drawCircle(
                color = borderColor.copy(alpha = 0.3f),
                radius = waveRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 1f),
            )

            // Radial waveform ring — 128 samples mapped around the circle
            val sampleCount = vizData.size.coerceAtMost(128)
            if (sampleCount > 0) {
                for (i in 0 until sampleCount) {
                    val angle = (i.toFloat() / sampleCount) * 2f * PI.toFloat() - (PI.toFloat() / 2f) + rotationAngle
                    val sample = vizData[i].coerceIn(-1f, 1f)
                    val r0 = waveRadius
                    val r1 = waveRadius + sample * (outerRadius - waveRadius) * 0.8f

                    val x0 = cx + cos(angle) * r0
                    val y0 = cy + sin(angle) * r0
                    val x1 = cx + cos(angle) * r1
                    val y1 = cy + sin(angle) * r1

                    drawLine(
                        color = deckColor.copy(alpha = 0.6f),
                        start = Offset(x0, y0),
                        end = Offset(x1, y1),
                        strokeWidth = 1.5f,
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

            // Center spindle
            drawCircle(
                color = borderColor,
                radius = innerRadius * 0.4f,
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
        text = "%.2f".format(velocity),
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
                            onSourceChange(entry.index)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
