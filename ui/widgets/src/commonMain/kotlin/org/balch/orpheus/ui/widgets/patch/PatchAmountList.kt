package org.balch.orpheus.ui.widgets.patch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.VoiceSlot
import org.balch.orpheus.core.audio.patch.reachesEngine
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.RotaryKnob

/** With All focused: one row per cable, `source → dest [slot]  ±NN%`, each with a small knob. */
@Composable
internal fun PatchAmountList(
    patch: DuoPatch,
    engine: OrpheusEngineId,
    onPatchChange: (DuoPatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        patch.cables.forEach { cable ->
            val color = PatchColors.source(cable.source)
            val live = !cable.parked && cable.dest.reachesEngine(engine)
            val slot = if (cable.slot == VoiceSlot.BOTH) "" else " [${cable.slot.name}]"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("patch-amount-row-${cable.tagKey}")
                    .alpha(if (live) 1f else 0.45f)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${sourceLabel(cable.source)} → ${destLabel(cable.dest, engine)}$slot" +
                        if (cable.parked) "  parked" else "",
                    color = color,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = percent(cable.amount),
                    color = OrpheusColors.pickerText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                RotaryKnob(
                    value = cable.amount,
                    onValueChange = { onPatchChange(patch.setAmount(cable.source, cable.dest, cable.slot, it)) },
                    range = -1f..1f,
                    size = 20.dp,
                    trackColor = OrpheusColors.pickerField,
                    progressColor = color,
                    knobColor = OrpheusColors.pickerSurface,
                    indicatorColor = color,
                    valueFormatter = null,
                    bipolar = true,
                )
            }
        }
    }
}
