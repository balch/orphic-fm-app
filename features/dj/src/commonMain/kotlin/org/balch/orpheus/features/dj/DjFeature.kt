package org.balch.orpheus.features.dj

import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.plugin.symbols.DjSymbol

interface DjFeature : SynthFeature<DjUiState, DjPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.DJ
            override val title = "DJ"

            override val markdown = """
        Dual-deck DJ turntable that captures live audio from synth, drums, bass,
        or master mix into circular buffers and plays them back with variable-speed
        scratch control. Touch/drag the platters to scratch, use the crossfader to
        blend between decks.

        ## Controls
        - **WET A** (dj_wet_a): Deck A dry/wet (0=dry source, 1=turntable only). Default 0 (bypass).
        - **WET B** (dj_wet_b): Deck B dry/wet (0=dry source, 1=turntable only). Default 0 (bypass).
        - **SOURCE A/B** (dj_source_a/b): Capture source per deck — Synth, Drums, Bass, or Master.
        - **CROSSFADER** (dj_crossfader): Blend between Deck A (left) and Deck B (right).
        - **DELAY SEND** (dj_delay_send): Post-crossfader send to delay effect.
        - **REVERB SEND** (dj_reverb_send): Post-crossfader send to reverb effect.
            """.trimIndent()

            override val portControlKeys = mapOf(
                DjSymbol.WET_A.controlId.key to "Deck A dry/wet (0=dry source, 1=turntable only)",
                DjSymbol.WET_B.controlId.key to "Deck B dry/wet (0=dry source, 1=turntable only)",
                DjSymbol.SOURCE_A.controlId.key to "Deck A capture source (0=Synth, 1=Drums, 2=Bass, 3=Master)",
                DjSymbol.SOURCE_B.controlId.key to "Deck B capture source (0=Synth, 1=Drums, 2=Bass, 3=Master)",
                DjSymbol.CROSSFADER.controlId.key to "Crossfader (0=all A, 0.5=center, 1=all B)",
                DjSymbol.DELAY_SEND.controlId.key to "Post-crossfader send to delay (0-1)",
                DjSymbol.REVERB_SEND.controlId.key to "Post-crossfader send to reverb (0-1)",
            )
        }
    }
}
