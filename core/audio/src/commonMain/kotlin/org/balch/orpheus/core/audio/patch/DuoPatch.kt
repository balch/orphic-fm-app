package org.balch.orpheus.core.audio.patch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.balch.orpheus.core.audio.OrpheusEngineId

enum class PatchFamily { LFO, WAVES, FLUX, FM }

/** Declaration order is the C++ source index. Do not reorder. */
enum class PatchSource(val family: PatchFamily, val label: String) {
    LFO_AB(PatchFamily.LFO, "A·B"),
    LFO_A(PatchFamily.LFO, "A"),
    LFO_B(PatchFamily.LFO, "B"),
    LFO_A90(PatchFamily.LFO, "A 90°"),
    WAVES_1(PatchFamily.WAVES, "1"),
    WAVES_2(PatchFamily.WAVES, "2"),
    WAVES_3(PatchFamily.WAVES, "3"),
    WAVES_4(PatchFamily.WAVES, "4"),
    FLUX_X1(PatchFamily.FLUX, "X1"),
    FLUX_X2(PatchFamily.FLUX, "X2"),
    FLUX_X3(PatchFamily.FLUX, "X3"),
    FLUX_Y(PatchFamily.FLUX, "Y"),
    FLUX_T1(PatchFamily.FLUX, "T1"),
    FLUX_T2(PatchFamily.FLUX, "T2"),
    FLUX_T3(PatchFamily.FLUX, "T3"),
    VOICE_FM(PatchFamily.FM, "FM"),
}

/** Declaration order is the C++ destination index. [fullScale] is the value at amount 1.0. */
enum class PatchDest(val perVoice: Boolean, val fullScale: Float) {
    HARMONICS(false, 1f),
    TIMBRE(false, 1f),
    MORPH(false, 1f),
    FM(false, 400f),
    PITCH(true, 12f),
    ENV(true, 1f),
}

enum class VoiceSlot { BOTH, A, B }

@Serializable
data class PatchCable(
    val source: PatchSource,
    val dest: PatchDest,
    val slot: VoiceSlot = VoiceSlot.BOTH,
    val amount: Float,
    val parked: Boolean = false,
)

