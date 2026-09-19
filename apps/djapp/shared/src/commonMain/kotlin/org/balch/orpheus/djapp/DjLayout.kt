package org.balch.orpheus.djapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Minimum width for TV mode. A 1080p Android TV reports roughly 960x540dp at density 2.0,
 * not 1920x1080, so a higher-looking threshold would never fire on the target device.
 */
val LargeScreenMinWidth: Dp = 900.dp

/** Minimum height for TV mode, which keeps phone landscape (around 412dp tall) out. */
val LargeScreenMinHeight: Dp = 500.dp

/**
 * Minimum width for two panels side by side. Set by rendering: a Mix + Timer pair clips at 660dp,
 * touches the edge at 680dp and fits at 700dp; 700 leaves margin for larger user font scales.
 */
val PortraitPairMinWidth: Dp = 700.dp

/**
 * Minimum height for the pair stack in a window wider than it is tall. Set by rendering the whole
 * screen at 766dp wide: the macro knob labels clip at 660dp, touch the panel edge at 680dp and
 * clear it at 700dp. Under this a wide window takes the landscape layout.
 */
val PortraitPairMinHeight: Dp = 700.dp

/**
 * Smallest portrait Pulsar slot that fits the full step grid. Set by rendering at a closed iPhone
 * Duo's 466dp width (`renderPulsarCompactSweep`): the knob labels clip at 320dp, touch the panel
 * edge at 335dp and clear it at 350dp.
 */
val PulsarFullGridSlotHeight: Dp = 350.dp

/** Under this the 8 track rows stop reading as rows; a shorter slot clips instead. */
val PulsarGridMinHeight: Dp = 64.dp

/**
 * The step grid's height for a portrait Pulsar [slot]: every dp the slot is short of
 * [PulsarFullGridSlotHeight] comes off the grid, the one part of the panel that can give.
 */
fun pulsarGridHeightFor(slot: Dp, full: Dp): Dp =
    flexHeightFor(slot, PulsarFullGridSlotHeight, full, PulsarGridMinHeight)

/**
 * Smallest slot that fits Horn's full rotor display. Set by rendering at 466dp wide
 * (`renderLowerPanelCompactSweep`): the knob row clips at 220dp, touches the panel edge at
 * 235dp and clears it at 250dp.
 */
val HornFullDisplaySlotHeight: Dp = 250.dp

/** Under this the two rotor drawings stop reading; a shorter slot clips instead. */
val HornDisplayMinHeight: Dp = 88.dp

/** Horn's rotor display height for [slot], by the same rule as [pulsarGridHeightFor]. */
fun hornDisplayHeightFor(slot: Dp, full: Dp): Dp =
    flexHeightFor(slot, HornFullDisplaySlotHeight, full, HornDisplayMinHeight)

/** [full] less whatever [slot] is short of [fullSlot], never under [min]. */
private fun flexHeightFor(slot: Dp, fullSlot: Dp, full: Dp, min: Dp): Dp =
    (full - (fullSlot - slot).coerceAtLeast(0.dp)).coerceAtLeast(min)

/** Every screen arrangement. Switch on it with no `else`, so a new one is a compile error. */
sealed interface DjLayout {
    /** Header, Pulsar, one nav-selected panel, bottom nav. */
    data object Portrait : DjLayout

    /** Header, Pulsar, up to two panels side by side; the bottom nav toggles them. */
    data object PortraitPair : DjLayout

    /** Pulsar left, header and one nav-selected panel right, nav rail. */
    data object Landscape : DjLayout

    /** The visualization fills the screen and panels dock around it. */
    data object LargeScreen : DjLayout

    /** Half-folded: Pulsar alone above [hinge]; header, then the pair (when [pair]) or one panel, below. */
    data class Tabletop(val hinge: Hinge, val pair: Boolean) : DjLayout
}

/**
 * Whether the host allows TV mode at all. No host overrides it today: desktop once tied it to
 * fullscreen, and now any window big enough gets the dock.
 */
val LocalTvModeAllowed = compositionLocalOf { true }

/**
 * The only place layout precedence lives. A hinge decides alone; otherwise the dock wins when
 * allowed and big enough, a wide window still tall enough for the pair stacks it (a desktop
 * window under the dock leaves landscape's half-width Pulsar column clipping and half empty),
 * orientation picks landscape or portrait, and wide portrait gets a pair.
 */
fun resolveLayout(
    width: Dp,
    height: Dp,
    tvModeAllowed: Boolean = true,
    hinge: Hinge? = null,
): DjLayout = when {
    hinge != null -> DjLayout.Tabletop(hinge, pair = width >= PortraitPairMinWidth)
    tvModeAllowed && width >= LargeScreenMinWidth && height >= LargeScreenMinHeight ->
        DjLayout.LargeScreen
    width > height && (width < PortraitPairMinWidth || height < PortraitPairMinHeight) ->
        DjLayout.Landscape
    width >= PortraitPairMinWidth -> DjLayout.PortraitPair
    else -> DjLayout.Portrait
}

/** Whether the lower region holds the pair rather than one nav-selected panel. */
fun DjLayout.showsPair(): Boolean = when (this) {
    DjLayout.PortraitPair -> true
    is DjLayout.Tabletop -> pair
    DjLayout.Portrait, DjLayout.Landscape, DjLayout.LargeScreen -> false
}

/**
 * A nav rail and landscape-style sheets. Tabletop keeps portrait chrome, so its nav bar sits on
 * the flat half under the thumbs.
 */
fun DjLayout.usesLandscapeChrome(): Boolean = when (this) {
    DjLayout.Landscape, DjLayout.LargeScreen -> true
    DjLayout.Portrait, DjLayout.PortraitPair, is DjLayout.Tabletop -> false
}

/**
 * Hands [content] the [DjLayout] for the space this box fills; [modifier] must size the box.
 * Decided from the previous frame's size, in composition: deciding mid-layout (BoxWithConstraints)
 * disposes sheet dialogs inside the layout pass and crashes the desktop scene.
 */
@Composable
fun DjLayoutBox(
    modifier: Modifier = Modifier,
    tvModeAllowed: Boolean = LocalTvModeAllowed.current,
    hinge: Hinge? = LocalHinge.current,
    content: @Composable BoxScope.(DjLayout) -> Unit,
) {
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    Box(modifier.onSizeChanged { measured = it }) {
        // Nothing to lay out until the first measure reports a size.
        if (measured != IntSize.Zero) {
            val width = with(density) { measured.width.toDp() }
            val height = with(density) { measured.height.toDp() }
            content(resolveLayout(width, height, tvModeAllowed, hinge))
        }
    }
}
