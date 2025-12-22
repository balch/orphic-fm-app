package org.balch.songe.core.audio

import org.balch.songe.core.audio.dsp.AudioEngine
import org.balch.songe.core.audio.dsp.DspSongeEngine

/**
 * JVM implementation of SongeEngine.
 * Delegates to DspSongeEngine using the JVM AudioEngine (JSyn).
 */
class JvmSongeEngine : SongeEngine by DspSongeEngine(AudioEngine())
