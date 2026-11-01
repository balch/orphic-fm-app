package org.balch.orpheus.features.record.telemetry

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import org.balch.orpheus.core.audio.patch.PatchCable

/** Synth state at one instant (take start or end), flattened for the skill to read. */
@Serializable
data class TakeSnapshot(
    val bpm: Float,
    val pairs: List<PairSnapshot>,
    val quads: List<QuadSnapshot>,
    val effects: EffectsSnapshot,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PairSnapshot(
    val pair: Int,
    val engineId: Int,
    val engineName: String,
    val harmonics: Float,
    val timbre: Float,
    val morph: Float,
    val cables: List<PatchCable> = emptyList(),
    /** Read-only: the mod source name old takes wrote; [upgraded] turns it into [cables]. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val modSource: String? = null,
    val modSourceLevel: Float,
    val voices: List<VoiceSnapshot>,
)

@Serializable
data class VoiceSnapshot(val voice: Int, val tune: Float, val midiNote: Float, val envSpeed: Float)

@Serializable
data class QuadSnapshot(
    val quad: Int,
    val pitch: Float,
    val pitchSource: Int,
    val triggerSource: Int,
    val hold: Float,
)

@Serializable
data class EffectsSnapshot(
    val delayTime1: Float,
    val delayTime2: Float,
    val delayFeedback: Float,
    val delayMix: Float,
    val reverbAmount: Float,
    val distortionDrive: Float,
    val vibratoDepth: Float,
)
