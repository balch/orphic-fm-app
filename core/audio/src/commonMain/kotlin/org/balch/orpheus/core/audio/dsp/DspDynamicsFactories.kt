package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspEnvelopeFactory() : Envelope.Factory {
    override fun create(): Envelope = DspEnvelope()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspPeakFollowerFactory() : PeakFollower.Factory {
    override fun create(): PeakFollower = DspPeakFollower()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspLimiterFactory() : Limiter.Factory {
    override fun create(): Limiter = DspLimiter()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DspHardClipFactory() : HardClip.Factory {
    override fun create(): HardClip = DspHardClip()
}
