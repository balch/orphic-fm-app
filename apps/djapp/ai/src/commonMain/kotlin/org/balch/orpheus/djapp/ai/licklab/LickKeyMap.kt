package org.balch.orpheus.djapp.ai.licklab

import androidx.compose.ui.input.key.Key

enum class KeyMode { InKey, Chromatic }

/** Mac key to note offset: scale degrees in [KeyMode.InKey], semitones in [KeyMode.Chromatic]. */
object LickKeyMap {
    private val homeRow = listOf(
        Key.A, Key.S, Key.D, Key.F, Key.G, Key.H, Key.J, Key.K, Key.L, Key.Semicolon, Key.Apostrophe,
    )
    private val topRow = listOf(Key.Q, Key.W, Key.E, Key.R, Key.T, Key.Y, Key.U, Key.I, Key.O, Key.P)
    private val bottomRow = listOf(Key.Z, Key.X, Key.C, Key.V, Key.B, Key.N, Key.M)

    private val inKey: Map<Key, Int> = buildMap {
        homeRow.forEachIndexed { i, k -> put(k, i) }
        topRow.forEachIndexed { i, k -> put(k, 7 + i) }
        bottomRow.forEachIndexed { i, k -> put(k, -7 + i) }
    }

    // Piano layout: home row is the white keys, the black keys sit on the top row between them.
    private val chromatic: Map<Key, Int> = mapOf(
        Key.A to 0, Key.W to 1, Key.S to 2, Key.E to 3, Key.D to 4, Key.F to 5, Key.T to 6,
        Key.G to 7, Key.Y to 8, Key.H to 9, Key.U to 10, Key.J to 11, Key.K to 12, Key.O to 13,
        Key.L to 14, Key.P to 15, Key.Semicolon to 16, Key.Apostrophe to 17,
    )

    fun offsetFor(key: Key, mode: KeyMode): Int? = keys(mode)[key]

    /** Every mapped key in [mode], home row first. */
    fun keys(mode: KeyMode): Map<Key, Int> = when (mode) {
        KeyMode.InKey -> inKey
        KeyMode.Chromatic -> chromatic
    }
}
