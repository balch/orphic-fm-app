package org.balch.orpheus.core.audio

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.DspSynthEngine

/**
 * Android implementation of SynthEngine.
 * Delegates to DspSynthEngine using the Android AudioEngine (JSyn).
 */
@Inject
@ContributesBinding(AppScope::class)
class AndroidSynthEngine : SynthEngine by DspSynthEngine(AudioEngine())
