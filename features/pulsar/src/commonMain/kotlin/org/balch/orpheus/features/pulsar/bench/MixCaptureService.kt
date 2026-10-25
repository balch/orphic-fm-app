package org.balch.orpheus.features.pulsar.bench

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import org.balch.orpheus.features.pulsar.models.Vibe

/**
 * Turns the live Mix Bench layers into a capture: the rows and a capture file. The live mix and the vibe are untouched.
 */
interface MixCaptureService {
    /**
     * [file] is the vibe the mix rows are read against and the capture holds, null for the playing one; [vibeRows]
     * are the other vibe field changes made on top of it, written next to the mix rows.
     */
    suspend fun capture(file: Vibe? = null, vibeRows: List<VibeFieldChange> = emptyList()): CaptureOutcome

    /**
     * The vibe source's hash when [vibeName] first loaded, which a capture's stale check compares against.
     * Null when unknown or when the vibe has no source.
     */
    fun baselineHash(vibeName: String): String? = null
}

/**
 * [file] is the capture's absolute path, null when none was written. [stale] is null with no source file
 * to compare.
 */
data class CaptureOutcome(
    val ok: Boolean,
    val message: String,
    val file: String?,
    val rows: List<CaptureRow>,
    val stale: Boolean?,
    val aiVibe: Boolean,
    val vibeRows: List<VibeFieldChange> = emptyList(),
)

/** Default where no capture writer exists: iOS, and the og/core edition (the writer ships with desktop and Android ai). */
@ContributesBinding(AppScope::class)
@Inject
class UnavailableMixCaptureService : MixCaptureService {
    override suspend fun capture(file: Vibe?, vibeRows: List<VibeFieldChange>) = CaptureOutcome(
        ok = false,
        message = "capture not available on this platform yet",
        file = null,
        rows = emptyList(),
        stale = null,
        aiVibe = false,
    )
}
