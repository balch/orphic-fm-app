package org.balch.orpheus.core.presets

import org.balch.orpheus.core.audio.TestSynthEngine
import kotlin.test.Test
import kotlin.test.assertEquals

class DrumPresetBugTest {

    class MockDrumEngine : TestSynthEngine() {
        private val freqs = FloatArray(3)
        private val tones = FloatArray(3)
        private val decays = FloatArray(3)
        private val p4s = FloatArray(3)
        private val p5s = FloatArray(3)

        override fun setDrumTone(type: Int, frequency: Float, tone: Float, decay: Float, p4: Float, p5: Float) {
            if (type in 0..2) {
                freqs[type] = frequency
                tones[type] = tone
                decays[type] = decay
                p4s[type] = p4
                p5s[type] = p5
            }
        }

        override fun getDrumFrequency(type: Int): Float = freqs[type]
        override fun getDrumTone(type: Int): Float = tones[type]
        override fun getDrumDecay(type: Int): Float = decays[type]
        override fun getDrumP4(type: Int): Float = p4s[type]
        override fun getDrumP5(type: Int): Float = p5s[type]
    }

    @Test
    fun testDrumFrequencyPersistence() {
        val engine = MockDrumEngine()
        val loader = PresetLoader(engine)
        
        // 1. Simulate setting a normalized frequency (0.5f)
        val normalizedFreq = 0.5f
        engine.setDrumTone(0, normalizedFreq, 0.5f, 0.5f, 0.5f, 0.5f)
        
        // 2. Capture state as preset
        val preset = loader.currentStateAsPreset("Test")
        
        // Now it should stay as 0.5f because MockDrumEngine.getDrumFrequency 
        // returns what was set (normalized), and SynthEngine.setDrumTone 
        // now expects normalized values.
        assertEquals(0.5f, preset.drumBdFrequency, "Drum BD frequency should be stored as normalized 0..1 value")
    }
}
