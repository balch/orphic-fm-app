package org.balch.orpheus.djapp

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.TimerStatus
import org.balch.orpheus.ui.theme.OrpheusColors
import kotlin.time.Duration

/**
 * Alpha applied to the selected-tab indicator pill. Kept low so the pill reads as a
 * neon wash behind the icon/label rather than a solid block — the hand-set icon and
 * text tints (see [DjAppNavScaffold]) already carry the actual selection signal.
 */
private const val NavIndicatorAlpha = 0.16f

/**
 * Unselected icon/label opacity. Raised from 0.6 alongside the TV bar's own readability pass:
 * an unselected destination still has to be readable, not merely present, and 0.6 sat close to
 * "disabled" against this app's dark chrome.
 */
private const val NavUnselectedAlpha = 0.75f

/**
 * Selected-tab indicator color for both the bottom bar and the rail. M3's default
 * indicator (`colorScheme.secondaryContainer`) renders invisible against our
 * transparent nav background in dark posture, and baseline lavender under a light
 * system theme — a deliberate neon-cyan wash replaces it instead.
 */
internal val NavIndicatorColor = OrpheusColors.neonCyan.copy(alpha = NavIndicatorAlpha)

// M3's phone bar measures (internal there): the bar the tabs are laid out in, their gap, and an icon.
private val NavBarHeight = 80.dp
private val NavBarItemGap = 8.dp
private val NavIconSize = 24.dp

/**
 * The phone bar's vibe name, a size up from the tabs' 11sp so it reads as the title. It keeps
 * labelSmall's 16sp line: 1.23em at 13sp, about the theme's proportional 1.25em, and the tabs' own line.
 */
internal val barNameStyle: TextStyle
    @Composable get() {
        val labelSmall = MaterialTheme.typography.labelSmall
        return remember(labelSmall) { labelSmall.copy(fontSize = 13.sp) }
    }

/** How far the bar's name is drawn below the tab labels' line, for room under the ring: tune from 1 to 2dp. */
internal val BarNameDrop = 2.dp

/** The bar's name may draw past its slot, up to this far short of the neighbouring tab labels. */
internal val BarNameClearance = 8.dp

/**
 * The bar's name lane for a slot of `slotPx`: centred on the slot and [BarNameClearance] short of
 * the wider label beside the transport, wherever that label sits centred in its own equal slot.
 */
@Composable
private fun barNameLane(tabs: List<DjRoute>, transportIndex: Int): (Int) -> Int {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelPx = listOfNotNull(tabs.getOrNull(transportIndex - 1), tabs.getOrNull(transportIndex))
        .maxOfOrNull { measurer.measure(it.label, labelStyle).size.width } ?: 0
    val density = LocalDensity.current
    val gapPx = with(density) { NavBarItemGap.roundToPx() }
    val clearancePx = with(density) { BarNameClearance.roundToPx() }
    // The neighbour's label starts (slot - label) / 2 into its slot, a gap past the transport's edge.
    return remember(labelPx, gapPx, clearancePx) { { slotPx -> 2 * (slotPx + gapPx - clearancePx) - labelPx } }
}

/**
 * M3's rail width (internal there). The transport takes exactly this: fillMaxWidth in the rail's
 * column would stretch the rail across the window, since that column is as wide as its widest child.
 */
private val RailItemWidth = 80.dp
/** Keeps the vibe name clear of the rail's glass border; a long one scrolls in what is left. */
private val RailLabelInset = 4.dp
/** The rail's ring, 64dp inside its 72dp between the label insets; the phone bar's [BarRingSize] matches it. */
internal val RailRingSize = 64.dp
/** The ring on a rail too short for [RailRingSize] and its title, such as the Fold 8's 360dp cover screen. */
internal val RailCompactRingSize = 48.dp

// Rail heights, measured against the rendered rail at font scale 1 and 1.3 in RailLayoutTest.
/** M3's own gap between rail items; the compact rail tightens it before shrinking the ring further. */
private val RailTabGap = 4.dp
private val RailCompactTabGap = 2.dp
/** The rail's 4dp top and bottom padding, its 4dp gaps either side of the spacer, and a 4dp margin. */
private val RailFixedHeight = 20.dp

/** M3's rail item: a 24dp icon, 8dp to its label line, 4dp above and below; never under 56dp. */
private fun railItemHeight(labelLine: Dp): Dp = max(56.dp, 40.dp + labelLine)

/** The ring's column (4dp padding around the ring and its title line) and 8dp to the rail's edge. */
private fun railRingColumnHeight(ringSize: Dp, labelLine: Dp): Dp = 4.dp + ringSize + labelLine + 4.dp + 8.dp

/**
 * The rail's transport, largest first: the ring with the title, then a smaller ring under tighter
 * tabs. Each keeps the whole title line in the rail; dragging the dome skips, as in the phone bar.
 */
