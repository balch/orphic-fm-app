package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeEnvelopeFactory @Inject constructor() : Envelope.Factory {
    override fun create(): Envelope = OboeEnvelope()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboePeakFollowerFactory @Inject constructor() : PeakFollower.Factory {
    override fun create(): PeakFollower = OboePeakFollower()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeLimiterFactory @Inject constructor() : Limiter.Factory {
    override fun create(): Limiter = OboeLimiter()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeHardClipFactory @Inject constructor() : HardClip.Factory {
    override fun create(): HardClip = OboeHardClip()
}
