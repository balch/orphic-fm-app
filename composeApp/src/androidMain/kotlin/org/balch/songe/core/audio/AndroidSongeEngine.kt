package org.balch.songe.core.audio

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import org.balch.songe.core.audio.dsp.AudioEngine
import org.balch.songe.core.audio.dsp.DspSongeEngine

/**
 * Android implementation of SongeEngine.
 * Delegates to DspSongeEngine using the Android AudioEngine (JSyn).
 */
@Inject
@ContributesBinding(AppScope::class)
class AndroidSongeEngine : SongeEngine by DspSongeEngine(AudioEngine())
