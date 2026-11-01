package org.balch.orpheus.core.audio

import org.balch.orpheus.core.audio.OrpheusEngineId.*

/** Picker chips. [DX_PATCHES] holds the 96 six-op patches rather than bare engines. */
enum class EngineCategory(val label: String) {
    ANALOG("Analog"),
    HARMONIC("Harmonic"),
    TEXTURE("Texture"),
    PHYSICAL("Physical"),
    VOICE("Voice"),
    BRAIDS("Braids"),
    CHAOS("Chaos"),
    DRUMS("Drums"),
    DX_PATCHES("DX Patches"),
}

/** One patch of a DX bank. [harmonics] is the bucket centre the engine decodes back to [index]. */
data class DxPatch(val engine: OrpheusEngineId, val index: Int, val name: String, val family: String) {
    val harmonics: Float get() = (index + 0.5f) / 32.64f
}

sealed interface EngineChoice {
    val engine: OrpheusEngineId

    data class Engine(override val engine: OrpheusEngineId) : EngineChoice

    data class Patch(val patch: DxPatch) : EngineChoice {
        override val engine: OrpheusEngineId get() = patch.engine
    }
}

enum class EngineSlotKind { DUO, DRUM, PULSAR }

object EngineCatalog {

    private val categoryOf: Map<OrpheusEngineId, EngineCategory> = buildMap {
        fun put(category: EngineCategory, vararg ids: OrpheusEngineId) = ids.forEach { put(it, category) }
        put(EngineCategory.ANALOG, OSC, VA, VCF, WSH, PD)
        put(EngineCategory.HARMONIC, FM, ADD, WTB, TRN, CHD, ENS, NES)
        put(EngineCategory.TEXTURE, GRN, NSE, PAR, SWM, MST)
        put(EngineCategory.PHYSICAL, STR, MOD)
        put(EngineCategory.VOICE, SPK, VOWEL_FOF)
        put(
            EngineCategory.BRAIDS,
            TRIPLE_SAW, TRIPLE_SQUARE, TRIPLE_TRIANGLE, TRIPLE_SINE, TRIPLE_RING_MOD,
            CSAW, TOY, QUESTION_MARK,
        )
        put(EngineCategory.CHAOS, LRZ, ROS, DUF, HEN, CHU)
        // FM_DRUM is in the spec table but no picker offers it, so it stays uncategorised.
        put(EngineCategory.DRUMS, BD, SD, HH)
        put(EngineCategory.DX_PATCHES, DX, DX2, DX3)
    }

    /** Null only for [FM_DRUM], which no picker offers. */
    fun category(id: OrpheusEngineId): EngineCategory? = categoryOf[id]

    private fun familyOf(engine: OrpheusEngineId, index: Int): String = when (engine) {
        DX -> if (index <= 10 || index == 19) "Bass" else "Synth"
        DX2 -> when (index) {
            in 0..10 -> "Keys"
            in 11..15 -> "Plucked"
            in 16..23 -> "Mallets & Bells"
            else -> "Drums"
        }
        else -> when (index) {
            in 0..7 -> "Organ"
            in 8..22 -> "Pads"
            in 23..28 -> "Strings"
            else -> "Brass"
        }
    }

    private val dxEngines = listOf(DX, DX2, DX3)

    val patches: List<DxPatch> = dxEngines.flatMap { engine ->
        (0..31).map { i -> DxPatch(engine, i, FmPatchNames.patchNameAt(engine, i)!!, familyOf(engine, i)) }
    }

    /** The patch [harmonics] selects, or null when [engine] is not a DX engine. */
    fun patchAt(engine: OrpheusEngineId, harmonics: Float): DxPatch? {
        if (!engine.forcePinHarmonics) return null
        val index = FmPatchNames.patchIndexFor(engine, harmonics) ?: return null
        return patches[dxEngines.indexOf(engine) * 32 + index]
    }

    fun choiceFor(engine: OrpheusEngineId, harmonics: Float): EngineChoice =
        patchAt(engine, harmonics)?.let { EngineChoice.Patch(it) } ?: EngineChoice.Engine(engine)

