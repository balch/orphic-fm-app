package org.balch.orpheus.djapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.balch.orpheus.features.pulsar.MusicPulse
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.infrastructure.LocalTvFocusRegion
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.LocalVizStage
import org.balch.orpheus.ui.viz.vizStageOverhang
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

internal const val SwipeCommitDp = 32f
internal const val SwipeFlingDpPerSec = 800f
internal const val SwipeFlingMinDp = 12f

internal enum class SwipeDecision { Next, Previous, None }

/** A drag right is Next and left is Previous, as the dock's ◀ ▶ tiles and ← → keys sit. Short, slow drags spring back. */
internal fun swipeDecision(dragDp: Float, velocityDpPerSec: Float): SwipeDecision {
    val far = abs(dragDp) >= SwipeCommitDp
    val flung = abs(dragDp) >= SwipeFlingMinDp &&
        abs(velocityDpPerSec) >= SwipeFlingDpPerSec &&
        sign(velocityDpPerSec) == sign(dragDp)
    if (!far && !flung) return SwipeDecision.None
    return if (dragDp > 0) SwipeDecision.Next else SwipeDecision.Previous
}

/**
 * The phone bar's ring, oversized against the 24dp tab icons and the size of the rail's
 * [RailRingSize]: one dome for both chromes. The bar's 80dp holds its name, and the ring rises above it.
 */
internal val BarRingSize = 64.dp

/** Above the ring and below its name, inside the transport's pointer and semantics bounds. */
internal val TransportPadding = 4.dp

/**
 * The dome's keyboard focus mark: a thin circle a clear gap outside the ring's loudest crest, so it
 * reads as a halo rather than another wave.
 */
internal val DomeFocusGap = 2.dp
internal val DomeFocusStroke = 2.dp

/** The focus mark's radius, to the middle of its stroke, around a ring [ringSize] across. */
internal fun domeFocusRadius(ringSize: Dp): Dp = ringSize / 2 + DomeFocusGap + DomeFocusStroke / 2

/** The phone bar's and the rail's focus mark, in the ring's own cyan. */
private val RingFocusColor = ColorProducer { OrpheusColors.neonCyan }

/** Ring box edge to dome edge: clear of the ring's innermost crest by a hair, so retuning the ring moves the dome with it. */
internal fun domeInset(ringSize: Dp): Dp = ringInnerClearance(ringSize) + 1.5.dp

private val NoTilt: () -> Float = { 0f }

// ==================== Marquee label ====================
// Shared by the transport's resting label (bar/rail) and the dock's step tiles.

/** How far an edge fades to transparent where text runs past it, car-stereo style. */
private val MarqueeFadeWidth = 7.dp

// A beat to read the name before the first pass, and a longer one between passes.
private const val MarqueeInitialDelayMillis = 1_200
internal const val MarqueeRepeatDelayMillis = 1_600
private val MarqueeVelocity = 30.dp
private const val MarqueeSpacingFraction = 0.25f

/**
 * Whether a resting label that overflows scrolls: off TV hardware, playing or paused. Whether it
 * overflows is known only in layout, where the marquee measures it.
 */
internal fun marqueeActive(televisionHardware: Boolean): Boolean = !televisionHardware

private fun fadeRamp(px: Float, fadePx: Float) = (px / fadePx).coerceIn(0f, 1f)

/**
 * The left edge's fade [offsetPx] into a pass, where a second copy follows [gapPx] behind the
 * first: 0 is crisp, 1 the full [MarqueeFadeWidth] ramp. It grows in with the offset and eases out
 * as the second copy reaches the start.
 */
internal fun marqueeFadeLeft(offsetPx: Float, textPx: Float, gapPx: Float, fadePx: Float): Float {
    if (fadePx <= 0f) return 0f
    val secondStart = textPx + gapPx - offsetPx
    return fadeRamp(offsetPx, fadePx) * fadeRamp(secondStart, fadePx)
}

