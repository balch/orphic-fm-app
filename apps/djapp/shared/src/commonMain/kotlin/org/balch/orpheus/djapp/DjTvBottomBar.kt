package org.balch.orpheus.djapp

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.VibeNavState
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.infrastructure.LocalTvFocusRegion
import org.balch.orpheus.ui.infrastructure.orpheusChromeWash
import org.balch.orpheus.ui.infrastructure.raisedAccentSurface
import org.balch.orpheus.ui.infrastructure.tvFocusRegionBorder
import org.balch.orpheus.ui.theme.lighten
import org.balch.orpheus.ui.theme.OrpheusTheme

/**
 * Icon size for a bottom bar item — doubled from the original 30dp per the user's explicit
 * "twice the size" follow-up. Couch-distance legibility, not just a bigger touch target.
 */
private val TvBottomBarIconSize = 56.dp

/**
 * Gap between a toggle's icon and its label below -- and, since task 28C, the same distance a step
 * tile's arrow and name sit down from its own content top, so both land on the same line.
 */
private val TvBottomBarIconLabelGap = 6.dp

/** Label/value text size for a bottom bar item — doubled from the original 13sp. */
private val TvBottomBarLabelSize = 24.sp

/**
 * Minimum touch/focus target. Width and height are NOT forced equal: these are icon-over-label
 * columns, so width naturally follows the (short) label text while height carries the "twice as
 * big" read. Forcing width to match the height too (148dp squares) would widen the centre group's
 * four toggles by about 190dp, taken from the ◀/▶ step tiles either side of it.
 *
 * Wide enough for "Pulsar", the longest label, so all four toggles share this width and the dome
 * between them stays on the bar's centre.
 *
 * The height is kept from when Timer's running countdown made the tallest item three lines, so
 * the dock above does not reflow.
 */
internal val TvBottomBarMinWidth = 106.dp
private val TvBottomBarMinHeight = 148.dp

/** The gap between the bar's items, the dome's slot among them. */
internal val TvBottomBarItemGap = 8.dp

/** The item's inset around its content. */
private val TvBottomBarItemPadding = 14.dp

/**
 * Wash alpha for the TV bar's docked item fill — passed to [orpheusChromeWash], NOT a bare
 * `background(accent.copy(alpha=...))` on its own. A flat wash alone was tried first and
 * rejected by rendering it: over a busy/bright backdrop (and for pale palettes) the backdrop's
 * own hue bled through and muddied the accent — a green wash read as olive over an orange
 * visualization. [orpheusChromeWash]'s dark cosmicPurple→deepPurple base fixes that the same way
 * it does for the top bar's idle plate, so this needs its own (stronger) alpha rather than
 * [NavIndicatorColor]'s: that fixed neonCyan wash has no dark base under it at all, so its low
 * alpha was tuned for a completely different recipe.
 */
private const val TvDockedWashAlpha = 0.5f

/**
 * How far the docked/focused icon and label are lightened off the visualization accent.
 *
 * High because it fights that item's own accent wash: at the bare accent the selected items were
 * the least readable things in the bar. Drop it and they mud out again; take it to 1f and the bar
 * stops following the visualization at all.
 */
private const val TvBottomBarContentLighten = 0.7f

/** Idle icon/label opacity. Undocked items still have to be readable from a couch, not just present. */
private const val TvBottomBarIdleAlpha = 0.75f

/** The bar's content inset from its sides; the song band lines up with it. */
internal val TvBottomBarSidePadding = 20.dp

/** Size of the shown/hidden toggle badge in an item's corner. */
private val TvBottomBarToggleSize = 26.dp

/** Side-slot width a step tile needs before it shows the vibe name beside its arrow. */
internal val StepTileNameMinWidth = 140.dp

/** A step tile shows its name only when the slot is wide enough AND a neighbour name exists. */
internal fun stepTileShowsName(slotWidth: Dp, name: String?): Boolean =
    !name.isNullOrBlank() && slotWidth >= StepTileNameMinWidth

/** The step tile's arrow and name between them, and the gap from the name to the tile's edge. */
private val StepTileGap = 10.dp

/** The ◀ tile's name: the previous vibe, or the current one when ◀ would restart it. */
internal fun previousTileName(nav: VibeNavState): String? = if (nav.previousRestarts) nav.currentName else nav.previousName

