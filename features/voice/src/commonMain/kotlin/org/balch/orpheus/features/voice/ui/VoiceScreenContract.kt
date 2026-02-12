package org.balch.orpheus.features.voice.ui

import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.features.midi.MidiPanelActions
import org.balch.orpheus.features.voice.VoicePanelActions

interface VoiceActions {
    fun setDuoModSource(pairIndex: Int, source: ModSource)
    fun setVoiceTune(index: Int, value: Float)
    fun setDuoModDepth(pairIndex: Int, value: Float)
    fun setVoiceEnvelopeSpeed(index: Int, value: Float)
    fun setHold(index: Int, holding: Boolean)
    fun pulseStart(index: Int)
    fun pulseEnd(index: Int)
    fun setPairSharpness(pairIndex: Int, value: Float)
    fun setQuadPitch(quadIndex: Int, value: Float)
    fun setQuadHold(quadIndex: Int, value: Float)
    fun setFmStructure(crossQuad: Boolean)
    fun setTotalFeedback(value: Float)
    fun setVibrato(value: Float)
    fun setVoiceCoupling(value: Float)
    fun setDialogActive(active: Boolean)
    
    // Wobble tracking for finger movement modulation
    fun wobblePulseStart(index: Int, x: Float, y: Float)
    fun wobbleMove(index: Int, x: Float, y: Float)
    fun wobblePulseEnd(index: Int)
    fun setQuadTriggerSource(quadIndex: Int, sourceIndex: Int)
    fun setQuadEnvelopeTriggerMode(quadIndex: Int, enabled: Boolean)
    fun setPairEngine(pairIndex: Int, engineOrdinal: Int)
    fun setPairHarmonics(pairIndex: Int, value: Float)
    fun setPairMorph(pairIndex: Int, value: Float)
    fun setPairModDepth(pairIndex: Int, value: Float)
}

interface MidiActions {
    fun selectVoiceForLearning(voiceIndex: Int)
}

/**
 * Creates a VoiceActions adapter from VoicePanelActions.
 * This provides a single source of truth for mapping between the two interfaces.
 */
fun VoicePanelActions.toVoiceActions(): VoiceActions = object : VoiceActions {
    override fun setDuoModSource(pairIndex: Int, source: ModSource) = this@toVoiceActions.setDuoModSource(pairIndex, source)
    override fun setVoiceTune(index: Int, value: Float) = this@toVoiceActions.setVoiceTune(index, value)
    override fun setDuoModDepth(pairIndex: Int, value: Float) = this@toVoiceActions.setDuoModDepth(pairIndex, value)
    override fun setVoiceEnvelopeSpeed(index: Int, value: Float) = this@toVoiceActions.setVoiceEnvelopeSpeed(index, value)
    override fun setHold(index: Int, holding: Boolean) = this@toVoiceActions.setHold(index, holding)
    override fun pulseStart(index: Int) = this@toVoiceActions.pulseStart(index)
    override fun pulseEnd(index: Int) = this@toVoiceActions.pulseEnd(index)
    override fun setPairSharpness(pairIndex: Int, value: Float) = this@toVoiceActions.setPairSharpness(pairIndex, value)
    override fun setQuadPitch(quadIndex: Int, value: Float) = this@toVoiceActions.setQuadPitch(quadIndex, value)
    override fun setQuadHold(quadIndex: Int, value: Float) = this@toVoiceActions.setQuadHold(quadIndex, value)
    override fun setFmStructure(crossQuad: Boolean) = this@toVoiceActions.setFmStructure(crossQuad)
    override fun setTotalFeedback(value: Float) = this@toVoiceActions.setTotalFeedback(value)
    override fun setVibrato(value: Float) = this@toVoiceActions.setVibrato(value)
    override fun setVoiceCoupling(value: Float) = this@toVoiceActions.setVoiceCoupling(value)
    override fun setDialogActive(active: Boolean) { /* handled by parent */ }
    override fun wobblePulseStart(index: Int, x: Float, y: Float) = this@toVoiceActions.wobblePulseStart(index, x, y)
    override fun wobbleMove(index: Int, x: Float, y: Float) = this@toVoiceActions.wobbleMove(index, x, y)
    override fun wobblePulseEnd(index: Int) = this@toVoiceActions.wobblePulseEnd(index)
    override fun setQuadTriggerSource(quadIndex: Int, sourceIndex: Int) = this@toVoiceActions.setQuadTriggerSource(quadIndex, sourceIndex)
    override fun setQuadEnvelopeTriggerMode(quadIndex: Int, enabled: Boolean) = this@toVoiceActions.setQuadEnvelopeTriggerMode(quadIndex, enabled)
    override fun setPairEngine(pairIndex: Int, engineOrdinal: Int) = this@toVoiceActions.setPairEngine(pairIndex, engineOrdinal)
    override fun setPairHarmonics(pairIndex: Int, value: Float) = this@toVoiceActions.setPairHarmonics(pairIndex, value)
    override fun setPairMorph(pairIndex: Int, value: Float) = this@toVoiceActions.setPairMorph(pairIndex, value)
    override fun setPairModDepth(pairIndex: Int, value: Float) = this@toVoiceActions.setPairModDepth(pairIndex, value)
}

/**
 * Creates a MidiActions adapter from MidiPanelActions.
 * This provides a single source of truth for mapping between the two interfaces.
 */
fun MidiPanelActions.toMidiActions(): MidiActions = object : MidiActions {
    override fun selectVoiceForLearning(voiceIndex: Int) = this@toMidiActions.selectVoiceForLearning(voiceIndex)
}

