package org.balch.orpheus.plugins.grains

/**
 * Processing modes matching C++ Clouds PlaybackMode ordinals.
 */
enum class GrainsMode(val displayName: String) {
    GRANULAR("Gran"),       // 0
    STRETCH("Stretch"),     // 1
    LOOPING_DELAY("Loop"),  // 2
    SPECTRAL("Spectral"),   // 3
}
