package org.balch.orpheus.features.visualizations.viz.fish

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A fish in the aquarium visualization.
 * Mutable fields are updated in-place each frame for performance.
 * Plain class (not data class) because mutable vars make equals/hashCode unreliable.
 */
class Fish(
    val id: Int,
    val baseColor: Color,
    val baseSize: Float,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var heading: Float = 0f,
    var smoothHeading: Float = 0f,
    var tailPhase: Float = 0f,
    var voiceIndex: Int = 0,
    var energy: Float = 0f,
    var pulseScale: Float = 1f,
    var alpha: Float = 1f,
    var isFadingOut: Boolean = false,
    /** Body mirror across the long axis: +1 upright, -1 rolled over. Eases between the two. */
    var roll: Float = 1f,
    /** Where [roll] is heading; held steady while the fish points near-vertically. */
    var rollTarget: Float = 1f,
)

/** Below this |cos(heading)| the fish is near-vertical and keeps whatever roll it had. */
private const val ROLL_DEADBAND = 0.15f

/** Per-frame approach rate for [Fish.roll]; a flip reads as a quick roll, not a jump cut. */
internal const val ROLL_EASE = 0.25f

/**
 * Roll [Fish.roll] should settle at for this heading: +1 while the nose points right,
 * -1 once it points left so the fish keeps its back up instead of swimming belly-first.
 * Near vertical the [current] target is held, which keeps a hovering fish from chattering.
 */
internal fun rollTargetFor(smoothHeading: Float, current: Float): Float {
    val facing = cos(smoothHeading)
    return when {
        facing > ROLL_DEADBAND -> 1f
        facing < -ROLL_DEADBAND -> -1f
        else -> current
    }
}

/**
 * Screen-space Y of the fish's dorsal (back) direction. Negative means the back points up.
 * Only depends on the two values the draw transform is built from.
 */
internal fun dorsalScreenY(smoothHeading: Float, roll: Float): Float = -roll * cos(smoothHeading)

/**
 * Draws one fish nose-first along its heading.
 * The artwork is authored facing right, so leftward travel mirrors the body across its own long
 * axis ([Fish.roll] as scaleY) rather than rotating past vertical, which would land it belly-up.
 * Paths are passed in because the caller pre-allocates them to keep the frame allocation-free.
 */
internal fun DrawScope.drawFish(f: Fish, w: Float, h: Float, bodyPath: Path, tailPath: Path) {
    if (f.alpha <= 0.01f) return

    val cx = f.x * w
    val cy = f.y * h
    val fishLen = f.baseSize * w * f.pulseScale
    val fishHeight = fishLen * 0.4f

    val brightness = 1f + f.energy * 0.8f  // much brighter flash on active voice
    val fishColor = f.baseColor.copy(
        red = (f.baseColor.red * brightness).coerceAtMost(1f),
        green = (f.baseColor.green * brightness).coerceAtMost(1f),
        blue = (f.baseColor.blue * brightness).coerceAtMost(1f),
        alpha = f.alpha
    )

    val tailSwing = sin(f.tailPhase) * fishHeight * 0.5f
    val pivot = Offset(cx, cy)

    rotate(degrees = f.smoothHeading * 180f / PI.toFloat(), pivot = pivot) {
        scale(scaleX = 1f, scaleY = f.roll, pivot = pivot) {
            bodyPath.reset()
            bodyPath.moveTo(cx + fishLen * 0.5f, cy)
            bodyPath.cubicTo(
                cx + fishLen * 0.3f, cy - fishHeight * 0.5f,
                cx - fishLen * 0.1f, cy - fishHeight * 0.5f,
                cx - fishLen * 0.3f, cy
            )
            bodyPath.cubicTo(
                cx - fishLen * 0.1f, cy + fishHeight * 0.5f,
                cx + fishLen * 0.3f, cy + fishHeight * 0.5f,
                cx + fishLen * 0.5f, cy
            )
            bodyPath.close()
            drawPath(bodyPath, fishColor)

            // Forked tail fin
            tailPath.reset()
            tailPath.moveTo(cx - fishLen * 0.3f, cy)
            tailPath.lineTo(cx - fishLen * 0.55f, cy - fishHeight * 0.5f + tailSwing)
            tailPath.lineTo(cx - fishLen * 0.4f, cy + tailSwing * 0.3f)
            tailPath.lineTo(cx - fishLen * 0.55f, cy + fishHeight * 0.5f + tailSwing)
            tailPath.close()
            drawPath(tailPath, fishColor.copy(alpha = fishColor.alpha * 0.8f))

            val eyeX = cx + fishLen * 0.25f
            val eyeR = fishLen * 0.05f
            drawCircle(Color.White.copy(alpha = f.alpha), eyeR, Offset(eyeX, cy - fishHeight * 0.1f))
            drawCircle(Color.Black.copy(alpha = f.alpha), eyeR * 0.5f, Offset(eyeX, cy - fishHeight * 0.1f))
        }
    }
}
