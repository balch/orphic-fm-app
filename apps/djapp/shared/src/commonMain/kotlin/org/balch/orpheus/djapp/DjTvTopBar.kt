package org.balch.orpheus.djapp

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
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
import org.balch.orpheus.ui.infrastructure.tvFocusRegionBorder
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.lighten
import org.balch.orpheus.ui.theme.readableOnDark
import org.balch.orpheus.ui.widgets.AppTitleTreatment
import org.balch.orpheus.ui.widgets.TvInlinePicker
import org.balch.orpheus.ui.viz.Visualization

/**
 * Shared height for every element in the top bar — the toggles, both pickers and the title all
 * match this exactly, so the bar reads as one row of equally weighted elements rather than a big
 * title flanked by small controls.
 */
private val TvTopBarControlHeight = 52.dp

/** Focus ring width on a top bar control — the one visual channel the idle plate never uses. */
private val TvTopBarFocusBorderWidth = 3.dp

/** Label size for the top bar's toggles — closer to the title's own visual weight. */
private val TvTopBarLabelSize = 16.sp

/**
 * A docked toggle's extra wash of the accent over the bar's idle plate, which already carries a
 * lighter one: enough that a docked toggle reads as lit beside the title and the pickers.
 */
private const val TvTopBarDockedWashAlpha = 0.34f

/** How far a docked or focused toggle's icon and label are lightened off the accent, as the bottom bar's are. */
private const val TvTopBarLitLighten = 0.7f

/** Idle toggle content, as readable as the bottom bar's idle items. */
private const val TvTopBarIdleAlpha = 0.75f
private val TopBarIdleTint = Color.White.copy(alpha = TvTopBarIdleAlpha).let { idle -> ColorProducer { idle } }

/** The armed ring around the Ends toggle: its gap outside the plate, and its stroke. */
private val TvTopBarArmedGap = 3.dp
private val TvTopBarArmedStroke = 2.5.dp

/** The shown/hidden badge on a focused toggle's corner. */
private val TvTopBarBadgeSize = 18.dp

/**
 * The top bar's dock toggles, all grouped at its start: Pulsar, Info, then Ends. Independent of
 * the dock's own slot order (see [assignDock]).
 */
private val TopBarOrder: List<DjRoute> = listOf(PulsarTab, VibeInfoTab, EndsTab)

/** Filters [dockable] down to [TopBarOrder], preserving that fixed display order. */
internal fun topBarPanels(dockable: List<DjRoute>): List<DjRoute> = TopBarOrder.filter { it in dockable }

/**
 * Width the centred title plate reserves for itself, and the clearance kept either side of it.
 *
 * The three slots are aligned independently against the full bar (see the [DjTvTopBar] KDoc), so
 * they share coordinate space and nothing stops a wide side group from painting over the title —
 * the right-hand group is drawn last, so it wins. That is invisible at 1280dp but not at 1032dp,
 * which is what an iPad 13" gives in portrait: a 12-character vibe name covered "Orphic DJ" there.
 * Budgeting each side against this reserve bounds the group instead of moving the title, so the
 * fixed centre the layout is built around survives. [TvInlinePicker] already truncates to one line,
 * so a bounded group ellipsises rather than overflowing.
 */
private val TvTopBarTitleReserve = 180.dp
private val TvTopBarTitleClearance = 12.dp

/** Floor for the side budget, so a very narrow bar still leaves the pickers tappable. */
private val TvTopBarMinSideWidth = 120.dp

/**
 * The side budget below which the pickers drop their "Vibe: "/"Viz: " prefixes. Ends now sits in
 * the left group at every width (see [DjTvTopBar]), so this sizes only the right group, which the
 * pickers now have to themselves: the prefixed pair ("Vibe: Preview", "Viz: Off") first fits whole
 * at a 342.5dp side budget, which a bar gives from about 930dp — well under the old 1200dp, since
 * the pickers no longer share this side with Ends. Narrower (900dp, the dock's floor), either value
 * would ellipsise, so both pickers drop their prefix and show the value alone.
 */
private val TvTopBarPickerPrefixWidth = 345.dp

/**
 * The left group's toggle padding, icon and icon-to-label gap, and the gap between toggles — the
 * fixed, tightened style every toggle now uses, since Ends always shares the row with Pulsar and
 * Info: it fits a 900dp bar's 328dp side with PLAYS. A longer ending style ellipsises there.
 */
