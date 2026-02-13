package org.balch.orpheus.features.voice

import androidx.compose.runtime.Immutable
import org.balch.orpheus.core.audio.ModSource

@Immutable
data class VoicePanelActions(
    val setMasterVolume: (Float) -> Unit,
    val setVibrato: (Float) -> Unit,
    val setVoiceTune: (Int, Float) -> Unit,
    val setVoiceModDepth: (Int, Float) -> Unit,
    val setDuoModDepth: (Int, Float) -> Unit,
    val setPairSharpness: (Int, Float) -> Unit,
    val setVoiceEnvelopeSpeed: (Int, Float) -> Unit,
    val pulseStart: (Int) -> Unit,
    val pulseEnd: (Int) -> Unit,
    val setHold: (Int, Boolean) -> Unit,
    val setDuoModSource: (Int, ModSource) -> Unit,
    val setQuadPitch: (Int, Float) -> Unit,
    val setQuadHold: (Int, Float) -> Unit,
    val setFmStructure: (Boolean) -> Unit,
    val setTotalFeedback: (Float) -> Unit,
    val setVoiceCoupling: (Float) -> Unit,
    val wobblePulseStart: (Int, Float, Float) -> Unit,
    val wobbleMove: (Int, Float, Float) -> Unit,
    val wobblePulseEnd: (Int) -> Unit,
    val setBend: (Float) -> Unit,
    val releaseBend: () -> Unit,
    val setStringBend: (stringIndex: Int, bendAmount: Float, voiceMix: Float) -> Unit,
    val releaseStringBend: (stringIndex: Int) -> Int,
    val setSlideBar: (yPosition: Float, xPosition: Float) -> Unit,
    val releaseSlideBar: () -> Unit,
    val setBpm: (Double) -> Unit,
    val setQuadTriggerSource: (Int, Int) -> Unit,
    val setQuadPitchSource: (Int, Int) -> Unit,
    val setQuadEnvelopeTriggerMode: (Int, Boolean) -> Unit,
    val setPairEngine: (Int, Int) -> Unit,
    val setPairHarmonics: (Int, Float) -> Unit,
    val setPairMorph: (Int, Float) -> Unit,
    val setPairModDepth: (Int, Float) -> Unit
) {
    companion object {
        val EMPTY = VoicePanelActions(
            setMasterVolume = {}, setVibrato = {},
            setVoiceTune = {_, _ -> }, setVoiceModDepth = {_, _ -> },
            setDuoModDepth = {_, _ -> }, setPairSharpness = {_, _ -> },
            setVoiceEnvelopeSpeed = {_, _ -> }, pulseStart = {}, pulseEnd = {},
            setHold = {_, _ -> }, setDuoModSource = {_, _ -> },
            setQuadPitch = {_, _ -> }, setQuadHold = {_, _ -> },
            setFmStructure = {}, setTotalFeedback = {},
            setVoiceCoupling = {}, wobblePulseStart = {_, _, _ -> },
            wobbleMove = {_, _, _ -> }, wobblePulseEnd = {},
            setBend = {}, releaseBend = {},
            setStringBend = {_, _, _ -> }, releaseStringBend = { 0 },
            setSlideBar = {_, _ -> }, releaseSlideBar = {},
            setBpm = {}, setQuadTriggerSource = {_, _ -> },
            setQuadPitchSource = {_, _ -> },
            setQuadEnvelopeTriggerMode = {_, _ -> },
            setPairEngine = {_, _ -> },
            setPairHarmonics = {_, _ -> },
            setPairMorph = {_, _ -> },
            setPairModDepth = {_, _ -> }
        )
    }
}
