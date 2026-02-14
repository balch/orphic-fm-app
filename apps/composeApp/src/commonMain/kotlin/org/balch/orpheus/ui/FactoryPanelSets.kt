package org.balch.orpheus.ui

import org.balch.orpheus.core.PanelId
import org.balch.orpheus.core.panels.PanelSet
import org.balch.orpheus.core.panels.factoryPanelSet

/**
 * Factory-defined panel sets that ship with the app.
 * These cannot be deleted by the user.
 */
object FactoryPanelSets {

    /** All 20 panels visible and expanded, in default ordering. */
    val All = factoryPanelSet("All") {
        collapse(PanelId.Companion.PRESETS)
        collapse(PanelId.Companion.MIDI)
        collapse(PanelId.Companion.EVO)
        expand(PanelId.Companion.VIZ)
        collapse(PanelId.Companion.LFO)
        collapse(PanelId.Companion.DELAY)
        collapse(PanelId.Companion.REVERB)
        expand(PanelId.Companion.DISTORTION)
        collapse(PanelId.Companion.FLUX)
        collapse(PanelId.Companion.FLUX_TRIGGERS)
        collapse(PanelId.Companion.DRUMS)
        collapse(PanelId.Companion.BEATS)
        collapse(PanelId.Companion.RESONATOR)
        collapse(PanelId.Companion.GRAINS)
        collapse(PanelId.Companion.WARPS)
        collapse(PanelId.Companion.TWEAKS)
        collapse(PanelId.Companion.CODE)
        collapse(PanelId.Companion.LOOPER)
        collapse(PanelId.Companion.SPEECH)
        collapse(PanelId.Companion.AI)
    }

    /** Effects-focused layout. */
    val Effects = factoryPanelSet("Effects") {
        collapse(PanelId.Companion.PRESETS)
        expand(PanelId.Companion.DELAY)
        expand(PanelId.Companion.REVERB)
        expand(PanelId.Companion.DISTORTION)
        expand(PanelId.Companion.RESONATOR)
        expand(PanelId.Companion.WARPS)
        expand(PanelId.Companion.GRAINS)
    }

    /** Sequencer-focused layout. */
    val Sequencer = factoryPanelSet("Sequencer") {
        collapse(PanelId.Companion.PRESETS)
        expand(PanelId.Companion.CODE)
        expand(PanelId.Companion.BEATS)
        expand(PanelId.Companion.DRUMS)
        expand(PanelId.Companion.FLUX)
        expand(PanelId.Companion.FLUX_TRIGGERS)
    }

    /** Performance-focused layout. */
    val Performer = factoryPanelSet("Performer") {
        expand(PanelId.Companion.PRESETS)
        expand(PanelId.Companion.EVO)
        expand(PanelId.Companion.TWEAKS)
        expand(PanelId.Companion.MIDI)
    }

    /** Minimal layout with just presets. */
    val Minimal = factoryPanelSet("Minimal") {
        expand(PanelId.Companion.PRESETS)
    }

    /** All factory sets in order. */
    val all: List<PanelSet> = listOf(All, Effects, Sequencer, Performer, Minimal)
}