    fun choices(kind: EngineSlotKind, hasNative: Boolean): List<EngineChoice> {
        val engines = OrpheusEngineId.entries.filter { id ->
            id != FM_DRUM && id !in dxEngines && (hasNative || !id.isNativeOnly) && when (kind) {
                EngineSlotKind.DUO -> !id.isPulsarOnly && categoryOf[id] != EngineCategory.DRUMS
                EngineSlotKind.DRUM -> id.ordinal <= DX3.ordinal
                EngineSlotKind.PULSAR -> true
            }
        }
        val bare = engines
            .sortedBy { categoryOf.getValue(it).ordinal }
            .map { EngineChoice.Engine(it) }
        val dx = dxEngines
            .filter { hasNative || !it.isNativeOnly }
            .flatMap { e -> patches.filter { it.engine == e }.map { EngineChoice.Patch(it) } }
        return bare + dx
    }

    /** Case-insensitive substring match over short code, enum name, display name, category, patch name and family. */
    fun search(choices: List<EngineChoice>, query: String): List<EngineChoice> {
        val q = query.trim()
        if (q.isEmpty()) return choices
        return choices.filter { c ->
            val fields = buildList {
                add(codeFor(c.engine))
                add(c.engine.name)
                add(c.engine.displayName)
                category(c.engine)?.let { add(it.label) }
                if (c is EngineChoice.Patch) {
                    add(c.patch.name)
                    add(c.patch.family)
                }
            }
            fields.any { it.contains(q, ignoreCase = true) }
        }
    }

    /** The short pill code for a bare engine (the Braids codes read "3SAW", not "TRIPLE_SAW"). */
    fun codeFor(id: OrpheusEngineId): String = when (id) {
        OSC -> "OSC"
        VCF -> "VCF"; PD -> "PD"; DX -> "DX"; DX2 -> "DX2"; DX3 -> "DX3"
        TRN -> "TRN"; ENS -> "ENS"; NES -> "NES"
        VA -> "VA"; WSH -> "WSH"; FM, FM_DRUM -> "FM"; GRN -> "GRN"; ADD -> "ADD"
        WTB -> "WTB"; CHD -> "CHD"; SPK -> "SPK"; SWM -> "SWM"; NSE -> "NSE"
        PAR -> "PAR"; STR -> "STR"; MOD -> "MOD"
        BD -> "BD"; SD -> "SD"; HH -> "HH"
        TRIPLE_SAW -> "3SAW"; TRIPLE_SQUARE -> "3SQR"; TRIPLE_TRIANGLE -> "3TRI"
        TRIPLE_SINE -> "3SIN"; TRIPLE_RING_MOD -> "3RM"
        CSAW -> "CSAW"; TOY -> "TOY"; VOWEL_FOF -> "VOW"; QUESTION_MARK -> "?"
        LRZ -> "LRZ"; ROS -> "ROS"; DUF -> "DUF"; HEN -> "HEN"; CHU -> "CHU"
        MST -> "MST"
    }

    fun labelFor(choice: EngineChoice): String = when (choice) {
        is EngineChoice.Engine -> codeFor(choice.engine)
        is EngineChoice.Patch -> "${codeFor(choice.patch.engine)} · ${choice.patch.name}"
    }

    fun key(choice: EngineChoice): String = when (choice) {
        is EngineChoice.Engine -> choice.engine.name
        is EngineChoice.Patch -> "${choice.patch.engine.name}:${choice.patch.index}"
    }

    fun fromKey(key: String): EngineChoice? {
        val engineName = key.substringBefore(':')
        val engine = OrpheusEngineId.entries.firstOrNull { it.name == engineName } ?: return null
        if (!key.contains(':')) return EngineChoice.Engine(engine)
        val index = key.substringAfter(':').toIntOrNull() ?: return null
        return patches.firstOrNull { it.engine == engine && it.index == index }?.let { EngineChoice.Patch(it) }
    }
}

/** Puts [choice] first in [recent], dropping any earlier copy, and keeps at most [cap] keys. */
fun pushRecent(recent: List<String>, choice: EngineChoice, cap: Int = 8): List<String> {
    val key = EngineCatalog.key(choice)
    return (listOf(key) + recent.filter { it != key }).take(cap)
}