/**
 * The right edge's, as [marqueeFadeLeft]'s, in a slot [slotPx] wide: it eases out as the first
 * copy's end comes into view and back in as the second copy arrives.
 */
internal fun marqueeFadeRight(offsetPx: Float, textPx: Float, gapPx: Float, slotPx: Float, fadePx: Float): Float {
    if (fadePx <= 0f) return 0f
    val firstEnd = textPx - offsetPx
    val secondStart = textPx + gapPx - offsetPx
    return maxOf(
        fadeRamp(firstEnd - slotPx, fadePx),
        fadeRamp(slotPx + fadePx - secondStart, fadePx) * fadeRamp(secondStart + textPx - slotPx, fadePx),
    )
}

/**
 * The edge fades' four-stop gradient for [left] and [right] fades and stops [edge] in from each
 * side. Most of a pass holds both fades still, so a draw reuses the last brush and its shader.
 */
private class MarqueeFadeBrush(val left: Float, val right: Float, val edge: Float) : ShaderBrush() {
    override fun createShader(size: Size): Shader = LinearGradientShader(
        from = Offset.Zero,
        to = Offset(size.width, 0f),
        colors = listOf(Color.Black.copy(alpha = 1f - left), Color.Black, Color.Black, Color.Black.copy(alpha = 1f - right)),
        colorStops = listOf(0f, edge, 1f - edge, 1f),
    )
}

/**
 * A one-line label that scrolls in place of its resting ellipsis when it overflows, off TV
 * hardware. On TV it is the plain ellipsized [Text], measured by nothing.
 */
@Composable
internal fun MarqueeLabel(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    // In place of [color]: read in draw, so a colour that follows a drag never recomposes the label.
    colorProducer: ColorProducer? = null,
) {
    // Whether an overflowing name would scroll here; whether this one overflows is known only in layout.
    val live = marqueeActive(televisionHardware = LocalTelevisionHardware.current)
    val marquee = if (live) rememberMarquee(text) else null
    // Built once per marquee: rebuilt, its lambdas would remeasure and re-record the label.
    val scrolling = if (marquee != null) remember(marquee) { Modifier.marquee(marquee) } else null
    // A live name starts at its left edge, where a pass begins; the parent centres one that fits.
    val align = if (marquee != null) null else textAlign
    val labelModifier = if (scrolling != null) modifier.then(scrolling) else modifier
    if (colorProducer != null) {
        Text(
            text = text,
            color = colorProducer,
            style = style,
            fontSize = fontSize,
            textAlign = align,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = labelModifier,
        )
    } else {
        Text(
            text = text,
            style = style,
            color = color,
            fontSize = fontSize,
            textAlign = align,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = labelModifier,
        )
    }
}

/** What a pass scrolls, in whole pixels; null while the text fits its slot. */
private data class MarqueeGeometry(val textPx: Int, val gapPx: Int)

@Stable
private class MarqueeState {
    var geometry: MarqueeGeometry? by mutableStateOf(null)
    val offset = Animatable(0f)

    /** The last frame's edge fades, kept by the draw: not snapshot state, nothing observes it. */
    var fades: MarqueeFadeBrush? = null
}

/** Animator duration scale pinned to 1, as basicMarquee does: at 0 an endless pass would spin. */
private object FixedMotionDurationScale : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

@Composable
private fun rememberMarquee(text: String): MarqueeState {
    // Keyed on the name, so a new one measures afresh and starts from its first letter.
    val marquee = remember(text) { MarqueeState() }
    val pxPerSec = with(LocalDensity.current) { MarqueeVelocity.toPx() }
    LaunchedEffect(marquee, pxPerSec) {
        withContext(FixedMotionDurationScale) {
            snapshotFlow { marquee.geometry }.collectLatest { geometry ->
                marquee.offset.snapTo(0f)
                if (geometry == null) return@collectLatest
                val travel = (geometry.textPx + geometry.gapPx).toFloat()
                val passMillis = (travel / pxPerSec * 1_000).roundToInt()
                marquee.offset.animateTo(travel, tween(passMillis, MarqueeInitialDelayMillis, LinearEasing))
                while (true) {
                    // A pass ends a whole copy on, drawn exactly as its start: it rests there on a
                    // plain delay, which asks for no frames, and snaps back as the next pass begins.
                    delay(MarqueeRepeatDelayMillis.toLong())
                    marquee.offset.snapTo(0f)
                    marquee.offset.animateTo(travel, tween(passMillis, easing = LinearEasing))
                }
            }
        }
    }
    return marquee
}

