package org.balch.orpheus.core.audio

import kotlinx.coroutines.flow.StateFlow

interface SynthEngine {
    fun start()
    fun stop()

    // Voice Control
    fun setVoiceTune(index: Int, tune: Float)
    fun setVoiceGate(index: Int, active: Boolean)
    fun setVoiceFeedback(index: Int, amount: Float)
    fun setVoiceFmDepth(index: Int, amount: Float) // FM modulation from pair
    fun setVoiceEnvelopeSpeed(index: Int, speed: Float) // 0=Fast, 1=Slow (continuous)
    fun setPairSharpness(pairIndex: Int, sharpness: Float) // Waveform (0=tri, 1=sq) per pair

    // specialized Drum Synthesis (808)
    fun triggerDrum(type: Int, accent: Float, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float)
    fun setDrumTone(type: Int, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float)
    fun triggerDrum(type: Int, accent: Float)


    // Group Control (Quad 1-4, 5-8, 9-12)
    fun setQuadPitch(quadIndex: Int, pitch: Float) // 0-1, 0.5=Unity
    fun setQuadHold(quadIndex: Int, amount: Float) // 0-1, VCA bias
    fun setQuadVolume(quadIndex: Int, volume: Float) // 0-1, output volume multiplier
    
    /**
     * Smoothly fade a quad's volume to a target level over a specified duration.
     * Uses JSyn's LinearRamp for sample-accurate, click-free transitions.
     * 
     * @param quadIndex 0-2 (Quad 1-3)
     * @param targetVolume Target volume level (0.0 to 1.0)
     * @param durationSeconds Duration of the fade in seconds
     */
    fun fadeQuadVolume(quadIndex: Int, targetVolume: Float, durationSeconds: Float)
    fun setVoiceHold(index: Int, amount: Float) // 0-1, per-voice VCA bias
    
    /**
     * Apply wobble modulation to a voice's volume.
     * This is a real-time modulation applied on top of the envelope output.
     * 
     * @param index Voice index (0-7)
     * @param wobbleOffset Modulation offset in range [-1, 1], where 0 = no modulation
     * @param range Maximum modulation depth (0-1), e.g., 0.3 = ±30% modulation
     */
    fun setVoiceWobble(index: Int, wobbleOffset: Float, range: Float = 0.3f)

    // Global
    fun setDrive(amount: Float)
    fun setDistortionMix(amount: Float) // 0=clean, 1=distorted (post-delay)
    fun setMasterVolume(amount: Float)

    // Delay Controls
    fun setDelayTime(index: Int, time: Float) // 0 or 1
    fun setDelayFeedback(amount: Float)
    fun setDelayMix(amount: Float)

    // Delay Modulation
    fun setDelayModDepth(index: Int, amount: Float)
    fun setDelayModSource(index: Int, isLfo: Boolean) // true=LFO, false=Self
    fun setDelayLfoWaveform(isTriangle: Boolean) // true=Triangle, false=Square (AND)

    @Deprecated("Use granular setDelayTime/Feedback instead")
    fun setDelay(time: Float, feedback: Float)

    // Hyper LFO
    fun setHyperLfoFreq(index: Int, frequency: Float) // 0=A, 1=B
    fun setHyperLfoMode(mode: Int) // 0=AND, 1=OFF, 2=OR
    fun setHyperLfoLink(active: Boolean)

    // Duo Mod Source
    fun setDuoModSource(duoIndex: Int, source: ModSource)

    // Advanced FM
    fun setFmStructure(crossQuad: Boolean) // true = 34>56, 78>12 routing
    fun setTotalFeedback(amount: Float) // 0-1, output→LFO feedback
    fun setVibrato(amount: Float) // 0-1, global pitch wobble depth
    fun setVoiceCoupling(amount: Float) // 0-1, partner envelope→frequency depth

    // Bender - spring-loaded pitch/timbre bend
    fun setBend(amount: Float) // -1 to +1, 0=center
    fun getBend(): Float

