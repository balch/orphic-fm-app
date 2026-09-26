package org.balch.orpheus.djapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.balch.orpheus.features.pulsar.playback.VibeMove
import org.balch.orpheus.features.pulsar.playback.VibeMoveKind
import org.balch.orpheus.features.pulsar.playback.VibeMoveOrigin
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.darken
import org.balch.orpheus.ui.theme.lighten
import kotlin.math.abs
import kotlin.math.roundToInt

/** Drag that tips the dome fully over. */
private const val DomeTiltDragDp = 40f

/** Light at rest, up-left, and how far it slides against the drag (fractions of the radius). */
private const val LightRestX = -0.35f
private const val LightRestY = -0.38f
private const val LightTravel = 0.45f

/**
 * Glyph size (of the diameter), its lean with the tilt (of the radius), and its narrowest foreshortening.
 * Sized for the oversized domes: 0.62 of the bar's 39dp dome is the 24dp of the tab icons beside it.
 */
private const val GlyphFraction = 0.62f
private const val GlyphTravel = 0.3f
private const val GlyphMinScaleX = 0.82f

internal const val DomeRollOutMillis = 140
private const val DomeRollInMillis = 180

/** The shortest first phase, of [DomeRollOutMillis]: a roll from the very edge still spends a few frames tipping. */
private const val DomeRollOutMinFraction = 0.15f

/**
 * The "swipe me" wiggle, a pretend drag: [DomeWiggleDelayMillis] after a dome first shows, out
 * [DomeWiggleDragDp] left, a hold, across to as far right, a hold, then a short drag's spring home.
 * About 1.2 s in all, never near the 32dp commit.
 */
internal const val DomeWiggleDelayMillis = 1_500L
internal const val DomeWiggleDragDp = 20f
private const val DomeWiggleOutMillis = 240
private const val DomeWiggleAcrossMillis = 360
private const val DomeWiggleHoldMillis = 120L

/** Tilt −1..1 for a sideways drag: positive to the right. */
internal fun domeTiltForDrag(dragDp: Float): Float = (dragDp / DomeTiltDragDp).coerceIn(-1f, 1f)

/** The roll follows the finger: Next is a right drag and rolls on right (+1), Previous left (−1). */
internal fun domeRollDirection(decision: SwipeDecision): Int = when (decision) {
    SwipeDecision.Next -> 1
    SwipeDecision.Previous -> -1
    SwipeDecision.None -> 0
}

/**
 * The roll for a vibe change the dome didn't make: right onward (a next, an advance, a pick), left
 * back (a previous, a restart). Its own swipe already rolled as the finger let go, so 0.
 */
internal fun domeRollFor(move: VibeMove): Int = when {
    move.origin == VibeMoveOrigin.DomeSwipe -> 0
    else -> when (move.kind) {
        VibeMoveKind.Next, VibeMoveKind.Pick -> 1
        VibeMoveKind.Previous, VibeMoveKind.Restart -> -1
    }
}

/**
 * The roll's first phase lasts only as long as what is left to tip from [fromTilt] to the edge, so
 * a commit that starts near the edge keeps moving instead of hesitating there.
 */
internal fun domeRollOutMillis(fromTilt: Float, direction: Int): Int =
    (DomeRollOutMillis * abs(direction - fromTilt).coerceIn(DomeRollOutMinFraction, 1f)).roundToInt()

/**
 * Tilt across a committed roll, [progress] 0..1: the first half tips from [fromTilt] over to
 * [direction] (+1 next, −1 previous), the second brings the new face round from −[direction] to rest.
 */
internal fun domeRollTilt(progress: Float, fromTilt: Float, direction: Int): Float {
    val p = progress.coerceIn(0f, 1f)
    val edge = direction.toFloat()
    return if (p <= 0.5f) fromTilt + (edge - fromTilt) * (p / 0.5f) else -edge * (1f - (p - 0.5f) / 0.5f)
}

/** The specular highlight (and the body's bright centre) from the dome's centre; slides against the tilt. */
internal fun domeHighlightOffset(tilt: Float, radius: Float): Offset {
    val t = tilt.coerceIn(-1f, 1f)
    return Offset((LightRestX - LightTravel * t) * radius, LightRestY * radius)
}

/** The glyph leans with the tilt... */
internal fun domeGlyphShift(tilt: Float, radius: Float): Float = GlyphTravel * radius * tilt.coerceIn(-1f, 1f)

/** ...and foreshortens as its face turns away. */
internal fun domeGlyphScaleX(tilt: Float): Float = 1f - (1f - GlyphMinScaleX) * abs(tilt.coerceIn(-1f, 1f))