/**
 * Measures the text past an overflowed slot and draws it twice, a gap apart, at a whole-pixel offset
 * read only in draw. Its own layer, so a scroll frame re-records the label alone.
 */
private fun Modifier.marquee(marquee: MarqueeState): Modifier = this
    // Offscreen only while scrolling, so the DstIn fades erase the label's pixels and nothing under it.
    .graphicsLayer {
        compositingStrategy = if (marquee.geometry != null) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        val geometry = marquee.geometry ?: return@drawWithContent drawContent()
        val offset = marquee.offset.value.roundToInt().toFloat()
        val second = (geometry.textPx + geometry.gapPx).toFloat()
        clipRect {
            translate(left = -offset) { this@drawWithContent.drawContent() }
            translate(left = second - offset) { this@drawWithContent.drawContent() }
        }
        val fadePx = MarqueeFadeWidth.toPx()
        val textPx = geometry.textPx.toFloat()
        val gapPx = geometry.gapPx.toFloat()
        val left = marqueeFadeLeft(offset, textPx, gapPx, fadePx)
        val right = marqueeFadeRight(offset, textPx, gapPx, size.width, fadePx)
        val edge = (fadePx / size.width).coerceIn(0f, 0.5f)
        val fades = marquee.fades?.takeIf { it.left == left && it.right == right && it.edge == edge }
            ?: MarqueeFadeBrush(left, right, edge).also { marquee.fades = it }
        drawRect(brush = fades, blendMode = BlendMode.DstIn)
    }
    .layout { measurable, constraints ->
        val slot = constraints.maxWidth
        val overflows = constraints.hasBoundedWidth && measurable.maxIntrinsicWidth(constraints.maxHeight) > slot
        if (overflows) {
            val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
            marquee.geometry = MarqueeGeometry(placeable.width, (slot * MarqueeSpacingFraction).roundToInt())
            layout(slot, placeable.height) { placeable.placeRelative(0, 0) }
        } else {
            marquee.geometry = null
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
    }

/**
 * The play/pause dome inside a ring whose elapsed arc traces the music while playing, in the
 * colours of the loudest tracks: the master mix's scope under [LocalScopeFeed], which the ring
 * holds while it plays off TV hardware in a started app, and a plain arc without one. Paused, the arc holds its
 * last shape and a highlight zips along it. Null progress draws the track only.
 */
@Composable
internal fun VibeTransportRing(
    paused: Boolean,
    progress: Float?,
    modifier: Modifier = Modifier,
    ringSize: Dp = BarRingSize,
    ringColor: Color = OrpheusColors.neonCyan,
    // The song times behind [progress]: with them the arc runs on between the tracker's updates.
    position: SongPosition? = null,
    // Read only in draw: see VibeDome.
    domeTilt: () -> Float = NoTilt,
    // Read only in the wave's frame loop: see rememberProgressWave.
    pulse: (() -> MusicPulse)? = null,
    // Render-harness seams only: pin the wave's phase and the paused zip, see rememberProgressWave.
    previewWavePhase: Float? = null,
    previewZipMs: Long? = null,
) {
    // No progress, nothing to wave or zip: don't spend frames on it.
    val zip = progress != null && ringElapsedLength(ringSize, progress) >= ZipMinLength
    val playing = !paused && progress != null
    // TV hardware lies flat and never polls the scope. Held only while started: a backgrounded iOS
    // app or a minimised desktop window stops, and its poll with it, though the music plays on.
    val feed = LocalScopeFeed.current.takeUnless { LocalTelevisionHardware.current }
    if (feed != null && playing) {
        HoldWhileStarted(feed) {
            feed.hold()
            DisposableHandle { feed.release() }
        }
    }
    val wave = rememberProgressWave(
        playing = playing, pulse = pulse, zip = zip,
        previewPhase = previewWavePhase, previewZipMs = previewZipMs, scope = feed?.frame,
    )
    val currentPosition by rememberUpdatedState(position)
    val smooth = rememberSmoothProgress(wave) { currentPosition }
    val path = remember { Path() }
    val domeDiameter = ringSize - domeInset(ringSize) * 2
    // Required, not preferred: a slot narrower than the ring (six phone tabs) lets it overhang the
    // gaps rather than squeeze the ring out of step with its dome.
    Box(modifier = modifier.requiredSize(ringSize), contentAlignment = Alignment.Center) {
        // Under the ring, so the shadow never dims its lower crests.
        VibeDomeShadow(tilt = domeTilt, diameter = domeDiameter)
        // Its own layer: each wave frame re-records the ring alone, not the bar or rail around it.
        // Its strokes and zip stops are built once per size, not every frame.
        Spacer(
            Modifier.matchParentSize().graphicsLayer().drawWithCache {
                val strokes = RingStrokes(size)
                val zip = ZipGradient()
                onDrawBehind {
                    if (progress == null) {
                        drawProgressRingTrack(ringColor, strokes)
                    } else {
                        drawProgressRing(path, smooth.fractionOr(progress), wave, ringColor, strokes, zip)
                    }
                }
            },
        )
        VibeDome(paused = paused, tilt = domeTilt, diameter = domeDiameter)
    }
}

/**
 * The play/pause dome of the phone bar, the rail and the dock's bottom bar (see DockDome), now also
 * the vibe navigator: the ring shows song progress, the label names the vibe, and a sideways drag
 * peeks and commits the neighbour. Hover and a press light the ring, and keyboard focus rings it.
 * Under [LocalVibeDomeCues] the dome also rolls with changes made elsewhere, wiggles to show it
 * swipes, and a committed swipe asks through the cues; [onNext] and [onPrevious] then serve the
 * accessibility actions alone.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun VibeTransportItem(
    name: String,
    previousName: String?,
    nextName: String?,
    progress: Float?,
    paused: Boolean,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    // A left drag will restart the vibe: its accessibility action says so, as the dock tile does.
    previousRestarts: Boolean = false,
    // The rail: a name that fits sits centred on the ring's axis; a longer one scrolls.
    centerLabel: Boolean = false,
    // The phone bar: lays out as its name alone, so the ring rises out over what is above.
    raiseRing: Boolean = false,
    // The name, resting and peeked. The rail keeps the tabs' labelSmall: its tier budgets measure that line.
    nameStyle: TextStyle = MaterialTheme.typography.labelSmall,
    // The phone bar and the dock: how wide the name may draw, given its slot's width in px. It stays
    // centred on the slot, and its layout, like the tap target, keeps the slot's width.
    nameLane: ((slotPx: Int) -> Int)? = null,
    // The dock: the name lays out no wider than the ring, however wide it draws, so the tap target is
    // the ring's width and the stage beside a raised ring keeps its taps.
    ringWideTarget: Boolean = false,
    // The phone bar and the dock: the name is drawn this far below its laid-out line, for room under the ring.
    // Draw-only, so the row's baseline alignment and the ring stay put.
    nameDrop: Dp = 0.dp,
    ringSize: Dp = BarRingSize,
    // The keyboard focus mark's colour, read in draw: the dock's follows its accent.
    focusColor: ColorProducer = RingFocusColor,
    // The song times behind [progress], so the ring's arc runs on between the tracker's updates.
    position: SongPosition? = null,
    // Read only in the ring's frame loop, never in composition.
    pulse: (() -> MusicPulse)? = null,
    // Render-harness seam only: draws the peek as if dragged this far.
    previewDragDp: Float = 0f,
    // Render-harness seams only: pin the ring's wave phase and paused zip.
    previewWavePhase: Float? = null,
    previewZipMs: Long? = null,
    // Render-harness seam only: draws the dome at this tilt.
    previewDomeTilt: Float? = null,
    // Tests pass their own, to read the tilt it draws.
    domeMotion: DomeMotion = rememberDomeMotion(),
) {
    val density = LocalDensity.current
    // The drag is read only where the label slides and fades, in placement and draw. Composition
    // reads which way it is past the peek threshold, written only as it crosses, so a swipe
    // recomposes at the threshold and at release rather than on every move.
    val drag = remember { mutableFloatStateOf(0f) }
    // The wiggle's pretend drag, apart from the finger's so neither ever writes over the other.
    val cueDrag = remember { mutableFloatStateOf(0f) }
    val peekSide = remember { mutableIntStateOf(0) }
    val shownDrag: () -> Float = remember(previewDragDp) {
        if (previewDragDp != 0f) { { previewDragDp } } else { { drag.floatValue + cueDrag.floatValue } }
    }
    fun showPeek() {
        val side = peekSideOf(drag.floatValue + cueDrag.floatValue)
        if (peekSide.intValue != side) peekSide.intValue = side
    }
    fun dragTo(dragDp: Float) {
        drag.floatValue = dragDp
        showPeek()
    }
    val playLabel = if (paused) "Play" else "Pause"
    val previousLabel = if (previousRestarts) "Restart vibe" else "Previous vibe"
    val dome = domeMotion
    val domeTilt: () -> Float = remember(dome, previewDomeTilt) { { previewDomeTilt ?: dome.tilt } }
    // Nothing on a TV's dome animates, so there it takes no cues at all.
    val cues = LocalVibeDomeCues.current.takeUnless { LocalTelevisionHardware.current }
    // A touch or a key on the dome: no wiggle to come, and none still going.
    val stopCue: () -> Unit = remember(cues, dome) { { cues?.skipWiggle(); dome.stopWiggle() } }
    if (cues != null) {
        // Rolls with every vibe change it didn't make, each from wherever the last left it.
        LaunchedEffect(cues, dome) {
            cues.moves.collect { move ->
                val direction = domeRollFor(move)
                if (direction != 0) dome.release(direction)
            }
        }
        // "Swipe me": once a launch, a beat after the dome shows, until a swipe has ever committed.
        LaunchedEffect(cues, dome) {
            if (cues.everSwiped()) return@LaunchedEffect
            delay(DomeWiggleDelayMillis)
            if (cues.takeWiggle()) {
                dome.wiggle { dragDp ->
                    cueDrag.floatValue = dragDp
                    showPeek()
                }
            }
        }
    }
    // A raised ring reaches into the stage: reported, so the faded panels' wake overlay leaves it live.
    // The dock raises its ring from outside and never fades its panels, so it reports nothing.
    val overhangStage = if (raiseRing) LocalVizStage.current else null
    val indication = remember(ringSize) { DomeIndication(ringSize) }
    // Written on focus change, read only in the mark's draw.
    val focused = remember { mutableStateOf(false) }
    // Focus-visible: the mark shows only in the platform's keyboard mode, which a pointer press leaves
    // though the dome keeps focus.
    val inputModes = LocalInputModeManager.current
    // The dock's, whose idle fade the mark rides; elsewhere the mark stays while focused.
    val region = LocalTvFocusRegion.current

    Column(
        modifier = if (raiseRing) modifier.raisedAboveName(ringSize) else modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .vizStageOverhang(overhangStage)
                .then(if (cues != null) Modifier.onPressOrKey(stopCue) else Modifier)
                // Only watches. Android leaves touch mode on any key; skiko only on Tab, so an arrow
                // or Space after a click brings the mark back here.
                .onPreviewKeyEvent {
                    if (inputModes.inputMode != InputMode.Keyboard) inputModes.requestInputMode(InputMode.Keyboard)
                    false
                }
                .pointerInput(onNext, onPrevious, dome, cues) {
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking(); dragTo(0f); dome.follow(0f) },
                        onDragCancel = { dragTo(0f); dome.release(0) },
                        onDragEnd = {
                            val velocity = with(density) { tracker.calculateVelocity().x.toDp().value }
                            val decision = swipeDecision(drag.floatValue, velocity)
                            // The dome rolls on in the drag's direction as the navigator changes the vibe;
                            // asked through the cues, the move comes back as the dome's and rolls nothing more.
                            dome.release(domeRollDirection(decision))
                            when (decision) {
                                SwipeDecision.Next -> if (cues != null) cues.swipe(next = true) else onNext()
                                SwipeDecision.Previous -> if (cues != null) cues.swipe(next = false) else onPrevious()
                                SwipeDecision.None -> Unit
                            }
                            dragTo(0f)
                        },
                        onHorizontalDrag = { change, amount ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            dragTo(drag.floatValue + with(density) { amount.toDp().value })
                            dome.follow(drag.floatValue)
                        },
                    )
                }
                .onFocusChanged { focused.value = it.isFocused }
                // Round and light on the ring, never a square over the name: see DomeIndication.
                .clickable(interactionSource = null, indication = indication, onClick = onTogglePlayback)
                // Same node as the click: mergeDescendants pulls in the ring's icon and the label
                // below, but an explicit contentDescription wins over that merged text for
                // announcement, so a transient peek string is never what gets read out.
                .semantics(mergeDescendants = true) {
                    contentDescription = "$playLabel, $name"
                    // The swipe's stand-ins: a screen reader's user has learned what the wiggle would show.
                    customActions = listOf(
                        CustomAccessibilityAction("Next vibe") { onNext(); cues?.swipeLearned(); true },
                        CustomAccessibilityAction(previousLabel) { onPrevious(); cues?.swipeLearned(); true },
                    )
                }
                .padding(vertical = TransportPadding),
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Its own layer under the ring, so the focus fade's frames re-record the mark alone. The
                // layer does not clip, and the circle reaches past the ring's box.
                Spacer(
                    Modifier.matchParentSize().graphicsLayer().drawWithCache {
                        val radius = domeFocusRadius(ringSize).toPx()
                        val stroke = Stroke(DomeFocusStroke.toPx())
                        onDrawBehind {
                            // The mode and the fade are read only while focused, so an unfocused dome never redraws with them.
                            if (!focused.value || inputModes.inputMode != InputMode.Keyboard) return@onDrawBehind
                            val fade = region?.alpha?.value ?: 1f
                            if (fade <= 0f) return@onDrawBehind
                            val color = focusColor()
                            drawCircle(color.copy(alpha = color.alpha * fade), radius, center, style = stroke)
                        }
                    },
                )
                VibeTransportRing(
                    paused = paused, progress = progress, ringSize = ringSize, position = position, domeTilt = domeTilt,
                    pulse = pulse, previewWavePhase = previewWavePhase, previewZipMs = previewZipMs,
                )
            }
            // The label slides with the drag: right peeks the next name, left the previous (or the restart).
            val side = if (previewDragDp != 0f) peekSideOf(previewDragDp) else peekSide.intValue
            val peekNext = side > 0 && nextName != null
            val peekPrevious = side < 0 && previousName != null
            val labelColor = remember(shownDrag, nextName, previousName) {
                ColorProducer { OrpheusColors.cosmicPurple.copy(alpha = labelAlpha(shownDrag(), nextName, previousName)) }
            }
            // Slides with the drag; in the phone bar it also drops and draws in its wider lane.
            val reportPx = if (ringWideTarget) with(density) { ringSize.roundToPx() } else null
            val nameModifier = remember(shownDrag, nameDrop, nameLane, reportPx) {
                Modifier.slideWith(shownDrag)
                    // Drawn lower, not laid out lower: a layer's translation would move its baseline too,
                    // and the row would align the whole transport, ring and all, back up by the drop.
                    // Whole pixels, as the marquee's offset is, so a fractional density never softens the glyphs.
                    .then(
                        if (nameDrop == 0.dp) Modifier
                        else Modifier.drawWithContent {
                            translate(top = nameDrop.toPx().roundToInt().toFloat()) { this@drawWithContent.drawContent() }
                        },
                    )
                    .nameLane(nameLane, reportPx)
            }
            if (peekNext || peekPrevious) {
                // The arrow is its own element so it never ellipsizes away with a long neighbour
                // name; only the name shrinks to make room for it.
                Row(modifier = nameModifier, verticalAlignment = Alignment.CenterVertically) {
                    if (peekNext) {
                        Text(
                            text = nextName,
                            color = labelColor,
                            style = nameStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(" ›", color = labelColor, style = nameStyle, maxLines = 1, softWrap = false)
                    } else {
                        Text("‹ ", color = labelColor, style = nameStyle, maxLines = 1, softWrap = false)
                        Text(
                            text = previousName!!,
                            color = labelColor,
                            style = nameStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            } else {
                MarqueeLabel(
                    text = name,
                    style = nameStyle,
                    colorProducer = labelColor,
                    textAlign = if (centerLabel) TextAlign.Center else null,
                    modifier = nameModifier,
                )
            }
        }
    }
}

/**
 * Calls [action] on a press, before the drag and the click see it, and on any key while the dome
 * has focus. It only watches: nothing is consumed.
 */
private fun Modifier.onPressOrKey(action: () -> Unit): Modifier = this
    .pointerInput(action) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) action()
            }
        }
    }
    .onPreviewKeyEvent { action(); false }

