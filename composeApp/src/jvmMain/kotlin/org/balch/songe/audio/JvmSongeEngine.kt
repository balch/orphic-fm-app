package org.balch.songe.audio

import org.balch.songe.audio.dsp.AudioEngine
import org.balch.songe.audio.dsp.SharedSongeEngine

/**
 * JVM implementation of SongeEngine.
 * Delegates to SharedSongeEngine using the JVM AudioEngine (JSyn).
 */
class JvmSongeEngine : SongeEngine by SharedSongeEngine(AudioEngine())
