package org.balch.orpheus.features.bass

import androidx.compose.runtime.Immutable
import org.balch.orpheus.core.audio.BassEngine
import org.balch.orpheus.core.audio.BassScale
import org.balch.orpheus.core.audio.ClockDivision

@Immutable
data class BassUiState(
    val engine: BassEngine = BassEngine.VCF_ACID,
    val rootNote: Int = 36,
    val scale: BassScale = BassScale.MINOR_PENTATONIC,
    val clockDivision: ClockDivision = ClockDivision.X1,  // 16th notes
    val stepCount: Int = 16,
    val mutation: Float = 0.0f,
    val cutoff: Float = 0.5f,
    val resonance: Float = 0.0f,
    val envelope: Float = 0.7f,
    val overdrive: Float = 0.0f,
    val compressor: Float = 0.0f,
    val mix: Float = 0.0f,
    val lfoMix: Float = 0.0f,
    val accentAmount: Float = 0.5f,
    val jitter: Float = 0.0f,
    val fxSend: Float = 0.0f,
    val triggerSource: Int = 0,   // 0=off, 1=T1, 2=T2, 3=T3
    val pitchSource: Int = 0,     // 0=off, 1=X1, 2=X2, 3=X3
    val timbreSource: Int = 0,    // 0=off, 1=Y
)
