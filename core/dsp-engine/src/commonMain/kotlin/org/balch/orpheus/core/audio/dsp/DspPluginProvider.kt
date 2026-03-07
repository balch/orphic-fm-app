package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.plugins.beats.BeatsPlugin
import org.balch.orpheus.plugins.bender.BenderPlugin
import org.balch.orpheus.plugins.delay.DelayPlugin
import org.balch.orpheus.plugins.distortion.DistortionPlugin
import org.balch.orpheus.plugins.drum.DrumPlugin
import org.balch.orpheus.plugins.duolfo.DuoLfoPlugin
import org.balch.orpheus.plugins.duolfo.VoicePlugin
import org.balch.orpheus.plugins.flux.FluxPlugin
import org.balch.orpheus.plugins.grains.GrainsPlugin
import org.balch.orpheus.plugins.looper.LooperPlugin
import org.balch.orpheus.plugins.perstringbender.PerStringBenderPlugin
import org.balch.orpheus.plugins.resonator.ResonatorPlugin
import org.balch.orpheus.plugins.reverb.ReverbPlugin
import org.balch.orpheus.plugins.stereo.StereoPlugin
import org.balch.orpheus.plugins.vibrato.VibratoPlugin
import org.balch.orpheus.plugins.warps.WarpsPlugin

@Inject
@SingleIn(AppScope::class)
class DspPluginProvider(
    val plugins: Set<DspPlugin>
) {
    /** Lookup plugins by URI for generic port access */
    private val byUri: Map<String, DspPlugin> by lazy { 
        plugins.associateBy { it.info.uri } 
    }
    
    /** Get a plugin by its URI */
    fun getPlugin(uri: String): DspPlugin? = byUri[uri]
    
    val hyperLfo by lazy { plugins.filterIsInstance<DuoLfoPlugin>().first() }
    val delayPlugin by lazy { plugins.filterIsInstance<DelayPlugin>().first() }
    val distortionPlugin by lazy { plugins.filterIsInstance<DistortionPlugin>().first() }
    val stereoPlugin by lazy { plugins.filterIsInstance<StereoPlugin>().first() }
    val vibratoPlugin by lazy { plugins.filterIsInstance<VibratoPlugin>().first() }
    val benderPlugin by lazy { plugins.filterIsInstance<BenderPlugin>().first() }
    val perStringBenderPlugin by lazy { plugins.filterIsInstance<PerStringBenderPlugin>().first() }
    val drumPlugin by lazy { plugins.filterIsInstance<DrumPlugin>().first() }
    val resonatorPlugin by lazy { plugins.filterIsInstance<ResonatorPlugin>().first() }
    val grainsPlugin by lazy { plugins.filterIsInstance<GrainsPlugin>().first() }
    val looperPlugin by lazy { plugins.filterIsInstance<LooperPlugin>().first() }
    val warpsPlugin by lazy { plugins.filterIsInstance<WarpsPlugin>().first() }
    val fluxPlugin by lazy { plugins.filterIsInstance<FluxPlugin>().first() }
    val voicePlugin by lazy { plugins.filterIsInstance<VoicePlugin>().first() }
    val beatsPlugin by lazy { plugins.filterIsInstance<BeatsPlugin>().first() }
    val reverbPlugin by lazy { plugins.filterIsInstance<ReverbPlugin>().first() }
    val ttsPlugin by lazy { plugins.filterIsInstance<TtsPlugin>().first() }
}