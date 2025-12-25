package org.balch.orpheus.core.audio

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.DspSynthEngine

/**
 * JVM implementation of SynthEngine.
 * Delegates to DspSynthEngine using the JVM AudioEngine (JSyn).
 */
@Inject
@ContributesBinding(AppScope::class)
class JvmSynthEngine : SynthEngine by DspSynthEngine(AudioEngine())
