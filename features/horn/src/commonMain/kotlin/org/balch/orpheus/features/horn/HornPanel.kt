package org.balch.orpheus.features.horn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.plugin.symbols.HornSymbol
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.SignalTrace
import org.balch.orpheus.ui.widgets.RotaryKnob
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Blackout Crimson palette — sourced from OrpheusColors
private val CrimsonBg = OrpheusColors.hornBg
private val CrimsonHorn = OrpheusColors.hornCrimson
private val CrimsonWoofer = OrpheusColors.hornWoofer
private val CrimsonBorder = OrpheusColors.hornBorder
private val CrimsonKnob = OrpheusColors.hornKnob

// Physics: horn target velocity in degrees/sec at speed=1.0
private const val MAX_HORN_DEG_PER_SEC = 720f   // 2 rotations/sec at full speed
private const val MIN_HORN_DEG_PER_SEC = 20f    // barely moving at speed≈0
// Classic Leslie ratio: woofer spins slower than horn
private const val LESLIE_RATIO = 8f
// Inertia: time constants for acceleration/deceleration (seconds)
private const val RAMP_UP_TAU = 1.0f    // ~1s to reach target speed
private const val RAMP_DOWN_TAU = 3.0f  // ~3s to coast to stop (brake feel)

@Composable
fun HornPanel(
    feature: HornFeature = HornViewModel.feature(),
    inVizFlow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    outVizFlow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions
    val inViz by inVizFlow.collectAsState()
    val outViz by outVizFlow.collectAsState()

    // ── Physics-based rotor animation ──────────────────────────────────────
    // Each rotor has a current velocity (deg/sec) that accelerates/decelerates
    // toward a target velocity with realistic inertia. Angle accumulates each
    // frame based on current velocity × dt.

    // Target velocities derived from knobs
    val hornTargetDps = if (uiState.brake) 0f
        else lerp(MIN_HORN_DEG_PER_SEC, MAX_HORN_DEG_PER_SEC, uiState.speed)
    val ratioFactor = lerp(LESLIE_RATIO, 1f, uiState.ratio)
    val wooferTargetDps = hornTargetDps / ratioFactor

    // rememberUpdatedState so the LaunchedEffect always sees current targets
    val currentHornTarget by rememberUpdatedState(hornTargetDps)
    val currentWooferTarget by rememberUpdatedState(wooferTargetDps)

    // Mutable state for physics simulation
    var hornAngle by remember { mutableFloatStateOf(0f) }
    var wooferAngle by remember { mutableFloatStateOf(0f) }
    var hornVelocity by remember { mutableFloatStateOf(0f) }
    var wooferVelocity by remember { mutableFloatStateOf(0f) }

    // Frame-by-frame physics loop
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastNanos == 0L) { lastNanos = frameNanos; return@withFrameNanos }
                val dt = (frameNanos - lastNanos) / 1_000_000_000f  // seconds
                lastNanos = frameNanos

                // Read current targets (updated via rememberUpdatedState)
                val hornTarget = currentHornTarget
                val wooferTarget = currentWooferTarget

                // Exponential slew toward target velocity (different time constants for up/down)
                val hornTau = if (hornTarget > hornVelocity) RAMP_UP_TAU else RAMP_DOWN_TAU
                val wooferTau = if (wooferTarget > wooferVelocity) RAMP_UP_TAU else RAMP_DOWN_TAU
                val hornAlpha = 1f - kotlin.math.exp(-dt / hornTau)
                val wooferAlpha = 1f - kotlin.math.exp(-dt / wooferTau)

                hornVelocity += hornAlpha * (hornTarget - hornVelocity)
                wooferVelocity += wooferAlpha * (wooferTarget - wooferVelocity)

                // Stop completely when velocity is negligible
                if (hornVelocity < 0.5f && hornTarget == 0f) hornVelocity = 0f
                if (wooferVelocity < 0.5f && wooferTarget == 0f) wooferVelocity = 0f

                // Accumulate angle
                hornAngle = (hornAngle + hornVelocity * dt) % 360f
                wooferAngle = (wooferAngle + wooferVelocity * dt) % 360f
            }
        }
    }

    CollapsibleColumnPanel(
        title = "HORN",
        expandedTitle = "Leslie",
        color = CrimsonHorn,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        backgroundContent = {
            SignalTrace(data = inViz, color = CrimsonWoofer.copy(alpha = 0.5f))
            SignalTrace(data = outViz, color = CrimsonHorn.copy(alpha = 0.6f))
        }
    ) {
        // Dual rotor animation area — fixed width, centered
        Row(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .height(160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CrimsonBg)
                .border(1.dp, CrimsonBorder, RoundedCornerShape(8.dp)),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConcentricRingsAnimation(
                hornAngle = hornAngle,
                wooferAngle = wooferAngle,
                speed = uiState.speed,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(8.dp),
            )
            CabinetCrossSectionAnimation(
                hornAngle = hornAngle,
                wooferAngle = wooferAngle,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(8.dp),
            )
        }

        // All controls in one row
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                value = uiState.speed,
                onValueChange = actions.setSpeed,
                label = "SPEED",
                controlId = HornSymbol.SPEED.controlId.key,
                size = 38.dp,
                trackColor = CrimsonBg,
                progressColor = CrimsonHorn,
                knobColor = CrimsonKnob,
                labelColor = CrimsonHorn,
            )
            RotaryKnob(
                value = uiState.ratio,
                onValueChange = actions.setRatio,
                label = "RATIO",
                controlId = HornSymbol.RATIO.controlId.key,
                size = 38.dp,
                trackColor = CrimsonBg,
                progressColor = CrimsonHorn,
                knobColor = CrimsonKnob,
                labelColor = CrimsonHorn,
            )
            RotaryKnob(
                value = uiState.depth,
                onValueChange = actions.setDepth,
                label = "DEPTH",
                controlId = HornSymbol.DEPTH.controlId.key,
                size = 38.dp,
                trackColor = CrimsonBg,
                progressColor = CrimsonHorn,
                knobColor = CrimsonKnob,
                labelColor = CrimsonHorn,
            )
            RotaryKnob(
                value = uiState.amount,
                onValueChange = actions.setAmount,
                label = "AMT",
                controlId = HornSymbol.AMOUNT.controlId.key,
                size = 38.dp,
                trackColor = CrimsonBg,
                progressColor = CrimsonHorn,
                knobColor = CrimsonKnob,
                labelColor = CrimsonHorn,
            )
            RotaryKnob(
                value = uiState.mix,
                onValueChange = actions.setMix,
                label = "MIX",
                controlId = HornSymbol.MIX.controlId.key,
                size = 38.dp,
                trackColor = CrimsonBg,
                progressColor = CrimsonHorn,
                knobColor = CrimsonKnob,
                labelColor = CrimsonHorn,
            )

            BrakeToggle(
                engaged = uiState.brake,
                onToggle = actions.setBrake,
                controlId = HornSymbol.BRAKE.controlId.key,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Concentric Rings Animation
// Two glowing arcs (inner=horn, outer=woofer) with trailing fade, rotating at
// their respective speeds. A center ember dot anchors the composition.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConcentricRingsAnimation(
    hornAngle: Float,
    wooferAngle: Float,
    speed: Float,
    modifier: Modifier = Modifier,
) {
    // Trail length in degrees: 60° at slow, 150° at fast
    val trailDegrees = lerp(60f, 150f, speed)

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val minDim = minOf(size.width, size.height)

        val outerRadius = minDim * 0.42f  // woofer ring
        val innerRadius = minDim * 0.25f  // horn ring
        val strokeWidth = minDim * 0.045f

        // --- Woofer ring (outer, darker crimson) ---
        drawRotorRing(
            cx = cx,
            cy = cy,
            radius = outerRadius,
            angle = wooferAngle,
            trailDegrees = trailDegrees * 0.75f,  // woofer trail slightly shorter
            headColor = CrimsonWoofer,
            strokeWidth = strokeWidth,
        )

        // --- Horn ring (inner, bright crimson) ---
        drawRotorRing(
            cx = cx,
            cy = cy,
            radius = innerRadius,
            angle = hornAngle,
            trailDegrees = trailDegrees,
            headColor = CrimsonHorn,
            strokeWidth = strokeWidth,
        )

        // --- Center ember dot ---
        val dotRadius = minDim * 0.045f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(CrimsonHorn, CrimsonHorn.copy(alpha = 0.4f), Color.Transparent),
                center = Offset(cx, cy),
                radius = dotRadius * 2f,
            ),
            radius = dotRadius * 2f,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = CrimsonHorn,
            radius = dotRadius * 0.5f,
            center = Offset(cx, cy),
        )

        // --- Ember glow at horn rotor head ---
        val hornRadians = (hornAngle - 90f) * (PI / 180f).toFloat()
        val hornHeadX = cx + innerRadius * cos(hornRadians)
        val hornHeadY = cy + innerRadius * sin(hornRadians)
        val glowRadius = strokeWidth * 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    CrimsonHorn.copy(alpha = 0.9f),
                    CrimsonHorn.copy(alpha = 0.3f),
                    Color.Transparent,
                ),
                center = Offset(hornHeadX, hornHeadY),
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = Offset(hornHeadX, hornHeadY),
        )

        // --- Ember glow at woofer rotor head ---
        val wooferRadians = (wooferAngle - 90f) * (PI / 180f).toFloat()
        val wooferHeadX = cx + outerRadius * cos(wooferRadians)
        val wooferHeadY = cy + outerRadius * sin(wooferRadians)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    CrimsonWoofer.copy(alpha = 0.8f),
                    CrimsonWoofer.copy(alpha = 0.25f),
                    Color.Transparent,
                ),
                center = Offset(wooferHeadX, wooferHeadY),
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = Offset(wooferHeadX, wooferHeadY),
        )
    }
}

