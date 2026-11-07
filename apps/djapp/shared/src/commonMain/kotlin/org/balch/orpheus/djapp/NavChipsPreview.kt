package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.delay
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.onboarding.OnboardingSequencer
import org.balch.orpheus.core.onboarding.OnboardingStep
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.ui.infrastructure.LocalTelevisionHardware
import org.balch.orpheus.ui.widgets.OnboardingCalloutFadeMillis

/** A beat after the bar shows, as the dome's wiggle waits, before the pretend finger lands. */
internal const val NavChipsPreviewDelayMillis = 1_500L

/** How long the runner waits for the bar to lay out its tabs before giving the launch up. */
internal const val NavChipsPreviewLayoutWaitMillis = 2_000L

/** The pretend press: the tab sinks and glows for this long, then lifts as a real one does. */
internal const val NavChipsPreviewPressMillis = 1_800L

/** How long the tab takes to rise once the press lands. */
internal const val NavChipsPreviewLiftMillis = 300L

/** How long the raised tab holds before it starts to settle. */
internal const val NavChipsPreviewHoldMillis = 1_000L

/** How long the tab and its glow ease home, the chip row left open. */
internal const val NavChipsPreviewSettleMillis = 1_000L

/** How long the "long press" reminder stays up on a later launch. */
internal const val NavLongPressHintMillis = 4_000L

/** Room for a callout to fade out before its popup goes. */
internal const val NavCalloutClearMillis = OnboardingCalloutFadeMillis.toLong() + 50L

internal const val NavLongPressHint = "Long press to open"

/** What the chips do, in the words of the pointer in hand. */
internal fun navChipRowHint(desktop: Boolean): String =
    if (desktop) "Click to open, long press to replace" else "Tap to open, long press to replace"

/**
 * The first launch presses a tab for the user; the second reminds, unless a real press came first.
 * The dome's wiggle sits out any launch this shows on: one hint a launch. Queued only with
 * `-PnavLongPressTour=true`, so the long press stays undiscovered for now.
 */
internal val NavLongPressStep = OnboardingStep("nav-long-press", maxShows = 2, ownsLaunch = true)

/** The callout over an open chip row, until the user has picked a chip. */
internal val NavChipRowStep = OnboardingStep("nav-chip-row-long-press")

/** The dome's "swipe me" wiggle, each launch until a swipe has ever committed. */
internal val DomeSwipeStep = OnboardingStep("dome-swipe", learnedAlready = { it.domeSwiped })

internal fun djOnboardingQueue(longPressTour: Boolean): List<OnboardingStep> =
    if (longPressTour) listOf(NavLongPressStep, DomeSwipeStep) else listOf(DomeSwipeStep)

/**
 * The DJ app's onboarding, over [djOnboardingQueue]. Held by the app graph so an Android activity recreated for
 * a rotation or a fold keeps what this launch has already shown.
 */
@SingleIn(AppScope::class)
@Inject
class DjOnboarding(preferences: AppPreferencesRepository, scope: AppCoroutineScope) :
    OnboardingSequencer(djOnboardingQueue(DjBuildKonfig.NAV_LONG_PRESS_TOUR), preferences, scope)

/** Unprovided in previews and most tests: there no bar presses itself and no callout shows. */
internal val LocalOnboarding = staticCompositionLocalOf<OnboardingSequencer?> { null }

/**
 * The tab the preview presses: Horn where it is on the bar, else the bar's last tab, the one beside
 * the tabs the row reveals. Null on a bar with no tabs.
 */
internal fun navChipsPreviewTab(onBar: List<DjRoute>): DjRoute? =
    onBar.firstOrNull { it == HornTab } ?: onBar.lastOrNull()

/**
 * Takes [NavLongPressStep]'s turn on this bar or rail once it is laid out: [onBar] is read again each
 * check, since a bar shows four tabs until it has measured itself. A bar with nothing off it gives
 * the launch up. The first show presses the tab; the others only remind. A television has no long
 * press to teach, so it gives the launch up at once rather than holding the wiggle back. Nothing
 * runs when the step isn't queued: `awaitTurn` would call a step outside the queue due.
 */
@Composable
internal fun NavChipsPreviewRunner(host: NavGestureHost?, onBar: () -> List<DjRoute>) {
    val onboarding = LocalOnboarding.current ?: return
    if (NavLongPressStep !in onboarding.queue) return
    if (LocalTelevisionHardware.current) {
        LaunchedEffect(onboarding) { onboarding.skip(NavLongPressStep) }
        return
    }
    if (host == null) return
    val tabs by rememberUpdatedState(onBar)
    LaunchedEffect(host, onboarding) {
        onboarding.awaitTurn(NavLongPressStep) ?: return@LaunchedEffect
        delay(NavChipsPreviewDelayMillis)
        var route = navChipsPreviewTab(tabs())?.takeIf { host.canOpenChips(it) }
        var waited = 0L
        while (route == null && waited < NavChipsPreviewLayoutWaitMillis) {
            delay(50)
            waited += 50
            route = navChipsPreviewTab(tabs())?.takeIf { host.canOpenChips(it) }
        }
        if (route == null) {
            onboarding.skip(NavLongPressStep)
            return@LaunchedEffect
        }
        onboarding.run(NavLongPressStep) { shows ->
            if (shows == 0) host.previewLongPress(route) else host.showLongPressHint(route)
        }
    }
}
