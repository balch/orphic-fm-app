package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynEnvelopeFactory @Inject constructor() : Envelope.Factory {
    override fun create(): Envelope = JsynEnvelope()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynPeakFollowerFactory @Inject constructor() : PeakFollower.Factory {
    override fun create(): PeakFollower = JsynPeakFollowerWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JsynLimiterFactory @Inject constructor() : Limiter.Factory {
    override fun create(): Limiter = JsynLimiter()
}
