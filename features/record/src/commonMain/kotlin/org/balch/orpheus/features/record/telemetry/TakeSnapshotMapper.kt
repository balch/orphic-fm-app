package org.balch.orpheus.features.record.telemetry

import org.balch.orpheus.core.audio.EngineCatalog
import org.balch.orpheus.core.audio.EngineChoice
import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.symbols.DelaySymbol
import org.balch.orpheus.core.plugin.symbols.DistortionSymbol
import org.balch.orpheus.core.plugin.symbols.ReverbSymbol
import org.balch.orpheus.core.plugin.symbols.VibratoSymbol
import org.balch.orpheus.core.plugin.symbols.VoiceSymbol
import org.balch.orpheus.core.presets.SynthPreset

private const val PAIRS = 6
private const val QUADS = 3

// Mirrors DspSynthEngine.VOICE_PITCH_MULT_SEMITONES (octave offset per voice).
private val VOICE_OFFSET_SEMITONES = floatArrayOf(-12f, -12f, 0f, 0f, 0f, 0f, 12f, 12f, 0f, 0f, 0f, 0f)

/** MIDI note a voice sounds at for its tune and quad pitch, matching the DSP engine. */
fun soundingMidiNote(voice: Int, tune: Float, quadPitch: Float): Float =
    33f + tune * 48f + VOICE_OFFSET_SEMITONES[voice] + 24f * (quadPitch - 0.5f)

/** Stored mod source (the [ModSource] ordinal) as a readable name. */
fun modSourceName(stored: Int): String {
    val source = ModSource.entries.getOrNull(stored) ?: return "UNKNOWN"
    return if (source == ModSource.VOICE_FM) "FM" else source.name
}

/** Builds a [TakeSnapshot] from preset port values; missing keys use the plugin port defaults. */
fun snapshotOf(preset: SynthPreset): TakeSnapshot {
    // Ints may be stored as IntValue or FloatValue depending on the preset's origin.
    fun float(port: PortSymbol, default: Float) = preset.getFloat(port.controlId.key, default)
    fun int(port: PortSymbol, default: Int): Int {
        val key = port.controlId.key
        return preset.getInt(key, preset.getFloat(key, default.toFloat()).toInt())
    }

    val quads = (0 until QUADS).map { q ->
        QuadSnapshot(
            quad = q,
            pitch = float(VoiceSymbol.quadPitch(q), 0.5f),
            pitchSource = int(VoiceSymbol.quadPitchSource(q), 0),
            triggerSource = int(VoiceSymbol.quadTriggerSource(q), 0),
            hold = float(VoiceSymbol.quadHold(q), 0f),
        )
    }

    val pairs = (0 until PAIRS).map { p ->
        val engineId = int(VoiceSymbol.duoEngine(p), -1)
        val voices = listOf(2 * p, 2 * p + 1).map { v ->
            val tune = float(VoiceSymbol.tune(v), 0.5f)
            VoiceSnapshot(
                voice = v,
                tune = tune,
                midiNote = soundingMidiNote(v, tune, quads[v / 4].pitch),
                envSpeed = float(VoiceSymbol.envSpeed(v), 0f),
            )
        }
        PairSnapshot(
            pair = p,
            engineId = engineId,
            engineName = OrpheusEngineId.fromId(engineId)?.let { EngineCatalog.labelFor(EngineChoice.Engine(it)) } ?: "?",
            harmonics = float(VoiceSymbol.duoHarmonics(p), 0f),
            timbre = float(VoiceSymbol.duoSharpness(p), 0f),
            morph = float(VoiceSymbol.duoMorph(p), 0f),
            modSource = modSourceName(int(VoiceSymbol.duoModSource(p), 0)),
            modSourceLevel = float(VoiceSymbol.duoModSourceLevel(p), 0f),
            voices = voices,
        )
    }

    // Effect defaults copied from DelayPlugin/ReverbPlugin/DistortionPlugin/VibratoPlugin.
    val effects = EffectsSnapshot(
        delayTime1 = float(DelaySymbol.TIME_1, 0.3f),
        delayTime2 = float(DelaySymbol.TIME_2, 0.3f),
        delayFeedback = float(DelaySymbol.FEEDBACK, 0.5f),
        delayMix = float(DelaySymbol.MIX, 0f),
        reverbAmount = float(ReverbSymbol.AMOUNT, 0f),
        distortionDrive = float(DistortionSymbol.DRIVE, 0f),
        vibratoDepth = float(VibratoSymbol.DEPTH, 0f),
    )

    return TakeSnapshot(bpm = preset.bpm, pairs = pairs, quads = quads, effects = effects)
}
