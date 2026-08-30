package org.balch.orpheus.features.pulsar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.infrastructure.LocalTvFocusChrome
import org.balch.orpheus.ui.panels.CollapsibleColumnPanel
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.lighten
import org.balch.orpheus.ui.theme.proportional
import org.balch.orpheus.ui.widgets.EnginePickerButton
import org.balch.orpheus.ui.widgets.EnumDropdown
import org.balch.orpheus.ui.widgets.HorizontalRotaryKnob
import org.balch.orpheus.ui.widgets.LabelSide
import org.balch.orpheus.ui.widgets.RotaryKnob
import kotlin.time.Duration.Companion.milliseconds

/**
 * Index lists for the ROOT and SCALE dropdowns.
 *
 * Hoisted to top level on purpose: building these inline with `.indices.toList()` allocated a
 * fresh `List` on every recomposition, and because `List` is an unstable type that also defeated
 * skipping for both dropdowns. PulsarPanel's body recomposes at visualization frame rate (it
 * reads `vizData`), so that was a per-frame allocation plus a per-frame recompose.
 */
private val PULSAR_NOTE_INDICES: List<Int> = PULSAR_NOTE_NAMES.indices.toList()
private val PULSAR_SCALE_INDICES: List<Int> = PULSAR_SCALE_NAMES.indices.toList()

/**
 * Pulsar Beat Machine panel.
 *
 * Layout:
 * 1. Header row: Kit cycle button + Root dropdown + Scale dropdown + Mix knob + BPM knob
 * 2. Step grid (tappable for track selection)
 * 3. Voice detail strip (only when track selected)
 * 4. Macro knobs: ENERGY, COMPLEXITY, SPACE, MOOD, DELAY, REVERB
 */
@OptIn(ExperimentalFoundationApi::class)
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
    // TV docks the vibe-ending picker as its own bottom-bar button (see DjTvBottomBar's "Ends"
    // item) so it stays reachable without expanding this panel; PERC/BPM/DEEP stay put here on
    // every layout. Phone/tablet/desktop keep ENDING in the panel exactly as before.
    showEndingControl: Boolean = true,
) {
    val vizData by vizFlow.collectAsState()
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
        val arrangementState by pulsar.arrangementStateFlow.collectAsState()
        val actions = pulsar.actions

        // Row 1: Selectors only — skipped on real TV hardware, not the LargeScreen layout signal
        // (a tablet/fullscreen desktop also enters LargeScreen but must keep this row): VIBE is
        // duplicated in the TV top bar, but ROOT/SCALE/ENV have no other entry point anywhere.
        // Skipping composition (not just hiding) matters on TV — this panel is its heaviest.
        if (!LocalTelevisionHardware.current) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                val vibeList = remember { pulsar.vibeList }
                // The manual anomaly trigger arms the Void Anomaly (equivalent to the
                // ENDING pill's outro arm); the dropdown tints cosmicPurple while armed
                // so the user knows the trigger took effect. Once the duck actually
                // starts, voidGain (live from the C++ audio thread) dips below 1 and
                // deepens the tint further — it breathes back as the mix returns.
                val anomalyArmed by actions.anomalyArmed.collectAsState()
                EnumDropdown(
                    modifier = Modifier.widthIn(max = 120.dp),
                    label = "VIBE",
                    selectedDisplay = state.vibe.name,
                    entries = vibeList,
                    displayName = { it.name },
                    onSelected = { actions.setVibe(it) },
                    color = OrpheusColors.cosmicPurple,
                    onLongPress = actions.onTriggerAnomaly,
                    highlight = maxOf(if (anomalyArmed) 0.35f else 0f, 1f - vizData.voidGain),
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

                // Envelope mode toggle
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "ENV",
                        style = MaterialTheme.typography.labelSmall.proportional(),
                        color = OrpheusColors.cosmicPurple.lighten(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .clickable { actions.setEnvelopeMode((state.envelopeMode + 1) % 3) }
                            .clip(RoundedCornerShape(6.dp))
                            .background(OrpheusColors.darkVoid.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            modifier = Modifier.widthIn(min = 40.dp),
                            text = when (state.envelopeMode) {
                                1 -> "WAVES"
                                2 -> "BLEND"
                                else -> "AD"
                            },
                            color = OrpheusColors.cosmicPurple,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

            }
        }

        // Row 2: Knobs flanking the step grid
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {

            // Center: Step grid — weight(1f) fills remaining Row space, max capped inside
            val activeTransition by actions.activeTransition.collectAsState()
            val finalSectionIdx by actions.finalSectionIndex.collectAsState()
            val songEndingOn by actions.songEndingEnabled.collectAsState()
            val resolvedStyle by actions.resolvedTransitionStyle.collectAsState()
            PulsarStepGrid(
                vizData = vizData,
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
                // resolvedStyle has RANDOM already pre-rolled to a concrete
                // substyle, so the suffix never reads "verse 3/8 — RANDOM".
                pendingTransition = if (songEndingOn) resolvedStyle else null,
                modifier = Modifier
                    .width(360.dp)
                    .height(120.dp)
                    .alpha(.8f)
                ,
            )
        }

        // Row 3: Voice detail strip with animated show/hide
        // Auto-dismiss after 10s of inactivity, suppressed while picker is open
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

            // Auto-end + transition style pill: shows active style when enabled,
            // PLAYS when not. Tap opens the transition settings sheet. Long-press
            // arms the outro immediately — equivalent to the auto-trigger firing
            // now, regardless of playing-time or random roll. When armed, the
            // pill background tints to cosmicPurple so the user knows their
            // long-press took effect; resets when the next vibe loads.
            //
            // TV moves this control to the bottom bar's "Ends" button (see
            // showEndingControl) — gated here rather than removed so PERC/BPM/DEEP still just
            // reflow across a Row with one fewer child, no leftover gap.
            if (showEndingControl) {
                val songEndingEnabled by actions.songEndingEnabled.collectAsState()
                val transitionSpec by actions.transitionSpec.collectAsState()
                val outroArmed by actions.outroArmed.collectAsState()
                // Pill reflects the user's PICKED mode (RANDOM stays RANDOM) — the
                // resolved substyle shows up in the step-grid final-section suffix
                // and is what actually fires.
                val pillLabel = if (songEndingEnabled) transitionSpec.style.name else "PLAYS"
                var showTransitionSheet by remember { mutableStateOf(false) }
                val pillBg = if (outroArmed) {
                    OrpheusColors.cosmicPurple.copy(alpha = 0.35f)
                } else {
                    OrpheusColors.darkVoid.copy(alpha = 0.6f)
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "ENDING",
                        color = OrpheusColors.cosmicPurple.lighten(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall.proportional(),
                        maxLines = 1,
                    )
                    Box(
                        modifier = Modifier
                            .combinedClickable(
                                onClick = { showTransitionSheet = true },
                                onLongClick = { actions.onArmOutro() },
                            )
                            .clip(RoundedCornerShape(2.dp))
                            .background(pillBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            modifier = Modifier.widthIn(min = 60.dp),
                            text = pillLabel,
                            color = OrpheusColors.cosmicPurple,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
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

        // Row 4: Big macro knobs
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

        // (small knobs now flank the grid above)
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
        // LocalTelevisionHardware=true drops Row 1 (VIBE/ROOT/SCALE/ENV); LocalTvFocusChrome=true
        // gives the knobs their TV focus treatment; showEndingControl=false drops the ENDING pill
        // — matches exactly what DjAppScreen provides when this panel is docked on real TV hardware.
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
