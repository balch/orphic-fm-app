package org.balch.orpheus.features.drum

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.EnginePickerPopup
import org.balch.orpheus.ui.widgets.HorizontalToggle
import org.balch.orpheus.ui.widgets.PICKER_SIZE
import org.balch.orpheus.ui.widgets.PickerConfig
import org.balch.orpheus.ui.widgets.PulseButton
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.computePickerSegment
import org.balch.orpheus.ui.widgets.drumEngineLabel
import org.balch.orpheus.ui.widgets.pickerSegmentToOrdinal
import kotlin.math.sqrt

@Composable
fun DrumsPanel(
    drumFeature: DrumFeature = DrumViewModel.feature(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val state by drumFeature.stateFlow.collectAsState()
    val actions = drumFeature.actions

    CollapsibleColumnPanel(
        title = "808",
        color = OrpheusColors.ninersRed,
        expandedTitle = "808 Drums",
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Routing Toggle: MAIN (direct to stereo) vs FX (through effects chain)
            HorizontalToggle(
                modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
                startLabel = "MAIN",
                endLabel = "FX",
                isStart = state.drumsBypass,
                onToggle = { actions.setDrumsBypass(it) },
                color = OrpheusColors.ninersRed
            )

            // Header Row for common parameters
            HeaderRow()

            Spacer(Modifier.height(4.dp))

            // BD Row (Red) - I key
            DrumRow(
                tag = "bd",
                color = OrpheusColors.ninersRed,
                frequency = state.bdFrequency,
                tone = state.bdTone,
                decay = state.bdDecay,
                p4 = state.bdP4,
                engine = state.bdEngine,
                onEngineChange = actions.setBdEngine,
                pickerConfig = org.balch.orpheus.ui.widgets.DRUM_BD_PICKER_CONFIG,
                isActive = state.isBdActive,
                onFrequencyChange = actions.setBdFrequency,
                onToneChange = actions.setBdTone,
                onDecayChange = actions.setBdDecay,
                onP4Change = actions.setBdP4,
                onTriggerStart = actions.startBdTrigger,
                onTriggerEnd = actions.stopBdTrigger
            )

            Spacer(Modifier.height(8.dp))

            // SD Row (Gold) - O key
            DrumRow(
                tag = "sd",
                color = OrpheusColors.ninersGold,
                frequency = state.sdFrequency,
                tone = state.sdTone,
                decay = state.sdDecay,
                p4 = state.sdP4,
                engine = state.sdEngine,
                onEngineChange = actions.setSdEngine,
                pickerConfig = org.balch.orpheus.ui.widgets.DRUM_SD_PICKER_CONFIG,
                isActive = state.isSdActive,
                onFrequencyChange = actions.setSdFrequency,
                onToneChange = actions.setSdTone,
                onDecayChange = actions.setSdDecay,
                onP4Change = actions.setSdP4,
                onTriggerStart = actions.startSdTrigger,
                onTriggerEnd = actions.stopSdTrigger
            )

            Spacer(Modifier.height(8.dp))

            // HH Row (White) - P key
            DrumRow(
                tag = "hh",
                color = Color.White,
                frequency = state.hhFrequency,
                tone = state.hhTone,
                decay = state.hhDecay,
                p4 = state.hhP4,
                engine = state.hhEngine,
                onEngineChange = actions.setHhEngine,
                pickerConfig = org.balch.orpheus.ui.widgets.DRUM_HH_PICKER_CONFIG,
                isActive = state.isHhActive,
                onFrequencyChange = actions.setHhFrequency,
                onToneChange = actions.setHhTone,
                onDecayChange = actions.setHhDecay,
                onP4Change = actions.setHhP4,
                onTriggerStart = actions.startHhTrigger,
                onTriggerEnd = actions.stopHhTrigger
            )
        }
    }
}

@Composable
private fun HeaderRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.weight(.6f))
        LabelHeader("TUNE", Modifier.weight(1f))
        LabelHeader("TONE", Modifier.weight(1f))
        LabelHeader("DECAY", Modifier.weight(1f))
        LabelHeader("MOD", Modifier.weight(1f))
        LabelHeader("TRIG", Modifier.weight(1f))
    }
}

