package org.balch.orpheus.core.tempo

import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.plugin.symbols.MAX_BPM
import org.balch.orpheus.core.plugin.symbols.MIN_BPM
import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalTempoRangeTest {
    private val tempo = GlobalTempo(object : AudioEngine {
        override fun start() {}
        override fun stop() {}
        override val isRunning = false
        override val sampleRate = 48000
        override fun getCpuLoad() = 0f
        override fun getCurrentTime() = 0.0
    })

    @Test
    fun theRangeIsSixtyToTwoHundred() {
        assertEquals(60f, MIN_BPM)
        assertEquals(200f, MAX_BPM)
    }

    @Test
    fun tempoClampsToTheRange() {
        tempo.setBpm(10.0)
        assertEquals(MIN_BPM.toDouble(), tempo.getBpm())
        tempo.setBpm(500.0)
        assertEquals(MAX_BPM.toDouble(), tempo.getBpm())
    }

    @Test
    fun theSliderHasNoDeadTravel() {
        assertEquals(MIN_BPM, GlobalTempo.bpmAtSlider(0f))
        assertEquals(MAX_BPM, GlobalTempo.bpmAtSlider(1f))
        assertEquals(0f, GlobalTempo.sliderFraction(MIN_BPM))
        assertEquals(1f, GlobalTempo.sliderFraction(MAX_BPM))
    }

    @Test
    fun aTempoSurvivesTheSlider() {
        assertEquals(120f, GlobalTempo.bpmAtSlider(GlobalTempo.sliderFraction(120f)), 0.001f)
    }

    @Test
    fun aTempoOutsideTheRangePinsTheSlider() {
        assertEquals(0f, GlobalTempo.sliderFraction(40f))
        assertEquals(1f, GlobalTempo.sliderFraction(240f))
    }
}
