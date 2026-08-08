package org.balch.orpheus.features.lfo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.plugin.symbols.DuoLfoSymbol
import org.balch.orpheus.core.plugin.symbols.LorenzSymbol
import org.balch.orpheus.core.plugin.symbols.PolyLfoSymbol
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.viz.SignalTrace
import org.balch.orpheus.ui.widgets.HorizontalMiniSlider
import org.balch.orpheus.ui.widgets.LearnModeState
import org.balch.orpheus.ui.widgets.Learnable
import org.balch.orpheus.ui.widgets.LocalLearnModeState
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.VerticalRangeTrimSlider
import org.balch.orpheus.ui.widgets.VerticalToggle
import org.balch.orpheus.ui.widgets.learnable
import kotlin.math.roundToInt

/**
 * The 5 selectable LFO modes displayed in the segmented button row.
 * Modes 0-2 correspond to DuoLFO (source=0), mode 3 = PolyLFO (source=1),
 * mode 4 = Lorenz (source=2).
 */
private enum class LfoModeOption(val label: String) {
    OFF("OFF"),
    AND("&&"),
    OR("||"),
    DRIFT("DRIFT"),
    CHAOS("CHAOS"),
}

/**
 * Derive the selected [LfoModeOption] from [LfoUiState].
 */
private fun LfoUiState.toModeOption(): LfoModeOption = when (source) {
    1 -> LfoModeOption.DRIFT
    2 -> LfoModeOption.CHAOS
    else -> when (mode) {
        HyperLfoMode.AND -> LfoModeOption.AND
        HyperLfoMode.OFF -> LfoModeOption.OFF
        HyperLfoMode.OR -> LfoModeOption.OR
    }
}

/**
 * HyperLfoPanel consuming feature() interface.
 */
@Composable
fun DuoLfoPanel(
    feature: LfoFeature,
    vizFlow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    vizCh1Flow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    vizCh2Flow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    vizCh3Flow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "LFO",
        color = OrpheusColors.neonCyan,
        expandedTitle = "Pong",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader,
        backgroundContent = {
            SignalTrace(data = vizCh3Flow, color = OrpheusColors.neonMagenta, alpha = 0.25f)  // pitch
            SignalTrace(data = vizCh2Flow, color = OrpheusColors.warpsGreen, alpha = 0.25f)   // harmonics
            SignalTrace(data = vizCh1Flow, color = OrpheusColors.warmGlow, alpha = 0.25f)     // morph
            SignalTrace(data = vizFlow, color = OrpheusColors.neonCyan, alpha = 0.25f)    // timbre
        }
    ) {
        val learnState = LocalLearnModeState.current
        val selectedOption = uiState.toModeOption()
        val isActive = selectedOption != LfoModeOption.OFF
        val activeColor = if (isActive) OrpheusColors.neonCyan
            else OrpheusColors.neonCyan.copy(alpha = 0.4f)

        // ── Mode selector (5-way segmented button) ──
        val segColors = SegmentedButtonDefaults.colors(
            activeContainerColor = OrpheusColors.neonCyan,
            activeContentColor = OrpheusColors.panelBackground,
            inactiveContentColor = OrpheusColors.neonCyan,
            inactiveContainerColor = OrpheusColors.panelBackground
        )

        val modeOptions = remember { LfoModeOption.entries }

        Learnable(
            controlId = DuoLfoSymbol.SOURCE.controlId.key,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.height(32.dp),
            ) {
                modeOptions.forEachIndexed { index, option ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = modeOptions.size
                        ),
                        onClick = {
                            when (option) {
                                LfoModeOption.AND -> {
                                    actions.setSource(0)
                                    actions.setMode(HyperLfoMode.AND)
                                }
                                LfoModeOption.OFF -> {
                                    actions.setSource(0)
                                    actions.setMode(HyperLfoMode.OFF)
                                }
                                LfoModeOption.OR -> {
                                    actions.setSource(0)
                                    actions.setMode(HyperLfoMode.OR)
                                }
                                LfoModeOption.DRIFT -> {
                                    actions.setSource(1)
                                }
                                LfoModeOption.CHAOS -> {
                                    actions.setSource(2)
                                }
                            }
                        },
                        selected = selectedOption == option,
                        colors = segColors,
                        icon = {}
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // ── Source-specific controls + attenuator ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Source-specific controls (animated crossfade)
            val disabledColor = OrpheusColors.neonCyan.copy(alpha = 0.2f)

            AnimatedContent(
                targetState = selectedOption,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "lfo_source_controls"
            ) { option ->
                when (option) {
                    LfoModeOption.OFF -> LorenzControls(uiState, actions, disabledColor, learnState)
                    LfoModeOption.AND, LfoModeOption.OR -> DuoLfoControls(uiState, actions, activeColor, learnState)
                    LfoModeOption.DRIFT -> PolyLfoControls(uiState, actions, activeColor, learnState)
                    LfoModeOption.CHAOS -> LorenzControls(uiState, actions, activeColor, learnState)
                }
            }

            VerticalRangeTrimSlider(
                min = uiState.rangeMin,
                max = uiState.rangeMax,
                onRangeChange = { newMin, newMax ->
                    if (isActive) {
                        actions.setRangeMin(newMin)
                        actions.setRangeMax(newMax)
                    }
                },
                color = if (isActive) activeColor else disabledColor,
                trackHeight = 90,
                topLabel = "+",
                bottomLabel = "-",
            )
        }
    }
}