private val DomeLilac = OrpheusColors.cosmicPurple.lighten(0.55f)
private val DomeIndigo = OrpheusColors.cosmicPurple.darken(0.62f)

/**
 * Contact shadow, in dome radii: a flat ellipse under the dome's base (half-width [ShadowSpread],
 * height [ShadowSquash] of its width) dropped [ShadowDrop] below the centre, so a soft rim shows
 * beneath the dome as elevation. It drifts [ShadowDrift] with the tilt.
 */
private const val ShadowSpread = 1f
private const val ShadowSquash = 0.45f
private const val ShadowDrop = 0.7f
private const val ShadowDrift = 0.1f

/** The shadow's reach from the dome's centre at rest, in the units of [radius]. */
internal fun domeShadowExtent(radius: Float): Rect {
    val halfWidth = ShadowSpread * radius
    val halfHeight = halfWidth * ShadowSquash
    return Rect(-halfWidth, ShadowDrop * radius - halfHeight, halfWidth, ShadowDrop * radius + halfHeight)
}

/** The shadow's box spans its reach (1.15 radii below the centre, 1.1 beside it with the drift), so its layer holds all of it. */
private const val ShadowBoxScale = 1.2f

/**
 * The dome's contact shadow, in a layer of its own. Drawn beneath the ring, so it never dims the
 * ring's lower crests; [tilt] is read only in draw.
 */
@Composable
internal fun VibeDomeShadow(tilt: () -> Float, diameter: Dp, modifier: Modifier = Modifier) {
    Spacer(
        modifier.size(diameter * ShadowBoxScale).graphicsLayer().drawWithCache {
            val r = diameter.toPx() / 2
            val shadowRadius = r * ShadowSpread
            val shadow = Brush.radialGradient(
                // Held dark into the outer third: that rim is all that shows below the dome.
                0f to Color.Black.copy(alpha = 0.6f), 0.7f to Color.Black.copy(alpha = 0.42f), 1f to Color.Transparent,
                center = Offset.Zero, radius = shadowRadius,
            )
            onDrawBehind {
                withTransform({
                    translate(center.x + ShadowDrift * r * tilt(), center.y + ShadowDrop * r)
                    scale(1f, ShadowSquash, Offset.Zero)
                }) {
                    drawCircle(shadow, shadowRadius, Offset.Zero)
                }
            }
        },
    )
}

/**
 * Play/pause as the top half of a sphere lit from the upper left; its shadow is [VibeDomeShadow].
 * [tilt] is read only in draw, and the dome and glyph draw in layers of their own, so a drag or a
 * roll re-records just the dome, never the bar or rail around it.
 */
@Composable
internal fun VibeDome(
    paused: Boolean,
    tilt: () -> Float,
    diameter: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(diameter).graphicsLayer().drawWithCache {
            val r = diameter.toPx() / 2
            val c = size.center
            val body = Brush.radialGradient(
                0f to DomeLilac, 0.42f to OrpheusColors.cosmicPurple, 1f to DomeIndigo,
                center = Offset.Zero, radius = r * 1.6f,
            )
            val limb = Brush.radialGradient(
                0.72f to Color.Transparent, 1f to DomeIndigo.copy(alpha = 0.6f), center = c, radius = r,
            )
            val highlightRadius = r * 0.4f
            val highlight = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.9f), 1f to Color.Transparent,
                center = Offset.Zero, radius = highlightRadius,
            )
            val outline = Path().apply { addOval(Rect(c, r)) }
            onDrawBehind {
                val t = tilt()
                val light = domeHighlightOffset(t, r)
                // The brush is centred on the light; drawing the circle back at the dome's centre moves only the light.
                translate(center.x + light.x, center.y + light.y) {
                    drawCircle(body, r, Offset(-light.x, -light.y))
                }
                drawCircle(limb, r, center)
                clipPath(outline) {
                    withTransform({
                        translate(center.x + light.x, center.y + light.y)
                        rotate(-45f, Offset.Zero)
                        scale(1f, 0.6f, Offset.Zero)
                    }) {
                        drawCircle(highlight, highlightRadius, Offset.Zero)
                    }
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(diameter * GlyphFraction).graphicsLayer {
                val t = tilt()
                translationX = domeGlyphShift(t, diameter.toPx() / 2)
                scaleX = domeGlyphScaleX(t)
            },
        )
    }
}

/**
 * The dome's tilt: follows the drag, then rolls on through the far edge when the swipe commits or
 * springs back when it doesn't. A vibe change from anywhere else rolls it the same way, each roll
 * starting from wherever the last one left it, and a wiggle shows it swipes. Static on TV hardware.
 */
