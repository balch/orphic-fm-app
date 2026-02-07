package org.balch.orpheus.core.presets

import org.balch.orpheus.core.audio.TestSynthEngine
import kotlin.test.Test
import kotlin.test.assertEquals

class PresetLoaderTest {

    @Test
    fun testApplyPresetEmitsToFlow() {
        val engine = TestSynthEngine()
        val loader = PresetLoaderV1(engine)
        val preset = SynthPreset(name = "Test Preset")

        loader.applyPreset(preset)

        val emitted = loader.presetFlow.replayCache.firstOrNull()
        assertEquals(preset, emitted)
    }

    @Test
    fun testCurrentStateAsPresetCapturesEngineValues() {
        val engine = TestSynthEngine()
        engine.setMasterVolume(0.8f)
        engine.setDrive(0.5f)
        engine.setVoiceTune(0, 0.9f)

        val loader = PresetLoaderV1(engine)
        val preset = loader.currentStateAsPreset("Captured Preset")

        assertEquals("Captured Preset", preset.name)
        assertEquals(0.5f, preset.getFloat("org.balch.orpheus.plugins.distortion:drive"))
        assertEquals(0.9f, preset.getFloat("org.balch.orpheus.plugins.voice:tune_0", 0.5f))
        assertEquals(0.5f, preset.getFloat("org.balch.orpheus.plugins.voice:tune_1", 0.5f)) // Default from TestSynthEngine
    }
}