/**
 * Draws a single rotor arc: a gradient trail that fades from [headColor] at the head to
 * transparent 120° behind, plus a small cap at the head position.
 */
private fun DrawScope.drawRotorRing(
    cx: Float,
    cy: Float,
    radius: Float,
    angle: Float,
    trailDegrees: Float,
    headColor: Color,
    strokeWidth: Float,
) {
    val sweepSteps = 12
    val stepDegrees = trailDegrees / sweepSteps

    // Draw multiple arc segments from tail (transparent) to head (opaque)
    for (i in 0 until sweepSteps) {
        val alpha = (i + 1f) / sweepSteps
        val startAngle = angle - trailDegrees + i * stepDegrees - 90f
        val segColor = headColor.copy(alpha = alpha * alpha)  // quadratic fade for more dramatic trail

        val diameter = radius * 2f
        val topLeft = Offset(cx - radius, cy - radius)
        drawArc(
            color = segColor,
            startAngle = startAngle,
            sweepAngle = stepDegrees + 0.5f,  // slight overlap to avoid gaps
            useCenter = false,
            topLeft = topLeft,
            size = Size(diameter, diameter),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Cabinet Cross-Section Animation
// A schematic cutaway of a Leslie cabinet: horn rotor (top) and woofer drum
// (bottom) rendered with 3D foreshortening via scaleX = cos(phase).
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CabinetCrossSectionAnimation(
    hornAngle: Float,
    wooferAngle: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val cabinetPadding = 6f
        val cornerRadius = 10f
        val shelfY = h * 0.5f

        // Cabinet outline
        drawRoundRect(
            color = CrimsonBorder,
            topLeft = Offset(cabinetPadding, cabinetPadding),
            size = Size(w - cabinetPadding * 2f, h - cabinetPadding * 2f),
            cornerRadius = CornerRadius(cornerRadius),
            style = Stroke(width = 1.5f),
        )

        // Louvered vent lines — left and right sides
        val louverCount = 7
        val louverAreaTop = cabinetPadding + cornerRadius
        val louverAreaBottom = h - cabinetPadding - cornerRadius
        val louverSpacing = (louverAreaBottom - louverAreaTop) / (louverCount + 1)
        val louverLength = 10f
        val louverColor = CrimsonBorder.copy(alpha = 0.8f)

        for (i in 1..louverCount) {
            val ly = louverAreaTop + i * louverSpacing
            // Left vents
            drawLine(
                color = louverColor,
                start = Offset(cabinetPadding + 1f, ly),
                end = Offset(cabinetPadding + louverLength, ly),
                strokeWidth = 1f,
            )
            // Right vents
            drawLine(
                color = louverColor,
                start = Offset(w - cabinetPadding - louverLength, ly),
                end = Offset(w - cabinetPadding - 1f, ly),
                strokeWidth = 1f,
            )
        }

        // Horizontal shelf divider
        drawLine(
            color = CrimsonBorder,
            start = Offset(cabinetPadding + 4f, shelfY),
            end = Offset(w - cabinetPadding - 4f, shelfY),
            strokeWidth = 1f,
        )

        // Section label: HORN (top half)
        val labelY = cabinetPadding + 10f
        // We can't draw text on Canvas directly in common Compose; use the foreshortened rotor
        // to visually identify the sections without text (text drawing requires platform APIs
        // not available cross-platform in Canvas draw scope).

        // ─── Horn rotor (top half) ───
        // Foreshortening: rotor paddle appears to compress horizontally as it turns edge-on.
        // cos(angle in radians) gives the visual width fraction: 1.0 = face-on, 0 = edge-on.
        val hornRad = hornAngle * (PI / 180f).toFloat()
        val hornScaleX = abs(cos(hornRad)).coerceAtLeast(0.05f)

        val hornCx = w / 2f
        val hornCy = (cabinetPadding + shelfY) / 2f
        val hornPaddleW = (w - cabinetPadding * 2f - 16f) * 0.55f
        val hornPaddleH = (shelfY - cabinetPadding) * 0.22f

        // Glow halo behind horn paddle
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    CrimsonHorn.copy(alpha = 0.15f),
                    Color.Transparent,
                ),
                center = Offset(hornCx, hornCy),
                radius = hornPaddleW * 0.7f,
            ),
            radius = hornPaddleW * 0.7f,
            center = Offset(hornCx, hornCy),
        )

        // The horn paddle — foreshortened pill shape
        drawHornPaddle(
            cx = hornCx,
            cy = hornCy,
            halfWidth = hornPaddleW / 2f * hornScaleX,
            halfHeight = hornPaddleH / 2f,
            color = CrimsonHorn,
            glowAlpha = 0.6f,
        )

        // ─── Woofer drum (bottom half) ───
        val wooferRad = wooferAngle * (PI / 180f).toFloat()
        val wooferScaleX = abs(cos(wooferRad)).coerceAtLeast(0.05f)

        val wooferCx = w / 2f
        val wooferCy = (shelfY + h - cabinetPadding) / 2f
        val wooferPaddleW = (w - cabinetPadding * 2f - 16f) * 0.70f  // woofer is wider
        val wooferPaddleH = (h - cabinetPadding - shelfY) * 0.28f

        // Glow halo behind woofer paddle
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    CrimsonWoofer.copy(alpha = 0.12f),
                    Color.Transparent,
                ),
                center = Offset(wooferCx, wooferCy),
                radius = wooferPaddleW * 0.65f,
            ),
            radius = wooferPaddleW * 0.65f,
            center = Offset(wooferCx, wooferCy),
        )

        drawHornPaddle(
            cx = wooferCx,
            cy = wooferCy,
            halfWidth = wooferPaddleW / 2f * wooferScaleX,
            halfHeight = wooferPaddleH / 2f,
            color = CrimsonWoofer,
            glowAlpha = 0.5f,
        )
    }
}

