package org.balch.orpheus.core.audio.dsp

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class SynthDspTest {
    @Test
    fun testSVFProcessesImpulse() {
        val svf = SynthDsp.StateVariableFilter()
        // Set normalized frequency (1000Hz / 44100Hz ≈ 0.023) and resonance
        svf.setFq(0.023f, 2.0f)
        
        // Process a few samples of an impulse
        val res1 = svf.process(1.0f)
        assertTrue(res1.lp != 0f || res1.bp != 0f || res1.hp != 0f, "Filter should produce output from impulse")
    }
    
    @Test
    fun testSVFResonates() {
        val svf = SynthDsp.StateVariableFilter()
        svf.setFq(0.05f, 10.0f)  // Higher Q for more resonance
        
        // Send impulse
        svf.process(1.0f)
        
        // Process many zero samples
        for (i in 0 until 100) {
            svf.process(0f)
        }
        val res = svf.process(0f)
        
        // Should still have some energy due to resonance
        assertTrue(abs(res.lp) > 0.000001f || abs(res.bp) > 0.000001f, "Filter should resonate")
    }
    
    @Test
    fun testSVFReset() {
        val svf = SynthDsp.StateVariableFilter()
        svf.setFq(0.1f, 5.0f)
        
        // Send impulse and let it ring
        svf.process(1.0f)
        for (i in 0 until 50) {
            svf.process(0f)
        }
        
        // Reset
        svf.reset()
        
        // After reset, output should be near zero
        val res = svf.process(0f)
        assertTrue(abs(res.lp) < 0.0001f && abs(res.bp) < 0.0001f && abs(res.hp) < 0.0001f, 
            "Filter should output zero after reset")
    }
    
    @Test
    fun testOnePoleFilter() {
        val opf = SynthDsp.OnePoleFilter()
        opf.setF(0.1f)
        
        // Process step response
        val out1 = opf.process(1.0f)
        val out2 = opf.process(1.0f)
        val out3 = opf.process(1.0f)
        
        // Output should be increasing toward 1.0
        assertTrue(out1 > 0f, "Filter should respond to input")
        assertTrue(out2 > out1, "Filter should be smoothing toward target")
        assertTrue(out3 > out2, "Filter should continue smoothing")
        assertTrue(out3 < 1.0f, "Filter should not overshoot")
    }
    
    @Test
    fun testSemitonesToRatio() {
        // 0 semitones = 1.0 (no change)
        val ratio0 = SynthDsp.semitonesToRatio(0f)
        assertTrue(abs(ratio0 - 1.0f) < 0.0001f, "0 semitones should be ratio 1.0")
        
        // 12 semitones = 2.0 (one octave up)
        val ratio12 = SynthDsp.semitonesToRatio(12f)
        assertTrue(abs(ratio12 - 2.0f) < 0.0001f, "12 semitones should be ratio 2.0")
        
        // -12 semitones = 0.5 (one octave down)
        val ratioNeg12 = SynthDsp.semitonesToRatio(-12f)
        assertTrue(abs(ratioNeg12 - 0.5f) < 0.0001f, "-12 semitones should be ratio 0.5")
    }
}
