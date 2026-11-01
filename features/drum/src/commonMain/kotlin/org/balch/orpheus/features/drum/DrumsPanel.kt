package org.balch.orpheus.features.drum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.EngineCategory
import org.balch.orpheus.core.audio.EngineChoice
import org.balch.orpheus.core.audio.EngineSlotKind
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.proportional
import org.balch.orpheus.ui.viz.SignalTrace
import org.balch.orpheus.ui.widgets.HorizontalToggle
import org.balch.orpheus.ui.widgets.PulseButton
import org.balch.orpheus.ui.widgets.RotaryKnob
import org.balch.orpheus.ui.widgets.engine.EnginePicker
import org.balch.orpheus.ui.widgets.engine.rememberEnginePickerController

/** A drum slot stores the engine ordinal; a DX patch pick also sets MOD to the patch's harmonics. */
internal fun drumOrdinalFor(choice: EngineChoice): Int = choice.engine.ordinal

/** The slot's current pick. A hidden engine such as FM_DRUM still resolves, so its pill renders. */
internal fun drumCurrentChoice(engine: Int, mod: Float): EngineChoice =
    EngineCatalog.choiceFor(OrpheusEngineId.entries.getOrNull(engine) ?: OrpheusEngineId.BD, mod)

@Composable
fun DrumsPanel(
    drumFeature: DrumFeature,
    drumOutVizFlow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
) {
    val state by drumFeature.stateFlow.collectAsStateWithLifecycle()
    val actions = drumFeature.actions

    CollapsibleColumnPanel(
        title = "808",
        color = OrpheusColors.ninersRed,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = false,
        modifier = modifier,
        showCollapsedHeader = showCollapsedHeader,
        backgroundContent = {
            SignalTrace(data = drumOutVizFlow, color = OrpheusColors.ninersRed)
        }
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
                hasNativeEngine = state.hasNativeEngine,
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
                hasNativeEngine = state.hasNativeEngine,
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
                hasNativeEngine = state.hasNativeEngine,
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
            style = MaterialTheme.typography.labelSmall.proportional(),
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
    hasNativeEngine: Boolean = false,
    glowEnergy: Float = 0f, // TODO: wire to real audio energy from SynthEngine drum levels
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
    val controller = rememberEnginePickerController()
    val choices = remember(hasNativeEngine) { EngineCatalog.choices(EngineSlotKind.DRUM, hasNativeEngine) }
    // Keyed on the patch, not the float, so a MOD drag doesn't rebuild the choice every frame.
    val engineId = OrpheusEngineId.entries.getOrNull(engine) ?: OrpheusEngineId.BD
    val patch = EngineCatalog.patchAt(engineId, p4)
    val current = remember(engine, patch?.index) { drumCurrentChoice(engine, p4) }

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
            EnginePicker(
                current = current,
                choices = choices,
                onPick = { choice ->
                    onEngineChange(drumOrdinalFor(choice))
                    if (choice is EngineChoice.Patch) onP4Change(choice.patch.harmonics)
                },
                color = color,
                startCategory = EngineCategory.DRUMS,
                compact = true,
                pillHeight = 28.dp,
                glowEnergy = glowEnergy,
                controller = controller,
                modifier = Modifier.weight(.6f),
            )

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
                controlId = "drums_${tag}_mod",
                bottomLabel = patch?.name,
                longLabel = patch?.let { EngineCatalog.labelFor(EngineChoice.Patch(it)) },
                locked = patch != null,
                onLockedClick = { controller.open(EngineCategory.DX_PATCHES) },
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
    controlId: String? = null,
    longLabel: String? = null,
    locked: Boolean = false,
    onLockedClick: (() -> Unit)? = null,
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
            controlId = controlId,
            longLabel = longLabel,
            locked = locked,
            onLockedClick = onLockedClick,
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

