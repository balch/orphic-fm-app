package org.balch.orpheus.features.ai.tools

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import org.balch.orpheus.core.di.FeatureScope

/** Asked before `pulsar_apply_vibe` replaces what plays: a non-null message refuses the apply and is what the agent reads. */
fun interface VibeApplyGuard {
    suspend fun refusal(): String?

    companion object {
        /** The result's `refusal` for a guarded apply: a guard only stops an apply that would clear the user's live mix. */
        const val REFUSAL = "WIPE_UNCONFIRMED"
    }
}

/** `allowEmpty` because only the DJ app's AI edition contributes a guard; Orpheus and the og edition apply unguarded. */
@ContributesTo(FeatureScope::class)
interface VibeApplyGuards {
    @Multibinds(allowEmpty = true)
    fun vibeApplyGuards(): Set<VibeApplyGuard>
}
