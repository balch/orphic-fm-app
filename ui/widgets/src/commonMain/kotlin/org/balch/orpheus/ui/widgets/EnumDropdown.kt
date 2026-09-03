package org.balch.orpheus.ui.widgets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.orpheusRaisedPlate
import org.balch.orpheus.ui.infrastructure.raisedAccentSurface
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.OrpheusTheme
import org.balch.orpheus.ui.theme.lighten
import org.balch.orpheus.ui.theme.proportional

// Metrics lifted from Material3's own Menu.kt so the lazy menu is visually indistinguishable
// from the DropdownMenu it replaces. Only the *layout strategy* changed, not the look.
//   MenuListItemContainerHeight      = 48.dp
//   DropdownMenuItemHorizontalPadding = 12.dp
//   DropdownMenuVerticalPadding       = 8.dp
//   MenuDefaults.shape                = CornerExtraSmall (4.dp)
//   item text style                   = MaterialTheme.typography.labelLarge

/** Minimum height of one menu entry. Matches M3's `MenuListItemContainerHeight`. */
private val MenuRowMinHeight: Dp = 48.dp

/** Horizontal inset of an entry's label. Matches M3's `DropdownMenuItemHorizontalPadding`. */
private val MenuRowHorizontalPadding: Dp = 12.dp

/** Top/bottom inset of the menu surface. Matches M3's `DropdownMenuVerticalPadding`. */
private val MenuContainerVerticalPadding: Dp = 8.dp

/** Corner radius of the menu surface. Matches M3's `MenuDefaults.shape`. */
private val MenuCornerRadius: Dp = 4.dp

/**
 * Insets of the anchor chip's content, and so the chip's height: the label row has no explicit
 * height and sizes to its text plus these.
 *
 * Public because panels hand-roll chips that sit directly beside a dropdown (Pulsar's ENV
 * cycle button is one), and a chip that keeps its own numbers falls out of step the moment
 * these change. Use them for anything that has to line up with a dropdown.
 */
val ChipHorizontalPadding: Dp = 12.dp
val ChipVerticalPadding: Dp = 8.dp

/** Drop shadow under the menu surface. Matches M3's `MenuDefaults.ShadowElevation`. */
private val MenuShadowElevation: Dp = 3.dp

/**
 * Gap between the anchor chip and the menu surface. M3's position provider seats the menu flush
 * against the anchor's bottom edge, so this is 0 to match.
 */
private val MenuAnchorGap: Dp = 0.dp

/**
 * How many rows of context to keep above the selected row when the menu opens.
 *
 * Tuning knob for the open-scroll feel. `0` pins the selection to the very top (maximum room
 * for what follows it), larger values show more of what precedes it. Anything beyond roughly
 * half the visible rows just reads as "centered".
 */
private const val MenuContextRows = 2

/**
 * First index to scroll to when the menu opens, so the current selection is on screen with a
 * little context above it rather than flush against the top edge.
 *
 * Deliberately a plain function so the open-scroll behavior is one obvious place to change.
 */
private fun menuScrollTarget(selectedIndex: Int, itemCount: Int): Int =
    (selectedIndex - MenuContextRows).coerceIn(0, (itemCount - 1).coerceAtLeast(0))

/**
 * Positions the menu directly under its anchor, flipping above when there is not enough room
 * below, and clamping into the window on both axes.
 *
 * This is the piece Material3's `DropdownMenu` would normally handle. We hand-roll it because
 * the menu body is a [LazyColumn], which `DropdownMenu` explicitly does not support (its content
 * goes into a plain scrollable `Column` sized with `IntrinsicSize.Max`, so every item composes
 * and gets measured twice on open).
 */
