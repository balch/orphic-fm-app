package org.balch.orpheus.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.features.drums808.DrumFeature
import org.balch.orpheus.features.drums808.DrumTriggerSource
import org.balch.orpheus.features.drums808.DrumViewModel
import org.balch.orpheus.features.flux.FluxFeature
import org.balch.orpheus.features.flux.FluxViewModel
import org.balch.orpheus.features.voice.VoiceViewModel
import org.balch.orpheus.features.voice.VoicesFeature
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.widgets.ValueCycleButton

@Composable
fun TriggerRouterPanel(
    modifier: Modifier = Modifier,
    drumFeature: DrumFeature = DrumViewModel.feature(),
    voiceFeature: VoicesFeature = VoiceViewModel.feature(),
    fluxFeature: FluxFeature = FluxViewModel.feature(),
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val drumState by drumFeature.stateFlow.collectAsState()
    val voiceState by voiceFeature.stateFlow.collectAsState()
    val fluxState by fluxFeature.stateFlow.collectAsState()
    
    val drumActions = drumFeature.actions
    val voiceActions = voiceFeature.actions
    val fluxActions = fluxFeature.actions

    CollapsibleColumnPanel(
        title = "PULSE",
        color = OrpheusColors.electricBlue,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        expandedTitle = "Pulse Matrix",
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Drum Section
            RouterSection(
                title = "DRUMS",
                items = listOf(
                    RoutingItem("BASS", drumState.bdTriggerSource.ordinal, listOf("INT", "T1", "T2", "T3")) { drumActions.setBdTriggerSource(DrumTriggerSource.values()[it]) },
                    RoutingItem("SNARE", drumState.sdTriggerSource.ordinal, listOf("INT", "T1", "T2", "T3")) { drumActions.setSdTriggerSource(DrumTriggerSource.values()[it]) },
                    RoutingItem("HIHAT", drumState.hhTriggerSource.ordinal, listOf("INT", "T1", "T2", "T3")) { drumActions.setHhTriggerSource(DrumTriggerSource.values()[it]) }
                ),
                color = OrpheusColors.neonMagenta
            )

            // Voice Trigger Section
            RouterSection(
                title = "V-TRIG",
                items = listOf(
                    RoutingItem("QUAD 1", voiceState.quadTriggerSources.getOrElse(0) { 0 }, listOf("INT", "T1", "T2", "T3")) { voiceActions.onQuadTriggerSourceChange(0, it) },
                    RoutingItem("QUAD 2", voiceState.quadTriggerSources.getOrElse(1) { 0 }, listOf("INT", "T1", "T2", "T3")) { voiceActions.onQuadTriggerSourceChange(1, it) },
                    RoutingItem("QUAD 3", voiceState.quadTriggerSources.getOrElse(2) { 0 }, listOf("INT", "T1", "T2", "T3")) { voiceActions.onQuadTriggerSourceChange(2, it) }
                ),
                color = OrpheusColors.synthGreen
            )

            // Voice Pitch Section
            RouterSection(
                title = "V-PITCH",
                items = listOf(
                    RoutingItem("QUAD 1", voiceState.quadPitchSources.getOrElse(0) { 0 }, listOf("---", "X1", "X2", "X3")) { voiceActions.onQuadPitchSourceChange(0, it) },
                    RoutingItem("QUAD 2", voiceState.quadPitchSources.getOrElse(1) { 0 }, listOf("---", "X1", "X2", "X3")) { voiceActions.onQuadPitchSourceChange(1, it) },
                    RoutingItem("QUAD 3", voiceState.quadPitchSources.getOrElse(2) { 0 }, listOf("---", "X1", "X2", "X3")) { voiceActions.onQuadPitchSourceChange(2, it) }
                ),
                color = OrpheusColors.warmGlow
            )

            // Flux Section
            RouterSection(
                title = "FLUX",
                items = listOf(
                    RoutingItem("CLK SRC", fluxState.clockSource, listOf("INT", "LFO")) { fluxActions.setClockSource(it) }
                ),
                color = OrpheusColors.electricBlue
            )
        }
    }
}

private data class RoutingItem(
    val label: String,
    val selectedIndex: Int,
    val options: List<String>,
    val onIndexChange: (Int) -> Unit
)

@Composable
private fun RouterSection(
    title: String,
    items: List<RoutingItem>,
    color: Color
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        items.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = item.label,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.width(50.dp)
                )
                ValueCycleButton(
                    value = item.selectedIndex,
                    values = item.options.indices.toList(),
                    onValueChange = item.onIndexChange,
                    modifier = Modifier.height(24.dp).width(60.dp),
                    labelProvider = { item.options[it] }
                )
            }
        }
    }
}
