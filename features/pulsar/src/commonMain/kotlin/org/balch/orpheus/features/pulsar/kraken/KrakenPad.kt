package org.balch.orpheus.features.pulsar.kraken

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.lighten
import org.balch.orpheus.ui.theme.proportional

val KrakenTargetLabels: List<String> = listOf("IV", "V", "REL", "DIVE")
private val KrakenTargetNames = listOf("four", "five", "the relative key", "the parallel key")
val KrakenPadWidth: Dp = 58.dp
private val KrakenShape = RoundedCornerShape(6.dp)

/** The pad for a row beside VIBE: its target over the hold surface, levelled with VIBE's caption. */
@Composable
fun KrakenPad(actions: PulsarPanelActions, modifier: Modifier = Modifier) {
    val target by actions.krakenTarget.collectAsStateWithLifecycle()
    val engaged by actions.krakenEngaged.collectAsStateWithLifecycle()
    val latched by actions.krakenLatched.collectAsStateWithLifecycle()
    val active by actions.krakenActive.collectAsStateWithLifecycle()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.width(KrakenPadWidth)) {
        KrakenTargetLabel(
            target = target, onCycle = actions.onKrakenCycleTarget,
            modifier = Modifier.fillMaxWidth(), centered = true,
        )
        // VIBE's caption-to-chip gap, so both surfaces start on the same line.
        Spacer(Modifier.height(2.dp))
        KrakenPadSurface(
            target = target, engaged = engaged, latched = latched, active = active,
            onPress = actions.onKrakenPress, onRelease = actions.onKrakenRelease,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** The target, which a tap cycles. Boxed for the top bar, where it stands as its own button. */
@Composable
fun KrakenTargetLabel(target: Int, onCycle: () -> Unit, modifier: Modifier = Modifier, boxed: Boolean = false, centered: Boolean = false) {
    val label = KrakenTargetLabels[target.coerceIn(0, KrakenTargetLabels.lastIndex)]
    Text(
        text = label,
        // The hero VIBE caption's size, so the two captions share a line.
        style = MaterialTheme.typography.labelSmall.proportional(),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = OrpheusColors.krakenTeal.lighten(),
        maxLines = 1,
        textAlign = if (centered) TextAlign.Center else null,
        modifier = modifier
            .then(if (boxed) Modifier.clip(KrakenShape).border(1.dp, OrpheusColors.krakenTeal.copy(alpha = 0.45f), KrakenShape) else Modifier)
            .clickable(onClickLabel = "Change Kraken target", onClick = onCycle)
            .then(if (boxed) Modifier.padding(horizontal = 12.dp, vertical = 8.dp) else Modifier),
    )
}

/** The hold surface: press on touch or select key down, release on up. Auto-repeat is ignored. */
@Composable
fun KrakenPadSurface(
    target: Int,
    engaged: Boolean,
    latched: Boolean,
    active: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // [0] a press of either kind is outstanding, [1] it is a key press.
    val held = remember { BooleanArray(2) }
    val latestRelease by rememberUpdatedState(onRelease)
    val release = { if (held[0]) { held[0] = false; held[1] = false; latestRelease() } }
    // A pad removed or defocused mid-press must not leave the shift held.
    DisposableEffect(Unit) { onDispose { release() } }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val teal = OrpheusColors.krakenTeal
    val fill = if (active) teal.copy(alpha = 0.35f) else OrpheusColors.darkVoid.copy(alpha = 0.6f)
    val edge = if (engaged || active || focused) teal else teal.copy(alpha = 0.45f)
    val glyph = if (active) Color.White else teal.copy(alpha = 0.85f)
    val name = KrakenTargetNames[target.coerceIn(0, KrakenTargetNames.lastIndex)]
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(KrakenShape)
            .background(fill)
            .border(if (engaged || active || focused) 2.dp else 1.dp, edge, KrakenShape)
            .pointerInput(onPress, onRelease) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    held[0] = true
                    onPress()
                    waitForUpOrCancellation()
                    release()
                }
            }
            .onKeyEvent { e ->
                if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) {
                    return@onKeyEvent false
                }
                when (e.type) {
                    KeyEventType.KeyDown -> { if (!held[0]) { held[0] = true; held[1] = true; onPress() }; true }
                    KeyEventType.KeyUp -> { if (held[1]) release(); true }
                    else -> false
                }
            }
            .onFocusChanged { if (!it.isFocused && held[1]) release() }
            .focusable(interactionSource = interaction)
            .semantics {
                contentDescription = if (latched) "Kraken, latched to $name" else "Kraken, shift to $name"
                customActions = listOf(
                    // A press while latched lets go; two quick presses latch.
                    CustomAccessibilityAction(if (latched) "Unlatch" else "Latch") {
                        onPress(); onRelease()
                        if (!latched) { onPress(); onRelease() }
                        true
                    },
                )
            },
    ) {
        if (latched) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = glyph, modifier = Modifier.size(20.dp))
        } else {
            Canvas(Modifier.size(22.dp)) { drawWaves(glyph) }
        }
    }
}

/** Three rows of swell: the deep water the Kraken pulls the key into. */
private fun DrawScope.drawWaves(color: Color) {
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    val w = size.width
    val gap = size.height / 4
    val a = gap * 0.4f
    for (row in 1..3) {
        val y = gap * row
        val path = Path().apply {
            moveTo(0f, y)
            cubicTo(w * 0.15f, y - a, w * 0.35f, y - a, w * 0.5f, y)
            cubicTo(w * 0.65f, y + a, w * 0.85f, y + a, w, y)
        }
        drawPath(path, color, style = stroke)
    }
}
