package org.balch.orpheus.features.pulsar.bench

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Turns the live Mix Bench layers into a capture: the rows, a capture file, and for an AI-made vibe
 * (one not in the catalog) the same change written back into the vibe, archived and re-applied.
 */
interface MixCaptureService {
    suspend fun capture(): CaptureOutcome

    /**
     * The vibe source's hash when [vibeName] first loaded, which a capture's stale check compares against.
     * Null when unknown or when the vibe has no source.
     */
    fun baselineHash(vibeName: String): String? = null
}

/**
 * [file] is the capture's absolute path, null when none was written. [stale] is null with no source file
 * to compare. [appliedToAiVibe] means the AI vibe now holds the rows and plays again with no bench layers.
 */
data class CaptureOutcome(
    val ok: Boolean,
    val message: String,
    val file: String?,
    val rows: List<CaptureRow>,
    val stale: Boolean?,
    val aiVibe: Boolean,
    val appliedToAiVibe: Boolean,
)

/** Default where no capture writer exists: iOS, and the og/core edition (the writer ships with desktop and Android ai). */
@ContributesBinding(AppScope::class)
@Inject
class UnavailableMixCaptureService : MixCaptureService {
    override suspend fun capture() = CaptureOutcome(
        ok = false,
        message = "capture not available on this platform yet",
        file = null,
        rows = emptyList(),
        stale = null,
        aiVibe = false,
        appliedToAiVibe = false,
    )
}