/** The ▶ tile's name: the vibe up next. */
internal fun nextTileName(nav: VibeNavState): String? = nav.nextName

/**
 * TalkBack/screen-reader text for the ◀ tile — read regardless of whether the slot is wide
 * enough to show the name on screen, so a focused icon-only tile still announces where it goes.
 */
internal fun previousTileDescription(nav: VibeNavState): String {
    val name = previousTileName(nav)?.takeIf { it.isNotBlank() }
    return when {
        nav.previousRestarts -> if (name != null) "Restart $name" else "Restart"
        name != null -> "Previous vibe, $name"
        else -> "Previous vibe"
    }
}

/** TalkBack/screen-reader text for the ▶ tile — same reasoning as [previousTileDescription]. */
internal fun nextTileDescription(nav: VibeNavState): String =
    nav.nextName?.takeIf { it.isNotBlank() }?.let { "Next vibe, $it" } ?: "Next vibe"

/**
 * Fixed bottom bar display order: DJ, Mix, Pulsar, Horn. This is independent of [dockable]'s
 * toggle-order and of each panel's actual dock position (left/right column vs. centre stage —
 * see [assignDock]), which follows dock-toggle order, not this list. Info, Timer and Ends are
 * dock toggles too, in the top bar (see [topBarPanels]).
 */
private val BottomBarOrder: List<DjRoute> = listOf(DjTab, MixTab, PulsarTab, HornTab)

/** Filters [dockable] down to [BottomBarOrder], preserving that fixed display order. */
internal fun bottomBarPanels(dockable: List<DjRoute>): List<DjRoute> =
    BottomBarOrder.filter { it in dockable }

/**
 * The dock's bottom bar: DJ, Mix, the play/pause dome, Pulsar and Horn in the centre, between ◀/▶
 * vibe navigator tiles at either end. Every toggle docks/undocks its own panel (see [DjPanelDock]).
 *
 * Play/pause is the vibe transport's dome in the centre slot, between the two middle toggles as in
 * the phone bar, hung from the toggles' label line with its name in a pill above it (see [DockDome]).
 * The song's story rides its own band under the top bar (see [DockSongBand]).
 *
 * Docked/focused tint follows the selected visualization's own title color (see
 * [org.balch.orpheus.djapp.DjTvTopBar] for the same idiom applied to the top bar), so both TV bars
 * read as one consistent piece of chrome instead of the bottom bar wearing a fixed neonCyan
 * regardless of what's on screen. See [TvBottomBarItem] for the item-level detail.
 */
