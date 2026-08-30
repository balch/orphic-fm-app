package org.balch.orpheus.djapp

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarUiState
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.visualizations.VizFeature
import org.balch.orpheus.features.visualizations.VizViewModel
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalTvFocusRegion
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.orpheusRaisedPlate
import org.balch.orpheus.ui.infrastructure.raisedAccentSurface
import org.balch.orpheus.ui.theme.lighten
import org.balch.orpheus.ui.infrastructure.tvFocusRegionBorder
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.readableOnDark
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.widgets.AppTitleTreatment
import org.balch.orpheus.ui.widgets.TvInlinePicker
import org.balch.orpheus.ui.viz.Visualization

/**
 * Shared height for every element in the top bar — Play/Pause, both pickers, and the title all
 * match this exactly, so the bar reads as one row of equally weighted elements rather than a big
 * title flanked by small controls.
 */
private val TvTopBarControlHeight = 52.dp

/** Icon size for the top bar's own buttons — bigger than the 24dp default for couch viewing. */
private val TvTopBarIconSize = 32.dp

/** Focus ring width on a top bar control — the one visual channel the idle plate never uses. */
private val TvTopBarFocusBorderWidth = 3.dp

/** Label size for the top bar's own buttons — closer to the title's own visual weight. */
private val TvTopBarLabelSize = 16.sp

/**
 * Synthetic entry for [TvVizPicker]'s "Random" mode, which is a flag on [VizFeature]'s state
 * rather than a real [Visualization] in its list — wrapping both in one sealed type lets the
 * random option and the real catalog share [org.balch.orpheus.ui.widgets.TvInlinePicker]'s
 * single generic entries list.
 */
private sealed interface VizPickerEntry {
    data object Random : VizPickerEntry
    data class Item(val viz: Visualization) : VizPickerEntry
}

/**
 * TV mode's top bar: Play/Pause (left, focused by default), the app title (centred,
 * non-interactive), and the Vibe + Viz pickers (right, adjacent). Info and the vibe-ending picker
 * live on [DjTvBottomBar] instead — everything here is either a transport control or read-only
 * branding.
 *
 * Layout is a 3-slot [Box]: the title is centred against the FULL bar width, not squeezed between
 * the left/right groups, so its position does not shift with their differing widths.
 */
