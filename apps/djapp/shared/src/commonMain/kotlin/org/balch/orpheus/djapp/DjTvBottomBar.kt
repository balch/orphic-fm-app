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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarPanelActions
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.TimerStatus
import org.balch.orpheus.features.timer.TimerUiState
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.ui.infrastructure.LocalLiquidEffects
import org.balch.orpheus.ui.infrastructure.LocalTvFocusRegion
import org.balch.orpheus.ui.infrastructure.orpheusChromeWash
import org.balch.orpheus.ui.infrastructure.raisedAccentSurface
import org.balch.orpheus.ui.infrastructure.tvFocusRegionBorder
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.theme.lighten
import org.balch.orpheus.ui.theme.OrpheusTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Icon size for a bottom bar item — doubled from the original 30dp per the user's explicit
 * "twice the size" follow-up. Couch-distance legibility, not just a bigger touch target.
 */
private val TvBottomBarIconSize = 56.dp

/** Label/countdown/value text size for a bottom bar item — doubled from the original 13sp. */
private val TvBottomBarLabelSize = 24.sp

/**
 * Minimum touch/focus target. Width and height are NOT forced equal: these are icon-over-label
 * columns, so width naturally follows the (short) label text while height carries the "twice as
 * big" read. Forcing width to match the doubled height too (a literal 152dp square) would blow
 * the ~1150dp usable budget for seven items at 1280dp — see DjTvBottomBar's doc comment.
 */
private val TvBottomBarMinWidth = 100.dp
private val TvBottomBarMinHeight = 148.dp

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

/** Size of the shown/hidden toggle badge in an item's corner. */
private val TvBottomBarToggleSize = 26.dp

/**
 * Fixed bottom bar display order: DJ, Mix, Horn, Pulsar, Timer, then Vibe Info and Ends last.
 * This is independent of [dockable]'s toggle-order and of each panel's actual dock position
 * (left/right column vs. centre stage — see [assignDock]), which follows dock-toggle order,
 * not this list. Vibe Info and Ends are both genuine dock toggles too (see [largeScreenPanels]),
 * placed last: Ends is explicitly "at the end" per the user's own instruction.
 */
private val BottomBarOrder: List<DjRoute> =
    listOf(DjTab, MixTab, HornTab, PulsarTab, TimerTab, VibeInfoTab, EndsTab)

/** Filters [dockable] down to [BottomBarOrder], preserving that fixed display order. */
internal fun bottomBarPanels(dockable: List<DjRoute>): List<DjRoute> =
    BottomBarOrder.filter { it in dockable }

/**
 * TV mode's bottom bar: one dock toggle per panel in [BottomBarOrder], all rendered the exact
 * same way — every item here docks/undocks its own panel (see [DjPanelDock]), including Vibe
 * Info and Ends. There is no separate "action" group any more: Info and Ends both dock like
 * DJ/Mix/Horn/Timer, just placed last in display order.
 *
 * Two items carry an extra signal beyond docked/focused:
 * - Timer shows a live countdown in place of its icon while running or paused.
 * - Ends shows the *selected ending style* as its label (mirroring `PulsarPanel`'s own ENDING
 *   pill labelling exactly, via the same [PulsarFeature.actions] state, so the two can never
 *   disagree) and gets a third, independent visual channel — a glowing ring — when
 *   [PulsarPanelActions.outroArmed] is true, i.e. a song ending is actively in progress. Docked
 *   (accent wash/plate), focused (raised plate), and armed (ring) are three separate channels
 *   that must all read at once: armed never touches the tint or the plate, so it cannot be
 *   confused with "docked" or "focused" — the same separation already used for docked-vs-focused
 *   here and for focused-vs-adjusting on the rotary dial.
 *
 * Docked/focused tint follows the selected visualization's own title color (see
 * [org.balch.orpheus.djapp.DjTvTopBar] for the same idiom applied to the top bar), so both TV bars
 * read as one consistent piece of chrome instead of the bottom bar wearing a fixed neonCyan
 * regardless of what's on screen. Armed stays a fixed [OrpheusColors.cosmicPurple] — the same
 * fixed hue [org.balch.orpheus.ui.infrastructure.orpheusRaisedPlate]'s own chrome base wears — on
 * purpose: a viz-derived armed ring could land on (or near) whatever hue the docked/focused accent
 * happens to be that moment, destroying the "armed never looks like docked or focused" guarantee
 * this bar depends on. See [TvBottomBarItem] for the item-level detail.
 */
