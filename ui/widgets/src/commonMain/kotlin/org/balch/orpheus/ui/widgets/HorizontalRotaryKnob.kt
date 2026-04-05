package org.balch.orpheus.ui.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.math.roundToInt

enum class LabelSide { START, END }

/**
 * A compact horizontal rotary knob with label/value beside the dial.
 * Use [LabelSide.START] to place text on the left, [LabelSide.END] for the right.
 */
@Composable
fun HorizontalRotaryKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    labelStyle: TextStyle = MaterialTheme.typography.labelSmall,
    controlId: String? = null,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    size: Dp = 32.dp,
    trackColor: Color = OrpheusColors.deepPurple,
    progressColor: Color = OrpheusColors.neonCyan,
    knobColor: Color = OrpheusColors.softPurple,
    indicatorColor: Color = OrpheusColors.neonCyan,
    labelColor: Color = progressColor,
    labelSide: LabelSide = LabelSide.END,
    enabled: Boolean = true,
    valueFormatter: KnobValueFormatter? = { v ->
        ((v * 100).roundToInt() / 100.0).toString()
    },
) {
    val learnState = LocalLearnModeState.current
    val isLearning = controlId != null && learnState.isLearning(controlId)
    val highlightMod = if (controlId != null) Modifier.highlightable(controlId) else Modifier
    val dragState = rememberKnobDragState(value, range)

    val textAlign = if (labelSide == LabelSide.START) TextAlign.End else TextAlign.Start

    Row(
        modifier = modifier
            .then(highlightMod)
            .then(
                if (controlId != null && learnState.isActive) {
                    Modifier.learnable(controlId, learnState)
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val label = @Composable {
            if (label != null) {
                Text(
                    text = label,
                    style = labelStyle,
                    color = labelColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = textAlign,
                    maxLines = 1,
                )
            }
        }

        val knobValue = @Composable {
            if (valueFormatter != null) {
                KnobValueText(
                    dragState = dragState,
                    onValueChange = onValueChange,
                    range = range,
                    progressColor = progressColor,
                    valueFormatter = valueFormatter,
                    textAlign = textAlign,
                )
            }
        }

        val dial = @Composable {
            RotaryKnobDial(
                dragState = dragState,
                onValueChange = onValueChange,
                size = size,
                range = range,
                trackColor = trackColor,
                progressColor = progressColor,
                knobColor = knobColor,
                indicatorColor = indicatorColor,
                isLearning = isLearning,
                enabled = enabled,
            )
        }

        if (labelSide == LabelSide.START) {
            label()
            dial()
            knobValue()
        } else {
            knobValue()
            dial()
            label()
        }
    }
}

@Preview
@Composable
private fun HorizontalRotaryKnobEndPreview() {
    OrpheusTheme {
        HorizontalRotaryKnob(
            label = "BPM",
            value = 120f,
            range = 40f..300f,
            onValueChange = {},
            labelSide = LabelSide.END,
            valueFormatter = { "${it.toInt()}" },
            progressColor = OrpheusColors.cosmicPurple,
        )
    }
}

@Preview
@Composable
private fun HorizontalRotaryKnobStartPreview() {
    OrpheusTheme {
        HorizontalRotaryKnob(
            label = "REVERB",
            value = 0.35f,
            onValueChange = {},
            labelSide = LabelSide.START,
            progressColor = OrpheusColors.cosmicPurple,
        )
    }
}