@Composable
fun DjTvTopBar(
    vizFeature: VizFeature,
    pulsarFeature: PulsarFeature,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
    // Preview/render-harness seam only: forces one element's focus visual without real D-pad
    // input. The production call site in DjAppScreen.kt leaves this null.
    previewFocusedButton: TvTopBarButtonId? = null,
    // Preview/render-harness seam only: draws the same region-focus border a real
    // TvFocusRegionHolder would, without needing an actual D-pad focus event to drive it.
    previewRegionFocused: Boolean = false,
    // Preview/render-harness seam only: scales the same preview-only border's alpha, to show a
    // partially-faded TvFocusRegionHolder.alpha without a real holder+coroutine driving it.
    previewRegionFocusAlpha: Float = 1f,
) {
    val pulsarState by pulsarFeature.stateFlow.collectAsState()
    val effects = LocalLiquidEffects.current

    val playPauseFocusRequester = remember { FocusRequester() }
    // Fires once per DjTvTopBar composition (TV mode is entered once per app session), not on
    // every recomposition — stealing focus back on every recompose would make the remote unusable.
    LaunchedEffect(Unit) { playPauseFocusRequester.requestFocus() }

    // Region-focus border only, for now — see tvFocusRegionBorder's doc for why the holder+token
    // pattern keeps at most one container's border visible, and why reading it in the draw phase
    // costs nothing on frames where focus hasn't moved. The glass fill this bar could also carry
    // (matching CollapsibleColumnPanel's panelGlassChrome) is deliberately deferred: a real-device
    // trace showed the UI thread, not the GPU, is already the bottleneck here, so this pass adds
    // only a drawn stroke and nothing that recomposes or relayouts on focus change.
    //
    // Color follows the selected visualization (effects.title.titleColor), same as every other
    // element in this bar and DjTvBottomBar's own region border — a fixed neonCyan here would
    // disagree with the bottom bar's border under any non-default palette, undermining "both bars
    // read as one consistent piece of chrome."
    val focusRegion = LocalTvFocusRegion.current
    val focusToken = remember { Any() }
    val barShape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .focusGroup()
            .onFocusChanged { focusRegion?.setFocused(focusToken, it.hasFocus) }
            .tvFocusRegionBorder(
                holder = focusRegion,
                token = focusToken,
                color = effects.title.titleColor.readableOnDark(),
                shape = barShape,
            )
            .then(
                if (previewRegionFocused) {
                    Modifier.border(
                        2.dp,
                        effects.title.titleColor.copy(alpha = previewRegionFocusAlpha),
                        barShape,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        val paused = pulsarState.globalPaused
        TvTopBarButton(
            icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            label = if (paused) "Play" else "Pause",
            tint = effects.title.titleColor.readableOnDark(),
            onClick = onTogglePlayback,
            previewFocused = previewFocusedButton == TvTopBarButtonId.PLAY_PAUSE,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .focusRequester(playPauseFocusRequester),
        )

        // Centred against the full bar width — not a flex child between the left/right groups —
        // so its position stays fixed as either group's width changes. Non-focusable, non-
        // clickable, and forced into the opaque raised plate: over a bright/busy visualization
        // the translucent liquid-glass look (the non-raised default) washes out badly.
        AppTitleTreatment(
            title = "Orphic DJ",
            modifier = Modifier
                .align(Alignment.Center)
                .height(TvTopBarControlHeight),
            effects = effects.copy(title = effects.title.copy(titleSize = 26.sp)),
            showSizeEffects = true,
            horizontalPadding = 20.dp,
            verticalPadding = 10.dp,
            forceRaised = true,
            onClick = null,
        )

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvVibePicker(
                pulsarFeature = pulsarFeature,
                previewFocused = previewFocusedButton == TvTopBarButtonId.VIBE_PICKER,
            )
            TvVizPicker(
                vizFeature = vizFeature,
                previewFocused = previewFocusedButton == TvTopBarButtonId.VIZ_PICKER,
            )
        }
    }
}

/** Identifies one [DjTvTopBar] element for its preview-only focus override. */
enum class TvTopBarButtonId { PLAY_PAUSE, VIBE_PICKER, VIZ_PICKER }

/**
 * Vibe picker, built on [TvInlinePicker] — the same composable [TvVizPicker] uses, so the two
 * share one implementation and one visual language instead of being styled separately.
 */
@Composable
private fun TvVibePicker(pulsarFeature: PulsarFeature, previewFocused: Boolean) {
    val state by pulsarFeature.stateFlow.collectAsState()
    val vibeList = remember { pulsarFeature.vibeList }
    val effects = LocalLiquidEffects.current
    TvInlinePicker(
        label = "Vibe",
        selectedDisplay = state.vibe.name,
        entries = vibeList,
        displayName = { it.name },
        onSelected = { pulsarFeature.actions.setVibe(it) },
        color = effects.title.titleColor.readableOnDark(),
        previewFocused = previewFocused,
    )
}

/**
 * Viz picker, built on [TvInlinePicker]. "Random" is a flag on [VizFeature]'s state rather than a
 * real catalog entry, so it is wrapped alongside the real list in [VizPickerEntry] to fit
 * [TvInlinePicker]'s single generic entries list.
 *
 * Its accent tracks the active visualization's title color, as every top bar element now does:
 * text and plate both come from the title's own colors so the bar reads as one piece rather than
 * a themed title beside differently-tinted controls.
 */
@Composable
private fun TvVizPicker(vizFeature: VizFeature, previewFocused: Boolean) {
    val state by vizFeature.stateFlow.collectAsState()
    val effects = LocalLiquidEffects.current
    val entries = remember(state.visualizations) {
        listOf(VizPickerEntry.Random) + state.visualizations.map(VizPickerEntry::Item)
    }
    val selectedDisplay = if (state.isRandomVizMode) "Random" else state.selectedViz.name
    TvInlinePicker(
        label = "Viz",
        selectedDisplay = selectedDisplay,
        entries = entries,
        displayName = { entry ->
            when (entry) {
                VizPickerEntry.Random -> "Random"
                is VizPickerEntry.Item -> entry.viz.name
            }
        },
        onSelected = { entry ->
            when (entry) {
                VizPickerEntry.Random -> vizFeature.actions.onSetRandomMode(true)
                is VizPickerEntry.Item -> vizFeature.actions.onSelectViz(entry.viz)
            }
        },
        color = effects.title.titleColor.readableOnDark(),
        previewFocused = previewFocused,
    )
}

/**
 * One icon+label button, matching [TvTopBarControlHeight] so it reads at the same weight as the
 * title and the pickers. Always opaque — idle state uses
 * [org.balch.orpheus.ui.infrastructure.orpheusRaisedPlate], the exact same plate
 * [AppTitleTreatment]'s `forceRaised` gives the title, so every element in the top bar shares one
 * idle look — and when the D-pad cursor lands on it, it lifts further onto the brighter
 * accent-tinted [raisedAccentSurface] plate in its own [tint].
 */
@Composable
private fun TvTopBarButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    previewFocused: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val liveFocused by interactionSource.collectIsFocusedAsState()
    val isFocused = previewFocused || liveFocused
    val shape = RoundedCornerShape(8.dp)
    val effects = LocalLiquidEffects.current

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = TvTopBarControlHeight)
            .then(
                if (isFocused) {
                    // Plate AND ring. Idle is already a raised plate, so lifting onto a second
                    // plate was too close a relative to read as "the cursor is here" from a
                    // couch — every control in the bar looked alike. The ring is a channel idle
                    // does not use at all, which is what makes it unmistakable.
                    Modifier
                        .raisedAccentSurface(accent = tint, shape = shape)
                        .border(TvTopBarFocusBorderWidth, tint.lighten(0.45f), shape)
                } else {
                    // Idle plate follows the selected visualization, same as the title.
                    Modifier.orpheusRaisedPlate(
                        shape = shape,
                        accent = effects.title.titleColor,
                    )
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(TvTopBarIconSize),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            fontSize = TvTopBarLabelSize,
        )
    }
}

