package org.balch.orpheus.features.voice.ui

import org.balch.orpheus.core.audio.ModSource

interface VoiceActions {
    fun onDuoModSourceChange(pairIndex: Int, source: ModSource)
    fun onVoiceTuneChange(index: Int, value: Float)
    fun onDuoModDepthChange(pairIndex: Int, value: Float)
    fun onVoiceEnvelopeSpeedChange(index: Int, value: Float)
    fun onHoldChange(index: Int, holding: Boolean)
    fun onPulseStart(index: Int)
    fun onPulseEnd(index: Int)
    fun onPairSharpnessChange(pairIndex: Int, value: Float)
    fun onQuadPitchChange(quadIndex: Int, value: Float)
    fun onQuadHoldChange(quadIndex: Int, value: Float)
    fun onFmStructureChange(crossQuad: Boolean)
    fun onTotalFeedbackChange(value: Float)
    fun onVibratoChange(value: Float)
    fun onVoiceCouplingChange(value: Float)
    fun onDialogActiveChange(active: Boolean)
    
    // Wobble tracking for finger movement modulation
    fun onWobblePulseStart(index: Int, x: Float, y: Float)
    fun onWobbleMove(index: Int, x: Float, y: Float)
    fun onWobblePulseEnd(index: Int)
}

interface MidiActions {
    fun selectVoiceForLearning(voiceIndex: Int)
}
