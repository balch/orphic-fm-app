package org.balch.orpheus.features.horn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

// Blackout Crimson palette — dark, aggressive, evokes the heat of spinning speaker magnets
private val CrimsonBg = Color(0xFF080808)
private val CrimsonHorn = Color(0xFFCC2222)
private val CrimsonWoofer = Color(0xFF881111)
private val CrimsonBorder = Color(0xFF1A0808)
private val CrimsonKnob = Color(0xFFAA2222)

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
        // TODO: Dual rotor animation (Task 10)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CrimsonBg)
                .border(1.dp, CrimsonBorder, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "◎  ◎",
                color = CrimsonWoofer.copy(alpha = 0.4f),
                fontSize = 28.sp,
                letterSpacing = 16.sp,
            )
        }

        // Row 1: SPEED, RATIO, DEPTH, AMOUNT
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                value = uiState.speed,
                onValueChange = actions.setSpeed,
                label = "SPEED",
                controlId = HornSymbol.SPEED.controlId.key,
                size = 44.dp,
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
                size = 44.dp,
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
                size = 44.dp,
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
                size = 44.dp,
                trackColor = CrimsonBg,
                progressColor = CrimsonHorn,
                knobColor = CrimsonKnob,
                labelColor = CrimsonHorn,
            )
        }

        // Row 2: MIX knob + BRAKE toggle
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                value = uiState.mix,
                onValueChange = actions.setMix,
                label = "MIX",
                controlId = HornSymbol.MIX.controlId.key,
                size = 44.dp,
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
