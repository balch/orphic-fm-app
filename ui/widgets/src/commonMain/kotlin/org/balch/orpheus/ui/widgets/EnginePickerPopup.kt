package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.fletchmckee.liquid.liquid
import io.github.fletchmckee.liquid.rememberLiquidState
import org.balch.orpheus.ui.infrastructure.LocalDialogLiquidState
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.darken
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ═══════════════════════════════════════════════════════════
// Picker configuration
// ═══════════════════════════════════════════════════════════

/** One entry in the engine ring. */
data class PickerEntry(val label: String, val ordinal: Int, val color: Color)

/** Configuration for [EnginePickerPopup] describing the ring entries and center. */
data class PickerConfig(
    val ring: List<PickerEntry>,
    val centerLabel: String,
    val centerOrdinal: Int,
) {
    val segmentCount get() = ring.size
    val sweepDeg get() = 360f / segmentCount
}

// ── Voice engines (same ring, center = "OSC" ordinal 0) ──

val VOICE_PICKER_CONFIG = PickerConfig(
    ring = listOf(
        PickerEntry("FM",  5, OrpheusColors.warmGlow),
        PickerEntry("NSE", 6, OrpheusColors.neonCyan),
        PickerEntry("WSH", 7, OrpheusColors.enginePurple),
        PickerEntry("VA",  8, OrpheusColors.engineRed),
        PickerEntry("ADD", 9, OrpheusColors.engineBlue),
        PickerEntry("GRN", 10, OrpheusColors.engineGreen),
        PickerEntry("STR", 11, OrpheusColors.engineYellow),
        PickerEntry("MOD", 12, OrpheusColors.engineOrange),
    ),
    centerLabel = "OSC",
    centerOrdinal = 0,
)

// ── Drum engines (PlaitsEngineId ordinals, skipping 3=FMD) ──

private val DRUM_RING = listOf(
    PickerEntry("BD",  0, OrpheusColors.neonMagenta),
    PickerEntry("SD",  1, OrpheusColors.electricBlue),
    PickerEntry("HH",  2, OrpheusColors.synthGreen),
    PickerEntry("FM",  3, OrpheusColors.warmGlow),
    PickerEntry("FM2", 4, OrpheusColors.presetOrange),
    PickerEntry("NSE", 5, OrpheusColors.neonCyan),
    PickerEntry("WSH", 6, OrpheusColors.enginePurple),
    PickerEntry("VA",  7, OrpheusColors.engineRed),
    PickerEntry("ADD", 8, OrpheusColors.engineBlue),
    PickerEntry("GRN", 9, OrpheusColors.engineGreen),
    PickerEntry("STR", 10, OrpheusColors.engineYellow),
    PickerEntry("MOD", 11, OrpheusColors.engineOrange),
)

/** Drum picker with BD as center default. */
val DRUM_BD_PICKER_CONFIG = PickerConfig(DRUM_RING, "BD", 0)
/** Drum picker with SD as center default. */
val DRUM_SD_PICKER_CONFIG = PickerConfig(DRUM_RING, "SD", 1)
/** Drum picker with HH as center default. */
val DRUM_HH_PICKER_CONFIG = PickerConfig(DRUM_RING, "HH", 2)

// ═══════════════════════════════════════════════════════════
// Segment hit-testing
// ═══════════════════════════════════════════════════════════

/** Picker circle diameter. */
val PICKER_SIZE: Dp = 160.dp

/**
 * Determines which segment a pointer at (dx, dy) from the picker center is over.
 * @return -1 = center, 0..n = ring segment index, null = outside picker
 */
fun computePickerSegment(
    dx: Float, dy: Float, dist: Float, radiusPx: Float,
    config: PickerConfig = VOICE_PICKER_CONFIG,
): Int? {
    if (dist < radiusPx * 0.35f) return -1
    if (dist > radiusPx) return null
    var angle = atan2(dy, dx) * 180f / PI.toFloat()
    if (angle < 0) angle += 360f
    var relAngle = angle + 90f + config.sweepDeg / 2f
    while (relAngle < 0f) relAngle += 360f
    relAngle %= 360f
    return (relAngle / config.sweepDeg).toInt().coerceIn(0, config.segmentCount - 1)
}

/** Converts a segment index (-1 = center, 0+ = ring) to an engine ordinal. */
fun pickerSegmentToOrdinal(segment: Int, config: PickerConfig = VOICE_PICKER_CONFIG): Int =
    when (segment) {
        -1 -> config.centerOrdinal
        in config.ring.indices -> config.ring[segment].ordinal
        else -> config.centerOrdinal
    }

/** Maps a voice engine ordinal to its short display label. */
fun engineLabel(ordinal: Int): String = when (ordinal) {
    0 -> "OSC"
    5 -> "FM"; 6 -> "NSE"; 7 -> "WSH"
    8 -> "VA"; 9 -> "ADD"; 10 -> "GRN"; 11 -> "STR"; 12 -> "MOD"
    else -> "?"
}

