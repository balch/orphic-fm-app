package org.balch.orpheus.core.presets

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.audio.HyperLfoMode
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.WarpsSource
import org.balch.orpheus.core.audio.dsp.PortValue

/**
 * New dynamic synth preset structure using generic port values.
 * Replacing the legacy SynthPresetV1.
 */
@Serializable
data class SynthPreset(
    val name: String,
    val bpm: Float = 120f,
    val portValues: Map<String, PortValue> = emptyMap(),
    val createdAt: Long = Clock.System.now().toEpochMilliseconds()
) {
    fun getFloat(key: String, default: Float = 0f): Float =
        (portValues[key] as? PortValue.FloatValue)?.value ?: default

    fun getInt(key: String, default: Int = 0): Int =
        (portValues[key] as? PortValue.IntValue)?.value ?: default

    fun getBool(key: String, default: Boolean = false): Boolean =
        (portValues[key] as? PortValue.BoolValue)?.value ?: default
}

// ═══════════════════════════════════════════════════════════
// LEGACY COMPATIBILITY EXTENSIONS
// These allow ViewModels to access data as if it were the old model,
// but backed by the dynamic map.
// ═══════════════════════════════════════════════════════════

// VOICE (8 voices + 4 pairs + extras)
// Using URI "org.balch.orpheus.plugins.voice"
val SynthPreset.voiceTunes: List<Float>
    get() = List(8) { i -> getFloat("org.balch.orpheus.plugins.voice:tune_$i", 0.5f) }

val SynthPreset.voiceModDepths: List<Float>
    get() = List(8) { i -> getFloat("org.balch.orpheus.plugins.voice:mod_depth_$i", 0.0f) }

val SynthPreset.voiceEnvelopeSpeeds: List<Float>
    get() = List(8) { i -> getFloat("org.balch.orpheus.plugins.voice:env_speed_$i", 0.0f) }

val SynthPreset.pairSharpness: List<Float>
    get() = List(4) { i -> getFloat("org.balch.orpheus.plugins.voice:pair_sharpness_$i", 0.0f) }

val SynthPreset.duoModSources: List<ModSource>
    get() = List(4) { i ->
        val ordinal = getInt("org.balch.orpheus.plugins.voice:duo_mod_source_$i", 0)
        ModSource.entries.getOrElse(ordinal) { ModSource.OFF }
    }

// HYPER LFO
// Using URI "org.balch.orpheus.plugins.duolfo"
val SynthPreset.hyperLfoA: Float get() = getFloat("org.balch.orpheus.plugins.duolfo:freq_a", 0.0f)
val SynthPreset.hyperLfoB: Float get() = getFloat("org.balch.orpheus.plugins.duolfo:freq_b", 0.0f)
val SynthPreset.hyperLfoMode: HyperLfoMode
    get() {
        val ordinal = getInt("org.balch.orpheus.plugins.duolfo:mode", 0)
        return HyperLfoMode.entries.getOrElse(ordinal) { HyperLfoMode.OFF }
    }
val SynthPreset.hyperLfoLink: Boolean get() = getBool("org.balch.orpheus.plugins.duolfo:link", false)

// DELAY
// Using URI "org.balch.orpheus.plugins.delay"
val SynthPreset.delayTime1: Float get() = getFloat("org.balch.orpheus.plugins.delay:time_1", 0.3f)
val SynthPreset.delayTime2: Float get() = getFloat("org.balch.orpheus.plugins.delay:time_2", 0.3f)
val SynthPreset.delayMod1: Float get() = getFloat("org.balch.orpheus.plugins.delay:mod_depth_1", 0.0f)
val SynthPreset.delayMod2: Float get() = getFloat("org.balch.orpheus.plugins.delay:mod_depth_2", 0.0f)
val SynthPreset.delayFeedback: Float get() = getFloat("org.balch.orpheus.plugins.delay:feedback", 0.5f)
val SynthPreset.delayMix: Float get() = getFloat("org.balch.orpheus.plugins.delay:mix", 0.5f)
val SynthPreset.delayModSourceIsLfo: Boolean get() = getBool("org.balch.orpheus.plugins.delay:mod_source_is_lfo", true)
val SynthPreset.delayLfoWaveformIsTriangle: Boolean get() = getBool("org.balch.orpheus.plugins.delay:lfo_wave_is_triangle", true)

