package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.plugins.DspBenderPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspDistortionPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspDrumPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspDuoLfoPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspFluxPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspGrainsPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspLooperPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspPerStringBenderPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspResonatorPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspStereoPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspVibratoPlugin
import org.balch.orpheus.core.audio.dsp.plugins.DspWarpsPlugin
import org.balch.orpheus.plugins.delay.Lv2DelayPlugin

@Inject
@SingleIn(AppScope::class)
class DspPluginProvider(
    val plugins: Set<DspPlugin>
) {
    val hyperLfo by lazy { plugins.filterIsInstance<DspDuoLfoPlugin>().first() }
    val delayPlugin by lazy { plugins.filterIsInstance<Lv2DelayPlugin>().first() }
    val distortionPlugin by lazy { plugins.filterIsInstance<DspDistortionPlugin>().first() }
    val stereoPlugin by lazy { plugins.filterIsInstance<DspStereoPlugin>().first() }
    val vibratoPlugin by lazy { plugins.filterIsInstance<DspVibratoPlugin>().first() }
    val benderPlugin by lazy { plugins.filterIsInstance<DspBenderPlugin>().first() }
    val perStringBenderPlugin by lazy { plugins.filterIsInstance<DspPerStringBenderPlugin>().first() }
    val drumPlugin by lazy { plugins.filterIsInstance<DspDrumPlugin>().first() }
    val resonatorPlugin by lazy { plugins.filterIsInstance<DspResonatorPlugin>().first() }
    val grainsPlugin by lazy { plugins.filterIsInstance<DspGrainsPlugin>().first() }
    val looperPlugin by lazy { plugins.filterIsInstance<DspLooperPlugin>().first() }
    val warpsPlugin by lazy { plugins.filterIsInstance<DspWarpsPlugin>().first() }
    val fluxPlugin by lazy { plugins.filterIsInstance<DspFluxPlugin>().first() }
}