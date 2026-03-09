package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspEnvelopeFactory @Inject constructor() : Envelope.Factory {
    override fun create(): Envelope = DspEnvelope()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspPeakFollowerFactory @Inject constructor() : PeakFollower.Factory {
    override fun create(): PeakFollower = DspPeakFollower()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspLimiterFactory @Inject constructor() : Limiter.Factory {
    override fun create(): Limiter = DspLimiter()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DspHardClipFactory @Inject constructor() : HardClip.Factory {
    override fun create(): HardClip = DspHardClip()
}