@Composable
fun DjTvBottomBar(
    panels: List<DjRoute>,
    isDocked: (DjRoute) -> Boolean,
    onToggle: (DjRoute) -> Unit,
    pulsarFeature: PulsarFeature,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
    // The centre dome's ring, from dockDomeRingSize. Defaults to the phone bar's own ring only as an
    // unsized fallback.
    domeRingSize: Dp = BarRingSize,
    // The bar's glass, behind its content; television hardware goes without.
    glass: Boolean = false,
    // The region border when focus is in the bar, and the dome's focus mark; only TV hardware wears them.
    focusHighlights: Boolean = true,
    // Render-harness seams only: pin the dome ring's wave phase and paused zip, see rememberProgressWave.
    previewWavePhase: Float? = null,
    previewZipMs: Long? = null,
    // Preview/render-harness seam only: forces one item's focus visual without real D-pad
    // input. The production call site in DjAppScreen.kt leaves this null.
    previewFocusedRoute: DjRoute? = null,
    // Preview/render-harness seam only: same as [previewFocusedRoute] for the ◀/▶ step tiles,
    // which aren't a DjRoute and so can't share that parameter. They dock nothing, so even
    // focused they wear no shown/hidden badge.
    previewFocusPreviousTile: Boolean = false,
    previewFocusNextTile: Boolean = false,
    // Preview/render-harness seam only: draws the same region-focus border a real
    // TvFocusRegionHolder would, without needing an actual D-pad focus event to drive it.
    previewRegionFocused: Boolean = false,
    // Preview/render-harness seam only: scales the same preview-only border's alpha, to show a
    // partially-faded TvFocusRegionHolder.alpha without a real holder+coroutine driving it.
    previewRegionFocusAlpha: Float = 1f,
) {
    val navState = pulsarFeature.vibeNavFlow.collectAsStateWithLifecycle()
    val nav by navState

    // Single accent for every viz-following color in this bar — region border, the docked items'
    // wash and content, the dome's focus mark — mirrors DjTvTopBar's own title.titleColor source
    // exactly, so both bars re-theme together instead of the bottom bar keeping a fixed neonCyan.
    // Some visualizations change it every frame, so it is read in draw: those frames redraw what
    // wears it and recompose nothing here.
    val dockAccent = rememberDockAccent()
    val accentNow = remember(dockAccent) { { dockAccent.color() } }
    val nameStyle = MaterialTheme.typography.labelMedium.let { remember(it) { it.copy(fontSize = DockNameSize) } }
    // Between the two middle toggles, as the phone bar puts its transport.
    val domeIndex = panels.size / 2
    val domeSideRoom = if (LocalTelevisionHardware.current) 0.dp else DockDomeSideRoom

    // Region-focus border — see tvFocusRegionBorder's doc for the single-holder exclusivity
    // guarantee and why reading it in the draw phase costs nothing per frame.
    //
    // The glass fill is gated off television hardware (see shouldShowTvBarGlass): the deferral's
    // evidence was a real-device TELEVISION trace (the UI thread already janking on every frame with
    // a static Pulsar panel alone), so the television still gets only this drawn stroke, never a
    // recompose/relayout on focus change.
    val focusRegion = LocalTvFocusRegion.current
    val focusToken = remember { Any() }
    val barShape = RoundedCornerShape(8.dp)

    Box(modifier.fillMaxWidth().dockAccent(dockAccent)) {
        // Glass to the bezel, content inside the safe area; the bottom and both sides are screen edges.
        if (glass) DockBarGlass()
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    platformSafeAreaInsets().only(WindowInsetsSides.Bottom + WindowInsetsSides.Start + WindowInsetsSides.End),
                )
                .focusGroup()
                .onFocusChanged { focusRegion?.setFocused(focusToken, it.hasFocus) },
        ) {
            // Behind the controls, not over them: the dome rising out of the bar covers the border's
            // top edge instead of being cut by it.
            Spacer(
                Modifier
                    .matchParentSize()
                    .tvFocusRegionBorder(
                        holder = focusRegion.takeIf { focusHighlights },
                        token = focusToken,
                        color = accentNow,
                        shape = barShape,
                    )
                    .then(
                        if (previewRegionFocused) {
                            val accent = LocalLiquidEffects.current.title.titleColor
                            Modifier.border(2.dp, accent.copy(alpha = previewRegionFocusAlpha), barShape)
                        } else {
                            Modifier
                        }
                    ),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Bottom trimmed well below top: the safe-area inset already sits below the
                    // bar, so a full 12.dp here on top of that read bottom-heavy.
                    .padding(start = TvBottomBarSidePadding, end = TvBottomBarSidePadding, top = 10.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(TvBottomBarItemGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepSlot(Alignment.CenterStart, Modifier.weight(1f)) { slotWidth ->
                    val name = previousTileName(nav)
                    StepTile(
                        // Past 5 s ◀ restarts the song, and says so.
                        icon = if (nav.previousRestarts) Icons.Rounded.Replay else Icons.Rounded.SkipPrevious,
                        name = if (stepTileShowsName(slotWidth, name)) name.orEmpty() else "",
                        slotWidth = slotWidth,
                        onClick = pulsarFeature.actions.previousVibe,
                        contentDescription = previousTileDescription(nav),
                        arrowFirst = true,
                        previewFocused = previewFocusPreviousTile,
                    )
                }
                // Aligned by the first baseline, so the dome hangs from the toggles' label line.
                Row(horizontalArrangement = Arrangement.spacedBy(TvBottomBarItemGap)) {
                    for (index in 0..panels.size) {
                        if (index == domeIndex) {
                            DockDome(
                                pulsarFeature = pulsarFeature,
                                onTogglePlayback = onTogglePlayback,
                                ringSize = domeRingSize,
                                nameStyle = nameStyle,
                                accent = dockAccent,
                                modifier = Modifier.alignBy(FirstBaseline).padding(horizontal = domeSideRoom),
                                focusMark = focusHighlights,
                                previewWavePhase = previewWavePhase,
                                previewZipMs = previewZipMs,
                            )
                        }
                        val route = panels.getOrNull(index) ?: continue
                        TvBottomBarItem(
                            icon = route.icon,
                            label = route.label,
                            docked = isDocked(route),
                            onClick = { onToggle(route) },
                            accent = dockAccent,
                            modifier = Modifier.alignBy(FirstBaseline),
                            previewFocused = route == previewFocusedRoute,
                        )
                    }
                }
                StepSlot(Alignment.CenterEnd, Modifier.weight(1f)) { slotWidth ->
                    val name = nextTileName(nav)
                    StepTile(
                        icon = Icons.Rounded.SkipNext,
                        name = if (stepTileShowsName(slotWidth, name)) name.orEmpty() else "",
                        slotWidth = slotWidth,
                        onClick = pulsarFeature.actions.nextVibe,
                        contentDescription = nextTileDescription(nav),
                        arrowFirst = false,
                        previewFocused = previewFocusNextTile,
                    )
                }
            }
        }
    }
}

