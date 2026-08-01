package org.balch.orpheus.features.pulsar.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.audio.FadeCurve
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.StereoMode
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.plugin.PortValue

/**
 * Fully inert [SynthEngine] for commonTest code needing only a valid instance to satisfy a
 * constructor, e.g. [org.balch.orpheus.features.pulsar.PulsarSession].
 *
 * Duplicates `TestSynthEngine` (apps/orpheus/shared) and `SongEndingStubSynthEngine` (this
 * module's jvmTest) because neither is reachable: the dependency runs the other way, and
 * jvmTest doesn't carry into commonTest.
 */
internal class NoOpSynthEngine : SynthEngine {
    override fun start() {}
    override fun stop() {}
    override fun setVoiceTune(index: Int, tune: Float) {}
    override fun setVoiceGate(index: Int, active: Boolean) {}
    override fun setVoiceFeedback(index: Int, amount: Float) {}
    override fun setVoiceFmDepth(index: Int, amount: Float) {}
    override fun setVoiceEnvelopeSpeed(index: Int, speed: Float) {}
    override fun setDuoSharpness(duoIndex: Int, sharpness: Float) {}
    override fun triggerDrum(type: Int, accent: Float, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) {}
    override fun setDrumTone(type: Int, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) {}
    override fun triggerDrum(type: Int, accent: Float) {}
    override fun setQuadPitch(quadIndex: Int, pitch: Float) {}
    override fun setQuadHold(quadIndex: Int, amount: Float) {}
    override fun setQuadVolume(quadIndex: Int, volume: Float) {}
    override fun setQuadTriggerSource(quadIndex: Int, sourceIndex: Int) {}
    override fun setQuadPitchSource(quadIndex: Int, sourceIndex: Int) {}
    override fun setQuadEnvelopeTriggerMode(quadIndex: Int, enabled: Boolean) {}
    override fun getQuadPitch(quadIndex: Int): Float = 0f
    override fun getQuadHold(quadIndex: Int): Float = 0f
    override fun getQuadVolume(quadIndex: Int): Float = 0f
    override fun getQuadTriggerSource(quadIndex: Int): Int = 0
    override fun getQuadPitchSource(quadIndex: Int): Int = 0
    override fun getQuadEnvelopeTriggerMode(quadIndex: Int): Boolean = false
    override fun fadeQuadVolume(quadIndex: Int, targetVolume: Float, durationSeconds: Float) {}
    override fun setVoiceHold(index: Int, amount: Float) {}
    override fun setVoiceWobble(index: Int, wobbleOffset: Float, range: Float) {}
    override fun setDrive(amount: Float) {}
    override fun setDistortionMix(amount: Float) {}
    override fun setMasterVolume(amount: Float) {}
    override fun fadeMasterVolume(target: Float, durationMs: Int, curve: FadeCurve) {}
    override fun masterTapeStop(durationMs: Int) {}
    override fun masterScratch(durationMs: Int) {}
    override fun masterFilter(durationMs: Int) {}
    override fun setDelayTime(index: Int, time: Float) {}
    override fun setDelayFeedback(amount: Float) {}
    override fun setDelayMix(amount: Float) {}
    override fun setDelayModDepth(index: Int, amount: Float) {}
    override fun setHyperLfoFreq(index: Int, frequency: Float) {}
    override fun setHyperLfoMode(mode: Int) {}
    override fun setHyperLfoLink(active: Boolean) {}
    override fun getHyperLfoFreq(index: Int): Float = 0f
    override fun getHyperLfoMode(): Int = 0
    override fun getHyperLfoLink(): Boolean = false
    override fun setDuoModSource(duoIndex: Int, source: ModSource) {}
    override fun setFmStructure(crossQuad: Boolean) {}
    override fun setTotalFeedback(amount: Float) {}
    override fun setVibrato(amount: Float) {}
    override fun setVoiceCoupling(amount: Float) {}
    override fun setBend(amount: Float) {}
    override fun getBend(): Float = 0f
    override fun setStringBend(stringIndex: Int, bendAmount: Float, voiceMix: Float) {}
    override fun releaseStringBend(stringIndex: Int): Int = 0
    override fun setSlideBar(yPosition: Float, xPosition: Float) {}
    override fun releaseSlideBar() {}
    override fun resetStringBenders() {}
    override fun playTestTone(frequency: Float) {}
    override fun stopTestTone() {}
    override fun getPeak(): Float = 0f
    override fun getCpuLoad(): Float = 0f
    override fun getCurrentTime(): Double = 0.0
    override val peakFlow: StateFlow<Float> = MutableStateFlow(0f)
    override val cpuLoadFlow: StateFlow<Float> = MutableStateFlow(0f)
    override val voiceLevelsFlow: StateFlow<FloatArray> = MutableStateFlow(FloatArray(8))
    override val lfoOutputFlow: StateFlow<Float> = MutableStateFlow(0f)
    override val lfoAOutputFlow: StateFlow<Float> = MutableStateFlow(0f)
    override val lfoBOutputFlow: StateFlow<Float> = MutableStateFlow(0f)
    override val masterLevelFlow: StateFlow<Float> = MutableStateFlow(0f)
    override val bendFlow: StateFlow<Float> = MutableStateFlow(0f)
    override fun setPluginPort(pluginUri: String, symbol: String, value: PortValue): Boolean = false
    override fun getPluginPort(pluginUri: String, symbol: String): PortValue? = null
    override fun getVoiceTune(index: Int): Float = 0f
    override fun getVoiceFmDepth(index: Int): Float = 0f
    override fun getVoiceEnvelopeSpeed(index: Int): Float = 0f
    override fun getDuoSharpness(duoIndex: Int): Float = 0f
    override fun getDuoModSource(duoIndex: Int): ModSource = ModSource.OFF
    override fun getFmStructureCrossQuad(): Boolean = false
    override fun getTotalFeedback(): Float = 0f
    override fun getVibrato(): Float = 0f
    override fun getVoiceCoupling(): Float = 0f
    override fun getDelayTime(index: Int): Float = 0f
    override fun getDelayFeedback(): Float = 0f
    override fun getDelayMix(): Float = 0f
    override fun getDelayModDepth(index: Int): Float = 0f
    override fun getDrive(): Float = 0f
    override fun getDistortionMix(): Float = 0f
    override fun getMasterVolume(): Float = 0f
    override fun setVoicePan(index: Int, pan: Float) {}
    override fun getVoicePan(index: Int): Float = 0f
    override fun setMasterPan(pan: Float) {}
    override fun getMasterPan(): Float = 0f
    override fun setStereoMode(mode: StereoMode) {}
    override fun getStereoMode(): StereoMode = StereoMode.VOICE_PAN
    override fun setParameterAutomation(controlId: String, times: FloatArray, values: FloatArray, count: Int, duration: Float, mode: Int) {}
    override fun clearParameterAutomation(controlId: String) {}
    override fun getDrumFrequency(type: Int): Float = 0f
    override fun getDrumTone(type: Int): Float = 0f
    override fun getDrumDecay(type: Int): Float = 0f
    override fun getDrumP4(type: Int): Float = 0f
    override fun getDrumP5(type: Int): Float = 0f
    override fun loadTtsAudio(samples: FloatArray, sampleRate: Int) {}
    override fun playTts() {}
    override fun stopTts() {}
    override fun isTtsPlaying(): Boolean = false
    override fun setLooperRecord(recording: Boolean) {}
    override fun setLooperPlay(playing: Boolean) {}
    override fun setLooperOverdub(overdub: Boolean) {}
    override fun setLooperQuantize(enabled: Boolean) {}
    override fun setLooperLevel(level: Float) {}
    override fun clearLooper() {}
    override fun getLooperPosition(): Float = 0f
    override fun getLooperDuration(): Double = 0.0
}