// GLOBAL
// Using URI "org.balch.orpheus.plugins.distortion" for drive/mix
val SynthPreset.drive: Float get() = getFloat("org.balch.orpheus.plugins.distortion:drive", 0.0f)
val SynthPreset.distortionMix: Float get() = getFloat("org.balch.orpheus.plugins.distortion:mix", 0.5f)

// ADVANCED VOICE
// Using URI "org.balch.orpheus.plugins.voice"
val SynthPreset.fmStructureCrossQuad: Boolean get() = getBool("org.balch.orpheus.plugins.voice:fm_structure_cross_quad", false)
val SynthPreset.totalFeedback: Float get() = getFloat("org.balch.orpheus.plugins.voice:total_feedback", 0.0f)
val SynthPreset.vibrato: Float get() = getFloat("org.balch.orpheus.plugins.voice:vibrato", 0.0f)
val SynthPreset.voiceCoupling: Float get() = getFloat("org.balch.orpheus.plugins.voice:coupling", 0.0f)

val SynthPreset.quadGroupPitches: List<Float>
    get() = List(3) { i -> getFloat("org.balch.orpheus.plugins.voice:quad_pitch_$i", 0.5f) }
val SynthPreset.quadGroupHolds: List<Float>
    get() = List(3) { i -> getFloat("org.balch.orpheus.plugins.voice:quad_hold_$i", 0.0f) }
val SynthPreset.quadGroupVolumes: List<Float>
    get() = List(3) { i -> getFloat("org.balch.orpheus.plugins.voice:quad_volume_$i", 1.0f) }
val SynthPreset.quadTriggerSources: List<Int>
    get() = List(3) { i -> getInt("org.balch.orpheus.plugins.voice:quad_trigger_source_$i", 0) }
val SynthPreset.quadPitchSources: List<Int>
    get() = List(3) { i -> getInt("org.balch.orpheus.plugins.voice:quad_pitch_source_$i", 0) }
val SynthPreset.quadEnvelopeTriggerModes: List<Boolean>
    get() = List(3) { i -> getBool("org.balch.orpheus.plugins.voice:quad_env_trigger_mode_$i", false) }

// RESONATOR
// Using URI "org.balch.orpheus.plugins.resonator"
val SynthPreset.resonatorMode: Int get() = getInt("org.balch.orpheus.plugins.resonator:mode", 0)
val SynthPreset.resonatorTargetMix: Float get() = getFloat("org.balch.orpheus.plugins.resonator:target_mix", 0.5f)
val SynthPreset.resonatorStructure: Float get() = getFloat("org.balch.orpheus.plugins.resonator:structure", 0.25f)
val SynthPreset.resonatorBrightness: Float get() = getFloat("org.balch.orpheus.plugins.resonator:brightness", 0.5f)
val SynthPreset.resonatorDamping: Float get() = getFloat("org.balch.orpheus.plugins.resonator:damping", 0.3f)
val SynthPreset.resonatorPosition: Float get() = getFloat("org.balch.orpheus.plugins.resonator:position", 0.5f)
val SynthPreset.resonatorMix: Float get() = getFloat("org.balch.orpheus.plugins.resonator:mix", 0.0f)
val SynthPreset.resonatorSnapBack: Boolean get() = getBool("org.balch.orpheus.plugins.resonator:snap_back", false)