@Composable
fun DjTvBottomBar(
    panels: List<DjRoute>,
    isDocked: (DjRoute) -> Boolean,
    onToggle: (DjRoute) -> Unit,
    timerFeature: TimerFeature,
    pulsarFeature: PulsarFeature,
    modifier: Modifier = Modifier,
    // Preview/render-harness seam only: forces one item's focus visual without real D-pad
    // input. The production call site in DjAppScreen.kt leaves this null.
    previewFocusedRoute: DjRoute? = null,
    // Preview/render-harness seam only: draws the same region-focus border a real
    // TvFocusRegionHolder would, without needing an actual D-pad focus event to drive it.
    previewRegionFocused: Boolean = false,
    // Preview/render-harness seam only: scales the same preview-only border's alpha, to show a
    // partially-faded TvFocusRegionHolder.alpha without a real holder+coroutine driving it.
    previewRegionFocusAlpha: Float = 1f,
) {
    val timerState by timerFeature.stateFlow.collectAsState()
    val songEndingEnabled by pulsarFeature.actions.songEndingEnabled.collectAsState()
    val transitionSpec by pulsarFeature.actions.transitionSpec.collectAsState()
    val outroArmed by pulsarFeature.actions.outroArmed.collectAsState()
    // Exactly PulsarPanel's own ENDING pill expression (see the block gated behind
    // showEndingControl) — same source state, same expression, so the two labels never disagree.
    val endsValue = if (songEndingEnabled) transitionSpec.style.name else "PLAYS"

    // Single accent for every viz-following color in this bar — region border, item tint, and
    // the focused item's raisedAccentSurface — mirrors DjTvTopBar's own title.titleColor source
    // exactly, so both bars re-theme together instead of the bottom bar keeping a fixed neonCyan.
    val effects = LocalLiquidEffects.current
    val accent = effects.title.titleColor

    // Region-focus border only, for now — see tvFocusRegionBorder's doc for the single-holder
    // exclusivity guarantee and why reading it in the draw phase costs nothing per frame. The
    // glass fill this bar could also carry is deliberately deferred: a real-device trace showed
    // the UI thread already janking on every frame with a static Pulsar panel alone, so this pass
    // adds only a drawn stroke, never a recompose/relayout on focus change.
    val focusRegion = LocalTvFocusRegion.current
    val focusToken = remember { Any() }
    val barShape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusGroup()
            .onFocusChanged { focusRegion?.setFocused(focusToken, it.hasFocus) }
            .tvFocusRegionBorder(
                holder = focusRegion,
                token = focusToken,
                color = accent,
                shape = barShape,
            )
            .then(
                if (previewRegionFocused) {
                    Modifier.border(2.dp, accent.copy(alpha = previewRegionFocusAlpha), barShape)
                } else {
                    Modifier
                }
            )
            // Bottom trimmed well below top: the caller already adds an overscan safe-area
            // inset below this bar, so a full 12.dp here on top of that read bottom-heavy.
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        panels.forEach { route ->
            val showCountdown = route is TimerTab &&
                (timerState.status == TimerStatus.RUNNING || timerState.status == TimerStatus.PAUSED)
            val label = when (route) {
                EndsTab -> endsValue
                // Short form for the bar; the route's own label ("Vibe Info") stays intact for
                // everything keyed on it (preferences persistence, the phone-nav sheet saver).
                VibeInfoTab -> "Info"
                else -> route.label
            }
            TvBottomBarItem(
                icon = route.icon,
                label = label,
                docked = isDocked(route),
                accent = accent,
                onClick = { onToggle(route) },
                countdownText = if (showCountdown) formatNavCountdown(timerState.remainingTime) else null,
                countdownDimmed = timerState.status == TimerStatus.PAUSED,
                previewFocused = route == previewFocusedRoute,
                armed = route == EndsTab && outroArmed,
            )
        }
    }
}

/**
 * One item: icon (or, for Timer while running/paused, a live countdown) plus label. Three
 * independent signals, each on its own visual channel so none can be mistaken for another:
 * - Docked state (a panel is currently shown) is a persistent [accent] tint/wash.
 * - Focus (the D-pad cursor is on this item right now) is an opaque [accent]-tinted raised plate.
 * - [armed] (Ends only, currently) is a glowing ring drawn OUTSIDE the plate/wash, in
 *   [armedColor] — it never changes tint or background, so it reads independently of both, and
 *   never follows [accent] (see [DjTvBottomBar]'s doc for why armed stays fixed).
 */