/**
 * A side slot of the bottom bar: measures its own width so the tile knows whether a name fits.
 * Reports the raw [Dp] width rather than a pre-decided boolean — whether a name actually SHOWS
 * also depends on whether one exists (see [stepTileShowsName]), which only the caller knows.
 */
@Composable
private fun StepSlot(
    alignment: Alignment,
    modifier: Modifier = Modifier,
    content: @Composable (slotWidth: Dp) -> Unit,
) {
    val density = LocalDensity.current
    var width by remember { mutableStateOf(0.dp) }
    Box(
        modifier = modifier.onSizeChanged { width = with(density) { it.width.toDp() } },
        contentAlignment = alignment,
    ) {
        content(width)
    }
}

/**
 * A ◀/▶ vibe step tile on one line: its arrow at the bar's outer end and the neighbour's [name]
 * beside it, its baseline on the toggles' label line (task 28C) -- the same line DJ, Mix, Pulsar and
 * Horn's own labels sit on, since [TvBottomBarItem] stacks an identical icon over its label with
 * the same [TvBottomBarIconLabelGap]. A name too long for its slot marquee-scrolls, or on TV
 * hardware ellipsises. Not a dock toggle, so no docked wash and no shown/hidden badge; the D-pad
 * cursor lifts it onto the accent plate as it does the toggles. Its [contentDescription] is read
 * even when the slot is too narrow for the name: TalkBack focuses the tile on Android TV.
 *
 * The arrow is centred on that same line whether or not a name is showing: [iconTopOffset] and
 * [nameTopOffset] are both distances down from the tile's own content top (right after its 14dp
 * padding, the same reference point [TvBottomBarItem]'s icon sits on), computed once so no font
 * metric is hardcoded. Both children stay simply [Alignment.Top]-placed in the row; only their own
 * padding moves, so the tile's own box -- its height, and its top in the bar -- is untouched.
 */
