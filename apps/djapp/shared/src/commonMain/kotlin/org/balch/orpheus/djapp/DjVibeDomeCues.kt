package org.balch.orpheus.djapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.playback.VibeMove

/**
 * Whether this process has given its "swipe me" wiggle, or given it up to a touch or a key. Held by
 * the app graph, not the composition, so an Android activity recreated for a rotation or a fold
 * keeps it spent. Never persisted: the next launch wiggles again until a swipe has ever committed.
 */
@SingleIn(AppScope::class)
@Inject
class DomeWiggleLaunch {
    internal var spent = false
}

/**
 * What moves a play/pause dome besides a finger: the navigator's vibe moves, which it rolls with,
 * and the "swipe me" wiggle, once a launch until a swipe has ever committed. Provided once at the
 * app root, so every chrome's dome finds it wherever that dome lives. Main thread only.
 */
@Stable
internal class VibeDomeCues(
    /** Each vibe change the navigator accepts, as its transition starts. */
    val moves: Flow<VibeMove>,
    private val requestSwipe: (next: Boolean) -> Unit,
    // Where AppPreferences.domeSwiped lives, and the scope that writes it.
    private val preferences: AppPreferencesRepository,
    private val scope: CoroutineScope,
    // One wiggle a launch: taken by the first dome to reach it, or given up to a touch or a key.
    private val wiggle: DomeWiggleLaunch,
) {
    // Null until first loaded; true for good once a swipe commits.
    private var swiped: Boolean? = null

    /** Whether a swipe has ever committed on a dome, on this launch or an earlier one. */
    suspend fun everSwiped(): Boolean {
        swiped?.let { return it }
        val saved = preferences.load().domeSwiped
        // A swipe during the load already said true.
        if (swiped == null) swiped = saved
        return swiped == true
    }

    /** True for the one dome that may wiggle this launch, and only if no swipe has come first. */
    fun takeWiggle(): Boolean {
        if (wiggle.spent || swiped == true) return false
        wiggle.spent = true
        return true
    }

    /** A touch or a key on a dome: no wiggle this launch. */
    fun skipWiggle() {
        wiggle.spent = true
    }

    /**
     * A committed swipe's vibe, asked for as the dome's own: its move comes back marked so and rolls
     * nothing more. The first one is remembered for good.
     */
    fun swipe(next: Boolean) {
        requestSwipe(next)
        swipeLearned()
    }

    /**
     * The gesture is known: a committed swipe, or TalkBack's "Next vibe" and "Previous vibe", which
     * stand in for it. No wiggle now, and none on a later launch.
     */
    fun swipeLearned() {
        wiggle.spent = true
        if (swiped != true) {
            swiped = true
            scope.launch { preferences.update { it.copy(domeSwiped = true) } }
        }
    }
}

/** Unprovided in previews and most tests: there a dome moves only under a finger, and a swipe calls its onNext. */
internal val LocalVibeDomeCues = staticCompositionLocalOf<VibeDomeCues?> { null }

@Composable
internal fun rememberVibeDomeCues(
    pulsarFeature: PulsarFeature,
    preferences: AppPreferencesRepository,
    wiggle: DomeWiggleLaunch,
): VibeDomeCues {
    val scope = rememberCoroutineScope()
    return remember(pulsarFeature, preferences, scope, wiggle) {
        VibeDomeCues(pulsarFeature.vibeMoves, pulsarFeature.actions.swipeVibe, preferences, scope, wiggle)
    }
}