@Composable
private fun TvBottomBarItem(
    icon: ImageVector,
    label: String,
    docked: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    countdownText: String? = null,
    countdownDimmed: Boolean = false,
    previewFocused: Boolean = false,
    armed: Boolean = false,
    armedColor: Color = OrpheusColors.cosmicPurple,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val liveFocused by interactionSource.collectIsFocusedAsState()
    val isFocused = previewFocused || liveFocused

    // Focus also brightens the icon/label — otherwise an undocked-but-focused item sits inside
    // a bright raised plate with a muddy dim icon, undercutting the very thing the plate exists
    // to highlight.
    //
    // Lightened, not the bare accent: a docked item's own plate IS an accent wash, so drawing
    // accent content on it put accent on accent and left the selected items reading worse across
    // a room than the plain-white unselected ones — backwards. The plate already carries the
    // visualization's colour, so the content on top only has to stay legible while keeping the
    // hue.
    val tint = if (docked || isFocused) {
        accent.lighten(TvBottomBarContentLighten)
    } else {
        Color.White.copy(alpha = TvBottomBarIdleAlpha)
    }
    val shape = RoundedCornerShape(14.dp)

    // The armed ring is its own outer layer, offset from the plate/wash by a gap (mirrors the
    // gesture-pad "pinched" halo convention elsewhere in this app): a static border, so it costs
    // nothing beyond the one repaint when armed flips, and it never touches tint or background.
    // The 3dp gap is reserved UNCONDITIONALLY (only the border itself is conditional) so the
    // item's measured footprint never changes when armed flips — border alone doesn't affect
    // layout size, but padding does, and a size change here would reflow every sibling in the
    // centered Row underneath the D-pad cursor.
    Box(
        modifier = modifier
            .then(
                if (armed) Modifier.border(2.5.dp, armedColor.copy(alpha = 0.9f), shape)
                else Modifier
            )
            .padding(3.dp),
    ) {
        Column(
            modifier = Modifier
                .defaultMinSize(minWidth = TvBottomBarMinWidth, minHeight = TvBottomBarMinHeight)
                .then(
                    if (isFocused) {
                        Modifier.raisedAccentSurface(accent = accent, shape = shape)
                    } else if (docked) {
                        // orpheusChromeWash, NOT a bare background(accent@alpha) — see
                        // TvDockedWashAlpha's doc for why a flat wash muddies over a busy backdrop.
                        // Also NOT NavIndicatorColor (DjAppBottomNav.kt) — that constant is shared
                        // with the phone/tablet nav and must keep its own fixed neonCyan untouched.
                        Modifier.orpheusChromeWash(shape = shape, accent = accent, washAlpha = TvDockedWashAlpha)
                    } else {
                        Modifier
                    }
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick,
                )
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (countdownText != null) {
                TvBottomBarCountdownText(text = countdownText, dimmed = countdownDimmed)
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(TvBottomBarIconSize),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                fontSize = TvBottomBarLabelSize,
                maxLines = 1,
                softWrap = false,
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
                tint = tint,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(TvBottomBarToggleSize),
            )
        }
    }
}

/**
 * The countdown digits, pulsing via [runningAlphaPulse] while running. Split out as its own
 * (non-inline) composable so the pulse's 60Hz state read is scoped here — Column, which
 * [TvBottomBarItem] otherwise wraps this in, is inline and would otherwise let the read bubble
 * up and force a recompose (and full modifier-chain rebuild) of the whole item every frame. The
 * phone/tablet nav gets this for free because its icon slot is a `@Composable` lambda parameter
 * to `item()`, which is its own restart scope by construction (see DjAppBottomNav.kt).
 */
@Composable
private fun TvBottomBarCountdownText(text: String, dimmed: Boolean) {
    val alpha = if (dimmed) 0.45f else runningAlphaPulse()
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = TvBottomBarLabelSize,
        maxLines = 1,
        softWrap = false,
        color = OrpheusColors.sleepMoonlight.copy(alpha = alpha),
    )
}

// ==================== PREVIEWS ====================

