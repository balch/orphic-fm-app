package org.balch.orpheus.djapp.ai.collab

import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.ai.tools.VibeApplyGuard
import org.balch.orpheus.features.pulsar.bench.MixCaptureService

/**
 * The one check for anything that wipes the user's mix: `pulsar_apply_vibe` (through the [VibeApplyGuard] set),
 * `vibe_load` and the agent's `mix_reset`. While the wipe would clear controls the user changed and they have not
 * confirmed with [MixCollab.confirmWipe], the in-app agent is refused and the chat asks them. MCP is never refused.
 */
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeApplyGuard>())
@Inject
class DjVibeApplyGuard(
    private val collab: MixCollab,
    private val collabMixer: CollabMixer,
    private val events: CollabEvents,
    private val captureService: MixCaptureService,
) : VibeApplyGuard {
    private val log = logging("DjVibeApplyGuard")

    override suspend fun refusal(): String? = wipeRefusal()?.reason

    /** For an apply or a vibe load, which clear the user's levels, pans, sends, mutes, solos and macro knobs. */
    suspend fun wipeRefusal(): CollabResult.Refused? = decide(collab.userChangesWipedByLoad(), capture = true)

    /**
     * For `mix_reset`, which clears only the user's levels, pans and sends on unpinned strips. No capture first:
     * on an AI vibe a capture writes the edits back into the vibe and re-applies it, which would undo the reset.
     */
    suspend fun resetRefusal(): CollabResult.Refused? = decide(collab.userChangesClearedByReset(), capture = false)

    /** Null when the wipe may go ahead. With [capture], a confirmed wipe is captured first, so the user's mix is kept on disk. */
    private suspend fun decide(changes: Int, capture: Boolean): CollabResult.Refused? {
        if (collabMixer.toolOrigin() == EditOrigin.MCP || changes == 0) return null
        if (collab.consumeWipeConfirmation()) {
            if (capture) captureBeforeWipe()
            return null
        }
        events.emit(CollabEvent.WipeConfirmRequested(changes))
        return CollabResult.Refused(Refusal.WIPE_UNCONFIRMED, reason(changes))
    }

    // A failed capture never blocks the wipe the user confirmed.
    private suspend fun captureBeforeWipe() {
        try {
            val outcome = captureService.capture()
            if (!outcome.ok) log.warn { "capture before wipe failed: ${outcome.message}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "capture before wipe failed" }
        }
    }

    companion object {
        fun reason(changes: Int) =
            "This would clear the user's live mix (${changes(changes)}). Ask them first; they confirm with a button."

        /** "1 change", "3 changes". */
        fun changes(n: Int) = if (n == 1) "1 change" else "$n changes"
    }
}
