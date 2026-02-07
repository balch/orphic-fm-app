package org.balch.orpheus.core.plugin.symbols

import org.balch.orpheus.core.plugin.PortSymbol
import org.balch.orpheus.core.plugin.Symbol

const val BEATS_URI = "org.balch.orpheus.plugins.beats"

enum class BeatsSymbol(
    override val symbol: Symbol,
    override val uri: String = BEATS_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    X("x", displayName = "X"),
    Y("y", displayName = "Y"),
    BPM("bpm", displayName = "BPM"),
    MIX("mix", displayName = "Mix"),
    RANDOMNESS("randomness", displayName = "Randomness"),
    SWING("swing", displayName = "Swing"),
    MODE("mode", displayName = "Output Mode"),
    DENSITY_0("density_0", displayName = "Density 0"),
    DENSITY_1("density_1", displayName = "Density 1"),
    DENSITY_2("density_2", displayName = "Density 2"),
    EUCLIDEAN_0("euclidean_0", displayName = "Euclidean 0"),
    EUCLIDEAN_1("euclidean_1", displayName = "Euclidean 1"),
    EUCLIDEAN_2("euclidean_2", displayName = "Euclidean 2");

    companion object {
        private val densities = arrayOf(DENSITY_0, DENSITY_1, DENSITY_2)
        private val euclideans = arrayOf(EUCLIDEAN_0, EUCLIDEAN_1, EUCLIDEAN_2)

        fun density(index: Int): BeatsSymbol = densities[index]
        fun euclidean(index: Int): BeatsSymbol = euclideans[index]
    }
}