private class AnchoredMenuPositionProvider(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val below = anchorBounds.bottom + gapPx
        val above = anchorBounds.top - popupContentSize.height - gapPx
        val y = when {
            below + popupContentSize.height <= windowSize.height -> below
            above >= 0 -> above
            else -> (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        }
        val x = anchorBounds.left
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

/**
 * Labeled chip that opens a scrollable single-select menu.
 *
 * Shared by the Pulsar and Bass panels. The menu body is a [LazyColumn] in a raw [Popup] rather
 * than a Material3 `DropdownMenu`: `DropdownMenu` composes **every** child eagerly into a plain
 * `Column` and then applies `width(IntrinsicSize.Max)`, which re-measures every child's text a
 * second time. At Pulsar's catalog size (47 vibes when the WIP tier is visible) that lands as a
 * dropped frame on open. Lazily composing only the visible rows makes open cost independent of
 * [entries] size.
 *
 * Styling deliberately mirrors `DropdownMenuItem` (48dp rows, `labelLarge`, accent-colored label
 * for the current selection) so only the layout strategy changed, not the look.
 *
 * Because a lazy list cannot report intrinsics, the menu needs an explicit [menuWidth] where
 * `DropdownMenu` auto-sized itself; entries wider than that ellipsize. M3 clamped its own
 * auto-width to 112dp..280dp, so that is the sensible range to pick from.
 *
 * An [OrpheusVerticalScrollbar] tinted with [color] rides the trailing edge, so a menu capped at
 * [menuMaxHeight] reads as scrollable on sight instead of looking like the whole list. It hides
 * itself when [entries] fits without scrolling, so short menus are unchanged.
 *
 * @param selectedDisplay display string of the current selection, matched against [displayName]
 *   to highlight and scroll to the active row.
 * @param onLongPress optional long-press action on the anchor chip. Pulsar's VIBE chip uses it
 *   for the manual anomaly trigger.
 * @param highlight 0f..1f "armed"/active tint on the anchor chip, lerped toward
 *   [OrpheusColors.cosmicPurple].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> EnumDropdown(
    label: String,
    selectedDisplay: String,
    entries: List<T>,
    displayName: (T) -> String,
    onSelected: (T) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    highlight: Float = 0f,
    labelColor: Color = color.lighten(),
    menuWidth: Dp = 180.dp,
    // ~8 rows at 48dp. M3's menu grew to nearly the full window on a 47-item list; capping it
    // keeps the popup on screen without making browsing feel cramped.
    menuMaxHeight: Dp = 400.dp,
) {
    var expanded by remember { mutableStateOf(false) }
    val backgroundColor by animateColorAsState(
        targetValue = lerp(
            OrpheusColors.darkVoid.copy(alpha = 0.6f),
            OrpheusColors.cosmicPurple,
            highlight.coerceIn(0f, 1f),
        ),
        animationSpec = tween(200),
    )

    // Resolved once per (entries, selection) instead of two displayName() calls per row per
    // composition, which is what the old per-item `displayName(entry) == selectedDisplay` cost.
    val selectedIndex = remember(entries, selectedDisplay) {
        entries.indexOfFirst { displayName(it) == selectedDisplay }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.proportional(),
            color = labelColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )

        Spacer(Modifier.height(2.dp))

        Box(
            // Order matters: clip first so the press ripple is bounded by the rounded corners,
            // then background, then the click handler, then padding so the padded area stays
            // part of the touch target.
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(backgroundColor)
                .combinedClickable(
                    onClick = { expanded = true },
                    onLongClick = onLongPress,
                )
                .padding(horizontal = ChipHorizontalPadding, vertical = ChipVerticalPadding),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedDisplay,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select $label",
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )
            }

            if (expanded) {
                EnumDropdownMenu(
                    entries = entries,
                    displayName = displayName,
                    selectedIndex = selectedIndex,
                    color = color,
                    menuWidth = menuWidth,
                    menuMaxHeight = menuMaxHeight,
                    onSelected = {
                        onSelected(it)
                        expanded = false
                    },
                    onDismiss = { expanded = false },
                )
            }
        }
    }
}

/**
 * Bigger, single-line, D-pad-focus-aware sibling of [EnumDropdown], built so the DJ app's TV top
 * bar can render its Vibe and Viz pickers as ONE shared composable instead of two separately
 * styled controls. Renders "LABEL: value ▼" on one line (matching how the phone header's Viz
 * chip already reads, e.g. "Viz: Aquarium") and opens the exact same [EnumDropdownMenu] popup
 * [EnumDropdown] uses, so there is still only one dropdown-menu implementation between them.
 *
 * Not gated behind [org.balch.orpheus.ui.infrastructure.LocalTvFocusChrome]: this composable is
 * TV-exclusive by construction (only the DJ app's TV top bar calls it), so the raised-on-focus
 * treatment is always appropriate here, unlike shared widgets such as `RotaryKnobDial`. Idle
 * state uses [org.balch.orpheus.ui.infrastructure.orpheusRaisedPlate] — the same opaque plate the
 * app title wears — so every element in the top bar reads as one family at idle; only the
 * focused element's accent-tinted [raisedAccentSurface] shows which one holds the D-pad cursor.
 *
 * @param previewFocused preview/render-harness seam only — forces the focused visual without a
 *   real D-pad. Every production call site leaves this false.
 */
@Composable
fun <T> TvInlinePicker(
    label: String,
    selectedDisplay: String,
    entries: List<T>,
    displayName: (T) -> String,
    onSelected: (T) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    menuWidth: Dp = 220.dp,
    menuMaxHeight: Dp = 400.dp,
    previewFocused: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val liveFocused by interactionSource.collectIsFocusedAsState()
    val isFocused = previewFocused || liveFocused
    val shape = RoundedCornerShape(8.dp)
    val effects = LocalLiquidEffects.current

    val selectedIndex = remember(entries, selectedDisplay) {
        entries.indexOfFirst { displayName(it) == selectedDisplay }
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 52.dp)
                .then(
                    if (isFocused) {
                        Modifier.raisedAccentSurface(accent = color, shape = shape)
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
                    onClick = { expanded = true },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$label: ",
                color = color.lighten(),
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                maxLines = 1,
            )
            Text(
                text = selectedDisplay,
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Select $label",
                tint = color,
                modifier = Modifier.size(24.dp),
            )
        }

        if (expanded) {
            EnumDropdownMenu(
                entries = entries,
                displayName = displayName,
                selectedIndex = selectedIndex,
                color = color,
                menuWidth = menuWidth,
                menuMaxHeight = menuMaxHeight,
                onSelected = {
                    onSelected(it)
                    expanded = false
                },
                onDismiss = { expanded = false },
            )
        }
    }
}

@Composable
private fun <T> EnumDropdownMenu(
    entries: List<T>,
    displayName: (T) -> String,
    selectedIndex: Int,
    color: Color,
    menuWidth: Dp,
    menuMaxHeight: Dp,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    val selectedItemFocusRequester = remember { FocusRequester() }
    val gapPx = with(LocalDensity.current) { MenuAnchorGap.roundToPx() }
    val positionProvider = remember(gapPx) { AnchoredMenuPositionProvider(gapPx) }

    // Open on the current selection rather than the top of a long list, and land D-pad focus
    // there too (otherwise Up/Down starts from row 0, not the current value). requestFocus()
    // must follow scrollToItem(): the target row only attaches once scrolled into view.
    LaunchedEffect(selectedIndex, entries.size) {
        if (selectedIndex >= 0) {
            listState.scrollToItem(menuScrollTarget(selectedIndex, entries.size))
            selectedItemFocusRequester.requestFocus()
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(MenuCornerRadius),
            color = OrpheusColors.panelSurface,
            shadowElevation = MenuShadowElevation,
        ) {
            Box {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .width(menuWidth)
                        .heightIn(max = menuMaxHeight)
                        .padding(vertical = MenuContainerVerticalPadding),
                ) {
                    items(entries.size) { index ->
                        val entry = entries[index]
                        // Own interaction source per row so the D-pad cursor stays visible while
                        // browsing, not just when it first lands on the selected row above.
                        val rowInteractionSource = remember { MutableInteractionSource() }
                        val rowFocused by rowInteractionSource.collectIsFocusedAsState()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = MenuRowMinHeight)
                                .then(
                                    if (index == selectedIndex) {
                                        Modifier.focusRequester(selectedItemFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                                .then(
                                    if (rowFocused) Modifier.background(color.copy(alpha = 0.18f))
                                    else Modifier
                                )
                                .clickable(
                                    interactionSource = rowInteractionSource,
                                    indication = LocalIndication.current,
                                    onClick = { onSelected(entry) },
                                )
                                .padding(horizontal = MenuRowHorizontalPadding),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            // Selection (current value) is the accent-colored label, unchanged
                            // from the old DropdownMenuItem. Focus (the D-pad cursor) is the
                            // separate background wash above — the two can differ while browsing.
                            Text(
                                text = displayName(entry),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (index == selectedIndex) color else Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // The bar cannot simply fillMaxHeight() alongside the list: this Box wraps its
                // content, and a LazyColumn reports no intrinsics, so no resolved height exists
                // to fill until the list is measured. matchParentSize() reads the Box's final
                // size without feeding back into it, and the inner alignment then parks the bar
                // on the trailing edge at its own thickness. Vertical padding matches the list's
                // so the track lines up with the scrolling viewport rather than the surface.
                // Rows inset their text by 12dp, which clears the 8dp bar.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(vertical = MenuContainerVerticalPadding),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    OrpheusVerticalScrollbar(
                        state = listState,
                        modifier = Modifier.fillMaxHeight(),
                        color = color,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun EnumDropdownPreview() {
    OrpheusTheme {
        Box(Modifier.background(OrpheusColors.blackHoleBackground).padding(16.dp)) {
            EnumDropdown(
                label = "VIBE",
                selectedDisplay = "Dog House",
                entries = listOf("Bell Tolls", "Dog House", "Filter Funk", "Lost In Space"),
                displayName = { it },
                onSelected = {},
                color = OrpheusColors.cosmicPurple,
            )
        }
    }
}

/**
 * Catalog-sized menu, so the scrollbar is actually on screen.
 *
 * The four-entry preview above never overflows [menuMaxHeight], which means it never renders an
 * [OrpheusVerticalScrollbar] at all and can't catch a regression in the thumb.
 */
@Preview
@Composable
private fun EnumDropdownLongListPreview() {
    OrpheusTheme {
        Box(Modifier.background(OrpheusColors.blackHoleBackground).padding(16.dp)) {
            EnumDropdown(
                label = "VIBE",
                selectedDisplay = "Vibe 24",
                entries = List(47) { "Vibe ${it + 1}" },
                displayName = { it },
                onSelected = {},
                color = OrpheusColors.cosmicPurple,
            )
        }
    }
}

@Preview(name = "TvInlinePicker — idle")
@Composable
private fun TvInlinePickerPreview() {
    OrpheusTheme {
        Row(
            modifier = Modifier
                .background(OrpheusColors.blackHoleBackground)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TvInlinePicker(
                label = "VIBE",
                selectedDisplay = "Dog House",
                entries = listOf("Bell Tolls", "Dog House", "Filter Funk", "Lost In Space"),
                displayName = { it },
                onSelected = {},
                color = OrpheusColors.cosmicPurple,
            )
            TvInlinePicker(
                label = "Viz",
                selectedDisplay = "Aquarium",
                entries = listOf("Random", "Aquarium", "Spectrum"),
                displayName = { it },
                onSelected = {},
                color = OrpheusColors.neonCyan,
            )
        }
    }
}

@Preview(name = "TvInlinePicker — focused (raised)")
@Composable
private fun TvInlinePickerFocusedPreview() {
    OrpheusTheme {
        Box(Modifier.background(OrpheusColors.blackHoleBackground).padding(16.dp)) {
            TvInlinePicker(
                label = "VIBE",
                selectedDisplay = "Kaleidoscope Drift",
                entries = listOf("Dog House", "Kaleidoscope Drift"),
                displayName = { it },
                onSelected = {},
                color = OrpheusColors.cosmicPurple,
                previewFocused = true,
            )
        }
    }
}

/**
 * Idle plate under a non-default visualization palette (a stand-in orange/earthy palette, not
 * tied to any real viz file) — proves the picker's idle bevel/fill follow [LocalLiquidEffects]
 * instead of the old fixed cosmicPurple/neonCyan, while the VIBE picker's own text/icon color
 * deliberately stays put (see [org.balch.orpheus.djapp.DjTvTopBar]'s TvVizPicker doc for why).
 */
@Preview(name = "TvInlinePicker — viz palette")
@Composable
private fun TvInlinePickerVizPalettePreview() {
    OrpheusTheme {
        CompositionLocalProvider(
            LocalLiquidEffects provides VisualizationLiquidEffects(
                title = CenterPanelStyle(
                    titleColor = OrpheusColors.neonOrange,
                    borderColor = OrpheusColors.neonOrange.copy(alpha = 0.3f),
                ),
            ),
        ) {
            Box(Modifier.background(OrpheusColors.blackHoleBackground).padding(16.dp)) {
                TvInlinePicker(
                    label = "VIBE",
                    selectedDisplay = "Dog House",
                    entries = listOf("Bell Tolls", "Dog House", "Filter Funk"),
                    displayName = { it },
                    onSelected = {},
                    color = OrpheusColors.cosmicPurple,
                )
            }
        }
    }
}