/**
 * DuoLFO controls: two rate knobs, link toggle, and shape slider.
 */
@Composable
private fun DuoLfoControls(
    uiState: LfoUiState,
    actions: LfoPanelActions,
    activeColor: Color,
    learnState: LearnModeState,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            RotaryKnob(
                value = uiState.lfoA,
                onValueChange = actions.setLfoA,
                label = "RATE 1",
                controlId = DuoLfoSymbol.FREQ_A.controlId.key,
                size = 56.dp,
                progressColor = activeColor
            )
            VerticalToggle(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .learnable(DuoLfoSymbol.LINK.controlId.key, learnState),
                topLabel = "\uD83D\uDD17",
                bottomLabel = "\u25CB",
                isTop = uiState.linkEnabled,
                onToggle = { actions.setLink(it) },
                color = OrpheusColors.neonCyan
            )
            RotaryKnob(
                value = uiState.lfoB,
                onValueChange = actions.setLfoB,
                label = "RATE 2",
                controlId = DuoLfoSymbol.FREQ_B.controlId.key,
                size = 56.dp,
                progressColor = activeColor
            )
        }
        HorizontalMiniSlider(
            modifier = Modifier.learnable(DuoLfoSymbol.SHAPE.controlId.key, learnState),
            value = uiState.shape,
            onValueChange = actions.setShape,
            leftLabel = "\u25A1",
            rightLabel = "\u25B3",
            color = activeColor,
            trackWidth = 80
        )
    }
}

/**
 * PolyLFO controls: RATE, SHAPE, CPL knobs + SPREAD and SHP SPR mini sliders.
 */
@Composable
private fun PolyLfoControls(
    uiState: LfoUiState,
    actions: LfoPanelActions,
    activeColor: androidx.compose.ui.graphics.Color,
    @Suppress("UNUSED_PARAMETER") learnState: org.balch.orpheus.ui.widgets.LearnModeState,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                value = uiState.polyRate,
                onValueChange = actions.setPolyRate,
                label = "RATE",
                controlId = PolyLfoSymbol.RATE.controlId.key,
                size = 52.dp,
                progressColor = activeColor
            )
            RotaryKnob(
                value = uiState.polyShape,
                onValueChange = actions.setPolyShape,
                label = "SHAPE",
                controlId = PolyLfoSymbol.SHAPE.controlId.key,
                size = 52.dp,
                progressColor = activeColor,
                valueFormatter = ::polyLfoShapeName
            )
            RotaryKnob(
                value = uiState.polyCoupling,
                onValueChange = actions.setPolyCoupling,
                label = "CPL",
                controlId = PolyLfoSymbol.COUPLING.controlId.key,
                size = 52.dp,
                progressColor = activeColor
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalMiniSlider(
                value = uiState.polySpread,
                onValueChange = actions.setPolySpread,
                leftLabel = "\u25C0",
                rightLabel = "\u25B6",
                color = activeColor,
                trackWidth = 70,
                controlId = PolyLfoSymbol.SPREAD.controlId.key,
            )
            HorizontalMiniSlider(
                value = uiState.polyShapeSpread,
                onValueChange = actions.setPolyShapeSpread,
                leftLabel = "S",
                rightLabel = "S",
                color = activeColor,
                trackWidth = 70,
                controlId = PolyLfoSymbol.SHAPE_SPREAD.controlId.key,
            )
        }
    }
}

/**
 * Lorenz controls: RATE knob and BALANCE slider.
 */
@Composable
private fun LorenzControls(
    uiState: LfoUiState,
    actions: LfoPanelActions,
    activeColor: androidx.compose.ui.graphics.Color,
    @Suppress("UNUSED_PARAMETER") learnState: org.balch.orpheus.ui.widgets.LearnModeState,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RotaryKnob(
            value = uiState.lorenzRate,
            onValueChange = actions.setLorenzRate,
            label = "RATE",
            controlId = LorenzSymbol.RATE.controlId.key,
            size = 64.dp,
            progressColor = activeColor
        )
        HorizontalMiniSlider(
            value = uiState.lorenzBalance,
            onValueChange = actions.setLorenzBalance,
            leftLabel = "\u26A1",
            rightLabel = "\u223F",
            color = activeColor,
            trackWidth = 80,
            controlId = LorenzSymbol.BALANCE.controlId.key,
        )
    }
}

private val polyLfoShapeNames = arrayOf(
    "Bloom", "Ramp", "Tri", "Saw", "Plunge",
    "Bump", "Square", "Thick", "Sine", "Rich",
    "Odd", "Buzz", "Steps", "Fold", "Warp",
    "Spike", "Dice"
)

private fun polyLfoShapeName(value: Float): String {
    val position = (value * 16f).coerceIn(0f, 16f)
    val lower = position.toInt().coerceAtMost(15)
    val frac = position - lower
    return if (frac in 0.25f..0.75f && lower + 1 <= 16) {
        "${polyLfoShapeNames[lower]}+"
    } else {
        polyLfoShapeNames[position.roundToInt().coerceIn(0, 16)]
    }
}

@Preview(widthDp = 400, heightDp = 400)
@Preview(widthDp = 400, heightDp = 400, name = "140%", fontScale = 1.4f)
@Composable
fun HyperLfoPanelPreview() {
    OrpheusTheme {
        DuoLfoPanel(
            feature = LfoViewModel.previewFeature()
        )
    }
}
