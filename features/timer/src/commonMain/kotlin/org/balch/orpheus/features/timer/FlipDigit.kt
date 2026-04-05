package org.balch.orpheus.features.timer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.balch.orpheus.ui.infrastructure.LocalLiquidState
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.infrastructure.liquidVizEffects
import org.balch.orpheus.ui.theme.OrpheusColors

// Card background colors — deep indigo-black for sleep/nighttime feel.
// When liquid glass is active, these are transparent overlays on top of the
// glass effect. When liquid is unavailable, they provide the card appearance.
private val CardTopColor = Color(0xFF181828)
private val CardBottomColor = Color(0xFF10101E)
private val SplitLineColor = Color(0xCC000000)
private const val CARD_ALPHA_LIQUID = 0.35f   // translucent — let glass show through
private const val CARD_ALPHA_SOLID = 0.85f    // near-opaque fallback

// Distinct liquid lens for digit cards — higher refraction/saturation than panels
private val DigitLiquidScope = VisualizationLiquidScope(
    refraction = 0.6f,    // noticeable lens distortion
    curve = 0.3f,         // moderate curvature at center
    edge = 0.2f,          // subtle rim lighting
    saturation = 2.0f,    // vivid — makes viz colors pop through the glass
    dispersion = 0.5f,    // subtle chromatic aberration for a prismatic look
    contrast = 1.2f,      // slightly punchy
)

/**
 * A single split-flap digit with two-phase 3D flip animation and liquid glass effects.
 *
 * Renders a dark indigo card split by a horizontal divider. When [digit] changes:
 *   Phase 1 — Top flap (old digit) folds down from 0° to -90° with FastOutSlowIn easing
 *   Phase 2 — Bottom flap (new digit) rises from 90° to 0°, settling into place
 *
 * A shadow overlay darkens the flap proportionally to rotation, simulating reduced light
 * on the angled surface. Width is fixed at height * 0.7f.
 */
