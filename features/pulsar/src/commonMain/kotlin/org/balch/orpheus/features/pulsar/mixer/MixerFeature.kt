package org.balch.orpheus.features.pulsar.mixer

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.features.PanelId
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol

/**
 * Mixer band groups. Track indices match DogHouseVibe band layout
 * (Drummer=0..2, Bassist=3, Keys=4, FX=5..7).
 */
enum class MixerGroup(val tracks: IntArray) {
    PERC(intArrayOf(0, 1, 2)),
    BASS(intArrayOf(3)),
    KEYS(intArrayOf(4)),
    FX(intArrayOf(5, 6, 7)),
}

@Immutable
@Serializable
data class MixerUiState(
    /**
     * Per-group fader *travel* 0..1; index aligns with MixerGroup.ordinal.
     * Console-fader convention: 0.75 = unity (1×), 1.0 = +10 dB (~3.16×).
     * The C++ law in pulsar_fader_to_gain() turns travel into actual gain.
     * Default below is the "unity" point — overwritten almost immediately
     * by the echo flows from each band's controlFlow.
     */
    val groupGains: List<Float> = listOf(0.75f, 0.75f, 0.75f, 0.75f),
    /** Distortion drive 0..1. */
    val drive: Float = 0f,
    /** Master peak meter (0..1+, transient). */
    val peak: Float = 0f,
    /** True when every track in the group is muted (derived from PulsarFeature). */
    val groupMuted: List<Boolean> = listOf(false, false, false, false),
    /** Mirrors PulsarFeature.playing — used to gate the meter loop so meters fall to 0 on stop. */
    val playing: Boolean = false,
)

@Immutable
data class MixerPanelActions(
    val setGroupGain: (MixerGroup, Float) -> Unit,
    val setDrive: (Float) -> Unit,
) {
    companion object {
        val EMPTY = MixerPanelActions({ _, _ -> }, {})
    }
}

interface MixerFeature : SynthFeature<MixerUiState, MixerPanelActions> {
    // Inherits SynthFeature default: WhileSubscribed(5_000). The upstream merge/scan
    // is kept warm by FeatureStatePersistence's long-lived debounced .collect, so
    // Eagerly is unnecessary and would only mask a missing persistence binding.

    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.MIXER
            override val title = "Mix Bridge"

            override val markdown = """
        Five-channel band mixer for the DJApp. PERC/BASS/KEYS/FX faders write
        directly to the underlying per-track volume ports and echo back from
        them, so vibe loads update the faders automatically. DIST drives the
        master distortion stage.

        ## Controls
        - **PERC**: Percussion group volume (PERC_MIX scalar).
        - **BASS**: Bass track volume.
        - **KEYS**: Keys track volume.
        - **FX**: FX group volume (mean displayed; writes set all 3 the same).
        - **DIST**: Master distortion drive amount.
            """.trimIndent()

            override val portControlKeys: Map<String, String> = mapOf(
                PulsarSymbol.PERC_MIX.controlId.key  to "Percussion group gain",
                PulsarSymbol.BASS_GAIN.controlId.key to "Bass user gain",
                PulsarSymbol.KEYS_GAIN.controlId.key to "Keys user gain",
                PulsarSymbol.FX_GAIN.controlId.key   to "FX user gain",
                DistortionSymbol.DRIVE.controlId.key to "Master distortion drive",
            )
        }
    }
}
