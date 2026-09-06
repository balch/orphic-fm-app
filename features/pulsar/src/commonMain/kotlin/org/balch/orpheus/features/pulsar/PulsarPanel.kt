package org.balch.orpheus.features.pulsar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.infrastructure.LocalTvFocusChrome
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.lighten
import org.balch.orpheus.ui.widgets.DropdownCycleMinWidth
import org.balch.orpheus.ui.widgets.DropdownValueText
import org.balch.orpheus.ui.widgets.EnginePickerButton
import org.balch.orpheus.ui.widgets.EnumDropdown
import org.balch.orpheus.ui.widgets.HorizontalRotaryKnob
import org.balch.orpheus.ui.widgets.LabelSide
import org.balch.orpheus.ui.widgets.LabeledDropdown
import org.balch.orpheus.ui.widgets.RotaryKnob
import kotlin.time.Duration.Companion.milliseconds

/**
 * Index lists for the ROOT and SCALE dropdowns.
 *
 * Top level because this body recomposes at viz frame rate. Built inline, `.indices.toList()`
 * allocated a fresh unstable `List` every frame, which also defeated skipping for both dropdowns.
 */
private val PULSAR_NOTE_INDICES: List<Int> = PULSAR_NOTE_NAMES.indices.toList()
private val PULSAR_SCALE_INDICES: List<Int> = PULSAR_SCALE_NAMES.indices.toList()
private val PULSAR_ENVELOPE_INDICES: List<Int> = PULSAR_ENVELOPE_NAMES.indices.toList()

/**
 * Ceiling on the VIBE value, past which the name ellipsizes.
 *
 * The selector row is a single non-wrapping line, so its width has to be predictable. ROOT, SCALE
 * and ENV are all short and bounded; VIBE is the only one whose length is open-ended, so capping it
 * caps the row. Sized to hold a two-word name and keep the four inside a phone's width.
 */
private val VibeValueMaxWidth: Dp = 72.dp

/**
 * Pulsar Beat Machine panel.
 *
 * Top to bottom: VIBE/ROOT/SCALE/ENV selectors, the step grid, a voice detail strip that appears
 * only with a track selected, the PERC/BPM/DEEP/ENDING row, then the macro knobs.
 */