private val TvTopBarCompactPadding = 12.dp
private val TvTopBarCompactIconSize = 28.dp
private val TvTopBarCompactLabelGap = 6.dp
private val TvTopBarCompactGroupGap = 8.dp

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
 * The dock's top bar: the Pulsar, Info and Ends dock toggles (left), the app title (centred,
 * non-interactive), and the Vibe + Viz pickers (right). Ends always sits in the left group, after
 * Pulsar and Info, at every dock width and platform. The toggles dock their panels exactly as the
 * bottom bar's do, through the same [isDocked] and [onToggle]. Play/pause is the bottom bar's
 * centre dome (see [DockDome]). A narrow bar only drops the pickers' "Vibe: "/"Viz: " prefixes
 * (see [TvTopBarPickerPrefixWidth]).
 *
 * Layout is a 3-slot [Box]: the title is centred against the FULL bar width, not squeezed between
 * the left/right groups, so its position does not shift with their differing widths.
 */
@Composable
fun DjTvTopBar(
    panels: List<DjRoute>,
    isDocked: (DjRoute) -> Boolean,
    onToggle: (DjRoute) -> Unit,
    vizFeature: VizFeature,
    pulsarFeature: PulsarFeature,
    modifier: Modifier = Modifier,
    // The bar's glass, behind its content; television hardware goes without.
    glass: Boolean = false,
    // Preview/render-harness seam only: forces one element's focus visual without real D-pad
    // input. The production call site in DjAppScreen.kt leaves this null.
    previewFocusedButton: TvTopBarButtonId? = null,
    // Preview/render-harness seam only: the same for one of the toggles.
    previewFocusedRoute: DjRoute? = null,
    // Preview/render-harness seam only: draws the same region-focus border a real
    // TvFocusRegionHolder would, without needing an actual D-pad focus event to drive it.
    previewRegionFocused: Boolean = false,
    // Preview/render-harness seam only: scales the same preview-only border's alpha, to show a
    // partially-faded TvFocusRegionHolder.alpha without a real holder+coroutine driving it.
    previewRegionFocusAlpha: Float = 1f,
) {
    // Region-focus border — see tvFocusRegionBorder's doc for why the holder+token pattern keeps
    // at most one container's border visible, and why reading it in the draw phase costs nothing
    // on frames where focus hasn't moved.
    //
    // The glass fill is gated off television hardware (see shouldShowTvBarGlass): the deferral's
    // evidence was a real-device TELEVISION trace (UI thread, not GPU, already the bottleneck), so
    // the television still gets only this drawn stroke — nothing that recomposes or relayouts on
    // focus change.
    //
    // Color follows the selected visualization (effects.title.titleColor), same as every other
    // element in this bar and DjTvBottomBar's own region border — a fixed neonCyan here would
    // disagree with the bottom bar's border under any non-default palette, undermining "both bars
    // read as one consistent piece of chrome."
    val focusRegion = LocalTvFocusRegion.current
    val focusToken = remember { Any() }
    val barShape = RoundedCornerShape(8.dp)
    val startPanels = remember(panels) { panels.filter { it != EndsTab } }
    // Read in draw, as the bottom bar reads it: a visualization that changes it every frame
    // redraws the border and the toggles, and recomposes only the leaves that take a Color.
    val accent = rememberDockAccent()
    val regionColor = remember(accent) { { accent.color().readableOnDark() } }

    Box(modifier.fillMaxWidth().dockAccent(accent)) {
        // Glass to the bezel, content inside the safe area; the top and both sides are screen edges.
        if (glass) DockBarGlass()
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    platformSafeAreaInsets().only(WindowInsetsSides.Top + WindowInsetsSides.Start + WindowInsetsSides.End),
                )
                .focusGroup()
                .onFocusChanged { focusRegion?.setFocused(focusToken, it.hasFocus) },
        ) {
            Spacer(
                Modifier
                    .matchParentSize()
                    .tvFocusRegionBorder(
                        holder = focusRegion,
                        token = focusToken,
                        color = regionColor,
                        shape = barShape,
                    )
                    .then(
                        if (previewRegionFocused) {
                            // Inside the edge, as Modifier.border draws it.
                            Modifier.drawBehind {
                                val stroke = 2.dp.toPx()
                                inset(stroke / 2) {
                                    drawOutline(
                                        barShape.createOutline(size, layoutDirection, this),
                                        accent.color().copy(alpha = previewRegionFocusAlpha),
                                        style = Stroke(stroke),
                                    )
                                }
                            }
                        } else {
                            Modifier
                        }
                    ),
            )
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                // Centred against the full bar width — not a flex child between the left/right groups —
                // so its position stays fixed as either group's width changes.
                TopBarTitle(Modifier.align(Alignment.Center).height(TvTopBarControlHeight))

                // Each side bounded so it cannot reach the centred title — see [TvTopBarTitleReserve].
                // maxWidth here is the bar's inner width, the padding above already removed.
                val sideBudget = ((maxWidth - TvTopBarTitleReserve) / 2 - TvTopBarTitleClearance)
                    .coerceAtLeast(TvTopBarMinSideWidth)
                // The only remaining narrow-bar effect: below this, the pickers drop their prefixes.
                val picksShowPrefix = sideBudget >= TvTopBarPickerPrefixWidth
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .widthIn(max = sideBudget),
                    horizontalArrangement = Arrangement.spacedBy(TvTopBarCompactGroupGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    startPanels.forEach { route ->
                        TvTopBarToggle(
                            icon = route.icon,
                            // Short form for the bar; the route's own label ("Vibe Info") stays intact for
                            // everything keyed on it (preferences persistence, the phone-nav sheet saver).
                            label = if (route == VibeInfoTab) "Info" else route.label,
                            docked = isDocked(route),
                            onClick = { onToggle(route) },
                            accent = accent,
                            previewFocused = route == previewFocusedRoute,
                        )
                    }
                    if (EndsTab in panels) {
                        TvEndsToggle(
                            pulsarFeature = pulsarFeature,
                            docked = isDocked(EndsTab),
                            onClick = { onToggle(EndsTab) },
                            accent = accent,
                            previewFocused = previewFocusedRoute == EndsTab,
                            // Last in the row, so a long ending style is what ellipsises, not Pulsar or Info.
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
                PickerPair(
                    gap = 10.dp,
                    modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = sideBudget),
                    first = {
                        TvVibePicker(
                            pulsarFeature = pulsarFeature,
                            showLabel = picksShowPrefix,
                            previewFocused = previewFocusedButton == TvTopBarButtonId.VIBE_PICKER,
                        )
                    },
                    second = {
                        TvVizPicker(
                            vizFeature = vizFeature,
                            showLabel = picksShowPrefix,
                            previewFocused = previewFocusedButton == TvTopBarButtonId.VIZ_PICKER,
                        )
                    },
                )
            }
        }
    }
}

