package org.balch.orpheus.features.mediapipe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquid
import kotlin.math.PI
import kotlin.math.atan2
import org.balch.orpheus.core.gestures.HandLandmark

/** Defaults for the articulated glass lens. */
object HandLensDefaults {
    /**
     * OFF by product decision, not by framerate. Seen in context over a live visualization,
     * the articulated lens was not the effect the app wants: hands read better as the plain
     * tinted skeleton against the raw viz. The lens path is kept rather than deleted because
     * a different treatment is wanted here later, and the geometry underneath it (capsules,
     * palm polygon, winding normalization) is what any such treatment would build on.
     *
     * Note this was never settled on performance: the probe's both-hands measurement was
     * never obtained. If glass is revived, that reading is still owed.
     */
    const val GLASS_ENABLED: Boolean = false
}

/** Whether the glass path renders at all; anything else falls back. Pure so it tests bare. */
internal fun rendersGlass(
    glassEnabled: Boolean,
    hasLiquidState: Boolean,
    landmarkCount: Int,
): Boolean = glassEnabled && hasLiquidState && landmarkCount >= 21

/** Lens tint alpha: strong enough that handedness reads at a glance, weak enough to see through. */
private const val LENS_TINT_ALPHA = 0.25f

/**
 * The hand as an articulated glass lens over whatever [liquidState]'s liquefiable source is.
 *
 * Same coordinate contract as [HandSkeletonOverlay]: landmarks are normalized 0..1 against
 * the camera image's own dimensions, so this must fill the same aspect-ratio box the image
 * (or its stand-in) uses — it drops in wherever the skeleton goes, and Orphic FM can adopt
 * it later.
 *
 * Degrades to [fallback] — by default the skeleton — whenever glass is disabled, there is no
 * liquid source to refract, or the landmark list is short. Refraction, curve, edge lighting,
 * and frost come from the liquid library rather than from new shader code; values start from
 * FlipDigit's DigitLiquidScope, the repo's other small glass object over the viz, with a
 * lighter frost because a hand is smaller than a digit card.
 */
@Composable
fun HandGlassOverlay(
    landmarks: List<HandLandmark>,
    liquidState: LiquidState?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Cyan,
    glassEnabled: Boolean = HandLensDefaults.GLASS_ENABLED,
    fallback: @Composable (Modifier) -> Unit = { fallbackModifier ->
        HandSkeletonOverlay(
            landmarks = landmarks,
            isPinching = false,
            landmarkColor = tint,
            modifier = fallbackModifier,
        )
    },
) {
    val state = liquidState.takeIf {
        rendersGlass(glassEnabled, hasLiquidState = liquidState != null, landmarkCount = landmarks.size)
    }
    val geometry = if (state != null) handLensGeometry(landmarks) else null
    if (state == null || geometry == null) {
        fallback(modifier)
        return
    }

    // Rebuilt every recomposition on purpose: landmarks change every camera frame, so a
    // remember(landmarks) would pay a 21-element comparison to almost never hit. This churn
    // is exactly what the Task 1 probe measured and approved.
    val handShape = GenericShape { size, _ -> addHandLens(geometry, size.width, size.height) }
    val lensTint = tint.copy(alpha = LENS_TINT_ALPHA)

    Box(
        // this-qualified where names collide with scope: the composable's own tint parameter
        // is a val, and an unqualified `tint =` would try to assign it and fail to compile.
        modifier = modifier.liquid(state) {
            this.shape = handShape
            frost = 2.dp
            this.tint = lensTint
            refraction = 0.6f
            curve = 0.3f
            edge = 0.2f
            dispersion = 0.5f
            saturation = 1.4f
            contrast = 1.2f
        },
    )
}

/**
 * Projects the normalized geometry into pixel space. Radii scale by min(width, height) so
 * circles stay circles when the box is not square; endpoints scale per-axis like the
 * skeleton's landmarks do.
 */
internal fun Path.addHandLens(geometry: HandLensGeometry, width: Float, height: Float) {
    val radiusScale = minOf(width, height)
    for (capsule in geometry.capsules) {
        addCapsule(capsule, width, height, radiusScale)
    }
    val palm = geometry.palmPolygon
    if (palm.size >= 3) {
        moveTo(palm[0].x * width, palm[0].y * height)
        for (i in 1 until palm.size) {
            lineTo(palm[i].x * width, palm[i].y * height)
        }
        close()
    }
}

/**
 * One capsule as a single clockwise outline: a half-circle cap at each end joined by the
 * arcs' own endpoints. One subpath, one winding — NonZero fill cannot carve holes the way
 * it can when two circles and a quad overlap with mixed windings.
 */
private fun Path.addCapsule(capsule: LensCapsule, width: Float, height: Float, radiusScale: Float) {
    val ax = capsule.start.x * width
    val ay = capsule.start.y * height
    val bx = capsule.end.x * width
    val by = capsule.end.y * height
    val r = capsule.radius * radiusScale
    val degrees = atan2(by - ay, bx - ax) * (180f / PI.toFloat())
    arcTo(Rect(center = Offset(ax, ay), radius = r), degrees + 90f, 180f, forceMoveTo = true)
    arcTo(Rect(center = Offset(bx, by), radius = r), degrees + 270f, 180f, forceMoveTo = false)
    close()
}