/** Which way a drag has gone past the peek threshold, half the commit: 1 right, -1 left, 0 short of it. */
private fun peekSideOf(dragDp: Float): Int = when {
    (abs(dragDp) / SwipeCommitDp).coerceIn(0f, 1f) < 0.5f -> 0
    dragDp > 0f -> 1
    else -> -1
}

/** The label's alpha as a drag peeks a neighbour: half at the first move, full at the commit. */
private fun labelAlpha(dragDp: Float, nextName: String?, previousName: String?): Float {
    val peeking = (dragDp > 0f && nextName != null) || (dragDp < 0f && previousName != null)
    val peekAlpha = (abs(dragDp) / SwipeCommitDp).coerceIn(0f, 1f)
    return if (peeking) 0.5f + peekAlpha / 2 else 1f
}

/** Slides the label half as far as [dragDp], never past the commit; read in placement only. */
private fun Modifier.slideWith(dragDp: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.placeRelative((dragDp().coerceIn(-SwipeCommitDp, SwipeCommitDp).dp / 2).roundToPx(), 0)
    }
}

/**
 * Reports the transport from its name down and places the ring above that, so a row aligned by
 * baseline puts the name on its labels' line. Outside the pointer and semantics nodes, which still
 * measure the whole ring: with no clip in between, the raised part stays live.
 */
private fun Modifier.raisedAboveName(ringSize: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val lift = (TransportPadding.roundToPx() + ringSize.roundToPx()).coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - lift) { placeable.placeRelative(0, -lift) }
}

/**
 * Lets the name measure as wide as [lane] allows, never narrower than its slot, and draw centred
 * over it while reporting no more than the slot, or [reportPx] where that is narrower: the
 * transport's column and tap target keep that width.
 */
private fun Modifier.nameLane(lane: ((slotPx: Int) -> Int)?, reportPx: Int?): Modifier =
    if (lane == null && reportPx == null) this else layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
        val slot = constraints.maxWidth
        val drawn = if (lane != null) lane(slot).coerceAtLeast(slot) else slot
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = drawn))
        val report = if (reportPx != null) minOf(slot, reportPx).coerceAtLeast(constraints.minWidth) else slot
        val width = placeable.width.coerceIn(constraints.minWidth, report)
        layout(width, placeable.height) { placeable.placeRelative((width - placeable.width) / 2, 0) }
    }