@Serializable
data class DuoPatch(
    val cables: List<PatchCable> = emptyList(),
    val pitchBridged: Boolean = true,
    val envBridged: Boolean = true,
) {
    private fun bridged(dest: PatchDest): Boolean = when (dest) {
        PatchDest.PITCH -> pitchBridged
        PatchDest.ENV -> envBridged
        else -> true
    }

    /** The concrete slots a request for [slot] on [dest] touches. */
    private fun slotsFor(dest: PatchDest, slot: VoiceSlot): List<VoiceSlot> = when {
        bridged(dest) -> listOf(VoiceSlot.BOTH)
        slot == VoiceSlot.BOTH -> listOf(VoiceSlot.A, VoiceSlot.B)
        else -> listOf(slot)
    }

    private inline fun mapSlots(
        source: PatchSource,
        dest: PatchDest,
        slot: VoiceSlot,
        edit: (PatchCable) -> PatchCable?,
    ): DuoPatch {
        val slots = slotsFor(dest, slot)
        val out = cables.mapNotNull { c ->
            if (c.source == source && c.dest == dest && c.slot in slots) edit(c) else c
        }
        return copy(cables = out)
    }

    fun connect(
        source: PatchSource,
        dest: PatchDest,
        slot: VoiceSlot = VoiceSlot.BOTH,
        amount: Float,
    ): DuoPatch {
        val a = amount.sanitized()
        var out = cables
        for (s in slotsFor(dest, slot)) {
            val cable = PatchCable(source, dest, s, a)
            val i = out.indexOfFirst { it.source == source && it.dest == dest && it.slot == s }
            out = if (i >= 0) out.toMutableList().also { it[i] = cable } else out + cable
        }
        return copy(cables = out)
    }

    fun disconnect(source: PatchSource, dest: PatchDest, slot: VoiceSlot): DuoPatch =
        mapSlots(source, dest, slot) { null }

    fun setAmount(source: PatchSource, dest: PatchDest, slot: VoiceSlot, amount: Float): DuoPatch =
        mapSlots(source, dest, slot) { it.copy(amount = amount.sanitized()) }

    fun wake(source: PatchSource, dest: PatchDest, slot: VoiceSlot): DuoPatch =
        mapSlots(source, dest, slot) { it.copy(parked = false) }

    /** Bridge merges by source with A winning; split turns BOTH into A and B at the same amount. */
    fun setBridged(dest: PatchDest, bridged: Boolean): DuoPatch {
        if (!dest.perVoice || bridged(dest) == bridged) return this
        val rest = cables.filter { it.dest != dest }
        val newRow = convertRow(dest, bridged)
        return when (dest) {
            PatchDest.PITCH -> copy(cables = rest + newRow, pitchBridged = bridged)
            else -> copy(cables = rest + newRow, envBridged = bridged)
        }
    }

    /** The [dest] row rewritten for a bridged or split layout. */
    private fun convertRow(dest: PatchDest, toBridged: Boolean): List<PatchCable> {
        val row = cables.filter { it.dest == dest }
        return if (toBridged) {
            row.map { it.source }.distinct().map { src ->
                val a = row.firstOrNull { it.source == src && it.slot == VoiceSlot.A }
                (a ?: row.first { it.source == src }).copy(slot = VoiceSlot.BOTH)
            }
        } else {
            row.flatMap { listOf(it.copy(slot = VoiceSlot.A), it.copy(slot = VoiceSlot.B)) }
                .distinctBy { Triple(it.source, it.dest, it.slot) }
        }
    }

    /** Dedupes by cable key (first wins) and fixes any row whose slots break its layout. */
    private fun normalized(): DuoPatch {
        var p = copy(cables = cables.distinctBy { Triple(it.source, it.dest, it.slot) })
        for (dest in listOf(PatchDest.PITCH, PatchDest.ENV)) {
            val isBridged = p.bridged(dest)
            val broken = p.cables.any {
                it.dest == dest && (it.slot != VoiceSlot.BOTH) == isBridged
            }
            if (broken) {
                p = p.copy(cables = p.cables.filter { it.dest != dest } + p.convertRow(dest, isBridged))
            }
        }
        return p
    }

    /** DX engines pin harmonics, so arriving on one parks HARMONICS cables and leaving wakes them. */
    fun onEngineChange(from: OrpheusEngineId?, to: OrpheusEngineId?): DuoPatch {
        val fromDx = from?.forcePinHarmonics == true
        val toDx = to?.forcePinHarmonics == true
        if (fromDx == toDx) return this
        return copy(cables = cables.map {
            if (it.dest == PatchDest.HARMONICS) it.copy(parked = toDx) else it
        })
    }

    fun encode(): String = codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }

        /** Null for unparseable text. Cables with unknown enum names are dropped. */
        fun decode(text: String): DuoPatch? {
            val root = try {
                codec.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            val cables = (root["cables"] as? JsonArray).orEmpty().mapNotNull { el ->
                try {
                    codec.decodeFromJsonElement(PatchCable.serializer(), el)
                        .let { it.copy(amount = it.amount.sanitized()) }
                } catch (_: Exception) {
                    null
                }
            }
            fun flag(key: String) = (root[key] as? JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() ?: true
            return DuoPatch(cables, flag("pitchBridged"), flag("envBridged")).normalized()
        }
    }
}

private fun Float.sanitized(): Float = if (isFinite()) coerceIn(-1f, 1f) else 0f

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

/** Braids ignores timbre and Chaos has neither timbre nor pitch; FM exists only on OSC. */
fun PatchDest.reachesEngine(engine: OrpheusEngineId): Boolean {
    val braids = engine.id in 100..199
    val chaos = engine.id in 200..204
    return when (this) {
        PatchDest.FM -> engine == OrpheusEngineId.OSC
        PatchDest.TIMBRE -> engine != OrpheusEngineId.OSC && !braids && !chaos
        PatchDest.PITCH -> !chaos
        else -> true
    }
}

fun DuoPatch.modulatedDests(engine: OrpheusEngineId): Set<PatchDest> =
    cables.filter { !it.parked && it.dest.reachesEngine(engine) }.map { it.dest }.toSet()

/** Live per-voice dests ([PatchDest.perVoice]) that reach voice [slot] (A or B); BOTH cables count for each. */
fun DuoPatch.modulatedVoiceDests(engine: OrpheusEngineId, slot: VoiceSlot): Set<PatchDest> =
    cables.filter {
        !it.parked && it.dest.perVoice && it.dest.reachesEngine(engine) &&
            (it.slot == VoiceSlot.BOTH || it.slot == slot)
    }.map { it.dest }.toSet()
