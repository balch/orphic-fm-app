package org.balch.orpheus.features.dj

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.plugin.symbols.DjDrop
import org.balch.orpheus.core.plugin.symbols.DjSource
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.SignalTrace
import org.balch.orpheus.ui.widgets.BenderFaderWidget
import org.balch.orpheus.ui.widgets.RotaryKnob
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Strip visibility tuning.
private const val kStripRevealDelayMs = 300L       // sustained drag required before strip appears
private const val kStripAudibleWet = 0.5f          // deck must be this wet for strip to arm
private val kStripReservedHeight = 48.dp           // reserved space; strip fades in inside

// Shared zero-beat-phase fallback for @Preview composables and any call-site that
// hasn't wired the engine's real beatPhaseFlow. Hoisted so the default doesn't
// allocate a new MutableStateFlow on every composition.
private val kConstantZeroBeatPhase: StateFlow<Float> = MutableStateFlow(0f)

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
    beatPhaseFlow: StateFlow<Float> = kConstantZeroBeatPhase,
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
    val beatPhase by beatPhaseFlow.collectAsState()

    var platterABounds by remember { mutableStateOf<Rect?>(null) }
    var platterBBounds by remember { mutableStateOf<Rect?>(null) }
    var stripBounds by remember { mutableStateOf<Rect?>(null) }
    var armingDeck by remember { mutableStateOf<Int?>(null) }
    var armingZoneIdx by remember { mutableStateOf(0) }
    var armingProgress by remember { mutableStateOf(0f) }
    // Outer-box origin in window coords. We hit-test using window-space rects
    // (captured via boundsInWindow) and must convert local pointer positions
    // into window space by adding this offset.
    var outerOrigin by remember { mutableStateOf(Offset.Zero) }

    val currentActions by rememberUpdatedState(actions)
    val currentZoneOrder by rememberUpdatedState(state.zoneOrder)

    // Strip visibility — gated on an audible deck being actively dragged for
    // at least kStripRevealDelayMs. Fast flings (hold < delay) don't trigger
    // the strip. Decks with wet <= kStripAudibleWet are inaudible, so there's
    // no point offering a drop.
    val audibleDragged = (state.decks.getOrNull(0)?.dragActive == true && state.wetA > kStripAudibleWet) ||
                         (state.decks.getOrNull(1)?.dragActive == true && state.wetB > kStripAudibleWet)
    var stripRevealed by remember { mutableStateOf(false) }
    LaunchedEffect(audibleDragged) {
        if (audibleDragged) {
            delay(kStripRevealDelayMs)
            stripRevealed = true
        } else {
            stripRevealed = false
        }
    }
    val currentStripRevealed by rememberUpdatedState(stripRevealed)

    LaunchedEffect(armingDeck, armingZoneIdx) {
        armingProgress = 0f
        val deck = armingDeck ?: return@LaunchedEffect
        val zoneIdx = armingZoneIdx
        var startNanos = -1L
        while (armingDeck == deck && armingZoneIdx == zoneIdx) {
            var shouldBreak = false
            withFrameNanos { nowNanos ->
                if (startNanos < 0L) startNanos = nowNanos
                val elapsedMs = (nowNanos - startNanos) / 1_000_000f
                armingProgress = (elapsedMs / 120f).coerceIn(0f, 1f)
                if (elapsedMs >= 120f) {
                    val drop = currentZoneOrder.getOrNull(zoneIdx) ?: DjDrop.NONE
                    if (drop != DjDrop.NONE) {
                        currentActions.setDrop(deck, drop)
                    }
                    armingDeck = null
                    armingProgress = 0f
                    shouldBreak = true
                }
            }
            if (shouldBreak) break
        }
    }

    Box(
        modifier = Modifier
            .onGloballyPositioned { outerOrigin = it.positionInWindow() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPosWin = Offset(down.position.x + outerOrigin.x,
                                             down.position.y + outerOrigin.y)
                    val deck = when {
                        platterABounds?.contains(startPosWin) == true -> 0
                        platterBBounds?.contains(startPosWin) == true -> 1
                        else -> -1
                    }
                    if (deck < 0) return@awaitEachGesture

                    currentActions.setDragActive(deck, true)
                    currentActions.setPlatterDrag(deck, 0f)
                    var lastY = down.position.y

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val posWin = Offset(change.position.x + outerOrigin.x,
                                                change.position.y + outerOrigin.y)

                            val isLocked = state.decks.getOrNull(deck)?.drop != null &&
                                    state.decks.getOrNull(deck)?.drop != DjDrop.NONE
                            val strip = stripBounds
                            // Only treat pointer-in-strip as arming territory once the strip
                            // is actually visible — during the 300ms reveal delay (or when
                            // the deck is inaudible) the strip is chrome, not interactive.
                            val inStrip = currentStripRevealed && strip != null && strip.contains(posWin)

                            if (inStrip) {
                                // Inside strip. Arm a new zone only if no drop is locked yet;
                                // once locked the zone is permanent for this gesture, so further
                                // in-strip motion is a no-op (don't re-arm, don't scratch).
                                if (!isLocked) {
                                    // strip is non-null: inStrip implies it.
                                    val xInStrip = ((posWin.x - strip.left) / strip.width).coerceIn(0f, 0.9999f)
                                    val idx = (xInStrip * 4f).toInt().coerceIn(0, 3)
                                    armingDeck = deck
                                    armingZoneIdx = idx
                                }
                                // Keep lastY fresh so the first scratch sample after the pointer
                                // leaves the strip computes delta from the exit point, not from
                                // some stale pre-strip position (which would produce a click).
                                lastY = change.position.y
                            } else {
                                // Outside strip: full scratch control, whether locked or not.
                                // After lock-in this is the path back to platter control — the
                                // drop effect keeps processing whatever scratch velocity produces.
                                if (!isLocked) {
                                    armingDeck = null
                                    armingProgress = 0f
                                }
                                val deltaY = change.position.y - lastY
                                val v = (deltaY * -0.1f).coerceIn(-5f, 5f)
                                currentActions.setPlatterDrag(deck, v)
                                lastY = change.position.y
                            }
                            change.consume()
                        }
                    } finally {
                        currentActions.setPlatterRelease(deck)
                        currentActions.setDrop(deck, DjDrop.NONE)
                        currentActions.setDragActive(deck, false)
                        armingDeck = null
                        armingProgress = 0f
                    }
                }
            }
    ) {
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
        Column(modifier = Modifier.fillMaxWidth()) {
        // ── Deck A | Fader A | knobs | Fader B | Deck B ──────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Deck A platter + source
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                SourceDropdown(
                    source = state.sourceA,
                    onSourceChange = actions.setSourceA,
                    color = djColors.panelColor,
                )
                TurntablePlatter(
                    vizData = vizA,
                    frozen = state.frozenA,
                    locked = state.lockedA,
                    velocity = state.velocityA,
                    wet = state.wetA,
                    deckColor = djColors.deckAColor,
                    frozenColor = djColors.frozenColor,
                    deckLabel = "A",
                    onBounds = { platterABounds = it },
                    onToggleLock = { actions.toggleLock(0) },
                    modifier = Modifier.size(100.dp),
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
                    verticalArrangement = Arrangement.spacedBy(16.dp),
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
                        valueFormatter = null,
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
                        valueFormatter = null,
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                SourceDropdown(
                    source = state.sourceB,
                    onSourceChange = actions.setSourceB,
                    color = djColors.panelColor,
                )
                TurntablePlatter(
                    vizData = vizB,
                    frozen = state.frozenB,
                    locked = state.lockedB,
                    velocity = state.velocityB,
                    wet = state.wetB,
                    deckColor = djColors.deckBColor,
                    frozenColor = djColors.frozenColor,
                    deckLabel = "B",
                    onBounds = { platterBBounds = it },
                    onToggleLock = { actions.toggleLock(1) },
                    modifier = Modifier.size(100.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Strip always occupies kStripReservedHeight so the turntables never
        // shift when drops become available. Alpha + translationY animation
        // gives the slide-in-from-below look without collapsing layout.
        val stripAlpha by animateFloatAsState(
            targetValue = if (stripRevealed) 1f else 0f,
            animationSpec = tween(durationMillis = 180),
            label = "stripAlpha",
        )
        val stripOffset by animateDpAsState(
            targetValue = if (stripRevealed) 0.dp else 16.dp,
            animationSpec = tween(durationMillis = 180),
            label = "stripOffset",
        )
        DropZoneStrip(
            zoneOrder = state.zoneOrder,
            decks = state.decks,
            beatPhase = beatPhase,
            armingDeck = armingDeck,
            armingZoneIdx = armingZoneIdx,
            armingProgress = armingProgress,
            deckAColor = djColors.deckAColor,
            deckBColor = djColors.deckBColor,
            onStripBounds = { stripBounds = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(kStripReservedHeight)
                .graphicsLayer {
                    alpha = stripAlpha
                    translationY = stripOffset.toPx()
                },
        )
        } // Column
    }
    } // outer Box
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
    onBounds: (Rect) -> Unit,
    onToggleLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnToggleLock by rememberUpdatedState(onToggleLock)
    val currentVelocity by rememberUpdatedState(velocity)
    val currentWet by rememberUpdatedState(wet)

    // Continuous rotation animation — only spins when this deck's wet > 0
    var rotationAngle by remember { mutableStateOf(0f) }
    // Pulsing alpha for frozen overlay / locked spindle
    var pulseAlpha by remember { mutableStateOf(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos ->
                if (currentWet > 0.001f) {
                    rotationAngle += currentVelocity * 0.05f
                }
                val pulse = kotlin.math.abs(sin(frameNanos / 166_000_000f))
                pulseAlpha = 0.4f + 0.6f * pulse
            }
        }
    }

    val isFrozen = frozen || locked
    val borderColor = if (isFrozen) frozenColor else deckColor

    Box(
        modifier = modifier.onGloballyPositioned { coords -> onBounds(coords.boundsInWindow()) },
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
    modifier: Modifier = Modifier,
    source: DjSource,
    onSourceChange: (Int) -> Unit,
    color: Color,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .clickable { expanded = true }
            .clip(RoundedCornerShape(6.dp))
            .background(OrpheusColors.darkVoid.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .padding(start = 16.dp),
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

@Composable
private fun DropZoneStrip(
    zoneOrder: List<DjDrop>,
    decks: List<DeckDropState>,
    beatPhase: Float,
    armingDeck: Int?,
    armingZoneIdx: Int,
    armingProgress: Float,
    deckAColor: Color,
    deckBColor: Color,
    onStripBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anyLocked = decks.any { it.drop != DjDrop.NONE }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .onGloballyPositioned { onStripBounds(it.boundsInWindow()) },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        zoneOrder.forEachIndexed { idx, drop ->
            val accent = OrpheusColors.accentFor(drop)
            val lockedBy = buildList {
                if (decks.getOrNull(0)?.drop == drop) add("A" to deckAColor)
                if (decks.getOrNull(1)?.drop == drop) add("B" to deckBColor)
            }
            val dimmed = anyLocked && lockedBy.isEmpty()
            val arming = armingDeck != null && armingZoneIdx == idx && lockedBy.isEmpty()
            DropZoneCell(
                label = drop.label,
                accent = accent,
                beatPhase = beatPhase,
                lockedBy = lockedBy,
                dimmed = dimmed,
                arming = arming,
                armingProgress = if (arming) armingProgress else 0f,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            )
        }
    }
}

@Composable
private fun DropZoneCell(
    label: String,
    accent: Color,
    beatPhase: Float,
    lockedBy: List<Pair<String, Color>>,
    dimmed: Boolean,
    arming: Boolean,
    armingProgress: Float,
    modifier: Modifier = Modifier,
) {
    val pulse = (1f - kotlin.math.cos(beatPhase * 2f * PI.toFloat())) / 2f
    val baseAlpha = when {
        lockedBy.isNotEmpty() -> 1f
        dimmed               -> 0.2f + 0.15f * pulse
        else                 -> 0.35f + 0.45f * pulse
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = baseAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        // Locked: outer stroke glow
        if (lockedBy.isNotEmpty()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawRoundRect(
                    color = accent.copy(alpha = 0.6f),
                    style = Stroke(width = 6f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                )
            }
        }
        // Arming progress arc
        if (arming) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * armingProgress,
                    useCenter = false,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        // Name reveal when locked
        if (lockedBy.isNotEmpty()) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        // Deck-letter markers top-right
        if (lockedBy.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                lockedBy.forEach { (letter, color) ->
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(letter, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

private fun previewVizData(): FloatArray =
    FloatArray(129) { i -> if (i < 128) sin(i * PI.toFloat() / 16f) * 0.6f else 0.25f }

@Preview(name = "DJ Panel — Collapsed", widthDp = 500, heightDp = 80)
@Composable
private fun DjPanelCollapsedPreview() {
    OrpheusTheme {
        DjPanel(
            feature = DjViewModel.previewFeature(),
            vizFlowA = MutableStateFlow(FloatArray(0)),
            vizFlowB = MutableStateFlow(FloatArray(0)),
            outVizFlow = MutableStateFlow(FloatArray(0)),
        )
    }
}

@Preview(name = "DJ Panel — Expanded Default", widthDp = 500, heightDp = 300)
@Composable
private fun DjPanelExpandedPreview() {
    OrpheusTheme {
        DjPanel(
            feature = DjViewModel.previewFeature(),
            vizFlowA = MutableStateFlow(previewVizData()),
            vizFlowB = MutableStateFlow(previewVizData()),
            outVizFlow = MutableStateFlow(previewVizData()),
            isExpanded = true,
        )
    }
}

@Preview(name = "DJ Panel — Deck A Frozen, B Locked", widthDp = 500, heightDp = 300)
@Composable
private fun DjPanelFrozenLockedPreview() {
    OrpheusTheme {
        DjPanel(
            feature = DjViewModel.previewFeature(
                DjUiState(
                    wetA = 0.8f,
                    wetB = 0.6f,
                    velocityA = 1.0f,
                    velocityB = -0.5f,
                    frozenA = true,
                    lockedB = true,
                    sourceA = DjSource.SYNTH,
                    sourceB = DjSource.DRUMS,
                    delaySend = 0.4f,
                    reverbSend = 0.6f,
                )
            ),
            vizFlowA = MutableStateFlow(previewVizData()),
            vizFlowB = MutableStateFlow(previewVizData()),
            outVizFlow = MutableStateFlow(previewVizData()),
            isExpanded = true,
        )
    }
}

@Preview(name = "DJ Panel — Both Decks Hot", widthDp = 500, heightDp = 300)
@Composable
private fun DjPanelBothDecksHotPreview() {
    OrpheusTheme {
        DjPanel(
            feature = DjViewModel.previewFeature(
                DjUiState(
                    wetA = 1.0f,
                    wetB = 1.0f,
                    velocityA = 1.0f,
                    velocityB = 1.0f,
                    crossfader = 0.5f,
                    delaySend = 0.7f,
                    reverbSend = 0.9f,
                    sourceA = DjSource.MASTER,
                )
            ),
            vizFlowA = MutableStateFlow(previewVizData()),
            vizFlowB = MutableStateFlow(previewVizData()),
            outVizFlow = MutableStateFlow(previewVizData()),
            isExpanded = true,
        )
    }
}

@Preview(name = "DJ Panel — Drag Armed (no lock)", widthDp = 500, heightDp = 340)
@Composable
private fun DjPanelDragArmedPreview() {
    OrpheusTheme {
        DjPanel(
            feature = DjViewModel.previewFeature(
                DjUiState(
                    wetA = 0.8f, velocityA = -0.2f,
                    decks = listOf(
                        DeckDropState(dragActive = true, drop = DjDrop.NONE),
                        DeckDropState(),
                    ),
                ),
            ),
            vizFlowA = MutableStateFlow(previewVizData()),
            vizFlowB = MutableStateFlow(previewVizData()),
            outVizFlow = MutableStateFlow(previewVizData()),
            beatPhaseFlow = MutableStateFlow(0f),
            isExpanded = true,
        )
    }
}

@Preview(name = "DJ Panel — Deck A Locked (STUTTER)", widthDp = 500, heightDp = 340)
@Composable
private fun DjPanelDeckALockedPreview() {
    OrpheusTheme {
        DjPanel(
            feature = DjViewModel.previewFeature(
                DjUiState(
                    wetA = 0.8f, wetB = 0.6f,
                    velocityA = -0.3f, velocityB = 1.0f,
                    decks = listOf(
                        DeckDropState(dragActive = true, drop = DjDrop.STUTTER),
                        DeckDropState(dragActive = false, drop = DjDrop.NONE),
                    ),
                ),
            ),
            vizFlowA = MutableStateFlow(previewVizData()),
            vizFlowB = MutableStateFlow(previewVizData()),
            outVizFlow = MutableStateFlow(previewVizData()),
            beatPhaseFlow = MutableStateFlow(0.4f),
            isExpanded = true,
        )
    }
}

@Preview(name = "DJ Panel — Both Decks Locked", widthDp = 500, heightDp = 340)
@Composable
private fun DjPanelBothLockedPreview() {
    OrpheusTheme {
        DjPanel(
            feature = DjViewModel.previewFeature(
                DjUiState(
                    wetA = 0.8f, wetB = 0.7f,
                    decks = listOf(
                        DeckDropState(dragActive = true, drop = DjDrop.FILTER),
                        DeckDropState(dragActive = true, drop = DjDrop.BRAKE),
                    ),
                ),
            ),
            vizFlowA = MutableStateFlow(previewVizData()),
            vizFlowB = MutableStateFlow(previewVizData()),
            outVizFlow = MutableStateFlow(previewVizData()),
            beatPhaseFlow = MutableStateFlow(0.2f),
            isExpanded = true,
        )
    }
}

@Preview(name = "DJ Panel — Shuffled Zone Order", widthDp = 500, heightDp = 340)
@Composable
private fun DjPanelShuffledPreview() {
    OrpheusTheme {
        DjPanel(
            feature = DjViewModel.previewFeature(
                DjUiState(
                    wetA = 0.8f, velocityA = 1.0f,
                    decks = listOf(
                        DeckDropState(dragActive = true, drop = DjDrop.NONE),
                        DeckDropState(),
                    ),
                    zoneOrder = listOf(DjDrop.FREEZE, DjDrop.FILTER, DjDrop.STUTTER, DjDrop.BRAKE),
                ),
            ),
            vizFlowA = MutableStateFlow(previewVizData()),
            vizFlowB = MutableStateFlow(previewVizData()),
            outVizFlow = MutableStateFlow(previewVizData()),
            beatPhaseFlow = MutableStateFlow(0f),
            isExpanded = true,
        )
    }
}