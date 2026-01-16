package org.balch.orpheus.core.presets

import kotlinx.serialization.Serializable
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.features.lfo.HyperLfoMode
import org.balch.orpheus.util.currentTimeMillis

/**
 * Represents a saved Orpheus Drone preset containing all synthesizer parameters.
 */
@Serializable
data class DronePreset(
    val name: String,

    // Voice parameters (8 voices)
    val voiceTunes: List<Float> = List(8) { 0.5f },
    val voiceModDepths: List<Float> = List(8) { 0.0f },
    val voiceEnvelopeSpeeds: List<Float> = List(8) { 0.0f },

    // Pair parameters (4 pairs)
    val pairSharpness: List<Float> = List(4) { 0.0f },
    val duoModSources: List<ModSource> = List(4) { ModSource.OFF },

    // Hyper LFO
    val hyperLfoA: Float = 0.0f,
    val hyperLfoB: Float = 0.0f,
    val hyperLfoMode: HyperLfoMode = HyperLfoMode.OFF,
    val hyperLfoLink: Boolean = false,

    // Delay
    val delayTime1: Float = 0.3f,
    val delayTime2: Float = 0.3f,
    val delayMod1: Float = 0.0f,
    val delayMod2: Float = 0.0f,
    val delayFeedback: Float = 0.5f,
    val delayMix: Float = 0.5f,
    val delayModSourceIsLfo: Boolean = true,
    val delayLfoWaveformIsTriangle: Boolean = true,

    // Global
    val drive: Float = 0.0f,
    val distortionMix: Float = 0.5f,

    // Advanced
    val fmStructureCrossQuad: Boolean = false,
    val totalFeedback: Float = 0.0f,
    val vibrato: Float = 0.0f,
    val voiceCoupling: Float = 0.0f,
    val quadGroupPitches: List<Float> = List(3) { 0.5f },
    val quadGroupHolds: List<Float> = List(3) { 0.0f },
    val quadGroupVolumes: List<Float> = List(3) { 1.0f },

    // Resonator (Rings)
    val resonatorMode: Int = 0,        // 0=Modal, 1=String, 2=Sympathetic
    val resonatorTargetMix: Float = 0.5f, // 0=Drums only, 0.5=Both, 1=Synth only
    val resonatorStructure: Float = 0.25f,
    val resonatorBrightness: Float = 0.5f,
    val resonatorDamping: Float = 0.3f,
    val resonatorPosition: Float = 0.5f,
    val resonatorMix: Float = 0.5f,
    val resonatorSnapBack: Boolean = false,

    // Drums (808-style sound params)
    val drumBdFrequency: Float = 0.3f,
    val drumBdTone: Float = 0.5f,
    val drumBdDecay: Float = 0.5f,
    val drumBdP4: Float = 0.5f,
    val drumBdP5: Float = 0.5f,
    
    val drumSdFrequency: Float = 0.4f,
    val drumSdTone: Float = 0.5f,
    val drumSdDecay: Float = 0.5f,
    val drumSdP4: Float = 0.5f,

    val drumHhFrequency: Float = 0.6f,
    val drumHhTone: Float = 0.5f,
    val drumHhDecay: Float = 0.5f,
    val drumHhP4: Float = 0.5f,

    // Drum Beats (Grids-style pattern generator)
    val beatsX: Float = 0.5f,
    val beatsY: Float = 0.5f,
    val beatsDensities: List<Float> = listOf(0.5f, 0.5f, 0.5f),
    val beatsBpm: Float = 120f,
    val beatsOutputMode: Int = 0,      // DrumBeatsGenerator.OutputMode ordinal
    val beatsEuclideanLengths: List<Int> = listOf(16, 16, 16),
    val beatsRandomness: Float = 0f,
    val beatsSwing: Float = 0f,
    val beatsMix: Float = 0.7f,

    // Metadata
    val createdAt: Long = currentTimeMillis()
)