@Composable
private fun LabelHeader(text: String, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = OrpheusColors.warmGlow,
            maxLines = 1,
        )
    }
}

@Composable
private fun DrumRow(
    tag: String,
    color: Color,
    frequency: Float,
    tone: Float,
    decay: Float,
    p4: Float,
    engine: Int,
    onEngineChange: (Int) -> Unit,
    pickerConfig: PickerConfig,
    isActive: Boolean,
    onFrequencyChange: (Float) -> Unit,
    onToneChange: (Float) -> Unit,
    onDecayChange: (Float) -> Unit,
    onP4Change: (Float) -> Unit,
    onTriggerStart: () -> Unit,
    onTriggerEnd: () -> Unit
) {
    val learnState = org.balch.orpheus.ui.widgets.LocalLearnModeState.current
    val triggerId = "drums_${tag}_trig"
    val isLearningTrigger = learnState.isLearning(triggerId)

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        // Parameter + Trigger Columns
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Engine picker trigger (replaces label)
            var showEnginePicker by remember { mutableStateOf(false) }
            var hoveredSegment by remember { mutableStateOf<Int?>(null) }
            Box(
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
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
                                        val cx = size.width / 2f
                                        val cy = size.height / 2f
                                        val dx = pos.x - cx
                                        val dy = pos.y - cy
                                        val dist = sqrt(dx * dx + dy * dy)
                                        hoveredSegment = computePickerSegment(
                                            dx, dy, dist, pickerRadiusPx, pickerConfig
                                        )
                                    }
                                    event.changes.forEach { it.consume() }
                                    anyPressed = event.changes.any { it.pressed }
                                }

                                val seg = hoveredSegment
                                if (seg != null) {
                                    onEngineChange(
                                        pickerSegmentToOrdinal(seg, pickerConfig)
                                    )
                                }
                                showEnginePicker = false
                                hoveredSegment = null
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = drumEngineLabel(engine),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        maxLines = 1
                    )
                }
                if (showEnginePicker) {
                    EnginePickerPopup(
                        currentEngine = engine,
                        hoveredSegment = hoveredSegment,
                        color = color,
                        config = pickerConfig,
                        anchorSize = 34.dp,
                    )
                }
            }

            KnobGroup(
                value = frequency,
                onValueChange = onFrequencyChange,
                color = color,
                modifier = Modifier.weight(1f),
                controlId = "drums_${tag}_tune"
            )
            KnobGroup(
                value = tone,
                onValueChange = onToneChange,
                color = color,
                modifier = Modifier.weight(1f),
                controlId = "drums_${tag}_tone"
            )
            KnobGroup(
                value = decay,
                onValueChange = onDecayChange,
                color = color,
                modifier = Modifier.weight(1f),
                controlId = "drums_${tag}_decay"
            )
            KnobGroup(
                value = p4,
                onValueChange = onP4Change,
                color = color,
                modifier = Modifier.weight(1f),
                controlId = "drums_${tag}_mod"
            )

            // Trigger Pad aligned as a 5th column
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                PulseButton(
                    size = 32.dp,
                    label = "",
                    isActive = isActive,
                    onPulseStart = onTriggerStart,
                    onPulseEnd = onTriggerEnd,
                    activeColor = color,
                    isLearnMode = learnState.isActive,
                    isLearning = isLearningTrigger,
                    onLearnSelect = { learnState.onSelectControl(triggerId) },
                    modifier = Modifier.clip(CircleShape)
                )
            }
        }
    }
}

@Composable
private fun KnobGroup(
    value: Float,
    onValueChange: (Float) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    bottomLabel: String? = null,
    controlId: String? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RotaryKnob(
            value = value,
            onValueChange = onValueChange,
            size = 34.dp,
            progressColor = color,
            label = bottomLabel,
            controlId = controlId
        )
    }
}

@Preview(widthDp = 400, heightDp = 400)
@Composable
fun DrumEnginePanelPreview() {
    OrpheusTheme {
        DrumsPanel(
            drumFeature = DrumViewModel.previewFeature(),
            isExpanded = true,
        )
    }
}