internal enum class RailTransport(val ringSize: Dp, val tabGap: Dp) {
    Standard(RailRingSize, RailTabGap),
    Compact(RailCompactRingSize, RailCompactTabGap),
}

/** The rail height [transport] needs under [tabCount] tabs, with [labelLine] the label style's line at the user's font scale. */
internal fun railMinHeight(transport: RailTransport, tabCount: Int, labelLine: Dp): Dp {
    val tabs = railItemHeight(labelLine) * tabCount + transport.tabGap * (tabCount - 1).coerceAtLeast(0)
    return RailFixedHeight + tabs + railRingColumnHeight(transport.ringSize, labelLine)
}

/** The largest transport that fits [railHeight]; under the compact floor the rail stays compact. */
internal fun railTransportFor(railHeight: Dp, tabCount: Int, labelLine: Dp): RailTransport =
    RailTransport.entries.firstOrNull { railHeight >= railMinHeight(it, tabCount, labelLine) } ?: RailTransport.Compact

/**
 * Bottom bar in portrait, side rail otherwise; the play slot is the vibe transport.
 *
 * Selection is expressed as [isSelected] rather than a current route so the same scaffold
 * serves both models: single-panel layouts pass a route equality check, while the pair
 * layouts pass a membership test over the pair. TV mode has its own bars and never gets here.
 */
@Composable
fun DjAppNavScaffold(
    isSelected: (DjRoute) -> Boolean,
    onItemClick: (DjRoute) -> Unit,
    layout: DjLayout,
    pulsarFeature: PulsarFeature,
    timerFeature: TimerFeature,
    onTogglePlayback: () -> Unit,
    tabs: List<DjRoute> = djTabs,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val usesRail = layout.usesLandscapeChrome()
    // Only play/pause, so knob turns, tempo ramps and section pushes never rerun the transport.
    val paused by remember(pulsarFeature) {
        pulsarFeature.stateFlow.map { it.globalPaused }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = pulsarFeature.stateFlow.value.globalPaused)
    val timerState by timerFeature.stateFlow.collectAsStateWithLifecycle()
    val nav by pulsarFeature.vibeNavFlow.collectAsStateWithLifecycle()
    // Read by value only in the ring's frame loop: at the viz rate it must never recompose the scaffold.
    val pulse = rememberMusicPulse(pulsarFeature)
    val actions = pulsarFeature.actions
    // The bar's transport sits between the two middle tabs.
    val transportIndex = tabs.size / 2
    val barLane = if (usesRail) null else barNameLane(tabs, transportIndex)

    // The rail passes its tier; the phone bar passes null.
    val transport: @Composable (Modifier, RailTransport?) -> Unit = { itemModifier, rail ->
        VibeTransportItem(
            name = nav.currentName,
            previousName = if (nav.previousRestarts) nav.currentName else nav.previousName,
            nextName = nav.nextName,
            progress = nav.progress,
            paused = paused,
            onTogglePlayback = onTogglePlayback,
            onNext = actions.nextVibe,
            onPrevious = actions.previousVibe,
            modifier = itemModifier,
            previousRestarts = nav.previousRestarts,
            centerLabel = usesRail,
            raiseRing = rail == null,
            nameStyle = if (rail == null) barNameStyle else MaterialTheme.typography.labelSmall,
            nameLane = if (rail == null) barLane else null,
            nameDrop = if (rail == null) BarNameDrop else 0.dp,
            ringSize = rail?.ringSize ?: BarRingSize,
            position = nav.songPosition(),
            pulse = pulse,
        )
    }
    val tabIcon: @Composable (DjRoute, Boolean) -> Unit = { route, selected ->
        val showCountdown = route is TimerTab
            && (timerState.status == TimerStatus.RUNNING
                || timerState.status == TimerStatus.PAUSED)
        if (showCountdown) {
            val color = countdownColor(paused = timerState.status == TimerStatus.PAUSED)
            Box(
                modifier = Modifier,
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatNavCountdown(timerState.remainingTime),
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        } else {
            Icon(
                imageVector = route.icon,
                contentDescription = route.label,
                tint = if (selected) OrpheusColors.neonCyan
                       else Color.White.copy(alpha = NavUnselectedAlpha),
            )
        }
    }
    val tabLabel: @Composable (DjRoute, Boolean) -> Unit = { route, selected ->
        Text(
            text = route.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) OrpheusColors.neonCyan
                    else Color.White.copy(alpha = NavUnselectedAlpha),
        )
    }

    // What M3's suite scaffold gave the stage: white content color, and the bar's or rail's
    // insets already consumed so the stage never pads for them twice.
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        if (usesRail) {
            val density = LocalDensity.current
            var railHeight by remember { mutableStateOf(0.dp) }
            // The rail pads its items by these (the system bars on a sideways phone), so only the
            // height between them decides which transport fits.
            val railInsets = NavigationRailDefaults.windowInsets
            val railContentHeight = railHeight -
                with(density) { (railInsets.getTop(density) + railInsets.getBottom(density)).toDp() }
            // The tab labels' and the title's line at the user's font scale (1.3 on the user's Fold 8).
            val labelLine = with(density) { MaterialTheme.typography.labelSmall.lineHeight.toDp() }
            // Unmeasured on the first frame: the full ring, rather than a flash of the small one.
            val railTransport = if (railHeight > 0.dp) {
                railTransportFor(railContentHeight, tabs.size, labelLine)
            } else {
                RailTransport.Standard
            }
            Row(modifier) {
                GlassRail(Modifier.fillMaxHeight().onSizeChanged { railHeight = with(density) { it.height.toDp() } }) {
                    // Their own column, so a compact rail can set its tabs closer than M3's gap.
                    Column(verticalArrangement = Arrangement.spacedBy(railTransport.tabGap)) {
                        tabs.forEach { route ->
                            val selected = isSelected(route)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { onItemClick(route) },
                                icon = { tabIcon(route, selected) },
                                label = { tabLabel(route, selected) },
                                colors = NavigationRailItemDefaults.colors(indicatorColor = NavIndicatorColor),
                            )
                        }
                    }
                    // Tabs top, transport pinned to the rail's bottom edge.
                    Spacer(Modifier.weight(1f))
                    transport(
                        Modifier.width(RailItemWidth).padding(start = RailLabelInset, end = RailLabelInset, bottom = 8.dp),
                        railTransport,
                    )
                }
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .consumeWindowInsets(NavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)),
                ) { content() }
            }
        } else {
            Column(modifier) {
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .consumeWindowInsets(NavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom)),
                ) { content() }
                // NavigationBar's own Row without its Surface, whose clip would cut the raised ring and its hits.
                // Aligned by baseline: the transport lays out as its name alone, so the ring rises over the stage.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                        .defaultMinSize(minHeight = NavBarHeight)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(NavBarItemGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEachIndexed { index, route ->
                        if (index == transportIndex) transport(Modifier.weight(1f).alignBy(LastBaseline), null)
                        val selected = isSelected(route)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { onItemClick(route) },
                            // An icon tall at least, so a running Timer's countdown leaves its label on the line.
                            icon = {
                                Box(Modifier.heightIn(min = NavIconSize), contentAlignment = Alignment.Center) {
                                    tabIcon(route, selected)
                                }
                            },
                            label = { tabLabel(route, selected) },
                            modifier = Modifier.alignBy(LastBaseline),
                            colors = NavigationBarItemDefaults.colors(indicatorColor = NavIndicatorColor),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The rail in its glass: over a busy viz its labels vanished (the dock bars already wear it). A
 * composable of its own, the scaffold's only reader of the visualization's effects, which some
 * visualizations change every frame: those frames rebuild the glass around the rail, while the
 * rail itself, its tabs and the transport, skips.
 */
