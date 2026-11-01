package org.balch.orpheus.ui.widgets.patch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.PatchCable
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.RotaryKnob
import kotlin.math.roundToInt

/** A cable with its curve in bay coordinates, ready to draw and hit-test. */
internal data class DrawnCable(
    val cable: PatchCable,
    val points: List<Offset>,
    val color: Color,
    val emphasis: CableEmphasis,
) {
    val mid: Offset get() = points[points.size / 2]
}

/** Cables whose two jacks are both on screen; the rest wait for layout or a hidden jack. */
internal fun drawnCables(
    patch: DuoPatch,
    engine: OrpheusEngineId,
    state: PatchBayState,
    jacks: Map<JackId, Offset>,
): List<DrawnCable> = patch.cables.mapNotNull { c ->
    val from = jacks[JackId.Out(c.source)] ?: return@mapNotNull null
    val to = jacks[c.inJack] ?: return@mapNotNull null
    DrawnCable(c, cableCurve(from, to), PatchColors.source(c.source), state.emphasis(c, engine))
}

/**
 * All cables in one canvas behind the jacks: faint and parked ones first, bright on top. The drag
 * is read inside the draw lambda, so a moving pointer redraws the canvas without recomposing.
 */
@Composable
internal fun CableCanvas(drawn: List<DrawnCable>, state: PatchBayState, modifier: Modifier) {
    val focused = state.focus != PatchFocus.All
    Canvas(modifier) {
        val drag = state.drag
        val lifted = drag?.plug
        drawn.filter { it.cable != lifted }
            .sortedBy { if (it.emphasis == CableEmphasis.BRIGHT) 1 else 0 }
            .forEach { c ->
                when (c.emphasis) {
                    CableEmphasis.BRIGHT -> cable(c.points, c.color, if (focused) 4.dp.toPx() else 3.dp.toPx())
                    CableEmphasis.DIM -> cable(c.points, c.color.copy(alpha = 0.15f), 2.dp.toPx())
                    CableEmphasis.PARKED -> cable(
                        c.points, c.color.copy(alpha = 0.4f), 2.dp.toPx(),
                        PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                    )
                }
            }
        val origin = drag?.let { state.jacks[it.from] }
        if (drag != null && origin != null) {
            val color = PatchColors.source(drag.from.source)
            cable(cableCurve(origin, drag.pointer), color.copy(alpha = 0.9f), 3.dp.toPx())
            drawCircle(color, 5.dp.toPx(), drag.pointer)
        }
    }
}

private fun DrawScope.cable(points: List<Offset>, color: Color, width: Float, effect: PathEffect? = null) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, pathEffect = effect))
}

internal val KnobDial = 22.dp
internal val KnobBoxWidth = 36.dp
internal val KnobBoxHeight = 36.dp

/**
 * A small node at each cable's midpoint that carries its tag and [CableEmphasisKey], plus an
 * amount knob for each focused cable, spread along the cables by [placeKnobs] so none overlap.
 * Taps fall through the markers to the bay's own gestures.
 */
@Composable
internal fun CableOverlays(
    drawn: List<DrawnCable>,
    state: PatchBayState,
    onAmount: (PatchCable, Float) -> Unit,
) {
    val density = LocalDensity.current
    val markerPx = with(density) { Marker.roundToPx() }
    drawn.forEach { c ->
        key(c.cable.tagKey) {
            Box(
                Modifier
                    .offset { IntOffset(c.mid.x.roundToInt() - markerPx / 2, c.mid.y.roundToInt() - markerPx / 2) }
                    .size(Marker)
                    .testTag("patch-cable-${c.cable.tagKey}")
                    .semantics { cableEmphasis = c.emphasis },
            )
        }
    }
    val dragging by remember(state) { derivedStateOf { state.drag != null } }
    if (state.focus == PatchFocus.All || dragging) return
    val focused = drawn.filter { state.isFocused(it.cable) }
    val box = with(density) { Size(KnobBoxWidth.toPx(), KnobBoxHeight.toPx()) }
    val anchor = with(density) { Offset(KnobBoxWidth.toPx() / 2f, KnobDial.toPx() / 2f) }
    val spots = placeKnobs(focused.map { it.points }, box, anchor)
    focused.forEachIndexed { i, c ->
        key("knob-" + c.cable.tagKey) {
            val at = spots[i] - anchor
            CableKnob(c, onAmount, Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) })
        }
    }
}

private val Marker = 12.dp

/** A bipolar amount knob with its value under it, in a fixed box so [placeKnobs] can space them. */
@Composable
private fun CableKnob(c: DrawnCable, onAmount: (PatchCable, Float) -> Unit, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.size(KnobBoxWidth, KnobBoxHeight).testTag("patch-knob-${c.cable.tagKey}"),
    ) {
        RotaryKnob(
            value = c.cable.amount,
            onValueChange = { onAmount(c.cable, it) },
            range = -1f..1f,
            size = KnobDial,
            trackColor = OrpheusColors.pickerField,
            progressColor = c.color,
            knobColor = OrpheusColors.pickerSurface,
            indicatorColor = c.color,
            valueFormatter = null,
            bipolar = true,
        )
        Text(
            text = percent(c.cable.amount),
            color = c.color,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            // Backed so a cable passing under the value doesn't strike through it.
            modifier = Modifier
                .background(OrpheusColors.pickerSurface.copy(alpha = 0.85f), RoundedCornerShape(3.dp))
                .padding(horizontal = 2.dp),
        )
    }
}

/** `+35%`, `−20%` (a true minus sign), `0%`. */
internal fun percent(amount: Float): String {
    val n = (amount * 100f).roundToInt()
    return when {
        n > 0 -> "+$n%"
        n < 0 -> "\u2212${-n}%"
        else -> "0%"
    }
}