@Composable
fun FlipDigit(
    digit: Int,
    modifier: Modifier = Modifier,
    height: Dp = 108.dp,
    glowColor: Color = OrpheusColors.sleepMoonlight,
    animate: Boolean = true,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val liquidState = LocalLiquidState.current

    var previousDigit by remember { mutableIntStateOf(digit) }
    var currentDigit by remember { mutableIntStateOf(digit) }

    // Phase 1: top flap folds down (0° → -90°)
    val topFlapRotation = remember { Animatable(0f) }
    // Phase 2: bottom flap rises up (90° → 0°)
    val bottomFlapRotation = remember { Animatable(0f) }

    LaunchedEffect(digit) {
        if (digit != currentDigit) {
            if (animate) {
                previousDigit = currentDigit
                currentDigit = digit

                // Phase 1: top flap folds down
                topFlapRotation.snapTo(0f)
                bottomFlapRotation.snapTo(90f)
                topFlapRotation.animateTo(
                    -90f,
                    animationSpec = tween(
                        durationMillis = 350,
                        easing = FastOutSlowInEasing,
                    ),
                )

                // Phase 2: bottom flap settles into place
                bottomFlapRotation.animateTo(
                    0f,
                    animationSpec = tween(
                        durationMillis = 250,
                        easing = FastOutSlowInEasing,
                    ),
                )

                // Sync flap to current digit before resetting,
                // so the flap doesn't cover Layer 2 with a stale value.
                previousDigit = digit
                topFlapRotation.snapTo(0f)
            } else {
                // No animation — sync both so all layers match
                previousDigit = digit
                currentDigit = digit
            }
        }
    }

    val heightPx = with(density) { height.toPx() }
    val widthDp = height * 0.7f
    val widthPx = heightPx * 0.7f
    val cornerRadiusPx = with(density) { 4.dp.toPx() }
    val splitFraction = 0.5f
    val splitY = heightPx * splitFraction
    val textSizeTextUnit = with(density) { (heightPx * 0.58f).toSp() }
    val cameraDistanceFactor = 12f

    val digitTextStyle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = textSizeTextUnit,
        color = glowColor,
    )

    val textSizeKey = heightPx * 0.58f

    val currentMeasured = remember(currentDigit, textSizeKey) {
        textMeasurer.measure(currentDigit.toString(), digitTextStyle)
    }
    val previousMeasured = remember(previousDigit, textSizeKey) {
        textMeasurer.measure(previousDigit.toString(), digitTextStyle)
    }

    // Shadow alpha proportional to rotation angle (more rotated = darker)
    val topShadowAlpha = (topFlapRotation.value / -90f).coerceIn(0f, 1f) * 0.6f
    val bottomShadowAlpha = (bottomFlapRotation.value / 90f).coerceIn(0f, 1f) * 0.4f

    // Card alpha: translucent when liquid glass is active so the effect shows through
    val cardAlpha = if (liquidState != null) CARD_ALPHA_LIQUID else CARD_ALPHA_SOLID
    val topColor = CardTopColor.copy(alpha = cardAlpha)
    val bottomColor = CardBottomColor.copy(alpha = cardAlpha)

    Box(
        modifier = modifier
            .size(width = widthDp, height = height)
            .liquidVizEffects(
                liquidState = liquidState,
                scope = DigitLiquidScope,
                frostAmount = 6.dp,
                color = glowColor,
                tintAlpha = 0.08f,
                shape = RoundedCornerShape(4.dp),
            ),
    ) {

        // ── Layer 1: Bottom half — current digit (static) ──────────────────────────
        Canvas(modifier = Modifier.size(width = widthDp, height = height)) {
            val w = widthPx
            val h = heightPx

            clipRect(left = 0f, top = splitY, right = w, bottom = h) {
                drawRoundRect(
                    color = bottomColor,
                    size = Size(w, h),
                    cornerRadius = CornerRadius(cornerRadiusPx),
                )
                val textW = currentMeasured.size.width.toFloat()
                val textH = currentMeasured.size.height.toFloat()
                val textX = (w - textW) / 2f
                val textY = (h - textH) / 2f
                drawText(
                    textLayoutResult = currentMeasured,
                    topLeft = Offset(textX, textY),
                    color = glowColor,
                )
            }
        }

        // ── Layer 2: Top half — current digit (static, no rotation) ────────────────
        Canvas(modifier = Modifier.size(width = widthDp, height = height)) {
            val w = widthPx
            val h = heightPx

            clipRect(left = 0f, top = 0f, right = w, bottom = splitY) {
                drawRoundRect(
                    color = topColor,
                    size = Size(w, h),
                    cornerRadius = CornerRadius(cornerRadiusPx),
                )
                val textW = currentMeasured.size.width.toFloat()
                val textH = currentMeasured.size.height.toFloat()
                val textX = (w - textW) / 2f
                val textY = (h - textH) / 2f
                drawText(
                    textLayoutResult = currentMeasured,
                    topLeft = Offset(textX, textY),
                    color = glowColor,
                )
            }
        }

        // ── Layer 3: Top flap — old digit folds down (Phase 1) ─────────────────────
        val topRot = topFlapRotation.value
        Canvas(
            modifier = Modifier
                .size(width = widthDp, height = height)
                .graphicsLayer {
                    cameraDistance = cameraDistanceFactor * density.density
                    transformOrigin = TransformOrigin(0.5f, splitFraction)
                    rotationX = topRot
                },
        ) {
            val w = widthPx
            val h = heightPx

            clipRect(left = 0f, top = 0f, right = w, bottom = splitY) {
                drawRoundRect(
                    color = topColor,
                    size = Size(w, h),
                    cornerRadius = CornerRadius(cornerRadiusPx),
                )
                val textW = previousMeasured.size.width.toFloat()
                val textH = previousMeasured.size.height.toFloat()
                val textX = (w - textW) / 2f
                val textY = (h - textH) / 2f
                drawText(
                    textLayoutResult = previousMeasured,
                    topLeft = Offset(textX, textY),
                    color = glowColor,
                )

                // Shadow overlay — darkens as flap rotates away from viewer
                drawRect(
                    color = Color.Black.copy(alpha = topShadowAlpha),
                    size = Size(w, splitY),
                )
            }
        }

        // ── Layer 4: Bottom flap — new digit rises into place (Phase 2) ────────────
        val bottomRot = bottomFlapRotation.value
        if (bottomRot != 0f) {
            Canvas(
                modifier = Modifier
                    .size(width = widthDp, height = height)
                    .graphicsLayer {
                        cameraDistance = cameraDistanceFactor * density.density
                        transformOrigin = TransformOrigin(0.5f, splitFraction)
                        rotationX = bottomRot
                    },
            ) {
                val w = widthPx
                val h = heightPx

                clipRect(left = 0f, top = splitY, right = w, bottom = h) {
                    drawRoundRect(
                        color = bottomColor,
                        size = Size(w, h),
                        cornerRadius = CornerRadius(cornerRadiusPx),
                    )
                    val textW = currentMeasured.size.width.toFloat()
                    val textH = currentMeasured.size.height.toFloat()
                    val textX = (w - textW) / 2f
                    val textY = (h - textH) / 2f
                    drawText(
                        textLayoutResult = currentMeasured,
                        topLeft = Offset(textX, textY),
                        color = glowColor,
                    )

                    // Shadow overlay — darkens while flap is angled
                    drawRect(
                        color = Color.Black.copy(alpha = bottomShadowAlpha),
                        topLeft = Offset(0f, splitY),
                        size = Size(w, h - splitY),
                    )
                }
            }
        }

        // ── Split-line divider ────────────────────────────────────────────────────
        Canvas(modifier = Modifier.size(width = widthDp, height = height)) {
            drawLine(
                color = SplitLineColor,
                start = Offset(0f, splitY),
                end = Offset(widthPx, splitY),
                strokeWidth = 2f,
            )
        }
    }
}
