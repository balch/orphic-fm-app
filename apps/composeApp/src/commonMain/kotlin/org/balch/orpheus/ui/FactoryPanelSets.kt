package org.balch.orpheus.ui

import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.panels.PanelSet
import org.balch.orpheus.core.panels.factoryPanelSet

/**
 * Factory-defined panel sets that ship with the app.
 * The user cannot delete these.
 */
object FactoryPanelSets {

    private val All = factoryPanelSet("All") {
        collapse(PanelId.PRESETS)
        collapse(PanelId.MIDI)
        collapse(PanelId.EVO)
        expand(PanelId.VIZ)
        collapse(PanelId.LFO)
        collapse(PanelId.DELAY)
        collapse(PanelId.REVERB)
        expand(PanelId.DISTORTION)
        collapse(PanelId.FLUX)
        collapse(PanelId.FLUX_TRIGGERS)
        collapse(PanelId.DRUMS)
        collapse(PanelId.BEATS)
        collapse(PanelId.RESONATOR)
        collapse(PanelId.GRAINS)
        collapse(PanelId.WARPS)
        collapse(PanelId.TWEAKS)
        collapse(PanelId.CODE)
        collapse(PanelId.LOOPER)
        collapse(PanelId.SPEECH)
        collapse(PanelId.ASL_MAESTRO)
        collapse(PanelId.AI)
    }

    /** All panels except TWEAKS, for the desktop screen layout. */
    val DesktopScreen = (All - PanelId.TWEAKS).copy(name = "Desktop")

    /** Compact layout for mobile portrait mode. Excludes MIDI (no hardware on mobile) and AI (shown in bottom section). */
    val Compact = factoryPanelSet("Compact") {
        expand(PanelId.EVO)
        expand(PanelId.PRESETS)
        expand(PanelId.VIZ)
        expand(PanelId.TWEAKS)
        expand(PanelId.DISTORTION)
        expand(PanelId.LFO)
        expand(PanelId.DELAY)
        expand(PanelId.REVERB)
        expand(PanelId.WARPS)
        expand(PanelId.GRAINS)
        expand(PanelId.RESONATOR)
        expand(PanelId.FLUX)
        expand(PanelId.FLUX_TRIGGERS)
        expand(PanelId.DRUMS)
        expand(PanelId.BEATS)
        expand(PanelId.CODE)
        expand(PanelId.LOOPER)
        expand(PanelId.SPEECH)
        expand(PanelId.ASL_MAESTRO)
    }

    /** Effects-focused layout. */
    val Effects = factoryPanelSet("Effects") {
        collapse(PanelId.PRESETS)
        expand(PanelId.DELAY)
        expand(PanelId.REVERB)
        expand(PanelId.DISTORTION)
        expand(PanelId.RESONATOR)
        expand(PanelId.WARPS)
        expand(PanelId.GRAINS)
    }

    /** Sequencer-focused layout. */
    val Sequencer = factoryPanelSet("Sequencer") {
        collapse(PanelId.PRESETS)
        expand(PanelId.CODE)
        expand(PanelId.BEATS)
        expand(PanelId.DRUMS)
        expand(PanelId.FLUX)
        expand(PanelId.FLUX_TRIGGERS)
    }

    /** Performance-focused layout. */
    val Performer = factoryPanelSet("Performer") {
        expand(PanelId.PRESETS)
        expand(PanelId.EVO)
        expand(PanelId.TWEAKS)
        expand(PanelId.MIDI)
    }

    /** Minimal layout with just presets. */
    val Minimal = factoryPanelSet("Minimal") {
        expand(PanelId.PRESETS)
    }

    /** All factory sets in order. */
    val all: List<PanelSet> = listOf(DesktopScreen, Effects, Sequencer, Performer, Minimal, Compact)
}
