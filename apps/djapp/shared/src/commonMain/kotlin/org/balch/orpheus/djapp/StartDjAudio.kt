package org.balch.orpheus.djapp

import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.djapp.di.DjAppGraph

/** The DJ app runs its master at unity, the level every vibe transition returns to. */
const val DJ_START_MASTER_VOLUME = 1f

suspend fun DjAppGraph.startDjAudio() {
    synthOrchestrator.start(firstStartVolume = DJ_START_MASTER_VOLUME)

    // DJ-tuned reverb: short tail, high diffusion for tight space. Only audible
    // when the user dials up a reverb send. Persistence overrides on later launches.
    // Port writes must follow engine start; engine creation wipes them.
    synthEngine.setPluginPort(REVERB_PLUGIN, "time", PortValue.FloatValue(0.35f))
    synthEngine.setPluginPort(REVERB_PLUGIN, "damping", PortValue.FloatValue(0.6f))
    synthEngine.setPluginPort(REVERB_PLUGIN, "diffusion", PortValue.FloatValue(0.7f))
}

private const val REVERB_PLUGIN = "org.balch.orpheus.plugins.reverb"
