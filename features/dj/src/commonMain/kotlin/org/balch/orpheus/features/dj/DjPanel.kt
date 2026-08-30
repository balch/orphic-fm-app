package org.balch.orpheus.features.dj

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.sign
import kotlin.math.sin

// Strip visibility tuning.
private const val kStripRevealDelayMs = 300L       // sustained drag required before strip appears
private const val kStripAudibleWet = 0.5f          // deck must be this wet for strip to arm
private val kStripReservedHeight = 48.dp           // reserved space; strip fades in inside

// Shared zero-beat-phase fallback for @Preview composables and any call-site that
// hasn't wired the engine's real beatPhaseFlow. Hoisted so the default doesn't
// allocate a new MutableStateFlow on every composition.
private val kConstantZeroBeatPhase: StateFlow<Float> = MutableStateFlow(0f)

/**
 * Quadratic, signed velocity mapping for the drop fader.
 * Input fx is the normalized fader position in [-1, 1] (clamped). Output is a
 * signed velocity in [-10, 10] via sign(fx)·fx²·10. Gives fine control near
 * center and decisive extents. Matches spec §7.
 */
internal fun faderVelocity(fx: Float): Float {
    val c = fx.coerceIn(-1f, 1f)
    return sign(c) * c * c * 10f
}

/**
 * Picks a readable text color for a label drawn on top of [accent].
 * The drop palette spans very light (RING yellow-green, ECHO amber, STUTTER
 * acid green, FILTER ice blue) to medium-dark (OCTAVE deep violet, BRAKE red,
 * FREEZE magenta, PHASER teal), so a single fixed color can't read well
 * against all of them. Return a dark color for bright accents and a light
 * color for dark accents.
 *
 * sRGB relative luminance weights: 0.2126·R + 0.7152·G + 0.0722·B.
 * Suggested return values: [OrpheusColors.darkVoid] and [Color.White].
 */
internal fun contrastTextColor(accent: Color): Color {
    val luminance = 0.2126f * accent.red + 0.7152f * accent.green + 0.0722f * accent.blue
    return if (luminance > 0.5f) OrpheusColors.darkVoid else Color.White
}

/**
 * Preview-only override for local fader state in [DjPanel]. Lets @Preview
 * composables render mid-drive fader states that would otherwise require a
 * live pointer gesture. Production call sites pass null.
 */