@Composable
fun PulsarPanel(
    pulsar: PulsarFeature,
    vizFlow: StateFlow<PulsarVizData> = MutableStateFlow(PulsarVizData()),
    trackVizFlows: List<StateFlow<FloatArray>> = List(8) { MutableStateFlow(FloatArray(0)) },
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    showCollapsedHeader: Boolean = true,
    showExpandedTitle: Boolean = true,
    fillHeight: Boolean = true,
    // TV docks ENDING as its own bottom-bar button (DjTvBottomBar's "Ends") so it stays reachable
    // without expanding this panel. Everywhere else it stays here.
    showEndingControl: Boolean = true,
) {
    // Held as State, not unwrapped: the flow emits every 16ms during playback, and the grid
    // reads it in its draw phase. The one field this panel reads in composition goes through
    // derivedStateOf so the panel recomposes when the anomaly duck moves, not per emission.
    val vizState = vizFlow.collectAsState()
    val voidGain by remember(vizState) { derivedStateOf { vizState.value.voidGain } }
    CollapsibleColumnPanel(
        modifier = modifier,
        title = "PULSE",
        color = OrpheusColors.cosmicPurple,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        initialExpanded = true,
        expandedTitle = if (showExpandedTitle) "8 Track" else null,
        showCollapsedHeader = showCollapsedHeader,
        fillHeight = fillHeight,
    ) {
        val state by pulsar.stateFlow.collectAsState()
        // scoreTick/scoreHeld free-run at 5Hz once a score plays (other consumers read them);
        // this panel only renders bar-level fields, so pin them out here
        // before collecting -- otherwise the step grid would recompose 5x/sec for a score
        // it never shows.
        val arrangementState by remember(pulsar) {
            pulsar.arrangementStateFlow
                .map { it.copy(scoreTick = 0, scoreHeld = false) }
                .distinctUntilChanged()
        }.collectAsState(pulsar.arrangementStateFlow.value.copy(scoreTick = 0, scoreHeld = false))
        val actions = pulsar.actions

        // Gated on TV hardware, not LargeScreen: a tablet or fullscreen desktop is LargeScreen
        // too and must keep this row. VIBE is duplicated in the TV top bar, ROOT/SCALE/ENV are
        // not, so TV loses them. Skipping composition rather than hiding matters on TV, where
        // this is the heaviest panel.
        if (!LocalTelevisionHardware.current) {
            // Deliberately one line that never wraps. A Row measures each child against what the
            // earlier ones left over, so the only thing that can squeeze the rest is VIBE, whose
            // value length is unbounded. VibeValueMaxWidth caps it, which keeps the row's total
            // inside a phone's width without anything having to reflow.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                val vibeList = remember { pulsar.vibeList }
                // Long press arms the Void Anomaly, same as ENDING's outro arm. Armed tints the
                // dropdown cosmicPurple; once the duck starts, voidGain from the audio thread
                // dips below 1 and deepens the tint, breathing back as the mix returns.
                val anomalyArmed by actions.anomalyArmed.collectAsState()
                EnumDropdown(
                    label = "VIBE",
                    selectedDisplay = state.vibe.name,
                    entries = vibeList,
                    displayName = { it.name },
                    onSelected = { actions.setVibe(it) },
                    color = OrpheusColors.cosmicPurple,
                    onLongPress = actions.onTriggerAnomaly,
                    highlight = maxOf(if (anomalyArmed) 0.35f else 0f, 1f - voidGain),
                    valueMaxWidth = VibeValueMaxWidth,
                    // Fits the widest catalog name ("Kaleidoscope Drift") at labelLarge.
                    menuWidth = 200.dp,
                )

                EnumDropdown(
                    label = "ROOT",
                    selectedDisplay = PULSAR_NOTE_NAMES[state.rootNote],
                    entries = PULSAR_NOTE_INDICES,
                    displayName = { PULSAR_NOTE_NAMES[it] },
                    onSelected = actions.setRootNote,
                    color = OrpheusColors.cosmicPurple,
                    // M3's own DropdownMenu floor, so short note names look as they always did.
                    menuWidth = 112.dp,
                )

                EnumDropdown(
                    label = "SCALE",
                    selectedDisplay = PULSAR_SCALE_NAMES[state.scaleIndex],
                    entries = PULSAR_SCALE_INDICES,
                    displayName = { PULSAR_SCALE_NAMES[it] },
                    onSelected = actions.setScale,
                    color = OrpheusColors.cosmicPurple,
                    menuWidth = 140.dp,
                )

                // A menu rather than a tap-to-cycle: three modes is few enough to cycle but too
                // many to read off a chip that only ever shows one of them, and the chip is the
                // first thing a narrow row ellipsizes.
                EnumDropdown(
                    label = "ENV",
                    selectedDisplay = PULSAR_ENVELOPE_NAMES.getOrElse(state.envelopeMode) {
                        PULSAR_ENVELOPE_NAMES[0]
                    },
                    entries = PULSAR_ENVELOPE_INDICES,
                    displayName = { PULSAR_ENVELOPE_NAMES[it] },
                    onSelected = actions.setEnvelopeMode,
                    color = OrpheusColors.cosmicPurple,
                    menuWidth = 112.dp,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val activeTransition by actions.activeTransition.collectAsState()
            val finalSectionIdx by actions.finalSectionIndex.collectAsState()
            val songEndingOn by actions.songEndingEnabled.collectAsState()
            val resolvedStyle by actions.resolvedTransitionStyle.collectAsState()
            PulsarStepGrid(
                vizData = vizState,
                trackVizFlows = trackVizFlows,
                energy = state.energy,
                space = state.space,
                complexity = state.complexity,
                mood = state.mood,
                selectedTrack = state.selectedTrack,
                onTrackSelected = actions.selectTrack,
                arrangementState = arrangementState,
                arrangement = state.vibe.arrangement,
                activeTransition = activeTransition,
                finalSectionIndex = finalSectionIdx,
                // RANDOM is already pre-rolled to a concrete substyle here, so the suffix
                // never reads "verse 3/8, RANDOM".
                pendingTransition = if (songEndingOn) resolvedStyle else null,
                modifier = Modifier
                    .width(360.dp)
                    .height(120.dp)
                    .alpha(.8f)
                ,
            )
        }

        // Voice detail strip. Auto-dismisses after 10s idle, suppressed while a picker is open.
        AnimatedVisibility(
            visible = state.selectedTrack != null,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
        ) {
            val selected = state.selectedTrack ?: return@AnimatedVisibility
            var pickerOpen by remember { mutableStateOf(false) }
            val edmEngine = state.trackEnginesEdm[selected]
            val spaceEngine = state.trackEnginesSpace[selected]
            LaunchedEffect(selected, edmEngine, spaceEngine, pickerOpen) {
                if (!pickerOpen) {
                    delay(10_000L.milliseconds)
                    actions.selectTrack(null)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Animated mute toggle on track name
                val isMuted = state.trackMuted[selected]
                val animatedAlpha by animateFloatAsState(
                    targetValue = if (isMuted) 0.35f else 1.0f,
                    animationSpec = tween(200),
                )
                val animatedScale by animateFloatAsState(
                    targetValue = if (isMuted) 0.9f else 1.05f,
                    animationSpec = tween(200),
                )
                val animatedElevation by animateDpAsState(
                    targetValue = if (isMuted) 0.dp else 4.dp,
                    animationSpec = tween(200),
                )

                Surface(
                    onClick = { actions.toggleTrackMute(selected) },
                    shape = RoundedCornerShape(6.dp),
                    color = if (isMuted) Color.Transparent
                            else TrackColors[selected].copy(alpha = 0.15f),
                    shadowElevation = animatedElevation,
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = animatedScale
                            scaleY = animatedScale
                            alpha = animatedAlpha
                        },
                ) {
                    Text(
                        text = PULSAR_TRACK_NAMES[selected],
                        color = TrackColors[selected],
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }

                Text(
                    text = "HI",
                    color = OrpheusColors.cosmicPurple.copy(alpha = 0.5f),
                    fontSize = 9.sp,
                )
                EnginePickerButton(
                    currentEngine = edmEngine,
                    onEngineChange = { actions.setTrackEngineEdm(selected, it) },
                    color = OrpheusColors.cosmicPurple,
                    label = pulsarEngineLabel(edmEngine),
                    config = PULSAR_TRACK_PICKERS[selected],
                    v2Config = PULSAR_V2_PICKER,
                    v3Config = PULSAR_V3_PICKER,
                    v4Config = PULSAR_V4_PICKER,
                    size = 36.dp,
                    onExpandedChange = { pickerOpen = it },
                )

                Text(
                    text = "LO",
                    color = OrpheusColors.cosmicPurple.copy(alpha = 0.5f),
                    fontSize = 9.sp,
                )
                EnginePickerButton(
                    currentEngine = spaceEngine,
                    onEngineChange = { actions.setTrackEngineSpace(selected, it) },
                    color = OrpheusColors.cosmicPurple,
                    label = pulsarEngineLabel(spaceEngine),
                    config = PULSAR_TRACK_PICKERS[selected],
                    v2Config = PULSAR_V2_PICKER,
                    v3Config = PULSAR_V3_PICKER,
                    v4Config = PULSAR_V4_PICKER,
                    size = 36.dp,
                    onExpandedChange = { pickerOpen = it },
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            HorizontalRotaryKnob(
                value = state.percMix,
                onValueChange = actions.setPercMix,
                label = "PERC",
                controlId = PulsarSymbol.PERC_MIX.controlId.key,
                size = 28.dp,
                progressColor = OrpheusColors.cosmicPurple.lighten(),
                labelSide = LabelSide.START,
                valueFormatter = null,
            )

            HorizontalRotaryKnob(
                value = state.bpm,
                onValueChange = actions.setBpm,
                label = "BPM",
                controlId = PulsarSymbol.BPM.controlId.key,
                range = 40f..300f,
                size = 36.dp,
                progressColor = OrpheusColors.cosmicPurple.lighten(),
                labelSide = LabelSide.START,
                valueFormatter = { "${it.toInt()}" },
            )

            HorizontalRotaryKnob(
                value = state.deep,
                onValueChange = actions.setDeep,
                label = "DEEP",
                controlId = PulsarSymbol.DEEP.controlId.key,
                size = 28.dp,
                progressColor = OrpheusColors.cosmicPurple.lighten(),
                labelSide = LabelSide.START,
                valueFormatter = null,
            )

            // Shows the active transition style when auto-end is on, PLAYS when off. Tap opens
            // the settings sheet. Long press arms the outro now, skipping the playing-time and
            // random-roll checks, and tints the background until the next vibe loads.
            //
            // Gated rather than removed so the row reflows with one fewer child on TV.
            if (showEndingControl) {
                val songEndingEnabled by actions.songEndingEnabled.collectAsState()
                val transitionSpec by actions.transitionSpec.collectAsState()
                val outroArmed by actions.outroArmed.collectAsState()
                // Shows the picked mode, so RANDOM stays RANDOM. The resolved substyle is what
                // actually fires, and it shows up in the step grid's final-section suffix.
                val pillLabel = if (songEndingEnabled) transitionSpec.style.name else "PLAYS"
                var showTransitionSheet by remember { mutableStateOf(false) }
                val pillBg = if (outroArmed) {
                    OrpheusColors.cosmicPurple.copy(alpha = 0.35f)
                } else {
                    OrpheusColors.darkVoid.copy(alpha = 0.6f)
                }

                // Needs the floor: the label swings between PLAYS and whichever style is picked,
                // and without it the row's width moves with it.
                LabeledDropdown(
                    label = "ENDING",
                    onClick = { showTransitionSheet = true },
                    onLongClick = { actions.onArmOutro() },
                    background = pillBg,
                    minWidth = DropdownCycleMinWidth,
                ) {
                    DropdownValueText(text = pillLabel, color = OrpheusColors.cosmicPurple)
                }

                if (showTransitionSheet) {
                    TransitionSettingsSheet(
                        spec = transitionSpec,
                        enabled = songEndingEnabled,
                        onDismiss = { showTransitionSheet = false },
                        onSetEnabled = actions.onSetSongEndingEnabled,
                        onStyleChange = actions.onSetTransitionStyle,
                        onHandoffMsChange = actions.onSetTransitionHandoffMs,
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            RotaryKnob(
                value = state.energy,
                onValueChange = actions.setEnergy,
                label = "ENERGY",
                controlId = PulsarSymbol.ENERGY.controlId.key,
                size = 48.dp,
                progressColor = OrpheusColors.cosmicPurple,
                valueFormatter = null,
            )
            RotaryKnob(
                value = state.complexity,
                onValueChange = actions.setComplexity,
                label = "COMPLEXITY",
                controlId = PulsarSymbol.COMPLEXITY.controlId.key,
                size = 48.dp,
                progressColor = OrpheusColors.cosmicPurple,
                valueFormatter = null,
            )
            RotaryKnob(
                value = state.mood,
                onValueChange = actions.setMood,
                label = "MOOD",
                controlId = PulsarSymbol.MOOD.controlId.key,
                size = 48.dp,
                progressColor = OrpheusColors.cosmicPurple,
                valueFormatter = null,
            )
            RotaryKnob(
                value = state.space,
                onValueChange = actions.setSpace,
                label = "SPACE",
                controlId = PulsarSymbol.SPACE.controlId.key,
                size = 48.dp,
                progressColor = OrpheusColors.cosmicPurple,
                valueFormatter = null,
            )
            RotaryKnob(
                value = state.mix,
                onValueChange = actions.setMix,
                label = "MIX",
                controlId = PulsarSymbol.MIX.controlId.key,
                size = 32.dp,
                progressColor = OrpheusColors.cosmicPurple,
                valueFormatter = null,
            )
        }

    }
}

@Suppress("StateFlowValueCalledInComposition")
@Preview(widthDp = 500, heightDp = 420)
@Preview(widthDp = 500, heightDp = 420, name = "140%", fontScale = 1.4f)
@Composable
private fun PulsarPanelPreview() {
    OrpheusTheme {
        PulsarPanel(
            pulsar = PulsarViewModel.previewFeature(),
            isExpanded = true,
            showCollapsedHeader = false,
        )
    }
}

@Suppress("StateFlowValueCalledInComposition")
@Preview(widthDp = 500, heightDp = 420, name = "TV — no top row or ENDING")
@Composable
private fun PulsarPanelNoEndingPreview() {
    OrpheusTheme {
        // Matches what DjAppScreen provides on real TV hardware: no selector row, TV focus
        // treatment on the knobs, and ENDING moved out to the bottom bar.
        CompositionLocalProvider(
            LocalTvFocusChrome provides true,
            LocalTelevisionHardware provides true,
        ) {
            PulsarPanel(
                pulsar = PulsarViewModel.previewFeature(),
                isExpanded = true,
                showCollapsedHeader = false,
                showEndingControl = false,
            )
        }
    }
}

@Suppress("StateFlowValueCalledInComposition")
@Preview(widthDp = 500, heightDp = 420)
@Preview(widthDp = 500, heightDp = 420, name = "140%", fontScale = 1.4f)
@Composable
private fun PulsarPanelWithSelectionPreview() {
    OrpheusTheme {
        val preview = PulsarViewModel.previewFeature()
        PulsarPanel(
            pulsar = PulsarViewModel.previewFeature(
                PulsarUiState(
                    selectedTrack = 2,
                    mix = 0.8f,
                    vibe = preview.vibeList.first()
                )
            ),
            isExpanded = true,
            showCollapsedHeader = false,
        )
    }
}
