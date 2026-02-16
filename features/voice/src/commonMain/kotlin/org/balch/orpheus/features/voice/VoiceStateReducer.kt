package org.balch.orpheus.features.voice

import org.balch.orpheus.core.audio.ModSource
import org.balch.orpheus.core.audio.VoiceState

internal fun reduceVoiceState(state: VoiceUiState, intent: VoiceIntent): VoiceUiState =
    when (intent) {
        is VoiceIntent.Tune ->
            state.withVoice(intent.index) { it.copy(tune = intent.value) }

        is VoiceIntent.ModDepth -> state.withModDepth(intent.index, intent.value)
        is VoiceIntent.EnvelopeSpeed ->
            state.withEnvelopeSpeed(intent.index, intent.value)

        is VoiceIntent.PulseStart ->
            state.withVoice(intent.index) { it.copy(pulse = true) }

        is VoiceIntent.PulseEnd ->
            state.withVoice(intent.index) { it.copy(pulse = false) }

        is VoiceIntent.Hold ->
            state.withVoice(intent.index) { it.copy(isHolding = intent.holding) }

        is VoiceIntent.DuoSharpness ->
            state.withDuoSharpness(intent.duoIndex, intent.value)

        is VoiceIntent.DuoModSource ->
            state.withDuoModSource(intent.duoIndex, intent.source)

        is VoiceIntent.DuoEngine ->
            state.withDuoEngine(intent.duoIndex, intent.engineOrdinal)

        is VoiceIntent.DuoHarmonics ->
            state.withDuoHarmonics(intent.duoIndex, intent.value)

        is VoiceIntent.DuoMorph ->
            state.withDuoMorph(intent.duoIndex, intent.value)

        is VoiceIntent.DuoModSourceLevel ->
            state.withDuoModSourceLevel(intent.duoIndex, intent.value)

        is VoiceIntent.QuadPitch ->
            state.withQuadPitch(intent.quadIndex, intent.value)

        is VoiceIntent.QuadHold ->
            state.withQuadHold(intent.quadIndex, intent.value)

        is VoiceIntent.QuadVolume ->
            state.withQuadVolume(intent.quadIndex, intent.value)

        is VoiceIntent.QuadTriggerSource ->
            state.withQuadTriggerSource(intent.quadIndex, intent.sourceIndex)

        is VoiceIntent.QuadPitchSource ->
            state.withQuadPitchSource(intent.quadIndex, intent.sourceIndex)

        is VoiceIntent.QuadEnvelopeTriggerMode ->
            state.copy(quadEnvelopeTriggerModes = state.quadEnvelopeTriggerModes.toMutableList().also { it[intent.quadIndex] = intent.enabled })

        is VoiceIntent.AiVoiceEngineHighlight ->
            state.copy(aiVoiceEngineHighlights = state.aiVoiceEngineHighlights.mapIndexed { i, v ->
                if (i == intent.duoIndex) intent.show else v
            })

        is VoiceIntent.FmStructure ->
            state.copy(fmStructureCrossQuad = intent.crossQuad)

        is VoiceIntent.TotalFeedback ->
            state.copy(totalFeedback = intent.value)

        is VoiceIntent.Vibrato ->
            state.copy(vibrato = intent.value)

        is VoiceIntent.VoiceCoupling ->
            state.copy(voiceCoupling = intent.value)

        is VoiceIntent.MasterVolume ->
            state.copy(masterVolume = intent.value)

        is VoiceIntent.PeakLevel ->
            state.copy(peakLevel = intent.value)

        is VoiceIntent.BendPosition ->
            state.copy(bendPosition = intent.value)

        is VoiceIntent.SetBpm ->
            state.copy(bpm = intent.value)
    }

// Helper extensions for cleaner state transformations
internal fun VoiceUiState.withVoice(index: Int, transform: (VoiceState) -> VoiceState) =
    copy(voiceStates = voiceStates.mapIndexed { i, v -> if (i == index) transform(v) else v })

internal fun VoiceUiState.withModDepth(index: Int, value: Float) =
    copy(voiceModDepths = voiceModDepths.mapIndexed { i, d -> if (i == index) value else d })

internal fun VoiceUiState.withEnvelopeSpeed(index: Int, value: Float) =
    copy(voiceEnvelopeSpeeds = voiceEnvelopeSpeeds.mapIndexed { i, s -> if (i == index) value else s })

internal fun VoiceUiState.withDuoSharpness(duoIndex: Int, value: Float) =
    copy(duoSharpness = duoSharpness.mapIndexed { i, s -> if (i == duoIndex) value else s })

internal fun VoiceUiState.withDuoModSource(duoIndex: Int, source: ModSource) =
    copy(duoModSources = duoModSources.mapIndexed { i, s -> if (i == duoIndex) source else s })

internal fun VoiceUiState.withDuoEngine(duoIndex: Int, engineOrdinal: Int) =
    copy(duoEngines = duoEngines.mapIndexed { i, e -> if (i == duoIndex) engineOrdinal else e })

internal fun VoiceUiState.withDuoHarmonics(duoIndex: Int, value: Float) =
    copy(duoHarmonics = duoHarmonics.mapIndexed { i, h -> if (i == duoIndex) value else h })

internal fun VoiceUiState.withDuoMorph(duoIndex: Int, value: Float) =
    copy(duoMorphs = duoMorphs.mapIndexed { i, m -> if (i == duoIndex) value else m })

internal fun VoiceUiState.withDuoModSourceLevel(duoIndex: Int, value: Float) =
    copy(duoModSourceLevels = duoModSourceLevels.mapIndexed { i, d -> if (i == duoIndex) value else d })

internal fun VoiceUiState.withQuadPitch(quadIndex: Int, value: Float) =
    copy(quadGroupPitches = quadGroupPitches.mapIndexed { i, p -> if (i == quadIndex) value else p })

internal fun VoiceUiState.withQuadHold(quadIndex: Int, value: Float) =
    copy(quadGroupHolds = quadGroupHolds.mapIndexed { i, h -> if (i == quadIndex) value else h })

internal fun VoiceUiState.withQuadVolume(quadIndex: Int, value: Float) =
    copy(quadGroupVolumes = quadGroupVolumes.mapIndexed { i, v -> if (i == quadIndex) value else v })

internal fun VoiceUiState.withQuadTriggerSource(quadIndex: Int, sourceIndex: Int) =
    copy(quadTriggerSources = quadTriggerSources.mapIndexed { i, s -> if (i == quadIndex) sourceIndex else s })

internal fun VoiceUiState.withQuadPitchSource(quadIndex: Int, sourceIndex: Int) =
    copy(quadPitchSources = quadPitchSources.mapIndexed { i, s -> if (i == quadIndex) sourceIndex else s })
