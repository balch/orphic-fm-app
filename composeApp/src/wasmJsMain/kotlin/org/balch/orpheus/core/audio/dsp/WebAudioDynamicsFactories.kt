package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioEnvelopeFactory @Inject constructor(private val engine: OrpheusAudioEngine) : Envelope.Factory {
    override fun create(): Envelope = WebAudioEnvelope(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioPeakFollowerFactory @Inject constructor(private val engine: OrpheusAudioEngine) : PeakFollower.Factory {
    override fun create(): PeakFollower = WebAudioPeakFollower(engine.webAudioContext)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WebAudioLimiterFactory @Inject constructor(private val engine: OrpheusAudioEngine) : Limiter.Factory {
    override fun create(): Limiter = WebAudioLimiter(engine.webAudioContext)
}
