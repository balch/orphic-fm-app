package org.balch.orpheus.features.record.telemetry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.LegacyModSource
import org.balch.orpheus.core.audio.patch.legacyDuoPatch

/** Contents of `telemetry.json` for one recorded take. */
@Serializable
data class TakeTelemetry(
    val version: Int = 1,
    val takeId: String,
    val app: String = "orpheus-desktop",
    val sampleRate: Int,
    val durationSec: Float,
    val droppedFrames: Long,
    val start: TakeSnapshot,
    val end: TakeSnapshot,
    val events: List<TakeEvent>,
)

/** A timestamped event; [t] is seconds since record start. */
@Serializable
sealed interface TakeEvent {
    val t: Float
}

@Serializable
@SerialName("gateOn")
data class GateOn(override val t: Float, val voice: Int, val midiNote: Float) : TakeEvent

@Serializable
@SerialName("gateOff")
data class GateOff(override val t: Float, val voice: Int) : TakeEvent

@Serializable
@SerialName("control")
data class Control(override val t: Float, val key: String, val value: Float) : TakeEvent

@Serializable
@SerialName("midiNoteOn")
data class MidiNoteOn(override val t: Float, val note: Int, val velocity: Int, val voice: Int?) : TakeEvent

@Serializable
@SerialName("midiNoteOff")
data class MidiNoteOff(override val t: Float, val note: Int, val voice: Int?) : TakeEvent

/** Fills cables from the old `modSource` name on takes recorded before duo patches. */
fun TakeTelemetry.upgraded(): TakeTelemetry = copy(start = start.upgraded(), end = end.upgraded())

private fun TakeSnapshot.upgraded(): TakeSnapshot = copy(pairs = pairs.map { it.upgraded() })

private fun PairSnapshot.upgraded(): PairSnapshot {
    if (cables.isNotEmpty() || modSource == null) return this
    val legacy = when (modSource) {
        "FM" -> LegacyModSource.VOICE_FM
        else -> LegacyModSource.entries.firstOrNull { it.name == modSource && it != LegacyModSource.VOICE_FM }
    } ?: return copy(modSource = null)
    val onDx = OrpheusEngineId.fromId(engineId)?.forcePinHarmonics == true
    return copy(cables = legacyDuoPatch(legacy, onDx, pair).cables, modSource = null)
}

/** Decodes a take's telemetry.json, upgrading old takes. */
fun decodeTelemetry(text: String): TakeTelemetry =
    TelemetryJson.decodeFromString(TakeTelemetry.serializer(), text).upgraded()

val TelemetryJson: Json = Json {
    classDiscriminator = "type"
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}
