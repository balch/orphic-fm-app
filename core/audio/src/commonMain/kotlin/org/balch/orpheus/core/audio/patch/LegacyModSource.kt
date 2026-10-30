package org.balch.orpheus.core.audio.patch

import org.balch.orpheus.core.audio.OrpheusEngineId

/** Ordinal-compatible with the old per-duo ModSource. */
enum class LegacyModSource { VOICE_FM, OFF, LFO, FLUX, TIDES }

fun legacyDuoPatch(source: LegacyModSource, onDx: Boolean, duoIndex: Int): DuoPatch {
    // 0.5 semitone, stored as a fraction of the 12 semitone full scale.
    val pitch = 0.5f / 12f
    val cables = when (source) {
        LegacyModSource.OFF -> emptyList()
        LegacyModSource.LFO -> listOf(
            PatchCable(PatchSource.LFO_AB, PatchDest.TIMBRE, amount = 1f),
            PatchCable(PatchSource.LFO_A, PatchDest.MORPH, amount = 0.5f),
            PatchCable(PatchSource.LFO_B, PatchDest.HARMONICS, amount = 0.5f, parked = onDx),
            PatchCable(PatchSource.LFO_A90, PatchDest.PITCH, amount = pitch),
            PatchCable(PatchSource.LFO_AB, PatchDest.FM, amount = 0.5f),
        )
        LegacyModSource.TIDES -> listOf(
            PatchCable(PatchSource.WAVES_1, PatchDest.TIMBRE, amount = 1f),
            PatchCable(PatchSource.WAVES_2, PatchDest.MORPH, amount = 0.5f),
            PatchCable(PatchSource.WAVES_3, PatchDest.HARMONICS, amount = 0.5f, parked = onDx),
            PatchCable(PatchSource.WAVES_4, PatchDest.PITCH, amount = pitch),
            PatchCable(PatchSource.WAVES_1, PatchDest.FM, amount = 0.5f),
        )
        LegacyModSource.FLUX -> {
            val x = if (duoIndex % 2 == 0) PatchSource.FLUX_X1 else PatchSource.FLUX_X2
            listOf(
                PatchCable(x, PatchDest.TIMBRE, amount = 1f),
                PatchCable(x, PatchDest.FM, amount = 0.5f),
            )
        }
        LegacyModSource.VOICE_FM -> listOf(
            PatchCable(PatchSource.VOICE_FM, PatchDest.TIMBRE, amount = 1f),
            PatchCable(PatchSource.VOICE_FM, PatchDest.FM, amount = 0.5f),
        )
    }
    return DuoPatch(cables)
}

/**
 * A decodable [encoded] wins. If it is present but corrupt, fall back to the legacy source, or an
 * empty patch when that is unusable too. With no encoded patch, a missing legacy source is VOICE_FM.
 */
fun patchFromPreset(
    encoded: String?,
    legacySource: Float?,
    engine: OrpheusEngineId?,
    duoIndex: Int,
): DuoPatch {
    val onDx = engine?.forcePinHarmonics == true
    if (encoded != null) {
        DuoPatch.decode(encoded)?.let { return it }
        val legacy = legacySource?.toInt()?.let { LegacyModSource.entries.getOrNull(it) }
            ?: return DuoPatch()
        return legacyDuoPatch(legacy, onDx, duoIndex)
    }
    val source = legacySource?.toInt()?.let { LegacyModSource.entries.getOrNull(it) }
        ?: LegacyModSource.VOICE_FM
    return legacyDuoPatch(source, onDx, duoIndex)
}