/**
 * "Orphic DJ", non-focusable, non-clickable, and forced into the opaque raised plate: over a
 * bright or busy visualization the translucent liquid-glass look (the non-raised default) washes
 * out badly. A shared widget that takes its colours in composition, so it reads the effects here
 * and an accent change recomposes the title alone.
 */
@Composable
private fun TopBarTitle(modifier: Modifier) {
    val effects = LocalLiquidEffects.current
    AppTitleTreatment(
        title = "Orphic DJ",
        modifier = modifier,
        effects = effects.copy(title = effects.title.copy(titleSize = 26.sp)),
        showSizeEffects = true,
        horizontalPadding = 20.dp,
        verticalPadding = 10.dp,
        forceRaised = true,
        onClick = null,
    )
}

/**
 * The Vibe and Viz pickers side by side in the right group's whole side budget: each at its own
 * width when both fit; otherwise the narrower keeps its width and the wider ellipsises in the
 * rest, or, when neither fits half, they share it evenly.
 */
@Composable
private fun PickerPair(
    gap: Dp,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(first, second), modifier = modifier) { (a, b), constraints ->
        val firstPicker = a.single()
        val secondPicker = b.single()
        val gapPx = gap.roundToPx()
        val room = (constraints.maxWidth - gapPx).coerceAtLeast(0)
        val wantA = firstPicker.maxIntrinsicWidth(constraints.maxHeight)
        val wantB = secondPicker.maxIntrinsicWidth(constraints.maxHeight)
        val (widthA, widthB) = when {
            wantA + wantB <= room -> wantA to wantB
            wantB <= room / 2 -> (room - wantB) to wantB
            wantA <= room / 2 -> wantA to (room - wantA)
            else -> (room / 2) to (room - room / 2)
        }
        val pa = firstPicker.measure(Constraints(maxWidth = widthA, maxHeight = constraints.maxHeight))
        val pb = secondPicker.measure(Constraints(maxWidth = widthB, maxHeight = constraints.maxHeight))
        val height = maxOf(pa.height, pb.height)
        layout(pa.width + gapPx + pb.width, height) {
            pa.placeRelative(0, (height - pa.height) / 2)
            pb.placeRelative(pa.width + gapPx, (height - pb.height) / 2)
        }
    }
}

