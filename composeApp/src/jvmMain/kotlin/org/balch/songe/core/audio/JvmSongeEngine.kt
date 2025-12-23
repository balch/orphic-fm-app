package org.balch.songe.core.audio

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import org.balch.songe.core.audio.dsp.AudioEngine
import org.balch.songe.core.audio.dsp.DspSongeEngine

/**
 * JVM implementation of SongeEngine.
 * Delegates to DspSongeEngine using the JVM AudioEngine (JSyn).
 */
@Inject
@ContributesBinding(AppScope::class)
class JvmSongeEngine : SongeEngine by DspSongeEngine(AudioEngine())
