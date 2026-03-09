package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspEnvelopeFactory::class])
class JsynEnvelopeFactory @Inject constructor() : Envelope.Factory {
    override fun create(): Envelope = JsynEnvelope()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspPeakFollowerFactory::class])
class JsynPeakFollowerFactory @Inject constructor() : PeakFollower.Factory {
    override fun create(): PeakFollower = JsynPeakFollowerWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspLimiterFactory::class])
class JsynLimiterFactory @Inject constructor() : Limiter.Factory {
    override fun create(): Limiter = JsynLimiter()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspHardClipFactory::class])
class JsynHardClipFactory @Inject constructor() : HardClip.Factory {
    override fun create(): HardClip = JsynHardClip()
}
