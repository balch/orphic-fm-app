package org.balch.orpheus.core.audio

import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.audio.dsp.PortValue

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
    /** Trigger a specialized 808-style drum. Parameters are normalized 0..1. */
    fun triggerDrum(type: Int, accent: Float, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float)
    /** Set specialized 808-style drum parameters. Parameters are normalized 0..1. */
    fun setDrumTone(type: Int, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float)
    fun triggerDrum(type: Int, accent: Float)


    // Group Control (Quad 1-4, 5-8, 9-12)
    fun setQuadPitch(quadIndex: Int, pitch: Float) // 0-1, 0.5=Unity
    fun setQuadHold(quadIndex: Int, amount: Float) // 0-1, VCA bias
    fun setQuadVolume(quadIndex: Int, volume: Float) // 0-1, output volume multiplier
    fun setQuadTriggerSource(quadIndex: Int, sourceIndex: Int)
    fun setQuadPitchSource(quadIndex: Int, sourceIndex: Int)
    fun setQuadEnvelopeTriggerMode(quadIndex: Int, enabled: Boolean) // true = enable envelope retriggering

    fun getQuadPitch(quadIndex: Int): Float
    fun getQuadHold(quadIndex: Int): Float
    fun getQuadVolume(quadIndex: Int): Float
    fun getQuadTriggerSource(quadIndex: Int): Int
    fun getQuadPitchSource(quadIndex: Int): Int
    fun getQuadEnvelopeTriggerMode(quadIndex: Int): Boolean
    
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
    fun getCurrentTime(): Double

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

    // ═══════════════════════════════════════════════════════════
    // Generic Plugin Port Access
    // ═══════════════════════════════════════════════════════════
    
    /**
     * Set a plugin port value by plugin URI and port symbol.
     * Uses qualified symbol format: "org.balch.orpheus.plugins.grains:position"
     * 
     * @param pluginUri The plugin's unique identifier (e.g., "org.balch.orpheus.plugins.grains")
     * @param symbol The port's symbol (e.g., "position", "mix", "feedback")
     * @param value The typed port value
     * @return true if port was found and set
     */
    fun setPluginPort(pluginUri: String, symbol: String, value: PortValue): Boolean
    
    /**
     * Get a plugin port value by plugin URI and port symbol.
     * 
     * @param pluginUri The plugin's unique identifier
     * @param symbol The port's symbol
     * @return The current value, or null if not found
     */
    fun getPluginPort(pluginUri: String, symbol: String): PortValue?

    // Getters for State Saving
    fun getVoiceTune(index: Int): Float
    fun getVoiceFmDepth(index: Int): Float
    fun getVoiceEnvelopeSpeed(index: Int): Float
    fun getPairSharpness(pairIndex: Int): Float
    fun getDuoModSource(duoIndex: Int): ModSource
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
    fun setResonatorMode(mode: Int)
    fun setResonatorTarget(target: Int) // 0=Drums, 1=Both, 2=Synth (legacy)
    fun setResonatorTargetMix(targetMix: Float) // 0=Drums, 0.5=Both, 1=Synth (continuous)
    fun setResonatorStructure(value: Float)
    fun setResonatorBrightness(value: Float)
    fun setResonatorDamping(value: Float)
    fun setResonatorPosition(value: Float)
    fun setResonatorMix(value: Float)
    fun strumResonator(frequency: Float)
    
    // Resonator Getters
    fun getResonatorMode(): Int
    fun getResonatorTarget(): Int
    fun getResonatorTargetMix(): Float
    fun getResonatorStructure(): Float
    fun getResonatorBrightness(): Float
    fun getResonatorDamping(): Float
    fun getResonatorPosition(): Float
    fun getResonatorMix(): Float
    fun getResonatorSnapBack(): Boolean
    fun setResonatorSnapBack(enabled: Boolean)

    fun setGrainsPosition(value: Float)
    fun setGrainsSize(value: Float)
    fun setGrainsPitch(value: Float)
    fun setGrainsDensity(value: Float)
    fun setGrainsTexture(value: Float)
    fun setGrainsDryWet(value: Float)
    fun setGrainsFreeze(frozen: Boolean)
    fun setGrainsTrigger(trigger: Boolean)
    fun setGrainsMode(mode: Int) // 0=Granular, 1=Reverse, 2=Shimmer, 3=Spectral, 4=Karplus-Strong
    
    fun getGrainsPosition(): Float
    fun getGrainsSize(): Float
    fun getGrainsPitch(): Float
    fun getGrainsDensity(): Float
    fun getGrainsTexture(): Float
    fun getGrainsDryWet(): Float
    fun getGrainsFreeze(): Boolean
    fun getGrainsMode(): Int

    // Drum getters for persistence
    fun getDrumFrequency(type: Int): Float
    fun getDrumTone(type: Int): Float
    fun getDrumDecay(type: Int): Float
    fun getDrumP4(type: Int): Float
    fun getDrumP5(type: Int): Float
    
    // Drum Trigger Routing
    fun setDrumTriggerSource(drumIndex: Int, sourceIndex: Int)
    fun getDrumTriggerSource(drumIndex: Int): Int
    
    // Drum Pitch/Mod Routing
    fun setDrumPitchSource(drumIndex: Int, sourceIndex: Int)
    fun getDrumPitchSource(drumIndex: Int): Int
    


    // Beat Sequencer (Grids)
    fun setBeatsX(x: Float)
    fun getBeatsX(): Float
    fun setBeatsY(y: Float)
    fun getBeatsY(): Float
    fun setBeatsDensity(index: Int, density: Float)
    fun getBeatsDensity(index: Int): Float
    fun setBeatsBpm(bpm: Float)
    fun getBeatsBpm(): Float
    fun setBeatsOutputMode(mode: Int)
    fun getBeatsOutputMode(): Int
    fun setBeatsEuclideanLength(index: Int, length: Int)
    fun getBeatsEuclideanLength(index: Int): Int
    fun setBeatsRandomness(randomness: Float)
    fun getBeatsRandomness(): Float
    fun setBeatsSwing(swing: Float)
    fun getBeatsSwing(): Float
    fun setBeatsMix(mix: Float)
    fun getBeatsMix(): Float
    
    // Drum Bypass
    fun setDrumsBypass(bypass: Boolean)
    fun getDrumsBypass(): Boolean
    
    // Looper
    fun setLooperRecord(recording: Boolean)
    fun setLooperPlay(playing: Boolean)
    fun setLooperOverdub(overdub: Boolean) // Optionally later
    fun clearLooper()
    fun getLooperPosition(): Float
    fun getLooperDuration(): Double

    // Warps Meta-Modulator
    fun setWarpsAlgorithm(value: Float)
    fun setWarpsTimbre(value: Float)
    fun setWarpsLevel1(value: Float)
    fun setWarpsLevel2(value: Float)
    fun setWarpsCarrierSource(source: Int)  // See WarpsSource enum ordinals
    fun setWarpsModulatorSource(source: Int)
    fun setWarpsMix(value: Float)  // 0=dry (bypass), 1=wet (full effect)
    
    fun getWarpsAlgorithm(): Float
    fun getWarpsTimbre(): Float
    fun getWarpsLevel1(): Float
    fun getWarpsLevel2(): Float
    fun getWarpsCarrierSource(): Int
    fun getWarpsModulatorSource(): Int
    fun getWarpsMix(): Float

    // Flux / Marbles
    fun setFluxSpread(value: Float)
    fun setFluxBias(value: Float)
    fun setFluxSteps(value: Float)
    fun setFluxDejaVu(value: Float)
    fun setFluxLength(value: Int)
    fun setFluxScale(index: Int)
    
    fun getFluxSpread(): Float
    fun getFluxBias(): Float
    fun getFluxSteps(): Float
    fun getFluxDejaVu(): Float
    fun getFluxLength(): Int
    fun getFluxScale(): Int
    
    fun setFluxRate(rate: Float) // 0-1, divider control
    fun getFluxRate(): Float
    
    fun setFluxJitter(value: Float)
    fun getFluxJitter(): Float
    fun setFluxProbability(value: Float)
    fun getFluxProbability(): Float
    
    fun setFluxClockSource(sourceIndex: Int) // 0=Internal, 1=LFO
    fun getFluxClockSource(): Int
    fun setFluxGateLength(value: Float)
    fun getFluxGateLength(): Float
}

enum class ModSource {
    VOICE_FM,
    OFF,
    LFO,
    FLUX
}

enum class StereoMode {
    VOICE_PAN,      // Per-voice stereo positioning
    STEREO_DELAYS   // Delay 1→Left, Delay 2→Right (future)
}
