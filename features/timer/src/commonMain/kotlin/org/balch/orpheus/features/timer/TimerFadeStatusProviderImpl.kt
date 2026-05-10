package org.balch.orpheus.features.timer

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.TimerFadeStatusProvider

/**
 * Bridges [TimerFeature.stateFlow] to the cross-feature [TimerFadeStatusProvider]
 * surface so that Pulsar's song-ending ramp can yield while the sleep timer
 * is already pulling master volume to silence.
 *
 * [TimerOverlayProducer] establishes the precedent that injecting
 * `TimerFeature` directly is safe (no DI cycle): the timer's dependencies
 * (SynthEngine, MasterVolumeRamp, etc.) all sit beneath this binding.
 */
@SingleIn(AppScope::class)
@Inject
@ContributesBinding(AppScope::class)
class TimerFadeStatusProviderImpl(
    private val timerFeature: TimerFeature,
) : TimerFadeStatusProvider {
    override fun isFading(): Boolean =
        timerFeature.stateFlow.value.status == TimerStatus.FADING
}