@Preview(widthDp = 1280, heightDp = 200, name = "TV Bottom Bar — Default Dock")
@Composable
private fun DjTvBottomBarDefaultPreview() {
    OrpheusTheme {
        val docked = setOf<DjRoute>(PulsarTab, DjTab)
        DjTvBottomBar(
            panels = bottomBarPanels(largeScreenPanels()),
            isDocked = { it in docked },
            onToggle = {},
            timerFeature = TimerViewModel.previewFeature(),
            pulsarFeature = PulsarViewModel.previewFeature(),
        )
    }
}

@Preview(widthDp = 1280, heightDp = 200, name = "TV Bottom Bar — Everything Docked, Timer Running")
@Composable
private fun DjTvBottomBarEverythingDockedPreview() {
    OrpheusTheme {
        DjTvBottomBar(
            panels = bottomBarPanels(largeScreenPanels()),
            isDocked = { true },
            onToggle = {},
            timerFeature = TimerViewModel.previewFeature(
                TimerUiState(
                    initialTime = 45.minutes,
                    remainingTime = 12.minutes.plus(6.seconds),
                    status = TimerStatus.RUNNING,
                ),
            ),
            pulsarFeature = PulsarViewModel.previewFeature(),
        )
    }
}

@Preview(widthDp = 1280, heightDp = 200, name = "TV Bottom Bar — Focused Item (raised)")
@Composable
private fun DjTvBottomBarFocusedPreview() {
    OrpheusTheme {
        val docked = setOf<DjRoute>(PulsarTab, DjTab)
        DjTvBottomBar(
            panels = bottomBarPanels(largeScreenPanels()),
            isDocked = { it in docked },
            onToggle = {},
            timerFeature = TimerViewModel.previewFeature(),
            pulsarFeature = PulsarViewModel.previewFeature(),
            previewFocusedRoute = HornTab,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 200, name = "TV Bottom Bar — Ends: song ending armed + focused")
@Composable
private fun DjTvBottomBarEndsArmedFocusedPreview() {
    OrpheusTheme {
        val base = PulsarViewModel.previewFeature()
        val armedTapeActions = PulsarPanelActions(
            songEndingEnabled = MutableStateFlow(true),
            transitionSpec = MutableStateFlow(TransitionSpec(style = TransitionStyle.TAPE)),
            outroArmed = MutableStateFlow(true),
        )
        val pulsarArmed = object : PulsarFeature by base {
            override val actions: PulsarPanelActions = armedTapeActions
        }
        DjTvBottomBar(
            panels = bottomBarPanels(largeScreenPanels()),
            isDocked = { it == PulsarTab || it == EndsTab },
            onToggle = {},
            timerFeature = TimerViewModel.previewFeature(),
            pulsarFeature = pulsarArmed,
            previewFocusedRoute = EndsTab,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 200, name = "TV Bottom Bar — Ends: song ending armed, docked, not focused")
@Composable
private fun DjTvBottomBarEndsArmedDockedPreview() {
    OrpheusTheme {
        val base = PulsarViewModel.previewFeature()
        val armedRandomActions = PulsarPanelActions(
            songEndingEnabled = MutableStateFlow(true),
            transitionSpec = MutableStateFlow(TransitionSpec(style = TransitionStyle.RANDOM)),
            outroArmed = MutableStateFlow(true),
        )
        val pulsarArmed = object : PulsarFeature by base {
            override val actions: PulsarPanelActions = armedRandomActions
        }
        DjTvBottomBar(
            panels = bottomBarPanels(largeScreenPanels()),
            isDocked = { it == PulsarTab || it == EndsTab },
            onToggle = {},
            timerFeature = TimerViewModel.previewFeature(),
            pulsarFeature = pulsarArmed,
        )
    }
}

@Preview(widthDp = 1280, heightDp = 200, name = "TV Bottom Bar — Region focused")
@Composable
private fun DjTvBottomBarRegionFocusedPreview() {
    OrpheusTheme {
        val docked = setOf<DjRoute>(PulsarTab, DjTab)
        DjTvBottomBar(
            panels = bottomBarPanels(largeScreenPanels()),
            isDocked = { it in docked },
            onToggle = {},
            timerFeature = TimerViewModel.previewFeature(),
            pulsarFeature = PulsarViewModel.previewFeature(),
            previewRegionFocused = true,
        )
    }
}
