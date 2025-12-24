package org.balch.songe.core.presets

import org.balch.songe.core.audio.TestSongeEngine
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
        assertEquals(0.5f, preset.voiceTunes[1]) // Default from TestSongeEngine
    }
}
