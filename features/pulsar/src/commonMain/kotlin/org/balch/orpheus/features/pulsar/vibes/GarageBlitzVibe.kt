package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.features.pulsar.Engine
import org.balch.orpheus.features.pulsar.EnvelopeProfile
import org.balch.orpheus.features.pulsar.EnvelopeType
import org.balch.orpheus.features.pulsar.EvolutionTension
import org.balch.orpheus.features.pulsar.GenreProfile
import org.balch.orpheus.features.pulsar.ProgressionStyle
import org.balch.orpheus.features.pulsar.RhythmPattern
import org.balch.orpheus.features.pulsar.RootNote
import org.balch.orpheus.features.pulsar.ScaleType
import org.balch.orpheus.features.pulsar.TensionProfile
import org.balch.orpheus.features.pulsar.TonalTension
import org.balch.orpheus.features.pulsar.TrackMacroMap
import org.balch.orpheus.features.pulsar.TrackRole
import org.balch.orpheus.features.pulsar.TrackVoice
import org.balch.orpheus.features.pulsar.Evolution
import org.balch.orpheus.features.pulsar.PitchEvolution
import org.balch.orpheus.features.pulsar.Vibe
import org.balch.orpheus.features.pulsar.VibeEffects
import org.balch.orpheus.features.pulsar.VibeProvider

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class GarageBlitzVibe : VibeProvider {
    override val vibe = generateBaseVibe(
        name = "Garage Blitz",
        bpm = 138f,
        envelopeType = EnvelopeType.AD,
        rootNote = RootNote.G,
        scaleType = ScaleType.MINOR,
        rhythmDensity = RhythmPattern.DENSE_16TH,
        progressionStyle = ProgressionStyle.POP,
        chordsPerBar = 4,
        tension = TensionProfile(
            innerBars = 3, outerBars = 12, outerDepth = 0.3f,
            volume = 0.6f,
            tonal = TonalTension(octaveShift = true, keyShift = 5, chromaticPassing = 0.45f),
            timing = 0.3f,
            evolution = EvolutionTension(
                timbreLow = 0.15f, timbreHigh = 0.75f, timbreProbability = 0.95f,
                morphLow = 0.2f, morphHigh = 0.8f, morphProbability = 0.7f,
                attackPoint = 0.4f, releaseSpeed = 0.15f,
            ),
        ),
    )
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class FloorBlitzVibe : VibeProvider {
    override val vibe = generateBaseVibe(
        name = "Floor Blitz",
        bpm = 120f,
        envelopeType = EnvelopeType.AD,
        rootNote = RootNote.A,
        scaleType = ScaleType.PENTATONIC,
        rhythmDensity = RhythmPattern.FOUR_ON_FLOOR,
        progressionStyle = ProgressionStyle.BLUES,
        chordsPerBar = 3,
        tension = TensionProfile(
            innerBars = 4, outerBars = 16, outerDepth = 0.4f,
            volume = 0.55f,
            tonal = TonalTension(keyShift = 7, chromaticPassing = 0.3f),
            timing = 0.25f,
            evolution = EvolutionTension(
                timbreLow = 0.1f, timbreHigh = 0.8f, timbreProbability = 0.9f,
                morphLow = 0.2f, morphHigh = 0.65f, morphProbability = 0.6f,
                attackPoint = 0.35f, releaseSpeed = 0.25f,
            ),
        ),
    )
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class DarkBlitzVibe : VibeProvider {
    override val vibe = generateBaseVibe(
        name = "Dark Blitz",
        bpm = 75f,
        envelopeType = EnvelopeType.BLEND,
        rootNote = RootNote.D_SHARP,
        scaleType = ScaleType.MIXOLYDIAN,
        rhythmDensity = RhythmPattern.SPARSE,
        progressionStyle = ProgressionStyle.DARK,
        chordsPerBar = 2,
        stepCount = 32,
        tension = TensionProfile(
            innerBars = 8, outerBars = 32, outerDepth = 0.7f,
            volume = 0.7f,
            tonal = TonalTension(keyShift = 6, chromaticPassing = 0.5f),
            timing = 0.15f,
            evolution = EvolutionTension(
                timbreLow = 0.05f, timbreHigh = 0.9f, timbreProbability = 0.95f,
                morphLow = 0.1f, morphHigh = 0.85f, morphProbability = 0.8f,
                attackPoint = 0.5f, releaseSpeed = 0.1f,
            ),
        ),
    )
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class JazzBlitzVibe : VibeProvider {
    override val vibe = generateBaseVibe(
        name = "Jazz Blitz",
        bpm = 97f,
        envelopeType = EnvelopeType.BLEND,
        rootNote = RootNote.C,
        scaleType = ScaleType.MINOR_PENTATONIC,
        rhythmDensity = RhythmPattern.BACKBEAT,
        progressionStyle = ProgressionStyle.JAZZ,
        chordsPerBar = 2,
        stepCount = 32,
        tension = TensionProfile(
            innerBars = 4, outerBars = 16, outerDepth = 0.35f,
            volume = 0.4f,
            tonal = TonalTension(keyShift = 5, chromaticPassing = 0.55f),
            timing = 0.3f,
            evolution = EvolutionTension(
                timbreLow = 0.2f, timbreHigh = 0.85f, timbreProbability = 0.9f,
                morphLow = 0.15f, morphHigh = 0.75f, morphProbability = 0.7f,
                attackPoint = 0.25f, releaseSpeed = 0.3f,
            ),
        ),
    )
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class AscBlitzVibe : VibeProvider {
    override val vibe = generateBaseVibe(
        name = "Ascending Blitz",
        bpm = 86f,
        envelopeType = EnvelopeType.BLEND,
        rootNote = RootNote.C,
        scaleType = ScaleType.IN_SEN,
        rhythmDensity = RhythmPattern.FOUR_ON_FLOOR,
        progressionStyle = ProgressionStyle.ASCENDING,
        chordsPerBar = 2,
        stepCount = 22,
        tension = TensionProfile(
            innerBars = 6, outerBars = 24, outerDepth = 0.5f,
            volume = 0.65f,
            tonal = TonalTension(octaveShift = true, keyShift = 7, chromaticPassing = 0.35f),
            timing = 0.2f,
            evolution = EvolutionTension(
                timbreLow = 0.1f, timbreHigh = 0.7f, timbreProbability = 0.85f,
                morphLow = 0.2f, morphHigh = 0.8f, morphProbability = 0.75f,
                attackPoint = 0.6f, releaseSpeed = 0.15f,
            ),
        ),
    )
}

private fun generateBaseVibe(
    name: String,
    bpm: Float = 138f,
    envelopeType: EnvelopeType = EnvelopeType.AD,
    rootNote: RootNote = RootNote.G,
    scaleType: ScaleType = ScaleType.MINOR,
    rhythmDensity: RhythmPattern = RhythmPattern.DENSE_16TH,
    progressionStyle: ProgressionStyle = ProgressionStyle.POP,
    chordsPerBar: Int = 4,
    stepCount: Int = 16,
    tension: TensionProfile = TensionProfile(),
) = Vibe(
    name = name,
    bpm = bpm,
    envelopeType = envelopeType,
    rootNote = rootNote,
    scaleType = scaleType,
    energy = 0.75f,
    complexity = 0.5f,
    space = 0.25f,
    mood = 0.3f,
    genre = GenreProfile(
        swingAmount = 0.01f,
        ghostProbability = 0.30f,
        noteRangeLow = 36,
        noteRangeHigh = 66,
        rhythmDensity = rhythmDensity.density,
        progressionStyle = progressionStyle,
        chordsPerBar = chordsPerBar,
    ),
    tracks = listOf(
        TrackVoice(
            engineEdm = Engine.BD,
            engineSpace = Engine.BD,
            role = TrackRole.PERCUSSIVE,
            volume = 0.90f,
            pan = 0.00f,
            density = 0.60f,
            envelopeProfile = EnvelopeProfile.RHYTHM,
            macroMap = TrackMacroMap.RHYTHM
        ),
        TrackVoice(
            engineEdm = Engine.SD,
            engineSpace = Engine.SD,
            role = TrackRole.PERCUSSIVE,
            volume = 0.70f,
            pan = -0.10f,
            density = 0.45f,
            envelopeProfile = EnvelopeProfile.RHYTHM,
            macroMap = TrackMacroMap.RHYTHM
        ),
        TrackVoice(
            engineEdm = Engine.HH,
            engineSpace = Engine.HH,
            role = TrackRole.PERCUSSIVE,
            volume = 0.65f,
            pan = 0.15f,
            density = 0.80f,
            envelopeProfile = EnvelopeProfile.RHYTHM,
            macroMap = TrackMacroMap.RHYTHM
        ),
        TrackVoice(
            engineEdm = Engine.WSH,
            engineSpace = Engine.VA,
            role = TrackRole.MELODIC,
            volume = 0.80f,
            pan = 0.00f,
            density = 0.50f,
            envelopeProfile = EnvelopeProfile.MELODIC,
            macroMap = TrackMacroMap.MELODIC,
            noteRangeLow = 30,
            noteRangeHigh = 55,
            reverbBrightness = 0.5f,
            evolution = Evolution(pitch = PitchEvolution.Contour()),
        ),
        TrackVoice(
            engineEdm = Engine.CHD,
            engineSpace = Engine.CHD,
            role = TrackRole.MELODIC,
            volume = 0.65f,
            pan = -0.20f,
            density = 0.40f,
            envelopeProfile = EnvelopeProfile.MELODIC,
            macroMap = TrackMacroMap.MELODIC,
            noteRangeLow = 48,
            noteRangeHigh = 72,
            reverbBrightness = 0.5f,
            glideRate = 0.05f,
            evolution = Evolution(pitch = PitchEvolution.Contour()),
        ),
        TrackVoice(
            engineEdm = Engine.WSH,
            engineSpace = Engine.ENS,
            role = TrackRole.MELODIC,
            volume = 0.50f,
            pan = 0.25f,
            density = 0.25f,
            envelopeProfile = EnvelopeProfile.EFFECT,
            macroMap = TrackMacroMap.EFFECT,
            modLfoRate = 0.5f,
            modLfoDepth = 0.3f,
            modLfoShape = 0.3f,
            modLfoCoupling = 0.1f,
            holdProbability = 0.1f,
            holdLengthMin = 2,
            holdLengthMax = 4,
            reverbSend = 0.1f,
            delaySend = 0.15f,
            noteRangeLow = 36,
            noteRangeHigh = 60,
            reverbBrightness = 0.5f
        ),
        TrackVoice(
            engineEdm = Engine.NSE,
            engineSpace = Engine.PAR,
            role = TrackRole.PERCUSSIVE,
            volume = 0.35f,
            pan = -0.30f,
            density = 0.15f,
            envelopeProfile = EnvelopeProfile.EFFECT,
            macroMap = TrackMacroMap.EFFECT,
            modLfoRate = 0.4f,
            modLfoDepth = 0.25f,
            modLfoShape = 0.4f,
            modLfoCoupling = 0.1f,
            holdProbability = 0.05f,
            holdLengthMin = 2,
            holdLengthMax = 3,
            reverbSend = 0.1f,
            delaySend = 0.1f,
            noteRangeLow = 36,
            noteRangeHigh = 60,
            reverbBrightness = 0.5f
        ),
        TrackVoice(
            engineEdm = Engine.NES,
            engineSpace = Engine.NES,
            role = TrackRole.MELODIC,
            volume = 0.20f,
            pan = 0.35f,
            density = 0.10f,
            envelopeProfile = EnvelopeProfile.WILD,
            macroMap = TrackMacroMap.WILD,
            modLfoRate = 0.6f,
            modLfoDepth = 0.2f,
            modLfoShape = 0.5f,
            modLfoCoupling = 0.15f,
            holdProbability = 0.0f,
            holdLengthMin = 2,
            holdLengthMax = 4,
            reverbSend = 0.15f,
            delaySend = 0.1f,
            noteRangeLow = 36,
            noteRangeHigh = 66,
            reverbBrightness = 0.5f,
            glideRate = 0.05f
        ),
    ),
    stepCount = stepCount,
    tension = tension,
    effects = VibeEffects(
        delayTimeA = 0.12f,
        delayTimeB = 0.25f,
        delayFeedback = 0.2f,
        delayDamping = 0.3f,
        reverbSize = 0.35f,
        reverbDamping = 0.3f,
        reverbBrightness = 0.6f,
    )
)
