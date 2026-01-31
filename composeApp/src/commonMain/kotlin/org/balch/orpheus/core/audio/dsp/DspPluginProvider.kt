package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.plugins.DspDrumPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspDuoLfoPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspFluxPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspGrainsPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspLooperPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspPerStringBenderPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspWarpsPlugin
import org.balch.orpheus.plugins.bender.BenderPlugin
import org.balch.orpheus.plugins.delay.DelayPlugin
import org.balch.orpheus.plugins.distortion.DistortionPlugin
import org.balch.orpheus.plugins.resonator.ResonatorPlugin
import org.balch.orpheus.plugins.stereo.StereoPlugin
import org.balch.orpheus.plugins.vibrato.VibratoPlugin

@Inject
@SingleIn(AppScope::class)
class DspPluginProvider(
    val plugins: Set<DspPlugin>
) {
    val hyperLfo by lazy { plugins.filterIsInstance<DspDuoLfoPlugin>().first() }
    val delayPlugin by lazy { plugins.filterIsInstance<DelayPlugin>().first() }
    val distortionPlugin by lazy { plugins.filterIsInstance<DistortionPlugin>().first() }
    val stereoPlugin by lazy { plugins.filterIsInstance<StereoPlugin>().first() }
    val vibratoPlugin by lazy { plugins.filterIsInstance<VibratoPlugin>().first() }
    val benderPlugin by lazy { plugins.filterIsInstance<BenderPlugin>().first() }
    val perStringBenderPlugin by lazy { plugins.filterIsInstance<DspPerStringBenderPlugin>().first() }
    val drumPlugin by lazy { plugins.filterIsInstance<DspDrumPlugin>().first() }
    val resonatorPlugin by lazy { plugins.filterIsInstance<ResonatorPlugin>().first() }
    val grainsPlugin by lazy { plugins.filterIsInstance<DspGrainsPlugin>().first() }
    val looperPlugin by lazy { plugins.filterIsInstance<DspLooperPlugin>().first() }
    val warpsPlugin by lazy { plugins.filterIsInstance<DspWarpsPlugin>().first() }
    val fluxPlugin by lazy { plugins.filterIsInstance<DspFluxPlugin>().first() }
}