// DRUMS (808)
// Using URI "org.balch.orpheus.plugins.drum"
val SynthPreset.drumBdFrequency: Float get() = getFloat("org.balch.orpheus.plugins.drum:bd_freq", 0.6f)
val SynthPreset.drumBdTone: Float get() = getFloat("org.balch.orpheus.plugins.drum:bd_tone", 0.5f)
val SynthPreset.drumBdDecay: Float get() = getFloat("org.balch.orpheus.plugins.drum:bd_decay", 0.5f)
val SynthPreset.drumBdP4: Float get() = getFloat("org.balch.orpheus.plugins.drum:bd_p4", 0.5f)
val SynthPreset.drumBdP5: Float get() = getFloat("org.balch.orpheus.plugins.drum:bd_p5", 0.5f)

val SynthPreset.drumSdFrequency: Float get() = getFloat("org.balch.orpheus.plugins.drum:sd_freq", 0.3f)
val SynthPreset.drumSdTone: Float get() = getFloat("org.balch.orpheus.plugins.drum:sd_tone", 0.5f)
val SynthPreset.drumSdDecay: Float get() = getFloat("org.balch.orpheus.plugins.drum:sd_decay", 0.5f)
val SynthPreset.drumSdP4: Float get() = getFloat("org.balch.orpheus.plugins.drum:sd_p4", 0.5f)

val SynthPreset.drumHhFrequency: Float get() = getFloat("org.balch.orpheus.plugins.drum:hh_freq", 0.3f)
val SynthPreset.drumHhTone: Float get() = getFloat("org.balch.orpheus.plugins.drum:hh_tone", 0.5f)
val SynthPreset.drumHhDecay: Float get() = getFloat("org.balch.orpheus.plugins.drum:hh_decay", 0.5f)
val SynthPreset.drumHhP4: Float get() = getFloat("org.balch.orpheus.plugins.drum:hh_p4", 0.5f)

val SynthPreset.drumBdTriggerSource: Int get() = getInt("org.balch.orpheus.plugins.drum:bd_trigger_src", 0)
val SynthPreset.drumBdPitchSource: Int get() = getInt("org.balch.orpheus.plugins.drum:bd_pitch_src", 0)
val SynthPreset.drumSdTriggerSource: Int get() = getInt("org.balch.orpheus.plugins.drum:sd_trigger_src", 0)
val SynthPreset.drumSdPitchSource: Int get() = getInt("org.balch.orpheus.plugins.drum:sd_pitch_src", 0)
val SynthPreset.drumHhTriggerSource: Int get() = getInt("org.balch.orpheus.plugins.drum:hh_trigger_src", 0)
val SynthPreset.drumHhPitchSource: Int get() = getInt("org.balch.orpheus.plugins.drum:hh_pitch_src", 0)

val SynthPreset.drumsBypass: Boolean get() = getBool("org.balch.orpheus.plugins.drum:bypass", true)

// DRUM BEATS (Grids)
// Using URI "org.balch.orpheus.plugins.drum" ? Or beats? Let's use "org.balch.orpheus.plugins.beats"
val SynthPreset.beatsX: Float get() = getFloat("org.balch.orpheus.plugins.beats:x", 0.5f)
val SynthPreset.beatsY: Float get() = getFloat("org.balch.orpheus.plugins.beats:y", 0.5f)
val SynthPreset.beatsDensities: List<Float>
    get() = List(3) { i -> getFloat("org.balch.orpheus.plugins.beats:density_$i", 0.5f) }
val SynthPreset.beatsOutputMode: Int get() = getInt("org.balch.orpheus.plugins.beats:output_mode", 0)
val SynthPreset.beatsEuclideanLengths: List<Int>
    get() = List(3) { i -> getInt("org.balch.orpheus.plugins.beats:euclid_len_$i", 16) }
val SynthPreset.beatsRandomness: Float get() = getFloat("org.balch.orpheus.plugins.beats:randomness", 0f)
val SynthPreset.beatsSwing: Float get() = getFloat("org.balch.orpheus.plugins.beats:swing", 0f)
val SynthPreset.beatsMix: Float get() = getFloat("org.balch.orpheus.plugins.beats:mix", 0.7f)

