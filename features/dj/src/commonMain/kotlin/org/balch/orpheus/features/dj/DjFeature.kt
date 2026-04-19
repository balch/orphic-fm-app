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
        scratch control. While either platter is actively dragged, a four-zone Drop
        strip appears. Dwell 120 ms on a zone to lock in that drop; the four visible
        zones are drawn (weighted) from an 8-drop pool and reshuffle after every drag.

        Drop pool:
        - **FILTER** — resonant LPF sweep.
        - **BRAKE** — vinyl-style slowdown to silence.
        - **STUTTER** — beat-synced gate chops.
        - **FREEZE** — looped slice on the capture buffer.
        - **OCTAVE** — subharmonic bass layer.
        - **PHASER** — swirly phase sweep.
        - **ECHO** — dub-style delay with feedback.
        - **RING** — ring-mod metallic color.

        ## Controls
        - **WET A/B**: Deck dry/wet (0=dry source, 1=turntable only).
        - **SOURCE A/B**: Capture source per deck.
        - **CROSSFADER**: Blend between decks.
        - **DELAY/REVERB SEND**: Post-crossfader effect sends.
        - **DROP A/B**: Per-deck active drop kind (int IDs: 0=none, 1=FILTER, 2=BRAKE,
          3=STUTTER, 4=FREEZE, 5=OCTAVE, 6=PHASER, 7=ECHO, 8=RING).
            """.trimIndent()

            override val portControlKeys = mapOf(
                DjSymbol.WET_A.controlId.key to "Deck A dry/wet (0=dry source, 1=turntable only)",
                DjSymbol.WET_B.controlId.key to "Deck B dry/wet (0=dry source, 1=turntable only)",
                DjSymbol.SOURCE_A.controlId.key to "Deck A capture source (0=Synth, 1=Drums, 2=Bass, 3=Master, 4=Sum)",
                DjSymbol.SOURCE_B.controlId.key to "Deck B capture source (0=Synth, 1=Drums, 2=Bass, 3=Master, 4=Sum)",
                DjSymbol.CROSSFADER.controlId.key to "Crossfader (0=all A, 0.5=center, 1=all B)",
                DjSymbol.DELAY_SEND.controlId.key to "Post-crossfader send to delay (0-1)",
                DjSymbol.REVERB_SEND.controlId.key to "Post-crossfader send to reverb (0-1)",
                DjSymbol.DROP_A.controlId.key to "Deck A active drop (0=none, 1=FILTER, 2=BRAKE, 3=STUTTER, 4=FREEZE, 5=OCTAVE, 6=PHASER, 7=ECHO, 8=RING)",
                DjSymbol.DROP_B.controlId.key to "Deck B active drop (0=none, 1=FILTER, 2=BRAKE, 3=STUTTER, 4=FREEZE, 5=OCTAVE, 6=PHASER, 7=ECHO, 8=RING)",
            )
        }
    }
}
