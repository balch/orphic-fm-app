package org.balch.orpheus.djapp.vibepresets

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PluginControlId
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.HornSymbol
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.mixer.TrackGainSink
import org.balch.orpheus.features.pulsar.mixer.TrackTuning
import kotlin.math.roundToInt

/**
 * Reads the live value of every [PresetField] and writes a preset's values back. Both are
 * synchronous: the preset ViewModel calls them from inside the vibe-applied listener.
 *
 * TRACKS fields only exist when [captureTracks] is set (builds that show the TRACKS view).
 */
class VibePresetPorts(
    private val synth: SynthController,
    private val pulsar: PulsarFeature,
    private val tracks: TrackGainSink,
    private val captureTracks: Boolean,
) {
    private fun flow(id: PluginControlId) = synth.controlFlow(id)

    private val perc = flow(PulsarSymbol.PERC_MIX.controlId)
    private val bass = flow(PulsarSymbol.BASS_GAIN.controlId)
    private val keys = flow(PulsarSymbol.KEYS_GAIN.controlId)
    private val fx = flow(PulsarSymbol.FX_GAIN.controlId)
    private val drive = flow(DistortionSymbol.DRIVE.controlId)
    // DRIVE and MIX are written together, as MixerViewModel does.
    private val distMix = flow(DistortionSymbol.MIX.controlId)

    private val hornSpeed = flow(HornSymbol.SPEED.controlId)
    private val hornRatio = flow(HornSymbol.RATIO.controlId)
    private val hornDepth = flow(HornSymbol.DEPTH.controlId)
    private val hornMix = flow(HornSymbol.MIX.controlId)
    private val hornBrake = flow(HornSymbol.BRAKE.controlId)

    private val energy = flow(PulsarSymbol.ENERGY.controlId)
    private val complexity = flow(PulsarSymbol.COMPLEXITY.controlId)
    private val mood = flow(PulsarSymbol.MOOD.controlId)
    private val space = flow(PulsarSymbol.SPACE.controlId)
    private val deep = flow(PulsarSymbol.DEEP.controlId)
    private val root = flow(PulsarSymbol.ROOT_NOTE.controlId)
    private val scale = flow(PulsarSymbol.SCALE.controlId)
    private val envelope = flow(PulsarSymbol.ENVELOPE_MODE.controlId)

    private fun floatPort(flow: MutableStateFlow<PortValue>): (Float) -> Unit =
        { flow.value = FloatValue(it) }

    private fun intPort(flow: MutableStateFlow<PortValue>): (Float) -> Unit =
        { flow.value = IntValue(it.roundToInt()) }

    private class Binding(val read: () -> Float, val write: (Float) -> Unit)

    private fun port(flow: MutableStateFlow<PortValue>, write: (Float) -> Unit) =
        Binding({ flow.value.asFloat() }, write)

    private val bindings: Map<PresetField, Binding> = mapOf(
        PresetField.MIX_PERC to port(perc, floatPort(perc)),
        PresetField.MIX_BASS to port(bass, floatPort(bass)),
        PresetField.MIX_KEYS to port(keys, floatPort(keys)),
        PresetField.MIX_FX to port(fx, floatPort(fx)),
        PresetField.MIX_DIST to port(drive) {
            drive.value = FloatValue(it)
            distMix.value = FloatValue(it)
        },
        PresetField.HORN_SPEED to port(hornSpeed, floatPort(hornSpeed)),
        PresetField.HORN_RATIO to port(hornRatio, floatPort(hornRatio)),
        PresetField.HORN_DEPTH to port(hornDepth, floatPort(hornDepth)),
        PresetField.HORN_MIX to port(hornMix, floatPort(hornMix)),
        PresetField.HORN_BRAKE to port(hornBrake, intPort(hornBrake)),
        // During an accelerando the ramp pre-sets the section multiplier to 1.0 while the live tempo
        // climbs, so baseBpm drifts and the now-dot can flicker until the ramp lands.
        PresetField.PULSAR_BPM to Binding({ pulsar.baseBpm }, { pulsar.setBaseBpm(it) }),
        PresetField.PULSAR_ENERGY to port(energy, floatPort(energy)),
        PresetField.PULSAR_COMPLEXITY to port(complexity, floatPort(complexity)),
        PresetField.PULSAR_MOOD to port(mood, floatPort(mood)),
        // The actions also push the effective sends, so the ports alone would leave them stale.
        PresetField.PULSAR_SPACE to port(space) { pulsar.actions.setSpace(it) },
        PresetField.PULSAR_DEEP to port(deep) { pulsar.actions.setDeep(it) },
        PresetField.PULSAR_ENVELOPE to port(envelope, intPort(envelope)),
        PresetField.PULSAR_ROOT to port(root, intPort(root)),
        PresetField.PULSAR_SCALE to port(scale, intPort(scale)),
    )

    private val trackKeys: Set<String> = PresetField.entries
        .map { it.key }
        .filter { it.startsWith(TRACKS_PREFIX) }
        .toSet()

    /**
     * Fires when any captured port, the base tempo or the track tuning moves. Each source is a
     * StateFlow, so every one also fires once on collect.
     */
    val changes: Flow<Unit> = (
        listOf<Flow<Any>>(
            perc, bass, keys, fx, drive,
            hornSpeed, hornRatio, hornDepth, hornMix, hornBrake,
            energy, complexity, mood, space, deep, root, scale, envelope,
            pulsar.stateFlow.map { it.bpm }.distinctUntilChanged(),
        ) + if (captureTracks) listOf(tracks.state) else emptyList()
    ).map { source -> source.map { } }.merge()

    /** Every field's live value; the tracks fields come from [tuning] and only when captured. */
    fun read(tuning: TrackTuning): Map<String, Float> = buildMap {
        for ((field, binding) in bindings) put(field.key, binding.read())
        if (captureTracks) {
            for (t in 0 until TrackTuning.TRACK_COUNT) {
                put("tracks.$t.level", tuning.levels[t])
                put("tracks.$t.mute", if (tuning.muted[t]) 1f else 0f)
                put("tracks.$t.solo", if (tuning.soloed[t]) 1f else 0f)
            }
        }
    }

    /** Writes only the known keys present in [values]; everything else is left alone. */
    fun write(values: Map<String, Float>) {
        for ((key, value) in values) {
            val field = PresetField.byKey(key) ?: continue
            bindings[field]?.write?.invoke(value)
        }
        if (captureTracks && values.keys.any { it in trackKeys }) tracks.load(tuningFrom(values))
    }

    private fun tuningFrom(values: Map<String, Float>): TrackTuning {
        var tuning = TrackTuning()
        for (t in 0 until TrackTuning.TRACK_COUNT) {
            values["tracks.$t.level"]?.let { tuning = tuning.withLevel(t, it) }
            if (values["tracks.$t.mute"]?.let { it > 0.5f } == true) tuning = tuning.toggleMute(t)
            if (values["tracks.$t.solo"]?.let { it > 0.5f } == true) tuning = tuning.toggleSolo(t)
        }
        return tuning
    }

    private companion object {
        const val TRACKS_PREFIX = "tracks."
    }
}