// GRAINS
// Using URI "org.balch.orpheus.plugins.grains"
val SynthPreset.grainsPosition: Float get() = getFloat("org.balch.orpheus.plugins.grains:position", 0.2f)
val SynthPreset.grainsSize: Float get() = getFloat("org.balch.orpheus.plugins.grains:size", 0.5f)
val SynthPreset.grainsPitch: Float get() = getFloat("org.balch.orpheus.plugins.grains:pitch", 0.0f)
val SynthPreset.grainsDensity: Float get() = getFloat("org.balch.orpheus.plugins.grains:density", 0.5f)
val SynthPreset.grainsTexture: Float get() = getFloat("org.balch.orpheus.plugins.grains:texture", 0.5f)
val SynthPreset.grainsDryWet: Float get() = getFloat("org.balch.orpheus.plugins.grains:dry_wet", 0.0f)
val SynthPreset.grainsFreeze: Boolean get() = getBool("org.balch.orpheus.plugins.grains:freeze", false)
val SynthPreset.grainsMode: Int get() = getInt("org.balch.orpheus.plugins.grains:mode", 0)

// WARPS
// Using URI "org.balch.orpheus.plugins.warps"
val SynthPreset.warpsAlgorithm: Float get() = getFloat("org.balch.orpheus.plugins.warps:algorithm", 0.0f)
val SynthPreset.warpsTimbre: Float get() = getFloat("org.balch.orpheus.plugins.warps:timbre", 0.5f)
val SynthPreset.warpsCarrierLevel: Float get() = getFloat("org.balch.orpheus.plugins.warps:level1", 0.5f)
val SynthPreset.warpsModulatorLevel: Float get() = getFloat("org.balch.orpheus.plugins.warps:level2", 0.5f)
val SynthPreset.warpsCarrierSource: WarpsSource
    get() {
        val ordinal = getInt("org.balch.orpheus.plugins.warps:source1", 0)
        return WarpsSource.entries.getOrElse(ordinal) { WarpsSource.SYNTH }
    }
val SynthPreset.warpsModulatorSource: WarpsSource
    get() {
        val ordinal = getInt("org.balch.orpheus.plugins.warps:source2", 0)
        return WarpsSource.entries.getOrElse(ordinal) { WarpsSource.DRUMS }
    }
val SynthPreset.warpsMix: Float get() = getFloat("org.balch.orpheus.plugins.warps:mix", 0.0f)

// FLUX
// Using URI "org.balch.orpheus.plugins.flux"
val SynthPreset.fluxSpread: Float get() = getFloat("org.balch.orpheus.plugins.flux:spread", 0.5f)
val SynthPreset.fluxBias: Float get() = getFloat("org.balch.orpheus.plugins.flux:bias", 0.5f)
val SynthPreset.fluxSteps: Float get() = getFloat("org.balch.orpheus.plugins.flux:steps", 0.5f)
val SynthPreset.fluxDejaVu: Float get() = getFloat("org.balch.orpheus.plugins.flux:dejavu", 0.0f)
val SynthPreset.fluxLength: Int get() = getInt("org.balch.orpheus.plugins.flux:length", 8)
val SynthPreset.fluxScale: Int get() = getInt("org.balch.orpheus.plugins.flux:scale", 0)
val SynthPreset.fluxRate: Float get() = getFloat("org.balch.orpheus.plugins.flux:rate", 0.5f)
val SynthPreset.fluxJitter: Float get() = getFloat("org.balch.orpheus.plugins.flux:jitter", 0.0f)
val SynthPreset.fluxProbability: Float get() = getFloat("org.balch.orpheus.plugins.flux:probability", 0.5f)
val SynthPreset.fluxClockSource: Int get() = getInt("org.balch.orpheus.plugins.flux:clock_source", 0)
val SynthPreset.fluxGateLength: Float get() = getFloat("org.balch.orpheus.plugins.flux:gatelength", 0.5f)
