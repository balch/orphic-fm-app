package org.balch.orpheus.djapp.ai.collab

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.ai.tools.VibeApplyGuard

/**
 * The agent's `mix_reset` asks the user first while it would clear controls they changed and they have not confirmed
 * with [MixCollab.confirmWipe]. Loads and applies are never refused: the History keeps each vibe's mix. MCP is never refused.
 */
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeApplyGuard>())
@Inject
class DjVibeApplyGuard(
    private val collab: MixCollab,
    private val collabMixer: CollabMixer,
    private val events: CollabEvents,
) : VibeApplyGuard {
    override suspend fun refusal(): String? = null

    // The History keeps what the apply replaces, so the user can roll the apply back.
    override suspend fun beforeApply() = collabMixer.keepBeforeApply(collabMixer.toolOrigin())

    override fun applyFailed() = collabMixer.dropBeforeApply()

    /** For `mix_reset`, which clears only the user's levels, pans and sends on unpinned strips. */
    suspend fun resetRefusal(): CollabResult.Refused? {
        val changes = collab.userChangesClearedByReset()
        if (collabMixer.toolOrigin() == EditOrigin.MCP || changes == 0) return null
        if (collab.consumeWipeConfirmation()) return null
        events.emit(CollabEvent.WipeConfirmRequested(changes))
        return CollabResult.Refused(Refusal.WIPE_UNCONFIRMED, reason(changes))
    }

    companion object {
        fun reason(changes: Int) =
            "This would clear the user's live mix (${changes(changes)}). Ask them first; they confirm with a button."

        /** "1 change", "3 changes". */
        fun changes(n: Int) = if (n == 1) "1 change" else "$n changes"
    }
}