@Immutable
data class FaderPreviewOverride(
    val deck: Int,            // 0 or 1
    val pressed: Boolean,
    val thumbX: Float,        // normalized [-1, 1]
)

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
    fillHeight: Boolean = true,
    previewFaderOverride: FaderPreviewOverride? = null,
) {
    val djColors = remember { DjColors() }
    val state by feature.stateFlow.collectAsState()
    val actions = feature.actions
    val vizA by vizFlowA.collectAsState()
    val vizB by vizFlowB.collectAsState()
    val beatPhase by beatPhaseFlow.collectAsState()

    // Beat length measured from phase wraps. There is no BPM on this panel, and the
    // auto-scratch needs a subdivision to land on; a wrap-to-wrap interval gives one without
    // plumbing tempo through. Falls back to 500ms (120 BPM) until two wraps have been seen.
    var beatMillis by remember { mutableStateOf(500L) }
    var focusedDeck by remember { mutableStateOf(-1) }
    // Which deck currently owns the vertical axis, or -1. Separate from focus: a focused
    // deck still lets up/down move focus until it is grabbed.
    var grabbedDeck by remember { mutableStateOf(-1) }
    LaunchedEffect(beatPhaseFlow) {
        var previousPhase = 0f
        var lastWrap: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null
        beatPhaseFlow.collect { phase ->
            if (phase < previousPhase) {
                val mark = kotlin.time.TimeSource.Monotonic.markNow()
                lastWrap?.let { previous ->
                    val elapsed = previous.elapsedNow().inWholeMilliseconds
                    if (elapsed in 200L..2_000L) beatMillis = elapsed
                }
                lastWrap = mark
            }
            previousPhase = phase
        }
    }

    // Hit-test rects are stored in window-space pixels (boundsInWindow /
    // positionInWindow). Those pixel values are density-dependent, so when the
    // window crosses between monitors with different scale factors the cached
    // rects go stale relative to new pointer positions. Keying the remembered
    // state on LocalDensity discards the stale values; fresh layout from the
    // density change re-fires onGloballyPositioned with the new pixel rects.
    val density = LocalDensity.current
    var platterABounds by remember(density) { mutableStateOf<Rect?>(null) }
    var platterBBounds by remember(density) { mutableStateOf<Rect?>(null) }
    var stripBounds by remember(density) { mutableStateOf<Rect?>(null) }
    var armingDeck by remember { mutableStateOf<Int?>(null) }
    var armingZoneIdx by remember { mutableStateOf(0) }
    var armingProgress by remember { mutableStateOf(0f) }
    // Outer-box origin in window coords. We hit-test using window-space rects
    // (captured via boundsInWindow) and must convert local pointer positions
    // into window space by adding this offset.
    var outerOrigin by remember(density) { mutableStateOf(Offset.Zero) }

    // ── Fader state (per deck) ──
    // pressed[deck] = true while the pointer is actively over the fader for that deck.
    // targetX[deck] = the raw normalized pointer x in [-1, 1] that the pointer last drove.
    // displayX[deck] = animated value used for rendering + velocity output.
    var faderPressedA by remember {
        mutableStateOf(previewFaderOverride?.takeIf { it.deck == 0 }?.pressed ?: false)
    }
    var faderPressedB by remember {
        mutableStateOf(previewFaderOverride?.takeIf { it.deck == 1 }?.pressed ?: false)
    }
    var faderTargetXA by remember {
        mutableStateOf(previewFaderOverride?.takeIf { it.deck == 0 }?.thumbX ?: 0f)
    }
    var faderTargetXB by remember {
        mutableStateOf(previewFaderOverride?.takeIf { it.deck == 1 }?.thumbX ?: 0f)
    }

    val faderDisplayXA by animateFloatAsState(
        targetValue = if (faderPressedA) faderTargetXA else 0f,
        animationSpec = if (faderPressedA) tween(durationMillis = 30)
            else spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh),
        label = "faderDisplayXA",
    )
    val faderDisplayXB by animateFloatAsState(
        targetValue = if (faderPressedB) faderTargetXB else 0f,
        animationSpec = if (faderPressedB) tween(durationMillis = 30)
            else spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessHigh),
        label = "faderDisplayXB",
    )

    // ── Derived fader activation ──
    // faderActive[deck] = drop is locked AND not BRAKE. BRAKE has no meaningful
    // forward/reverse velocity to drive (spec §9).
    val anyDropLocked = state.decks.any { it.drop != DjDrop.NONE }
    val faderActiveA = (state.decks.getOrNull(0)?.drop ?: DjDrop.NONE)
        .let { it != DjDrop.NONE && it != DjDrop.BRAKE }
    val faderActiveB = (state.decks.getOrNull(1)?.drop ?: DjDrop.NONE)
        .let { it != DjDrop.NONE && it != DjDrop.BRAKE }
    val currentAnyDropLocked by rememberUpdatedState(anyDropLocked)

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
    val currentFaderActiveA by rememberUpdatedState(faderActiveA)
    val currentFaderActiveB by rememberUpdatedState(faderActiveB)

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
        modifier = modifier
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

                            val lockedDrop = state.decks.getOrNull(deck)?.drop ?: DjDrop.NONE
                            val isLocked = lockedDrop != DjDrop.NONE
                            val faderActiveForThisDeck = when (deck) {
                                0 -> currentFaderActiveA
                                1 -> currentFaderActiveB
                                else -> false
                            }
                            val strip = stripBounds
                            val inStripBounds = strip != null && strip.contains(posWin)
                            // Fader shares the strip row, so bounds coincide. Differentiate
                            // by whether this deck currently has a non-BRAKE drop locked.
                            val inFader = faderActiveForThisDeck && inStripBounds
                            val inStrip = !faderActiveForThisDeck && currentStripRevealed && inStripBounds

                            when {
                                inFader -> {
                                    // Fader drive: quadratic sign(fx)·fx²·10 velocity.
                                    val fx = (((posWin.x - strip.left) / strip.width) * 2f - 1f)
                                        .coerceIn(-1f, 1f)
                                    when (deck) {
                                        0 -> { faderPressedA = true; faderTargetXA = fx }
                                        1 -> { faderPressedB = true; faderTargetXB = fx }
                                    }
                                    currentActions.setPlatterDrag(deck, faderVelocity(fx))
                                    lastY = change.position.y
                                }
                                else -> {
                                    // Not on the fader. Arm a zone if dwelling in the strip,
                                    // and drive velocity based on whether a drop is locked and
                                    // where the pointer is.
                                    val ownPlatter = when (deck) {
                                        0 -> platterABounds
                                        1 -> platterBBounds
                                        else -> null
                                    }
                                    val onOwnPlatter = ownPlatter?.contains(posWin) == true

                                    if (inStrip && !isLocked && !currentAnyDropLocked) {
                                        // Inside strip, nothing locked yet — arm a zone
                                        // (gated on !currentAnyDropLocked: one drop at a time).
                                        val xInStrip = ((posWin.x - strip.left) / strip.width)
                                            .coerceIn(0f, 0.9999f)
                                        val idx = (xInStrip * 4f).toInt().coerceIn(0, 3)
                                        armingDeck = deck
                                        armingZoneIdx = idx
                                    } else if (!isLocked) {
                                        armingDeck = null
                                        armingProgress = 0f
                                    }
                                    // Release fader pressed state on any exit from the fader.
                                    when (deck) {
                                        0 -> faderPressedA = false
                                        1 -> faderPressedB = false
                                    }

                                    // Velocity:
                                    //  - No drop locked → free scratch anywhere under the
                                    //    gesture (deltaY-based drag). Off-platter still tracks
                                    //    mouse motion so the user can scratch freely.
                                    //  - Drop locked + on own platter → scratch (spec §3.5).
                                    //  - Drop locked + empty space (not on fader, not on own
                                    //    platter) → release so physics motor target wins
                                    //    (DROP_MOTOR_SPEED = 3.5×, spec §3.6).
                                    if (!isLocked || onOwnPlatter) {
                                        val deltaY = change.position.y - lastY
                                        // Time-normalize so mouse (high poll rate, small
                                        // per-event deltas) and trackpad (lower poll rate,
                                        // OS-smoothed larger deltas) feel the same. pxPerMs
                                        // is pixels of travel per millisecond — independent
                                        // of event cadence. Amp ramps from 3.5× at slow
                                        // speeds to 5× on fast flings (~3000+ px/s). Clamp
                                        // ±12 lets flings overshoot the fader's ±10 ceiling.
                                        val deltaTime = (change.uptimeMillis - change.previousUptimeMillis)
                                            .coerceAtLeast(1L)
                                        val pxPerMs = deltaY / deltaTime.toFloat()
                                        val speed = kotlin.math.abs(pxPerMs)
                                        // floor 3.5 (bite at slow speeds) + 1.5 range, capped
                                        // when speed reaches 3 px/ms (~3000 px/s).
                                        val ampNorm = (speed / 3f).coerceAtMost(1f)
                                        val amp = 3.5f + 1.5f * ampNorm
                                        val v = (pxPerMs * -amp).coerceIn(-12f, 12f)
                                        currentActions.setPlatterDrag(deck, v)
                                    } else {
                                        currentActions.setPlatterRelease(deck)
                                    }
                                    lastY = change.position.y
                                }
                            }
                            change.consume()
                        }
                    } finally {
                        currentActions.setPlatterRelease(deck)
                        currentActions.setDrop(deck, DjDrop.NONE)
                        currentActions.setDragActive(deck, false)
                        armingDeck = null
                        armingProgress = 0f
                        // Triggers spring-back animation as the fader fades out.
                        when (deck) {
                            0 -> faderPressedA = false
                            1 -> faderPressedB = false
                        }
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
        // Width always, height only when filling: a docked panel sizes to its content.
        modifier = if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        showCollapsedHeader = showCollapsedHeader,
        fillHeight = fillHeight,
        backgroundContent = {
            SignalTrace(data = outVizFlow, color = djColors.deckAColor, alpha = 0.25f)
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
                    focused = focusedDeck == 0,
                    adjusting = grabbedDeck == 0,
                    onBounds = { platterABounds = it },
                    onToggleLock = { actions.toggleLock(0) },
                    modifier = Modifier
                        .size(100.dp)
                        .deckDpad(
                            deck = 0,
                            actions = actions,
                            zoneOrder = state.zoneOrder,
                            beatMillis = { beatMillis },
                            onFocusChanged = { focused -> if (focused) focusedDeck = 0 else if (focusedDeck == 0) focusedDeck = -1 },
                            onAdjustingChanged = { grabbed -> if (grabbed) grabbedDeck = 0 else if (grabbedDeck == 0) grabbedDeck = -1 },
                        ),
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
                    focused = focusedDeck == 1,
                    adjusting = grabbedDeck == 1,
                    onBounds = { platterBBounds = it },
                    onToggleLock = { actions.toggleLock(1) },
                    modifier = Modifier
                        .size(100.dp)
                        .deckDpad(
                            deck = 1,
                            actions = actions,
                            zoneOrder = state.zoneOrder,
                            beatMillis = { beatMillis },
                            onFocusChanged = { focused -> if (focused) focusedDeck = 1 else if (focusedDeck == 1) focusedDeck = -1 },
                            onAdjustingChanged = { grabbed -> if (grabbed) grabbedDeck = 1 else if (grabbedDeck == 1) grabbedDeck = -1 },
                        ),
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
            faderActiveA = faderActiveA,
            faderActiveB = faderActiveB,
            faderDisplayXA = faderDisplayXA,
            faderDisplayXB = faderDisplayXB,
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
    focused: Boolean = false,
    adjusting: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val currentOnToggleLock by rememberUpdatedState(onToggleLock)
    val currentVelocity by rememberUpdatedState(velocity)
    val currentWet by rememberUpdatedState(wet)

    // Held as FloatState (no `by` delegate) so reads happen *inside* the
    // Canvas drawScope below — 60Hz writes only invalidate the draw pass
    // instead of recomposing this composable.
    val rotationAngle = remember { mutableFloatStateOf(0f) }
    // Pulsing alpha for frozen overlay / locked spindle.
    val pulseAlpha = remember { mutableFloatStateOf(1f) }
    val isFrozen = frozen || locked
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameNanos ->
                // At-rest early-out: if the deck is silent and not frozen,
                // there's no rotation and no pulse to compute.
                if (!isFrozen && currentWet <= 0.001f) return@withFrameNanos
                if (currentWet > 0.001f) {
                    rotationAngle.floatValue += currentVelocity * 0.05f
                }
                if (isFrozen) {
                    val pulse = kotlin.math.abs(sin(frameNanos / 166_000_000f))
                    pulseAlpha.floatValue = 0.4f + 0.6f * pulse
                }
            }
        }
    }

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
            // Snapshot animation state inside drawScope: writes to these
            // FloatStates only invalidate this draw, not recompose the parent.
            val rot = rotationAngle.floatValue
            val pulse = pulseAlpha.floatValue

            val cx = size.width / 2f
            val cy = size.height / 2f
            val outerRadius = (size.minDimension / 2f) - 4f
            val innerRadius = outerRadius * 0.3f
            val waveRadius = outerRadius * 0.7f

            // D-pad focus ring, drawn under everything so the platter art stays readable.
            // Grabbed draws heavier than merely focused: while grabbed the platter owns
            // up/down, so the ring is the only thing telling a remote why focus stopped
            // moving.
            if (focused || adjusting) {
                drawCircle(
                    color = deckColor,
                    radius = outerRadius + 2f,
                    center = Offset(cx, cy),
                    style = Stroke(width = if (adjusting) 9f else 4f),
                )
            }

            // Frozen: filled ice overlay that pulses
            if (isFrozen) {
                drawCircle(
                    color = frozenColor.copy(alpha = pulse * 0.15f),
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
                    val baseAngle = (i.toFloat() / sampleCount) * 2f * PI.toFloat() - (PI.toFloat() / 2f) + rot
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
            val playheadAngle = playheadPos * 2f * PI.toFloat() - (PI.toFloat() / 2f) + rot
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
                color = borderColor.copy(alpha = if (isFrozen) pulse else 1f),
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
    faderActiveA: Boolean,
    faderActiveB: Boolean,
    faderDisplayXA: Float,
    faderDisplayXB: Float,
    onStripBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anyLocked = decks.any { it.drop != DjDrop.NONE }
    val anyFader = faderActiveA || faderActiveB
    val faderAccent = when {
        faderActiveA -> OrpheusColors.accentFor(decks.getOrNull(0)?.drop ?: DjDrop.NONE)
        faderActiveB -> OrpheusColors.accentFor(decks.getOrNull(1)?.drop ?: DjDrop.NONE)
        else -> Color.Transparent
    }
    val trackAlpha by animateFloatAsState(
        targetValue = if (anyFader) 0.25f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "trackAlpha",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .onGloballyPositioned { onStripBounds(it.boundsInWindow()) },
    ) {
        val rowWidth = maxWidth
        val gapWidth = 4.dp
        val cellWidth = (rowWidth - gapWidth * 3) / 4

        // Track line (horizontal, full width) — fades in when any fader active.
        if (trackAlpha > 0f) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(faderAccent.copy(alpha = trackAlpha)),
            )
            // Center tick — vertical anchor at x=0.5 of row.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(width = 1.dp, height = 16.dp)
                    .background(faderAccent.copy(alpha = (trackAlpha * 1.6f).coerceAtMost(1f))),
            )
        }

        // Cells. Each renders at its grid slot OR (if it's a fader thumb) at display x.
        zoneOrder.forEachIndexed { idx, drop ->
            val accent = OrpheusColors.accentFor(drop)
            val lockedBy = buildList {
                if (decks.getOrNull(0)?.drop == drop) add("A" to deckAColor)
                if (decks.getOrNull(1)?.drop == drop) add("B" to deckBColor)
            }
            val dimmed = anyLocked && lockedBy.isEmpty()
            val arming = armingDeck != null && armingZoneIdx == idx && lockedBy.isEmpty()

            val isThumbA = faderActiveA && decks.getOrNull(0)?.drop == drop
            val isThumbB = faderActiveB && decks.getOrNull(1)?.drop == drop
            val isThumb = isThumbA || isThumbB

            val gridCenterX = cellWidth / 2 + (cellWidth + gapWidth) * idx
            val thumbCenterX = when {
                isThumbA -> cellWidth / 2 + (rowWidth - cellWidth) * ((faderDisplayXA + 1f) / 2f)
                isThumbB -> cellWidth / 2 + (rowWidth - cellWidth) * ((faderDisplayXB + 1f) / 2f)
                else -> gridCenterX
            }

            val targetAlpha = if (anyFader && !isThumb) 0f else 1f
            val targetScale = if (anyFader && !isThumb) 0.6f else 1f
            val cellAlpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = tween(durationMillis = 200),
                label = "cellAlpha$idx",
            )
            val cellScale by animateFloatAsState(
                targetValue = targetScale,
                animationSpec = tween(durationMillis = 200),
                label = "cellScale$idx",
            )

            DropZoneCell(
                label = drop.label,
                accent = accent,
                beatPhase = beatPhase,
                lockedBy = lockedBy,
                dimmed = dimmed,
                arming = arming,
                armingProgress = if (arming) armingProgress else 0f,
                modifier = Modifier
                    .offset(x = thumbCenterX - cellWidth / 2)
                    .width(cellWidth)
                    .height(44.dp)
                    .graphicsLayer {
                        alpha = cellAlpha
                        scaleX = cellScale
                    },
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
                color = contrastTextColor(accent),
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
                        // sizeIn, not size: a hard 12.dp box clips sp glyphs once the
                        // user raises their font scale. The floor keeps the badge round.
                        modifier = Modifier
                            .sizeIn(minWidth = 12.dp, minHeight = 12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            letter,
                            color = color,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
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

@Preview(name = "DJ Panel — Fader A Forward", widthDp = 500, heightDp = 340)
@Composable
private fun DjPanelFaderAEngagedForwardPreview() {
    OrpheusTheme {
        DjPanel(
            feature = DjViewModel.previewFeature(
                DjUiState(
                    wetA = 0.8f, velocityA = 6.4f,
                    decks = listOf(
                        DeckDropState(dragActive = true, drop = DjDrop.STUTTER),
                        DeckDropState(),
                    ),
                ),
            ),
            vizFlowA = MutableStateFlow(previewVizData()),
            vizFlowB = MutableStateFlow(previewVizData()),
            outVizFlow = MutableStateFlow(previewVizData()),
            beatPhaseFlow = MutableStateFlow(0.2f),
            isExpanded = true,
            previewFaderOverride = FaderPreviewOverride(deck = 0, pressed = true, thumbX = 0.8f),
        )
    }
}

@Preview(name = "DJ Panel — Fader A Reverse", widthDp = 500, heightDp = 340)
@Composable
private fun DjPanelFaderAEngagedReversePreview() {
    OrpheusTheme {
        DjPanel(
            feature = DjViewModel.previewFeature(
                DjUiState(
                    wetA = 0.8f, velocityA = -3.6f,
                    decks = listOf(
                        DeckDropState(dragActive = true, drop = DjDrop.ECHO),
                        DeckDropState(),
                    ),
                ),
            ),
            vizFlowA = MutableStateFlow(previewVizData()),
            vizFlowB = MutableStateFlow(previewVizData()),
            outVizFlow = MutableStateFlow(previewVizData()),
            beatPhaseFlow = MutableStateFlow(0.5f),
            isExpanded = true,
            previewFaderOverride = FaderPreviewOverride(deck = 0, pressed = true, thumbX = -0.6f),
        )
    }
}

@Preview(name = "DJ Panel — Fader A Springing Back", widthDp = 500, heightDp = 340)
@Composable
private fun DjPanelFaderASpringingPreview() {
    OrpheusTheme {
        DjPanel(
            feature = DjViewModel.previewFeature(
                DjUiState(
                    wetA = 0.8f, velocityA = 3.5f,
                    decks = listOf(
                        DeckDropState(dragActive = true, drop = DjDrop.FREEZE),
                        DeckDropState(),
                    ),
                ),
            ),
            vizFlowA = MutableStateFlow(previewVizData()),
            vizFlowB = MutableStateFlow(previewVizData()),
            outVizFlow = MutableStateFlow(previewVizData()),
            beatPhaseFlow = MutableStateFlow(0.3f),
            isExpanded = true,
            previewFaderOverride = FaderPreviewOverride(deck = 0, pressed = false, thumbX = 0f),
        )
    }
}

@Preview(name = "DJ Panel — BRAKE Locked (no fader)", widthDp = 500, heightDp = 340)
@Composable
private fun DjPanelBrakeLockedPreview() {
    OrpheusTheme {
        DjPanel(
            feature = DjViewModel.previewFeature(
                DjUiState(
                    wetA = 0.8f, velocityA = 0.3f,
                    decks = listOf(
                        DeckDropState(dragActive = true, drop = DjDrop.BRAKE),
                        DeckDropState(),
                    ),
                ),
            ),
            vizFlowA = MutableStateFlow(previewVizData()),
            vizFlowB = MutableStateFlow(previewVizData()),
            outVizFlow = MutableStateFlow(previewVizData()),
            beatPhaseFlow = MutableStateFlow(0.1f),
            isExpanded = true,
            // No previewFaderOverride — BRAKE has no fader.
        )
    }
}

@Preview(name = "DJ Panel — Fader Deck B", widthDp = 500, heightDp = 340)
@Composable
private fun DjPanelFaderDeckBActivePreview() {
    OrpheusTheme {
        DjPanel(
            feature = DjViewModel.previewFeature(
                DjUiState(
                    wetB = 0.8f, velocityB = 0.9f,
                    decks = listOf(
                        DeckDropState(),
                        DeckDropState(dragActive = true, drop = DjDrop.PHASER),
                    ),
                ),
            ),
            vizFlowA = MutableStateFlow(previewVizData()),
            vizFlowB = MutableStateFlow(previewVizData()),
            outVizFlow = MutableStateFlow(previewVizData()),
            beatPhaseFlow = MutableStateFlow(0.4f),
            isExpanded = true,
            previewFaderOverride = FaderPreviewOverride(deck = 1, pressed = true, thumbX = 0.3f),
        )
    }
}

// Mimics Orpheus HeaderPanel: DJ takes weight(1f).widthIn(min = 320.dp) inside a Row
// alongside collapsed sibling strips. Regression-tests that the panel modifier
// (weight) reaches the outer Box and isn't dropped on a wrapper.
@Preview(name = "DJ Panel — HeaderPanel Context", widthDp = 700, heightDp = 280)
@Composable
private fun DjPanelInHeaderPanelContextPreview() {
    OrpheusTheme {
        Row(modifier = Modifier.fillMaxSize()) {
            // Stand-in for collapsed sibling panels that flank the DJ panel.
            listOf("LFO", "DLY", "VOL").forEach { label ->
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .fillMaxSize()
                        .background(OrpheusColors.panelSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp)
                }
            }
            DjPanel(
                feature = DjViewModel.previewFeature(
                    DjUiState(wetA = 0.7f, wetB = 0.5f, velocityA = 1.0f, velocityB = 1.0f),
                ),
                vizFlowA = MutableStateFlow(previewVizData()),
                vizFlowB = MutableStateFlow(previewVizData()),
                outVizFlow = MutableStateFlow(previewVizData()),
                modifier = Modifier
                    .widthIn(min = 320.dp)
                    .weight(1f)
                    .fillMaxSize(),
                isExpanded = true,
            )
        }
    }
}

// ── TurntablePlatter sub-component states ──
// Direct calls to the private composable so visual iteration on the platter
// alone doesn't require routing through the full panel.
@Preview(name = "TurntablePlatter — Idle", widthDp = 140, heightDp = 140)
@Composable
private fun TurntablePlatterIdlePreview() {
    OrpheusTheme {
        Box(
            modifier = Modifier.size(140.dp).background(OrpheusColors.darkVoid),
            contentAlignment = Alignment.Center,
        ) {
            TurntablePlatter(
                vizData = FloatArray(0),
                frozen = false, locked = false,
                velocity = 0f, wet = 0f,
                deckColor = OrpheusColors.djRedLight,
                frozenColor = OrpheusColors.djIceBlue,
                deckLabel = "A",
                onBounds = {}, onToggleLock = {},
                modifier = Modifier.size(100.dp),
            )
        }
    }
}

@Preview(name = "TurntablePlatter — Playing", widthDp = 140, heightDp = 140)
@Composable
private fun TurntablePlatterPlayingPreview() {
    OrpheusTheme {
        Box(
            modifier = Modifier.size(140.dp).background(OrpheusColors.darkVoid),
            contentAlignment = Alignment.Center,
        ) {
            TurntablePlatter(
                vizData = previewVizData(),
                frozen = false, locked = false,
                velocity = 1.0f, wet = 0.8f,
                deckColor = OrpheusColors.djRedLight,
                frozenColor = OrpheusColors.djIceBlue,
                deckLabel = "A",
                onBounds = {}, onToggleLock = {},
                modifier = Modifier.size(100.dp),
            )
        }
    }
}

@Preview(name = "TurntablePlatter — Frozen", widthDp = 140, heightDp = 140)
@Composable
private fun TurntablePlatterFrozenPreview() {
    OrpheusTheme {
        Box(
            modifier = Modifier.size(140.dp).background(OrpheusColors.darkVoid),
            contentAlignment = Alignment.Center,
        ) {
            TurntablePlatter(
                vizData = previewVizData(),
                frozen = true, locked = false,
                velocity = 0f, wet = 0.8f,
                deckColor = OrpheusColors.djRedLight,
                frozenColor = OrpheusColors.djIceBlue,
                deckLabel = "A",
                onBounds = {}, onToggleLock = {},
                modifier = Modifier.size(100.dp),
            )
        }
    }
}

@Preview(name = "TurntablePlatter — Locked", widthDp = 140, heightDp = 140)
@Composable
private fun TurntablePlatterLockedPreview() {
    OrpheusTheme {
        Box(
            modifier = Modifier.size(140.dp).background(OrpheusColors.darkVoid),
            contentAlignment = Alignment.Center,
        ) {
            TurntablePlatter(
                vizData = previewVizData(),
                frozen = false, locked = true,
                velocity = 3.5f, wet = 1.0f,
                deckColor = OrpheusColors.djCream,
                frozenColor = OrpheusColors.djIceBlue,
                deckLabel = "B",
                onBounds = {}, onToggleLock = {},
                modifier = Modifier.size(100.dp),
            )
        }
    }
}

// ── DropZoneCell states row ──
// Renders the four canonical cell states side-by-side: dimmed (other deck has
// a lock), arming (dwell-progress arc), locked-by-A, and locked-by-both decks.
@Preview(name = "DropZoneCell — States Row", widthDp = 480, heightDp = 80)
@Composable
private fun DropZoneCellStatesPreview() {
    OrpheusTheme {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(OrpheusColors.darkVoid)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DropZoneCell(
                label = "FILTER",
                accent = OrpheusColors.accentFor(DjDrop.FILTER),
                beatPhase = 0.5f,
                lockedBy = emptyList(),
                dimmed = true,
                arming = false, armingProgress = 0f,
                modifier = Modifier.width(96.dp).height(44.dp),
            )
            DropZoneCell(
                label = "STUTTER",
                accent = OrpheusColors.accentFor(DjDrop.STUTTER),
                beatPhase = 0.5f,
                lockedBy = emptyList(),
                dimmed = false,
                arming = true, armingProgress = 0.6f,
                modifier = Modifier.width(96.dp).height(44.dp),
            )
            DropZoneCell(
                label = "ECHO",
                accent = OrpheusColors.accentFor(DjDrop.ECHO),
                beatPhase = 0f,
                lockedBy = listOf("A" to OrpheusColors.djRedLight),
                dimmed = false,
                arming = false, armingProgress = 0f,
                modifier = Modifier.width(96.dp).height(44.dp),
            )
            DropZoneCell(
                label = "FREEZE",
                accent = OrpheusColors.accentFor(DjDrop.FREEZE),
                beatPhase = 0f,
                lockedBy = listOf(
                    "A" to OrpheusColors.djRedLight,
                    "B" to OrpheusColors.djCream,
                ),
                dimmed = false,
                arming = false, armingProgress = 0f,
                modifier = Modifier.width(96.dp).height(44.dp),
            )
        }
    }
}