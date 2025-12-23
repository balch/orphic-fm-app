package org.balch.songe.core.presets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.songe.core.audio.ModSource
import org.balch.songe.core.audio.SongeEngine
import org.balch.songe.core.audio.StereoMode
import kotlin.test.Test
import kotlin.test.assertEquals

class PresetLoaderTest {

    @Test
    fun testApplyPresetEmitsToFlow() {
        val engine = TestSongeEngine()
        val loader = PresetLoader(engine)
        val preset = DronePreset(name = "Test Preset")

        loader.applyPreset(preset)

        val emitted = loader.presetFlow.replayCache.firstOrNull()
        assertEquals(preset, emitted)
    }

    @Test
    fun testCurrentStateAsPresetCapturesEngineValues() {
        val engine = TestSongeEngine()
        engine.setMasterVolume(0.8f)
        engine.setDrive(0.5f)
        engine.setVoiceTune(0, 0.9f)

        val loader = PresetLoader(engine)
        val preset = loader.currentStateAsPreset("Captured Preset")

        assertEquals("Captured Preset", preset.name)
        assertEquals(0.8f, preset.masterVolume)
        assertEquals(0.5f, preset.drive)
        assertEquals(0.9f, preset.voiceTunes[0])
        assertEquals(0.0f, preset.voiceTunes[1]) // Default
    }

    private class TestSongeEngine : SongeEngine {
        var _masterVolume: Float = 0f
        var _drive: Float = 0f
        var _distortionMix: Float = 0f
        val _voiceTunes = FloatArray(8)

        override fun start() {}
        override fun stop() {}

        override fun getMasterVolume(): Float = _masterVolume
        override fun getDrive(): Float = _drive
        override fun getDistortionMix(): Float = _distortionMix
        override fun getVoiceTune(index: Int): Float = _voiceTunes[index]

        override fun setMasterVolume(value: Float) { _masterVolume = value }
        override fun setDrive(value: Float) { _drive = value }
        override fun setDistortionMix(value: Float) { _distortionMix = value }
        
        override fun setVoiceTune(index: Int, tune: Float) { _voiceTunes[index] = tune }
        override fun setVoiceGate(index: Int, active: Boolean) {}
        override fun setVoiceFeedback(index: Int, amount: Float) {}
        override fun setVoiceFmDepth(index: Int, amount: Float) {}
        override fun setVoiceEnvelopeSpeed(index: Int, speed: Float) {}
        override fun setPairSharpness(pairIndex: Int, sharpness: Float) {}
        override fun setDuoModSource(duoIndex: Int, source: ModSource) {}
        override fun setQuadPitch(quadIndex: Int, pitch: Float) {}
        override fun setQuadHold(quadIndex: Int, amount: Float) {}
        override fun setFmStructure(crossQuad: Boolean) {}
        override fun setTotalFeedback(amount: Float) {}
        override fun setVibrato(amount: Float) {}
        override fun setVoiceCoupling(amount: Float) {}
        override fun setDelayTime(index: Int, time: Float) {}
        override fun setDelayFeedback(amount: Float) {}
        override fun setDelayMix(amount: Float) {}
        override fun setDelayModDepth(index: Int, amount: Float) {}
        override fun setDelayModSource(index: Int, isLfo: Boolean) {}
        override fun setDelayLfoWaveform(isTriangle: Boolean) {}
        override fun setDelay(time: Float, feedback: Float) {}
        override fun setHyperLfoFreq(index: Int, frequency: Float) {}
        override fun setHyperLfoMode(mode: Int) {}
        override fun setHyperLfoLink(active: Boolean) {}
        override fun playTestTone(frequency: Float) {}
        override fun stopTestTone() {}
        
        override fun getPeak(): Float = 0f
        override fun getCpuLoad(): Float = 0f
        
        override val peakFlow: StateFlow<Float> = MutableStateFlow(0f).asStateFlow()
        override val cpuLoadFlow: StateFlow<Float> = MutableStateFlow(0f).asStateFlow()

        override fun getVoiceFmDepth(index: Int): Float = 0f
        override fun getVoiceEnvelopeSpeed(index: Int): Float = 0f
        override fun getPairSharpness(pairIndex: Int): Float = 0f
        override fun getDuoModSource(duoIndex: Int): ModSource = ModSource.OFF
        override fun getQuadPitch(quadIndex: Int): Float = 0.5f
        override fun getQuadHold(quadIndex: Int): Float = 0f
        override fun getFmStructureCrossQuad(): Boolean = false
        override fun getTotalFeedback(): Float = 0f
        override fun getVibrato(): Float = 0f
        override fun getVoiceCoupling(): Float = 0f
        override fun getDelayTime(index: Int): Float = 0f
        override fun getDelayFeedback(): Float = 0f
        override fun getDelayMix(): Float = 0f
        override fun getDelayModDepth(index: Int): Float = 0f
        override fun getDelayModSourceIsLfo(index: Int): Boolean = false
        override fun getDelayLfoWaveformIsTriangle(): Boolean = true
        override fun getHyperLfoFreq(index: Int): Float = 0f
        override fun getHyperLfoMode(): Int = 1 // OFF
        override fun getHyperLfoLink(): Boolean = false

        // Stereo stubs
        override fun setVoicePan(index: Int, pan: Float) {}
        override fun getVoicePan(index: Int): Float = 0f
        override fun setMasterPan(pan: Float) {}
        override fun getMasterPan(): Float = 0f
        override fun setStereoMode(mode: StereoMode) {}
        override fun getStereoMode(): StereoMode = StereoMode.VOICE_PAN
    }
}

