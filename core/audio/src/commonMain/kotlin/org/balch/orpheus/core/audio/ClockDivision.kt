package org.balch.orpheus.core.audio

enum class ClockDivision(val displayName: String, val multiplier: Float) {
    X1("1x", 1.0f),
    X2("2x", 2.0f),
    X4("4x", 4.0f),
    X8("8x", 8.0f),
    X16("16x", 16.0f),
}
