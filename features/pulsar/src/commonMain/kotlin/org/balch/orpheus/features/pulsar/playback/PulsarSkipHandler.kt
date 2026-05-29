package org.balch.orpheus.features.pulsar.playback

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.playback.SkipDirection
import org.balch.orpheus.core.playback.SkipHandler
import org.balch.orpheus.features.pulsar.PulsarFeature

/**
 * Cycles through Pulsar's vibe list on system skip-next/previous commands
 * (Bluetooth / headset next-track buttons, OS media-notification controls).
 * Uses the user-configured global default transition spec
 * ([TransitionPreferences.defaultFlow]) — the same style the in-app picker
 * sets — rather than the per-vibe `transitionOut`, since "I want to move now"
 * is a different intent from "this song is over."
 *
 * Rapid taps cancel the in-flight transition via [collectLatest] on a
 * [MutableStateFlow] — each new emission cancels the previous
 * [PulsarTransitionRunner.runTransition] coroutine.
 */
@SingleIn(AppScope::class)
@Inject
class PulsarSkipHandler(
    pulsarFeatureProvider: () -> PulsarFeature,
    private val transitionRunner: PulsarTransitionRunner,
    private val transitionPreferences: TransitionPreferences,
    private val scope: AppCoroutineScope,
) : SkipHandler {

    private val pulsarFeature: PulsarFeature by lazy(pulsarFeatureProvider)
    private val skipTarget = MutableStateFlow("")

    init {
        scope.launch {
            skipTarget.drop(1).collectLatest { nextName ->
                if (nextName.isNotEmpty()) {
                    transitionRunner.runTransition(transitionPreferences.defaultFlow.value) {
                        pulsarFeature.applyVibeByName(nextName)
                    }
                }
            }
        }
    }

    override fun onSkip(direction: SkipDirection) {
        val names = pulsarFeature.vibeNames
        if (names.isEmpty()) return
        val current = pulsarFeature.vibeFlow.value
        val currentIndex = names.indexOf(current.name)
        val nextIndex = when (direction) {
            SkipDirection.NEXT -> (currentIndex + 1).mod(names.size)
            SkipDirection.PREVIOUS -> if (currentIndex <= 0) names.size - 1 else currentIndex - 1
        }
        skipTarget.value = names[nextIndex]
    }
}
