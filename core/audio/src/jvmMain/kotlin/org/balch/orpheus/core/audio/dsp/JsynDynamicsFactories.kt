package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspEnvelopeFactory::class])
@Inject
class JsynEnvelopeFactory() : Envelope.Factory {
    override fun create(): Envelope = JsynEnvelope()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspPeakFollowerFactory::class])
@Inject
class JsynPeakFollowerFactory() : PeakFollower.Factory {
    override fun create(): PeakFollower = JsynPeakFollowerWrapper()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspLimiterFactory::class])
@Inject
class JsynLimiterFactory() : Limiter.Factory {
    override fun create(): Limiter = JsynLimiter()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [DspHardClipFactory::class])
@Inject
class JsynHardClipFactory() : HardClip.Factory {
    override fun create(): HardClip = JsynHardClip()
}
