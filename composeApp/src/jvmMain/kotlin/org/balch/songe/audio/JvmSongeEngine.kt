package org.balch.songe.audio

import org.balch.songe.audio.dsp.AudioEngine
import org.balch.songe.audio.dsp.DspSongeEngine

/**
 * JVM implementation of SongeEngine.
 * Delegates to DspSongeEngine using the JVM AudioEngine (JSyn).
 */
class JvmSongeEngine : SongeEngine by DspSongeEngine(AudioEngine())
