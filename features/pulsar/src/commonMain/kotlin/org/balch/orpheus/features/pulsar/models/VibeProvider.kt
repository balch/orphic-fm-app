package org.balch.orpheus.features.pulsar.models

interface VibeProvider {
    /**
     * Display name for the vibe. MUST match `vibe.name` and is required to be
     * a cheap constant — accessing this never forces the heavy `vibe` body to
     * be constructed. Sorting / lookups use this; `vibe` is only realized
     * when the user actually selects the track.
     */
    val name: String

    /** Heavy vibe data. Implementations should declare this `by lazy { ... }`. */
    val vibe: Vibe
}