/** Maps a drum engine ordinal (PlaitsEngineId) to its short display label. */
fun drumEngineLabel(ordinal: Int): String = when (ordinal) {
    0 -> "BD"; 1 -> "SD"; 2 -> "HH"; 3 -> "FM"
    4 -> "FM2"; 5 -> "NSE"; 6 -> "WSH"
    7 -> "VA"; 8 -> "ADD"; 9 -> "GRN"; 10 -> "STR"; 11 -> "MOD"
    else -> "?"
}

// ═══════════════════════════════════════════════════════════
// Self-contained button + popup
// ═══════════════════════════════════════════════════════════

/**
 * Self-contained engine picker button that manages its own popup state and gesture handling.
 * Press-and-drag to select an engine from the radial popup.
 */
@Composable
fun EnginePickerButton(
    currentEngine: Int,
    onEngineChange: (Int) -> Unit,
    color: Color,
    label: String,
    config: PickerConfig = VOICE_PICKER_CONFIG,
    size: Dp = 28.dp,
    anchorSize: Dp = size,
    labelStyle: TextStyle = TextStyle(
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
    ),
    modifier: Modifier = Modifier,
) {
    var showEnginePicker by remember { mutableStateOf(false) }
    var hoveredSegment by remember { mutableStateOf<Int?>(null) }
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f))
                .border(1.dp, color.copy(alpha = 0.4f), CircleShape)
                .pointerInput(Unit) {
                    val pickerRadiusPx = PICKER_SIZE.toPx() / 2f
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                            .also { it.consume() }
                        showEnginePicker = true
                        hoveredSegment = null

                        var anyPressed = true
                        while (anyPressed) {
                            val event = awaitPointerEvent()
                            val pos = event.changes.firstOrNull()?.position
                            if (pos != null) {
                                val cx = this@pointerInput.size.width / 2f
                                val cy = this@pointerInput.size.height / 2f
                                val dx = pos.x - cx
                                val dy = pos.y - cy
                                val dist = sqrt(dx * dx + dy * dy)
                                hoveredSegment =
                                    computePickerSegment(dx, dy, dist, pickerRadiusPx, config)
                            }
                            event.changes.forEach { it.consume() }
                            anyPressed = event.changes.any { it.pressed }
                        }

                        val seg = hoveredSegment
                        if (seg != null) {
                            onEngineChange(pickerSegmentToOrdinal(seg, config))
                        }
                        showEnginePicker = false
                        hoveredSegment = null
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text(
                text = label,
                style = labelStyle,
                color = color,
                maxLines = 1
            )
        }
        if (showEnginePicker) {
            EnginePickerPopup(
                currentEngine = currentEngine,
                hoveredSegment = hoveredSegment,
                color = color,
                config = config,
                anchorSize = anchorSize,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Display-only popup
// ═══════════════════════════════════════════════════════════

/**
 * Display-only circular engine picker popup.
 * Renders a ring of engine options around a labeled center button.
 * Pointer interaction is handled by the parent; this composable just renders state.
 *
 * @param hoveredSegment -1 = center hovered, 0-5 = ring segment hovered, null = nothing
 * @param config which engines to show and how to label the center
 * @param anchorSize size of the trigger button so the popup centers over it
 */
@Composable
fun EnginePickerPopup(
    currentEngine: Int,
    hoveredSegment: Int?,
    color: Color,
    config: PickerConfig = VOICE_PICKER_CONFIG,
    anchorSize: Dp = 28.dp,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val offsetPx = with(density) { (-(PICKER_SIZE - anchorSize) / 2).roundToPx() }
    val liquidState = LocalDialogLiquidState.current ?: rememberLiquidState()

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(offsetPx, offsetPx),
        properties = PopupProperties(focusable = false)
    ) {
        Box(
            modifier = modifier
                .size(PICKER_SIZE)
                .liquid(liquidState) {
                    frost = 4.dp
                    saturation = .8f
                    dispersion = 0.2f
                    refraction = 0.5f
                    this.shape = shape
                    tint = OrpheusColors.midnightBlue.copy(alpha = 0.1f) // Glassier effect
                }
                .border(1.dp, color.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val textMeasurer = rememberTextMeasurer()

            Canvas(modifier = Modifier.size(PICKER_SIZE)) {
                drawRings(config, currentEngine, size, hoveredSegment, textMeasurer)
                drawCenter(config, currentEngine, size, hoveredSegment, textMeasurer, color)
            }
        }
    }
}

private fun DrawScope.drawCenter(
    config: PickerConfig,
    currentEngine: Int,
    size: Size,
    hoveredSegment: Int?,
    textMeasurer: TextMeasurer,
    color: Color,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = size.width / 2f
    val center = Offset(cx, cy)
    val centerRadius = radius * 0.32f

    val centerHovered = hoveredSegment == -1
    val centerIsCurrent = currentEngine == config.centerOrdinal
    val centerHighlighted =
        centerHovered || (centerIsCurrent && hoveredSegment == null)
    val cColor = if (centerHighlighted) color
    else color.darken(0.3f).copy(alpha = 0.5f)

    drawCircle(
        color = cColor.copy(
            alpha = if (centerHighlighted) 0.30f else 0.12f
        ),
        radius = centerRadius,
        center = center,
    )
    if (centerHovered) {
        drawCircle(
            color = color.copy(alpha = 0.35f),
            radius = centerRadius * 0.8f,
            center = center
        )
        drawCircle(
            color = color.copy(alpha = 0.15f),
            radius = centerRadius,
            center = center
        )
    }
    drawCircle(
        color = cColor.copy(alpha = 0.5f),
        radius = centerRadius,
        center = center,
        style = Stroke(width = 1.5.dp.toPx())
    )

    val centerStyle = TextStyle(
        color = if (centerHighlighted) Color.White
        else Color.White.copy(alpha = 0.5f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
    val cm = textMeasurer.measure(config.centerLabel, centerStyle)
    withTransform({
        translate(
            cx - cm.size.width / 2f,
            cy - cm.size.height / 2f
        )
    }) {
        drawText(cm)
    }
}

/** Build an annular sector (donut wedge) path between inner and outer radii. */
private fun annularSectorPath(
    cx: Float, cy: Float,
    innerR: Float, outerR: Float,
    startDeg: Float, sweepDeg: Float,
): Path = Path().apply {
    val outerRect = Rect(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
    val innerRect = Rect(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
    // Outer arc forward
    arcTo(outerRect, startDeg, sweepDeg, forceMoveTo = true)
    // Inner arc backward (closes the donut wedge)
    arcTo(innerRect, startDeg + sweepDeg, -sweepDeg, forceMoveTo = false)
    close()
}

private fun DrawScope.drawRings(
    config: PickerConfig,
    currentEngine: Int,
    size: Size,
    hoveredSegment: Int?,
    textMeasurer: TextMeasurer
) {
    val segments = config.segmentCount
    val sweep = config.sweepDeg
    val ring = config.ring

    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = size.width / 2f

    // Simon-style annular wedges (donut sectors)
    val outerRadius = radius * 0.92f
    val innerRadius = radius * 0.35f
    val gap = 4f
    val labelRadius = (outerRadius + innerRadius) / 2f

    val activeRingIdx = ring.indexOfFirst { it.ordinal == currentEngine }

    for (i in 0 until segments) {
        val entry = ring[i]
        val isHovered = hoveredSegment == i
        val isCurrent = i == activeRingIdx

        val arcStart = -90f + i * sweep + gap / 2f
        val arcSweep = sweep - gap

        // Glow behind hovered wedge
        if (isHovered) {
            val glowPath = annularSectorPath(
                cx, cy, innerRadius - 2.dp.toPx(), outerRadius + 2.dp.toPx(),
                arcStart - 1f, arcSweep + 2f
            )
            drawPath(glowPath, color = entry.color.copy(alpha = 0.15f))
        }

        // Filled wedge
        val wedgePath = annularSectorPath(cx, cy, innerRadius, outerRadius, arcStart, arcSweep)

        val wedgeColor = when {
            isHovered -> entry.color.copy(alpha = 0.45f)
            isCurrent -> entry.color.copy(alpha = 0.30f)
            else -> entry.color.darken(0.3f).copy(alpha = 0.15f)
        }
        drawPath(wedgePath, color = wedgeColor)

        // Outline stroke for definition
        val strokeAlpha = when {
            isHovered -> 0.5f
            isCurrent -> 0.35f
            else -> 0.15f
        }
        drawPath(wedgePath, color = entry.color.copy(alpha = strokeAlpha), style = Stroke(width = 1.dp.toPx()))

        // Label centered in the wedge
        val labelAngle = -90f + i * sweep + sweep / 2f
        val labelRad = (labelAngle * PI / 180f).toFloat()
        val lx = cx + labelRadius * cos(labelRad)
        val ly = cy + labelRadius * sin(labelRad)

        val labelStyle = TextStyle(
            color = when {
                isHovered -> Color.White
                isCurrent -> Color.White.copy(alpha = 0.9f)
                else -> Color.White.copy(alpha = 0.6f)
            },
            fontSize = 10.sp,
            fontWeight = if (isHovered || isCurrent) FontWeight.Bold
            else FontWeight.Normal,
        )
        val measured = textMeasurer.measure(entry.label, labelStyle)
        withTransform({
            translate(
                lx - measured.size.width / 2f,
                ly - measured.size.height / 2f
            )
        }) {
            drawText(measured)
        }
    }
}

@Preview
@Composable
private fun EnginePickerPopupPreview() {
    OrpheusTheme {
        Box(
            modifier = Modifier
                .size(200.dp)
                .background(OrpheusColors.blackHoleBackground)
                .offset(100.dp, 100.dp),

            contentAlignment = Alignment.Center
        ) {
            EnginePickerPopup(
                currentEngine = 0,
                hoveredSegment = 2,
                color = OrpheusColors.neonMagenta
            )
        }
    }
}
