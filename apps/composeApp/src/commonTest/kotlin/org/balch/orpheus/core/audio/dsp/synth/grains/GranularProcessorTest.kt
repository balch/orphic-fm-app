package org.balch.orpheus.plugins.grains.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GranularProcessorTest {

    @Test
    fun testInitialization() {
        val processor = GranularProcessor()
        processor.init(2)
        
        // Check default parameters
        val p = processor.parameters
        assertEquals(0.0f, p.position)
        assertEquals(false, p.freeze)
    }

    @Test
    fun testProcessSilence() {
        val processor = GranularProcessor()
        processor.init(2)
        
        val size = 256
        val inL = FloatArray(size)
        val inR = FloatArray(size)
        val outL = FloatArray(size)
        val outR = FloatArray(size)
        
        processor.process(inL, inR, outL, outR, size)
        
        // Output should be silence (possibly very small noise if dithered, but standard is 0)
        for (i in 0 until size) {
            assertEquals(0.0f, outL[i], 0.0001f, "Output Left should be silent")
            assertEquals(0.0f, outR[i], 0.0001f, "Output Right should be silent")
        }
    }
    
    @Test
    fun testThroughputDelay() {
        val processor = GranularProcessor()
        processor.init(2)
        
        processor.parameters.dryWet = 0.0f // All Dry
        
        val size = 256
        val inL = FloatArray(size) { 1.0f } // DC
        val inR = FloatArray(size) { -1.0f }
        val outL = FloatArray(size)
        val outR = FloatArray(size)
        
        processor.process(inL, inR, outL, outR, size)
        
        // Should match input
        assertEquals(1.0f, outL[0], 0.01f)
        assertEquals(-1.0f, outR[0], 0.01f)
    }

    @Test
    fun testWetProcessingDoesNotCrash() {
        val processor = GranularProcessor()
        processor.init(2)
        
        processor.parameters.dryWet = 1.0f // Full wet
        processor.parameters.position = 0.5f // Some delay
        processor.parameters.size = 0.5f
        
        val size = 256
        val inL = FloatArray(size) { if(it==0) 1.0f else 0.0f } // Impulse
        val inR = FloatArray(size)
        val outL = FloatArray(size)
        val outR = FloatArray(size)
        
        // Run multiple blocks to ensure stability
        for (i in 0 until 10) {
            processor.process(inL, inR, outL, outR, size)
        }
        
        // Just checking for no crash / exceptions
        assertTrue(true)
    }
}