    // Per-String Bender - individual string pitch bending for strings panel
    /**
     * Set bend for a specific string (controls its 2 associated voices).
     * @param stringIndex 0-3 (each string controls voices stringIndex*2 and stringIndex*2+1)
     * @param bendAmount -1 to +1, horizontal deflection
     * @param voiceMix 0 to 1, vertical position (0=voice A, 0.5=both, 1=voice B)
     */
    fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float)
    
    /**
     * Release a string, triggering spring animation.
     * @return Spring duration in milliseconds for UI animation sync
     */
    fun releaseStringBend(stringIndex: Int): Int
    
    /**
     * Set the slide bar position for global pitch control.
     * @param yPosition 0 to 1 (0=top, 1=bottom) - down = higher pitch
     * @param xPosition 0 to 1 (horizontal) - used for vibrato from wiggling
     */
    fun setSlideBar(yPosition: Float, xPosition: Float)
    
    /**
     * Release the slide bar (springs back to center).
     */
    fun releaseSlideBar()
    
    /**
     * Reset all per-string bender state to neutral.
     * Call this when switching away from strings panel or when using voice pads' global bender.
     */
    fun resetStringBenders()

    // Test/Debug
    fun playTestTone(frequency: Float = 440f)
    fun stopTestTone()

    // Monitoring
    fun getPeak(): Float
    fun getCpuLoad(): Float

    // Reactive monitoring flows (emit at ~100ms intervals)
    val peakFlow: StateFlow<Float>
    val cpuLoadFlow: StateFlow<Float>

    // Visualization flows (emit at ~30fps for plasma background)
    val voiceLevelsFlow: StateFlow<FloatArray>  // 8 voice levels, 0-1 range
    val lfoOutputFlow: StateFlow<Float>         // -1 to 1 range
    val masterLevelFlow: StateFlow<Float>       // 0-1 range, overall output including delay

    // Parameter Flows
    val driveFlow: StateFlow<Float>
    val distortionMixFlow: StateFlow<Float>
    val delayMixFlow: StateFlow<Float>
    val delayFeedbackFlow: StateFlow<Float>
    val quadPitchFlow: StateFlow<FloatArray>    // 3 quads
    val quadHoldFlow: StateFlow<FloatArray>     // 3 quads
    val bendFlow: StateFlow<Float>              // -1 to +1 range, bender position

    /**
     * AI TODO
     * Create flows for Distortion, Mode Delay, Quad and Duo Controls
     * See if there is a way to make it generic and let the plugins  handle the flows
     */

    // Getters for State Saving
    fun getVoiceTune(index: Int): Float
    fun getVoiceFmDepth(index: Int): Float
    fun getVoiceEnvelopeSpeed(index: Int): Float
    fun getPairSharpness(pairIndex: Int): Float
    fun getDuoModSource(duoIndex: Int): ModSource
    fun getQuadPitch(quadIndex: Int): Float
    fun getQuadHold(quadIndex: Int): Float
    fun getQuadVolume(quadIndex: Int): Float
    fun getFmStructureCrossQuad(): Boolean
    fun getTotalFeedback(): Float
    fun getVibrato(): Float
    fun getVoiceCoupling(): Float

    fun getDelayTime(index: Int): Float
    fun getDelayFeedback(): Float
    fun getDelayMix(): Float
    fun getDelayModDepth(index: Int): Float
    fun getDelayModSourceIsLfo(index: Int): Boolean
    fun getDelayLfoWaveformIsTriangle(): Boolean

    fun getHyperLfoFreq(index: Int): Float
    fun getHyperLfoMode(): Int
    fun getHyperLfoLink(): Boolean

    fun getDrive(): Float
    fun getDistortionMix(): Float
    fun getMasterVolume(): Float

    // Stereo Control
    fun setVoicePan(index: Int, pan: Float) // -1=Left, 0=Center, 1=Right
    fun getVoicePan(index: Int): Float
    fun setMasterPan(pan: Float)
    fun getMasterPan(): Float
    fun setStereoMode(mode: StereoMode)
    fun getStereoMode(): StereoMode

    // Automation
    fun setParameterAutomation(controlId: String, times: FloatArray, values: FloatArray, count: Int, duration: Float, mode: Int)
    fun clearParameterAutomation(controlId: String)
    
    // Rings Resonator
    fun setResonatorEnabled(enabled: Boolean)
    fun setResonatorMode(mode: Int)
    fun setResonatorStructure(value: Float)
    fun setResonatorBrightness(value: Float)
    fun setResonatorDamping(value: Float)
    fun setResonatorPosition(value: Float)
    fun setResonatorMix(value: Float)
    fun strumResonator(frequency: Float)
    
    // Resonator Getters
    fun getResonatorEnabled(): Boolean
    fun getResonatorMode(): Int
    fun getResonatorStructure(): Float
    fun getResonatorBrightness(): Float
    fun getResonatorDamping(): Float
    fun getResonatorPosition(): Float
    fun getResonatorMix(): Float
}

enum class ModSource {
    VOICE_FM,
    OFF,
    LFO
}

enum class StereoMode {
    VOICE_PAN,      // Per-voice stereo positioning
    STEREO_DELAYS   // Delay 1→Left, Delay 2→Right (future)
}
