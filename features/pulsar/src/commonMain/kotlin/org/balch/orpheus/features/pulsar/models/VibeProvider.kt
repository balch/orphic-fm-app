package org.balch.orpheus.features.pulsar.models

interface VibeProvider {
    /**
     * Display name for the vibe. `name.value` MUST match `vibe.name`; it comes from `VibeNames`.
     * A cheap constant: accessing this never forces the heavy `vibe` body to
     * be constructed. Sorting / lookups use this; `vibe` is only realized
     * when the user actually selects the track.
     */
    val name: VibeName

    /** Heavy vibe data. Implementations should declare this `by lazy { ... }`. */
    val vibe: Vibe
}