/** Identifies one [DjTvTopBar] element for its preview-only focus override. */
enum class TvTopBarButtonId { VIBE_PICKER, VIZ_PICKER }

/**
 * Vibe picker, built on [TvInlinePicker] — the same composable [TvVizPicker] uses, so the two
 * share one implementation and one visual language instead of being styled separately.
 */
@Composable
private fun TvVibePicker(
    pulsarFeature: PulsarFeature,
    showLabel: Boolean,
    previewFocused: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by pulsarFeature.stateFlow.collectAsStateWithLifecycle()
    val vibeList = remember { pulsarFeature.vibeList }
    val effects = LocalLiquidEffects.current
    TvInlinePicker(
        label = "Vibe",
        selectedDisplay = state.vibe.name,
        entries = vibeList,
        displayName = { it.name },
        onSelected = { pulsarFeature.actions.pickVibe(it) },
        color = effects.title.titleColor.readableOnDark(),
        modifier = modifier,
        previewFocused = previewFocused,
        onLongPress = pulsarFeature.actions.onTriggerAnomaly,
        showLabel = showLabel,
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
private fun TvVizPicker(vizFeature: VizFeature, showLabel: Boolean, previewFocused: Boolean, modifier: Modifier = Modifier) {
    val state by vizFeature.stateFlow.collectAsStateWithLifecycle()
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
        modifier = modifier,
        previewFocused = previewFocused,
        // Locked to the song that owns this visualization: shown, never changeable.
        locked = state.isVizLocked,
        showLabel = showLabel,
    )
}

/**
 * The Ends toggle: its label is the selected ending style, or PLAYS with endings off — exactly
 * PulsarPanel's own ENDING pill expression, from the same state, so the two never disagree — and
 * it wears the armed ring while a song ending is under way.
 */
@Composable
private fun TvEndsToggle(
    pulsarFeature: PulsarFeature,
    docked: Boolean,
    onClick: () -> Unit,
    accent: DockAccent,
    previewFocused: Boolean,
    modifier: Modifier = Modifier,
) {
    val songEndingEnabled by pulsarFeature.actions.songEndingEnabled.collectAsStateWithLifecycle()
    val transitionSpec by pulsarFeature.actions.transitionSpec.collectAsStateWithLifecycle()
    val outroArmed by pulsarFeature.actions.outroArmed.collectAsStateWithLifecycle()
    TvTopBarToggle(
        icon = EndsTab.icon,
        label = if (songEndingEnabled) transitionSpec.style.name else "PLAYS",
        docked = docked,
        onClick = onClick,
        accent = accent,
        armed = outroArmed,
        previewFocused = previewFocused,
        modifier = modifier,
    )
}

/**
 * A dock toggle at the top bar's control height, on the same raised plate as the title and the
 * pickers, in the left group's fixed tightened style (see [TvTopBarCompactPadding]) — Pulsar, Info
 * and Ends always share that row, so every toggle uses the size that fits all three at 900dp.
 * Three signals, each on a channel of its own:
 * - docked: a stronger wash of the [accent] on the plate, and the icon and label lit in it, as the
 *   bottom bar's docked items are;
 * - focused by the D-pad or keyboard: the accent plate and ring every top bar control wears, with a
 *   corner badge saying whether its panel is shown, since the focus plate covers the docked wash;
 * - [armed] (Ends, while a song ending is under way): a ring outside the plate in a fixed
 *   cosmicPurple, which never follows the accent, so it can't be mistaken for either of the others.
 *
 * Squeezed past its label, the label ellipsises.
 *
 * Idle and docked, it takes the [accent] in draw: the plate's glow, wash and bevel and the lit
 * content follow a visualization that changes it every frame without recomposing. Focused, it
 * reads the accent in composition, as the bottom bar's items do.
 */
@Composable
private fun TvTopBarToggle(
    icon: ImageVector,
    label: String,
    docked: Boolean,
    onClick: () -> Unit,
    accent: DockAccent,
    modifier: Modifier = Modifier,
    armed: Boolean = false,
    previewFocused: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val liveFocused by interactionSource.collectIsFocusedAsState()
    // A cursor's treatment: a mouse click takes focus too, and must not leave the toggle looking selected.
    val inputMode = LocalInputModeManager.current
    val focused = previewFocused || (liveFocused && inputMode.inputMode == InputMode.Keyboard)
    val focusAccent = if (focused) LocalLiquidEffects.current.title.titleColor else Color.Unspecified
    val shape = RoundedCornerShape(8.dp)
    val tint = remember(focused, docked, focusAccent, accent) {
        when {
            focused -> focusAccent.lighten(TvTopBarLitLighten).let { lit -> ColorProducer { lit } }
            docked -> ColorProducer { accent.color().lighten(TvTopBarLitLighten) }
            else -> TopBarIdleTint
        }
    }
    val plateAccent = remember(accent) { { accent.color() } }
    Box(
        // Drawn outside the plate rather than padded around it, so arming never moves the toggle.
        if (!armed) modifier else modifier.drawBehind {
            val gap = TvTopBarArmedGap.toPx()
            val stroke = TvTopBarArmedStroke.toPx()
            val inset = gap + stroke / 2
            drawRoundRect(
                color = OrpheusColors.cosmicPurple.copy(alpha = 0.9f),
                topLeft = Offset(-inset, -inset),
                size = Size(size.width + inset * 2, size.height + inset * 2),
                cornerRadius = CornerRadius(8.dp.toPx() + inset),
                style = Stroke(stroke),
            )
        },
    ) {
        Row(
            modifier = Modifier
                .height(TvTopBarControlHeight)
                .then(
                    when {
                        focused -> Modifier
                            .raisedAccentSurface(accent = focusAccent, shape = shape)
                            .border(TvTopBarFocusBorderWidth, focusAccent.lighten(0.45f), shape)
                        docked -> Modifier
                            .orpheusRaisedPlate(shape = shape, accent = plateAccent)
                            // What background(accent, shape) draws, the accent read in draw.
                            .drawWithCache {
                                val outline = shape.createOutline(size, layoutDirection, this)
                                onDrawBehind { drawOutline(outline, accent.color().copy(alpha = TvTopBarDockedWashAlpha)) }
                            }
                        else -> Modifier.orpheusRaisedPlate(shape = shape, accent = plateAccent)
                    }
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick,
                )
                .semantics { stateDescription = if (docked) "Shown" else "Hidden" }
                .padding(horizontal = TvTopBarCompactPadding),
            horizontalArrangement = Arrangement.spacedBy(TvTopBarCompactLabelGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockIcon(
                icon = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(TvTopBarCompactIconSize),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                fontSize = TvTopBarLabelSize,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (focused) {
            // On the plate's corner, so it costs no layout; the toggle's own state says it to a screen reader.
            Icon(
                imageVector = if (docked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = focusAccent.lighten(TvTopBarLitLighten),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = TvTopBarBadgeSize / 3, y = -TvTopBarBadgeSize / 3)
                    .size(TvTopBarBadgeSize),
            )
        }
    }
}

// ==================== PREVIEWS ====================

private val PreviewTopPanels = topBarPanels(largeScreenPanels())

@Preview(widthDp = 1280, heightDp = 120, name = "TV Top Bar — Ends: song ending armed, docked, Info focused")
@Composable
private fun DjTvTopBarEndsArmedPreview() {
    OrpheusTheme {
        val base = PulsarViewModel.previewFeature()
        val armed = object : PulsarFeature by base {
            override val actions: PulsarPanelActions = PulsarPanelActions(
                songEndingEnabled = MutableStateFlow(true),
                transitionSpec = MutableStateFlow(TransitionSpec(style = TransitionStyle.TAPE)),
                outroArmed = MutableStateFlow(true),
            )
        }
        DjTvTopBar(
            panels = PreviewTopPanels,
            isDocked = { it == PulsarTab || it == EndsTab },
            onToggle = {},
            vizFeature = VizViewModel.previewFeature(),
            pulsarFeature = armed,
            previewFocusedRoute = VibeInfoTab,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 120, name = "TV Top Bar — Playing")
@Composable
private fun DjTvTopBarPlayingPreview() {
    OrpheusTheme {
        val basePulsar = PulsarViewModel.previewFeature()
        DjTvTopBar(
            panels = PreviewTopPanels,
            isDocked = { it == PulsarTab },
            onToggle = {},
            vizFeature = VizViewModel.previewFeature(),
            pulsarFeature = PulsarViewModel.previewFeature(
                PulsarUiState(globalPaused = false, vibe = basePulsar.vibeList.first()),
            ),
        )
    }
}

/**
 * iPad 13" portrait is 1032dp, over [TvTopBarPickerPrefixWidth]'s bar width, so the Vibe picker
 * keeps its "Vibe: " prefix and ellipsises the name instead. Pinned to the longest name in the
 * catalog rather than a literal, so a longer vibe lands here automatically.
 */
@Preview(widthDp = 1032, heightDp = 120, name = "TV Top Bar — iPad portrait, longest vibe name")
@Composable
private fun DjTvTopBarNarrowLongVibePreview() {
    OrpheusTheme {
        val basePulsar = PulsarViewModel.previewFeature()
        DjTvTopBar(
            panels = PreviewTopPanels,
            isDocked = { it == PulsarTab },
            onToggle = {},
            vizFeature = VizViewModel.previewFeature(),
            pulsarFeature = PulsarViewModel.previewFeature(
                PulsarUiState(
                    globalPaused = false,
                    vibe = basePulsar.vibeList.maxBy { it.name.length },
                ),
            ),
        )
    }
}

/** The narrowest bar the dock's tests lay out: the pickers at their narrowest, values alone. */
@Preview(widthDp = 900, heightDp = 120, name = "TV Top Bar — 900dp, Ends docked, pickers value-only")
@Composable
private fun DjTvTopBarCompactPreview() {
    OrpheusTheme {
        DjTvTopBar(
            panels = PreviewTopPanels,
            isDocked = { it == PulsarTab || it == EndsTab },
            onToggle = {},
            vizFeature = VizViewModel.previewFeature(),
            pulsarFeature = PulsarViewModel.previewFeature(),
        )
    }
}

@Preview(widthDp = 1280, heightDp = 120, name = "TV Top Bar — Vibe picker focused (raised)")
@Composable
private fun DjTvTopBarVibeFocusedPreview() {
    OrpheusTheme {
        DjTvTopBar(
            panels = PreviewTopPanels,
            isDocked = { it == PulsarTab },
            onToggle = {},
            vizFeature = VizViewModel.previewFeature(),
            pulsarFeature = PulsarViewModel.previewFeature(),
            previewFocusedButton = TvTopBarButtonId.VIBE_PICKER,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 120, name = "TV Top Bar — Viz picker focused (raised)")
@Composable
private fun DjTvTopBarVizFocusedPreview() {
    OrpheusTheme {
        DjTvTopBar(
            panels = PreviewTopPanels,
            isDocked = { it == PulsarTab },
            onToggle = {},
            vizFeature = VizViewModel.previewFeature(),
            pulsarFeature = PulsarViewModel.previewFeature(),
            previewFocusedButton = TvTopBarButtonId.VIZ_PICKER,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 120, name = "TV Top Bar — Region focused")
@Composable
private fun DjTvTopBarRegionFocusedPreview() {
    OrpheusTheme {
        DjTvTopBar(
            panels = PreviewTopPanels,
            isDocked = { it == PulsarTab },
            onToggle = {},
            vizFeature = VizViewModel.previewFeature(),
            pulsarFeature = PulsarViewModel.previewFeature(),
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
                panels = PreviewTopPanels,
                isDocked = { it == PulsarTab },
                onToggle = {},
                vizFeature = VizViewModel.previewFeature(),
                pulsarFeature = PulsarViewModel.previewFeature(),
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
                panels = PreviewTopPanels,
                isDocked = { it == PulsarTab },
                onToggle = {},
                vizFeature = VizViewModel.previewFeature(),
                pulsarFeature = PulsarViewModel.previewFeature(),
            )
        }
    }
}
