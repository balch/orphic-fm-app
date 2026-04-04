package org.balch.orpheus.core.audio

enum class ClockDivision(val displayName: String, val multiplier: Float) {
    Q("1/4x", 0.25f),
    E("1/2x", 0.5f),
    X1("1x", 1.0f),
    X2("2x", 2.0f),
    X4("4x", 4.0f),
}