/**
 * Draws a foreshortened rotor paddle: a rounded rectangle with a gradient fill and soft edge glow.
 * [halfWidth] is already pre-scaled by cos(phase) for the 3D foreshortening effect.
 */
private fun DrawScope.drawHornPaddle(
    cx: Float,
    cy: Float,
    halfWidth: Float,
    halfHeight: Float,
    color: Color,
    glowAlpha: Float,
) {
    val paddleW = halfWidth * 2f
    val paddleH = halfHeight * 2f
    val cornerR = minOf(halfWidth, halfHeight) * 0.6f

    if (paddleW < 1f || paddleH < 1f) return

    // Fill with vertical gradient: lighter center, darker edges
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                color.copy(alpha = 0.9f),
                color.copy(alpha = 0.5f),
            ),
            startY = cy - halfHeight,
            endY = cy + halfHeight,
        ),
        topLeft = Offset(cx - halfWidth, cy - halfHeight),
        size = Size(paddleW, paddleH),
        cornerRadius = CornerRadius(cornerR),
    )

    // Edge glow / outline
    drawRoundRect(
        color = color.copy(alpha = glowAlpha),
        topLeft = Offset(cx - halfWidth, cy - halfHeight),
        size = Size(paddleW, paddleH),
        cornerRadius = CornerRadius(cornerR),
        style = Stroke(width = 1.5f),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Brake Toggle
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A tactile BRAKE toggle styled to match the Blackout Crimson panel palette.
 * When engaged, the button glows crimson — evoking the visual state of a motor being stopped.
 */
@Composable
private fun BrakeToggle(
    engaged: Boolean,
    onToggle: (Boolean) -> Unit,
    controlId: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Text(
            text = "BRAKE",
            style = MaterialTheme.typography.labelSmall,
            color = CrimsonHorn.copy(alpha = 0.7f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
        )

        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (engaged) CrimsonHorn.copy(alpha = 0.85f)
                    else CrimsonBg
                )
                .border(
                    width = 1.dp,
                    color = if (engaged) CrimsonHorn else CrimsonWoofer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp)
                )
                .clickable { onToggle(!engaged) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (engaged) "STOP" else "RUN",
                color = if (engaged) Color.White else CrimsonWoofer,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Utility
// ─────────────────────────────────────────────────────────────────────────────

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

// ─────────────────────────────────────────────────────────────────────────────
// Previews
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "Horn Panel — Collapsed Default", widthDp = 400, heightDp = 200)
@Composable
fun HornPanelCollapsedPreview() {
    OrpheusTheme {
        HornPanel(
            feature = HornViewModel.previewFeature(HornUiState()),
        )
    }
}

@Preview(name = "Horn Panel — Expanded Default", widthDp = 400, heightDp = 500)
@Composable
fun HornPanelExpandedPreview() {
    OrpheusTheme {
        HornPanel(
            feature = HornViewModel.previewFeature(HornUiState()),
            isExpanded = true,
        )
    }
}

@Preview(name = "Horn Panel — Brake Engaged", widthDp = 400, heightDp = 500)
@Composable
fun HornPanelBrakeEngagedPreview() {
    OrpheusTheme {
        HornPanel(
            feature = HornViewModel.previewFeature(
                HornUiState(
                    speed = 0.3f,
                    ratio = 0.7f,
                    depth = 0.8f,
                    amount = 0.6f,
                    mix = 0.75f,
                    brake = true,
                )
            ),
            isExpanded = true,
        )
    }
}

@Preview(name = "Horn Panel — Full Speed", widthDp = 400, heightDp = 500)
@Composable
fun HornPanelFullSpeedPreview() {
    OrpheusTheme {
        HornPanel(
            feature = HornViewModel.previewFeature(
                HornUiState(
                    speed = 1.0f,
                    ratio = 0.5f,
                    depth = 1.0f,
                    amount = 0.8f,
                    mix = 1.0f,
                    brake = false,
                )
            ),
            isExpanded = true,
        )
    }
}