// ==================== PREVIEWS ====================

@Preview(widthDp = 1280, heightDp = 120, name = "TV Top Bar — Paused, Play focused")
@Composable
private fun DjTvTopBarPausedPreview() {
    OrpheusTheme {
        DjTvTopBar(
            vizFeature = VizViewModel.previewFeature(),
            pulsarFeature = PulsarViewModel.previewFeature(),
            onTogglePlayback = {},
            previewFocusedButton = TvTopBarButtonId.PLAY_PAUSE,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 120, name = "TV Top Bar — Playing")
@Composable
private fun DjTvTopBarPlayingPreview() {
    OrpheusTheme {
        val basePulsar = PulsarViewModel.previewFeature()
        DjTvTopBar(
            vizFeature = VizViewModel.previewFeature(),
            pulsarFeature = PulsarViewModel.previewFeature(
                PulsarUiState(globalPaused = false, vibe = basePulsar.vibeList.first()),
            ),
            onTogglePlayback = {},
        )
    }
}

@Preview(widthDp = 1280, heightDp = 120, name = "TV Top Bar — Vibe picker focused (raised)")
@Composable
private fun DjTvTopBarVibeFocusedPreview() {
    OrpheusTheme {
        DjTvTopBar(
            vizFeature = VizViewModel.previewFeature(),
            pulsarFeature = PulsarViewModel.previewFeature(),
            onTogglePlayback = {},
            previewFocusedButton = TvTopBarButtonId.VIBE_PICKER,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 120, name = "TV Top Bar — Viz picker focused (raised)")
@Composable
private fun DjTvTopBarVizFocusedPreview() {
    OrpheusTheme {
        DjTvTopBar(
            vizFeature = VizViewModel.previewFeature(),
            pulsarFeature = PulsarViewModel.previewFeature(),
            onTogglePlayback = {},
            previewFocusedButton = TvTopBarButtonId.VIZ_PICKER,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 120, name = "TV Top Bar — Region focused")
@Composable
private fun DjTvTopBarRegionFocusedPreview() {
    OrpheusTheme {
        DjTvTopBar(
            vizFeature = VizViewModel.previewFeature(),
            pulsarFeature = PulsarViewModel.previewFeature(),
            onTogglePlayback = {},
            previewRegionFocused = true,
        )
    }
}

// Two stand-in palettes distinct enough to prove the bar re-themes with the selected
// visualization (Task: "the top bar must follow the selected visualization's style") — not tied
// to any real catalog entry so this preview can't be broken by edits to a specific viz file.
private val PinkVizPalette = VisualizationLiquidEffects(
    title = CenterPanelStyle(
        titleColor = OrpheusColors.synthPink,
        borderColor = OrpheusColors.synthPink.copy(alpha = 0.45f),
    ),
)
private val OrangeVizPalette = VisualizationLiquidEffects(
    title = CenterPanelStyle(
        titleColor = OrpheusColors.neonOrange,
        borderColor = OrpheusColors.neonOrange.copy(alpha = 0.3f),
    ),
)

@Preview(widthDp = 1280, heightDp = 120, name = "TV Top Bar — Viz palette: pink/warm")
@Composable
private fun DjTvTopBarPinkVizPalettePreview() {
    OrpheusTheme {
        CompositionLocalProvider(LocalLiquidEffects provides PinkVizPalette) {
            DjTvTopBar(
                vizFeature = VizViewModel.previewFeature(),
                pulsarFeature = PulsarViewModel.previewFeature(),
                onTogglePlayback = {},
            )
        }
    }
}

@Preview(widthDp = 1280, heightDp = 120, name = "TV Top Bar — Viz palette: orange/earthy")
@Composable
private fun DjTvTopBarOrangeVizPalettePreview() {
    OrpheusTheme {
        CompositionLocalProvider(LocalLiquidEffects provides OrangeVizPalette) {
            DjTvTopBar(
                vizFeature = VizViewModel.previewFeature(),
                pulsarFeature = PulsarViewModel.previewFeature(),
                onTogglePlayback = {},
            )
        }
    }
}
