package org.balch.orpheus.features.record

import kotlinx.coroutines.flow.StateFlow

/** Seam over the engine's master level so the Record ViewModel can be tested without an engine. */
interface MasterLevelSource {
    /** Overall output level, 0 to 1. */
    val level: StateFlow<Float>
}
