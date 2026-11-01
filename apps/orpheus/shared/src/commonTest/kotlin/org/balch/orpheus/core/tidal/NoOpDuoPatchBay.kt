package org.balch.orpheus.core.tidal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.audio.patch.DuoPatch
import org.balch.orpheus.core.audio.patch.DuoPatchBay
import org.balch.orpheus.core.audio.patch.LegacyModSource
import org.balch.orpheus.core.audio.patch.legacyDuoPatch

/** An in-memory bay for scheduler tests that never touch a duo patch. */
internal class NoOpDuoPatchBay : DuoPatchBay {
    private val state = MutableStateFlow(List(6) { DuoPatch() })
    override val patches: StateFlow<List<DuoPatch>> = state.asStateFlow()
    override fun setPatch(duo: Int, patch: DuoPatch) {
        state.value = state.value.toMutableList().also { it[duo] = patch }
    }
    override fun update(duo: Int, transform: (DuoPatch) -> DuoPatch) = setPatch(duo, transform(state.value[duo]))
    override fun applyLegacySource(duo: Int, source: LegacyModSource) =
        setPatch(duo, legacyDuoPatch(source, onDx = false, duoIndex = duo))
    override fun engineOf(duo: Int): OrpheusEngineId? = null
}