@Composable
private fun GlassRail(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.tvBarGlass(true).then(modifier)) {
        NavigationRail(containerColor = Color.Transparent, contentColor = Color.White, content = content)
    }
}

/**
 * Format a countdown for the 24dp nav icon slot. Unit suffix makes each
 * bucket self-describing:
 *   - >= 1h: "H:MM"  (e.g. "1:07")
 *   - >= 1m: "Nm"    (e.g. "42m")
 *   - <  1m: "Ns"    (e.g. "42s")
 */
internal fun formatNavCountdown(remaining: Duration): String {
    val totalSeconds = remaining.inWholeSeconds.coerceAtLeast(0L)
    if (totalSeconds < 60L) return "${totalSeconds}s"
    val totalMinutes = totalSeconds / 60L
    if (totalMinutes < 60L) return "${totalMinutes}m"
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    val mm = if (minutes < 10L) "0$minutes" else "$minutes"
    return "$hours:$mm"
}

/**
 * Alpha oscillator that mirrors the flip-clock colon pulse (1-second
 * reversing tween between 0.7 and 1.0). The State itself, for a read in draw.
 */
@Composable
internal fun runningAlphaPulse(): State<Float> {
    val transition = rememberInfiniteTransition(label = "navCountdownPulse")
    return transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "navCountdownAlpha",
    )
}

private val PausedCountdownColor = ColorProducer { OrpheusColors.sleepMoonlight.copy(alpha = 0.45f) }

/**
 * The countdown's colour: dimmed while [paused], pulsing while running. The pulse is read in draw,
 * so it redraws the digits every frame without recomposing them; they recompose when the text does.
 */
@Composable
internal fun countdownColor(paused: Boolean): ColorProducer {
    if (paused) return PausedCountdownColor
    val pulse = runningAlphaPulse()
    return remember(pulse) { ColorProducer { OrpheusColors.sleepMoonlight.copy(alpha = pulse.value) } }
}
