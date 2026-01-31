package org.balch.orpheus.features.voice.ui

import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.features.midi.MidiPanelActions
import org.balch.orpheus.features.voice.VoicePanelActions

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
    fun onQuadTriggerSourceChange(quadIndex: Int, sourceIndex: Int)
}

interface MidiActions {
    fun selectVoiceForLearning(voiceIndex: Int)
}

/**
 * Creates a VoiceActions adapter from VoicePanelActions.
 * This provides a single source of truth for mapping between the two interfaces.
 */
fun VoicePanelActions.toVoiceActions(): VoiceActions = object : VoiceActions {
    override fun onDuoModSourceChange(pairIndex: Int, source: ModSource) = this@toVoiceActions.onDuoModSourceChange(pairIndex, source)
    override fun onVoiceTuneChange(index: Int, value: Float) = this@toVoiceActions.onVoiceTuneChange(index, value)
    override fun onDuoModDepthChange(pairIndex: Int, value: Float) = this@toVoiceActions.onDuoModDepthChange(pairIndex, value)
    override fun onVoiceEnvelopeSpeedChange(index: Int, value: Float) = this@toVoiceActions.onVoiceEnvelopeSpeedChange(index, value)
    override fun onHoldChange(index: Int, holding: Boolean) = this@toVoiceActions.onHoldChange(index, holding)
    override fun onPulseStart(index: Int) = this@toVoiceActions.onPulseStart(index)
    override fun onPulseEnd(index: Int) = this@toVoiceActions.onPulseEnd(index)
    override fun onPairSharpnessChange(pairIndex: Int, value: Float) = this@toVoiceActions.onPairSharpnessChange(pairIndex, value)
    override fun onQuadPitchChange(quadIndex: Int, value: Float) = this@toVoiceActions.onQuadPitchChange(quadIndex, value)
    override fun onQuadHoldChange(quadIndex: Int, value: Float) = this@toVoiceActions.onQuadHoldChange(quadIndex, value)
    override fun onFmStructureChange(crossQuad: Boolean) = this@toVoiceActions.onFmStructureChange(crossQuad)
    override fun onTotalFeedbackChange(value: Float) = this@toVoiceActions.onTotalFeedbackChange(value)
    override fun onVibratoChange(value: Float) = this@toVoiceActions.onVibratoChange(value)
    override fun onVoiceCouplingChange(value: Float) = this@toVoiceActions.onVoiceCouplingChange(value)
    override fun onDialogActiveChange(active: Boolean) { /* handled by parent */ }
    override fun onWobblePulseStart(index: Int, x: Float, y: Float) = this@toVoiceActions.onWobblePulseStart(index, x, y)
    override fun onWobbleMove(index: Int, x: Float, y: Float) = this@toVoiceActions.onWobbleMove(index, x, y)
    override fun onWobblePulseEnd(index: Int) = this@toVoiceActions.onWobblePulseEnd(index)
    override fun onQuadTriggerSourceChange(quadIndex: Int, sourceIndex: Int) = this@toVoiceActions.onQuadTriggerSourceChange(quadIndex, sourceIndex)
}

/**
 * Creates a MidiActions adapter from MidiPanelActions.
 * This provides a single source of truth for mapping between the two interfaces.
 */
fun MidiPanelActions.toMidiActions(): MidiActions = object : MidiActions {
    override fun selectVoiceForLearning(voiceIndex: Int) = this@toMidiActions.onSelectVoiceForLearning(voiceIndex)
}

