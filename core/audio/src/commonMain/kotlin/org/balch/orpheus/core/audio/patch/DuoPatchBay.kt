package org.balch.orpheus.core.audio.patch

import kotlinx.coroutines.flow.StateFlow
import org.balch.orpheus.core.audio.OrpheusEngineId

interface DuoPatchBay {
    val patches: StateFlow<List<DuoPatch>>
    fun setPatch(duo: Int, patch: DuoPatch)
    /** Reads, transforms and stores one duo's patch as a single step. */
    fun update(duo: Int, transform: (DuoPatch) -> DuoPatch)
    fun applyLegacySource(duo: Int, source: LegacyModSource)
    fun engineOf(duo: Int): OrpheusEngineId?
}