@Stable
internal class DomeMotion(private val scope: CoroutineScope, private val static: Boolean) {
    private val tiltState = mutableFloatStateOf(0f)
    private val clock = Animatable(0f)
    private var settle: Job? = null
    private var wiggling = false

    /** Read only in draw. */
    val tilt: Float get() = tiltState.floatValue

    fun follow(dragDp: Float) {
        if (static) return
        settle?.cancel()
        tiltState.floatValue = domeTiltForDrag(dragDp)
    }

    /** [direction] +1 rolls on right toward the next vibe, −1 left toward the previous, 0 springs back. */
    fun release(direction: Int) {
        if (static) return
        settle?.cancel()
        val from = tiltState.floatValue
        settle = scope.launch {
            if (direction == 0) {
                clock.snapTo(from)
                clock.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) {
                    tiltState.floatValue = value
                }
            } else {
                clock.snapTo(0f)
                clock.animateTo(0.5f, tween(domeRollOutMillis(from, direction), easing = LinearEasing)) {
                    tiltState.floatValue = domeRollTilt(value, from, direction)
                }
                clock.animateTo(1f, tween(DomeRollInMillis, easing = LinearOutSlowInEasing)) {
                    tiltState.floatValue = domeRollTilt(value, from, direction)
                }
            }
        }
    }

    /**
     * The "swipe me" wiggle: tilts as a drag [DomeWiggleDragDp] left then right would, and springs
     * home. [drag] hears each frame's pretend drag, so a label can peek along, and 0 once it ends.
     * A follow or a release cuts it short.
     */
    fun wiggle(drag: (dragDp: Float) -> Unit) {
        if (static) return
        settle?.cancel()
        settle = scope.launch {
            wiggling = true
            try {
                val pretend: Animatable<Float, AnimationVector1D>.() -> Unit = {
                    tiltState.floatValue = domeTiltForDrag(value)
                    drag(value)
                }
                clock.snapTo(0f)
                clock.animateTo(-DomeWiggleDragDp, tween(DomeWiggleOutMillis, easing = FastOutSlowInEasing), block = pretend)
                // Holds ask for no frames.
                delay(DomeWiggleHoldMillis)
                clock.animateTo(DomeWiggleDragDp, tween(DomeWiggleAcrossMillis, easing = FastOutSlowInEasing), block = pretend)
                delay(DomeWiggleHoldMillis)
                clock.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), block = pretend)
            } finally {
                wiggling = false
                drag(0f)
            }
        }
    }

    /** A touch or a key mid-wiggle: it stops where it is and springs home, as a short drag's release does. */
    fun stopWiggle() {
        if (wiggling) release(0)
    }
}

@Composable
internal fun rememberDomeMotion(): DomeMotion {
    val static = LocalTelevisionHardware.current
    val scope = rememberCoroutineScope()
    return remember(scope, static) { DomeMotion(scope, static) }
}

/** How far hover and a press lighten the ring's disc. */
private const val DomeHoverLight = 0.08f
private const val DomePressLight = 0.2f

/**
 * Round, light feedback on the ring alone: the transport's node also holds the name, where a stock
 * ripple would centre. Hover lightens a disc the ring's size and a press lightens it further. No
 * focus layer: the transport's focus mark shows focus, and the dock's dome holds the launch focus,
 * where a layer would dim it.
 */
internal class DomeIndication(private val ringSize: Dp) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = DomeIndicationNode(interactionSource, ringSize)

    override fun equals(other: Any?): Boolean = other is DomeIndication && other.ringSize == ringSize

    override fun hashCode(): Int = ringSize.hashCode()
}

private class DomeIndicationNode(
    private val interactions: InteractionSource,
    private val ringSize: Dp,
) : Modifier.Node(), DrawModifierNode {
    private val light = Animatable(0f)
    private var hovers = 0
    private var presses = 0
    private var fade: Job? = null

    override fun onAttach() {
        coroutineScope.launch {
            interactions.interactions.collect { interaction ->
                when (interaction) {
                    is HoverInteraction.Enter -> hovers++
                    is HoverInteraction.Exit -> hovers--
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses--
                    else -> return@collect
                }
                val target = when {
                    presses > 0 -> DomePressLight
                    hovers > 0 -> DomeHoverLight
                    else -> 0f
                }
                fade?.cancel()
                fade = launch { light.animateTo(target, tween(if (target > light.value) 90 else 220)) }
            }
        }
    }

    // On the transport's node: the ring sits TransportPadding below its top, centred across it.
    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = light.value
        if (alpha <= 0f) return
        val radius = ringSize.toPx() / 2
        drawCircle(Color.White.copy(alpha = alpha), radius, Offset(size.width / 2, TransportPadding.toPx() + radius))
    }
}