@Composable
private fun StepTile(
    icon: ImageVector,
    name: String,
    slotWidth: Dp,
    onClick: () -> Unit,
    contentDescription: String,
    arrowFirst: Boolean,
    previewFocused: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val liveFocused by interactionSource.collectIsFocusedAsState()
    // As the toggles: a cursor's treatment, which a mouse click must not leave behind.
    val inputMode = LocalInputModeManager.current
    val focused = previewFocused || (liveFocused && inputMode.inputMode == InputMode.Keyboard)
    val accent = if (focused) LocalLiquidEffects.current.title.titleColor else Color.Unspecified
    val tint = if (focused) accent.lighten(TvBottomBarContentLighten) else Color.White.copy(alpha = TvBottomBarIdleAlpha)
    val shape = RoundedCornerShape(14.dp)
    val nameMaxWidth = (slotWidth - TvBottomBarItemPadding * 2 - TvBottomBarIconSize - StepTileGap).coerceAtLeast(0.dp)
    val nameStyle = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // How far a labelMedium/TvBottomBarLabelSize line's own vertical centre sits below its own top.
    // The toggle's label starts exactly at nameTopOffset (see its doc), so the arrow's centre needs
    // only this one number added on top -- not the label's own ascent or line height.
    val lineCentre = remember(nameStyle, density) {
        val result = measurer.measure(" ", nameStyle.copy(fontSize = TvBottomBarLabelSize))
        with(density) { ((result.getLineTop(0) + result.getLineBottom(0)) / 2f).toDp() }
    }
    // Where the toggle's own label starts, measured from its content top: past its icon and the gap
    // below it. The arrow is centred that same distance down, plus lineCentre (see above).
    val nameTopOffset = TvBottomBarIconSize + TvBottomBarIconLabelGap
    val iconTopOffset = TvBottomBarIconSize / 2 + TvBottomBarIconLabelGap + lineCentre
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = TvBottomBarMinWidth, minHeight = TvBottomBarMinHeight)
            .then(if (focused) Modifier.raisedAccentSurface(accent = accent, shape = shape) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(TvBottomBarItemPadding),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(StepTileGap)) {
            val arrow: @Composable () -> Unit = {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.padding(top = iconTopOffset).size(TvBottomBarIconSize),
                )
            }
            if (arrowFirst) arrow()
            if (name.isNotEmpty()) {
                MarqueeLabel(
                    text = name,
                    style = nameStyle,
                    color = tint,
                    fontSize = TvBottomBarLabelSize,
                    modifier = Modifier.padding(top = nameTopOffset).widthIn(max = nameMaxWidth),
                )
            }
            if (!arrowFirst) arrow()
        }
    }
}

/**
 * One dock toggle: icon over label. Two independent signals, each on its own visual channel so neither can be mistaken for
 * the other:
 * - Docked state (a panel is currently shown) is a persistent accent tint/wash.
 * - Focus (the D-pad cursor is on this item right now) is an opaque accent-tinted raised plate.
 *
 * The accent is the visualization's title colour, which some visualizations change every frame:
 * docked, the wash, icon and label read it from [accent] in draw, so those frames redraw the item
 * without recomposing it. Only the rare focus plate reads it in composition, and only while focused.
 */
@Composable
private fun TvBottomBarItem(
    icon: ImageVector,
    label: String,
    docked: Boolean,
    onClick: () -> Unit,
    accent: DockAccent,
    modifier: Modifier = Modifier,
    previewFocused: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val liveFocused by interactionSource.collectIsFocusedAsState()
    // Pointer clicks take focus too, so on desktop a mouse click left the item sitting in the
    // focused state with nothing to clear it — it read as "selected" long after the click. The
    // focus treatment exists for a cursor that has nowhere else to show itself, so it is gated on
    // the input mode actually being a cursor: D-pad and keyboard show it, mouse and touch do not.
    val inputMode = LocalInputModeManager.current
    val isFocused = previewFocused || (liveFocused && inputMode.inputMode == InputMode.Keyboard)
    val focusAccent = if (isFocused) LocalLiquidEffects.current.title.titleColor else Color.Unspecified

    // Focus also brightens the icon/label — otherwise an undocked-but-focused item sits inside
    // a bright raised plate with a muddy dim icon, undercutting the very thing the plate exists
    // to highlight.
    //
    // Lightened, not the bare accent: a docked item's own plate IS an accent wash, so drawing
    // accent content on it put accent on accent and left the selected items reading worse across
    // a room than the plain-white unselected ones — backwards. The plate already carries the
    // visualization's colour, so the content on top only has to stay legible while keeping the
    // hue.
    val tint = remember(isFocused, docked, focusAccent, accent) {
        when {
            isFocused -> focusAccent.lighten(TvBottomBarContentLighten).let { lit -> ColorProducer { lit } }
            docked -> ColorProducer { accent.color().lighten(TvBottomBarContentLighten) }
            else -> IdleItemTint
        }
    }
    val washAccent = remember(accent) { { accent.color() } }
    val shape = RoundedCornerShape(14.dp)

    Box(modifier) {
        Column(
            modifier = Modifier
                // Docked, its own layer: an accent frame re-records this item, not the whole bar.
                .then(if (docked) Modifier.graphicsLayer() else Modifier)
                .defaultMinSize(minWidth = TvBottomBarMinWidth, minHeight = TvBottomBarMinHeight)
                .then(
                    if (isFocused) {
                        Modifier.raisedAccentSurface(accent = focusAccent, shape = shape)
                    } else if (docked) {
                        // orpheusChromeWash, NOT a bare background(accent@alpha) — see
                        // TvDockedWashAlpha's doc for why a flat wash muddies over a busy backdrop.
                        // Also NOT NavIndicatorColor (DjAppBottomNav.kt) — that constant is shared
                        // with the phone/tablet nav and must keep its own fixed neonCyan untouched.
                        Modifier.orpheusChromeWash(shape = shape, accent = washAccent, washAlpha = TvDockedWashAlpha)
                    } else {
                        Modifier
                    }
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick,
                )
                .padding(TvBottomBarItemPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Top-packed, deliberately: every item's icon and label sit at the same height across the row.
            verticalArrangement = Arrangement.spacedBy(TvBottomBarIconLabelGap),
        ) {
            DockIcon(icon = icon, contentDescription = label, tint = tint, modifier = Modifier.size(TvBottomBarIconSize))
            Text(
                text = label,
                color = tint,
                style = MaterialTheme.typography.labelMedium,
                fontSize = TvBottomBarLabelSize,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Whether this panel is on screen, said outright — but only under the cursor. On every
        // item at once it was a row of badges competing with the icons for attention, when the
        // plate already says which panels are docked; what was actually missing is what pressing
        // select right now would do. So it answers that, for the one item that can be pressed.
        //
        // An overlay rather than a Column child, so appearing and disappearing costs no layout,
        // and a plain Icon takes no pointer input, so a press in this corner still reaches the
        // clickable underneath.
        if (isFocused) {
            Icon(
                imageVector = if (docked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (docked) "$label shown" else "$label hidden",
                tint = focusAccent.lighten(TvBottomBarContentLighten),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(TvBottomBarToggleSize),
            )
        }
    }
}

private val IdleItemTint = ColorProducer { Color.White.copy(alpha = TvBottomBarIdleAlpha) }

// ==================== PREVIEWS ====================

@Preview(widthDp = 1280, heightDp = 200, name = "TV Bottom Bar — Default Dock")
@Composable
private fun DjTvBottomBarDefaultPreview() {
    OrpheusTheme {
        val docked = setOf<DjRoute>(DjTab, MixTab)
        DjTvBottomBar(
            panels = bottomBarPanels(largeScreenPanels()),
            isDocked = { it in docked },
            onToggle = {},
            pulsarFeature = PulsarViewModel.previewFeature(),
            onTogglePlayback = {},
        )
    }
}

@Preview(widthDp = 1280, heightDp = 200, name = "TV Bottom Bar — Everything Docked")
@Composable
private fun DjTvBottomBarEverythingDockedPreview() {
    OrpheusTheme {
        DjTvBottomBar(
            panels = bottomBarPanels(largeScreenPanels()),
            isDocked = { true },
            onToggle = {},
            pulsarFeature = PulsarViewModel.previewFeature(),
            onTogglePlayback = {},
        )
    }
}

@Preview(widthDp = 1280, heightDp = 200, name = "TV Bottom Bar — Focused Item (raised)")
@Composable
private fun DjTvBottomBarFocusedPreview() {
    OrpheusTheme {
        val docked = setOf<DjRoute>(DjTab, MixTab)
        DjTvBottomBar(
            panels = bottomBarPanels(largeScreenPanels()),
            isDocked = { it in docked },
            onToggle = {},
            pulsarFeature = PulsarViewModel.previewFeature(),
            onTogglePlayback = {},
            previewFocusedRoute = HornTab,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 200, name = "TV Bottom Bar — Region focused")
@Composable
private fun DjTvBottomBarRegionFocusedPreview() {
    OrpheusTheme {
        val docked = setOf<DjRoute>(DjTab, MixTab)
        DjTvBottomBar(
            panels = bottomBarPanels(largeScreenPanels()),
            isDocked = { it in docked },
            onToggle = {},
            pulsarFeature = PulsarViewModel.previewFeature(),
            onTogglePlayback = {},
            previewRegionFocused = true,
        )
    }
}
