package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.ProgressionAnchor
import org.balch.orpheus.features.pulsar.models.ProgressionStyle
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.TensionProfile
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects

/**
 * Garage Blitz — raw two-note power-riff stomp.
 *
 * The riff IS the song: two notes alternating (root ↔ step up), both
 * bass and lead guitar locked on it. Backbeat drums, two-chord
 * progression (i ↔ VII), and lickMutation evolves the two-note ostinato
 * so it never feels mechanical.
 *
 * Broken out of generateBaseVibe() so the simple 2-note riff aesthetic
 * doesn't apply to Floor/Dark/Jazz/Ascending Blitz.
 */

/*
@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class GarageBlitzVibe : VibeProvider {
    override val name: String = "Garage Blitz"
    override val vibe: org.balch.orpheus.features.pulsar.models.Vibe by lazy {
        Vibe(
            name = "Garage Blitz",
            bpm = 132f,  // close to the original's tempo
            envelopeType = EnvelopeType.AD,
            rootNote = RootNote.G,
            scaleType = ScaleType.MAJOR,
            // The signature riff — 2 notes in G minor, alternating at 8th-note clip.
            // G = scale degree 0, A = scale degree 1 (whole step up in natural minor).
            // Slight velocity variation gives it a swagger; mutation lets it drift over time.
            lick = Lick(
                steps = listOf(
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),   // G — hit
                    LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.85f),   // A — slide up
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.90f),   // G — back
                    LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),   // A — slide up
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.95f),   // G
                    LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.85f),   // A
                    LickStep(scaleDegree = 0, duration = 1.0f, velocity = 0.95f),   // G — longer hold
                ),
                loopLength = 8,
            ),
            lickMutation = 0.45f,  // moderate — evolves without losing the 2-note identity
            energy = 0.75f,
            complexity = 0.5f,
            space = 0.2f,
            mood = 0.35f,
            genre = GenreProfile(
                swingAmount = 0.01f,
                ghostProbability = 0.25f,
                noteRangeLow = 36,
                noteRangeHigh = 66,
                rhythmDensity = RhythmPattern.BACKBEAT.density,  // not dense — punchy backbeat
                progressionStyle = ProgressionStyle.DARK,   // emphasizes i-VII-ish motion
                chordsPerBar = 2,  // slower harmonic motion (2-chord alternation feel)
            ),
            progressionAnchor = ProgressionAnchor.EVERY_4,
            progressionDriftRange = 0.15f,  // keep it locked on the 2-chord idea
            tracks = listOf(
                // Track 0: Kick — pounding on 1 and 3
                OrpheusEngine(engineId = OrpheusEngineId.ANALOG_BASS_DRUM, volume = 0.95f).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f, density = 0.55f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 1: Snare — backbeat on 2 and 4, the garage-rock stomp
                OrpheusEngine(engineId = OrpheusEngineId.ANALOG_SNARE_DRUM, volume = 0.80f).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare,
                        role = TrackRole.Percussive,
                        pan = -0.10f, density = 0.40f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 2: Hihat — steady 8th notes, driving
                OrpheusEngine(engineId = OrpheusEngineId.METALLIC_HI_HAT, volume = 0.60f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.15f, density = 0.60f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 3: Bass — WSH (gritty) plays the riff locked to chord root.
                // Bass doubles the rhythm of the lead but just pounds on root.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WAVESHAPING,
                    volume = 0.75f,
                    noteRangeLow = 40, noteRangeHigh = 52,
                    reverbBrightness = 0.4f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass.copy(engineId = OrpheusEngineId.VIRTUAL_ANALOG),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
                        pan = 0.00f, density = 0.50f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 4: Lead guitar — WSH with distortion character, plays THE riff.
                // LickMode.Fill spans the full bar; chordFollow = FOLLOW so it transposes
                // with the 2-chord progression (i ↔ VII) — the whole riff moves together.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WAVESHAPING,
                    volume = 0.85f,
                    harmonics = 0.75f, timbre = 0.6f, morph = 0.4f,  // gritty distorted tone
                    noteRangeLow = 52, noteRangeHigh = 67,
                    reverbBrightness = 0.55f,
                ).let { lead ->
                    TrackVoice(
                        engineEdm = lead,
                        engineSpace = lead,
                        role = TrackRole.Melodic(lickMode = LickMode.Fill),
                        pan = 0.00f, density = 0.60f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // Track 5: Organ stabs — Keith Richards humanization turned up.
                // Drops/ghosts/octave jumps keep the organ loose and alive — garage
                // rock organ should feel stomped at, not programmed.
                OrpheusEngine(
                    engineId = OrpheusEngineId.CHORD,
                    volume = 0.45f,
                    noteRangeLow = 48, noteRangeHigh = 72,
                    reverbBrightness = 0.5f,
                ).let { organ ->
                    TrackVoice(
                        engineEdm = organ,
                        engineSpace = organ,
                        role = TrackRole.Chordal(
                            comping = ChordComping(
                                style = CompingStyle.ROCK_DOWNBEATS,
                                sectionInversion = SectionInversion.FIRST_INVERSION,  // organ sits lifted
                                humanization = CompingHumanization(
                                    dropProbability = 0.18f,       // occasional missed stab
                                    ghostProbability = 0.22f,      // extra hits between beats
                                    octaveJumpProbability = 0.12f, // some stabs jump an octave
                                    extensionProbability = 0.15f,  // bluesy 7ths/9ths color
                                ),
                                fills = CompingFills(
                                    everyNBars = 4,                    // every 4 bars: organ rip
                                    fillType = FillType.ASCENDING_ARP,
                                    skipProbability = 0.3f,            // 70% chance it fires
                                ),
                            ),
                        ),
                        pan = -0.20f, density = 0.25f,  // bumped vol so humanization is audible
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                    )
                },
                // Track 6: Noise/tambourine — sparse garage texture
                OrpheusEngine(
                    engineId = OrpheusEngineId.NOISE,
                    volume = 0.25f,
                    modLfoRate = 0.4f, modLfoDepth = 0.25f, modLfoShape = 0.4f, modLfoCoupling = 0.1f,
                    holdProbability = 0.05f,
                    reverbSend = 0.15f, delaySend = 0.1f,
                    noteRangeLow = 48, noteRangeHigh = 72, reverbBrightness = 0.5f,
                ).let { fx ->
                    TrackVoice(
                        engineEdm = fx,
                        engineSpace = fx.copy(engineId = OrpheusEngineId.PARTICLE),
                        role = TrackRole.Percussive,
                        pan = -0.30f, density = 0.10f,
                        envelopeProfile = EnvelopeProfile.EFFECT,
                        macroMap = TrackMacroMap.EFFECT,
                    )
                },
                // Track 7: silent placeholder
                OrpheusEngine(engineId = OrpheusEngineId.CHIPTUNE, volume = 0.0f).let { silent ->
                    TrackVoice(
                        engineEdm = silent,
                        engineSpace = silent,
                        role = TrackRole.Melodic(),
                        density = 0.0f,
                        envelopeProfile = EnvelopeProfile.WILD,
                        macroMap = TrackMacroMap.WILD,
                    )
                },
            ),
            stepCount = 16,
            tension = TensionProfile(
                innerBars = 4, outerBars = 12, outerDepth = 0.3f,
                volume = 0.3f,
                tonal = TonalTension(chromaticPassing = 0.1f),
                timing = 0.15f,
                evolution = EvolutionTension(
                    timbreLow = 0.5f, timbreHigh = 0.85f, timbreProbability = 0.7f,  // the distortion breathes
                    morphLow = 0.3f, morphHigh = 0.55f, morphProbability = 0.5f,
                    attackPoint = 0.5f, releaseSpeed = 0.3f,
                ),
                spurtChance = 0.15f,  // occasional lick mutation spurts — the riff gets wilder sometimes
            ),
            effects = VibeEffects(
                delayTimeA = 0.1f,
                delayTimeB = 0.2f,
                delayFeedback = 0.15f,
                delayDamping = 0.3f,
                reverbSize = 0.25f,     // small room, not lush — garage feel
                reverbDamping = 0.4f,
                reverbBrightness = 0.6f,
            ),
    )
    }
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class FloorBlitzVibe : VibeProvider {
    override val name: String = "Floor Blitz"
    override val vibe: org.balch.orpheus.features.pulsar.models.Vibe by lazy {
        generateBaseVibe(
            name = "Floor Blitz",
            bpm = 120f,
            envelopeType = EnvelopeType.AD,
            rootNote = RootNote.A,
            scaleType = ScaleType.PENTATONIC,
            rhythmDensity = RhythmPattern.FOUR_ON_FLOOR,
            progressionStyle = ProgressionStyle.BLUES,
            chordsPerBar = 3,
            progressionAnchor = ProgressionAnchor.EVERY_4,
            progressionDriftRange = 0.2f,
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
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class DarkBlitzVibe : VibeProvider {
    override val name: String = "Dark Blitz"
    override val vibe: org.balch.orpheus.features.pulsar.models.Vibe by lazy {
        generateBaseVibe(
            name = "Dark Blitz",
            bpm = 75f,
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.D_SHARP,
            scaleType = ScaleType.MIXOLYDIAN,
            rhythmDensity = RhythmPattern.SPARSE,
            progressionStyle = ProgressionStyle.DARK,
            chordsPerBar = 2,
            stepCount = 32,
            progressionAnchor = ProgressionAnchor.EVERY_8,
            progressionDriftRange = 0.25f,
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
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class JazzBlitzVibe : VibeProvider {
    override val name: String = "Jazz Blitz"
    override val vibe: org.balch.orpheus.features.pulsar.models.Vibe by lazy {
        generateBaseVibe(
            name = "Jazz Blitz",
            bpm = 97f,
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.C,
            scaleType = ScaleType.MINOR_PENTATONIC,
            rhythmDensity = RhythmPattern.BACKBEAT,
            progressionStyle = ProgressionStyle.JAZZ,
            chordsPerBar = 2,
            stepCount = 32,
            progressionAnchor = ProgressionAnchor.EVERY_8,
            progressionDriftRange = 0.3f,
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
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class AscBlitzVibe : VibeProvider {
    override val name: String = "Ascending Blitz"
    override val vibe: org.balch.orpheus.features.pulsar.models.Vibe by lazy {
        generateBaseVibe(
            name = "Ascending Blitz",
            bpm = 86f,
            envelopeType = EnvelopeType.BLEND,
            rootNote = RootNote.C,
            scaleType = ScaleType.IN_SEN,
            rhythmDensity = RhythmPattern.FOUR_ON_FLOOR,
            progressionStyle = ProgressionStyle.ASCENDING,
            chordsPerBar = 2,
            stepCount = 22,
            progressionAnchor = ProgressionAnchor.EVERY_4,
            progressionDriftRange = 0.2f,
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
}

 */

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
    progressionAnchor: ProgressionAnchor = ProgressionAnchor.EVERY_4,
    progressionDriftRange: Float = 0.15f,
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
    progressionAnchor = progressionAnchor,
    progressionDriftRange = progressionDriftRange,
    tracks = listOf(
        TrackVoice(
            engineEdm = OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.90f),
            engineSpace = OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.90f),
            role = TrackRole.Percussive,
            pan = 0.00f,
            density = 0.60f,
            envelopeProfile = EnvelopeProfile.RHYTHM,
            macroMap = TrackMacroMap.RHYTHM
        ),
        TrackVoice(
            engineEdm = OrpheusEngine(engineId = OrpheusEngineId.SD, volume = 0.70f),
            engineSpace = OrpheusEngine(engineId = OrpheusEngineId.SD, volume = 0.70f),
            role = TrackRole.Percussive,
            pan = -0.10f,
            density = 0.45f,
            envelopeProfile = EnvelopeProfile.RHYTHM,
            macroMap = TrackMacroMap.RHYTHM
        ),
        TrackVoice(
            engineEdm = OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.65f),
            engineSpace = OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.65f),
            role = TrackRole.Percussive,
            pan = 0.15f,
            density = 0.80f,
            envelopeProfile = EnvelopeProfile.RHYTHM,
            macroMap = TrackMacroMap.RHYTHM
        ),
        // Bass: locked pocket — REPEAT + ROOT_ONLY, noteRangeLow 30→40 (E2 punch).
        // Removed PitchEvolution.Contour Markov drift — it was compounding with chord
        // transposition and making the bass wander.
        TrackVoice(
            engineEdm = OrpheusEngine(
                engineId = OrpheusEngineId.WSH,
                volume = 0.65f,
                noteRangeLow = 40,
                noteRangeHigh = 55,
                reverbBrightness = 0.5f,
            ),
            engineSpace = OrpheusEngine(
                engineId = OrpheusEngineId.VA,
                volume = 0.65f,
                noteRangeLow = 40,
                noteRangeHigh = 55,
                reverbBrightness = 0.5f,
            ),
            role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
            pan = 0.00f,
            density = 0.50f,
            envelopeProfile = EnvelopeProfile.MELODIC,
            macroMap = TrackMacroMap.MELODIC,
            barStrategy = BarStrategy.REPEAT,
        ),
        // CHD pad: REPEAT + no Markov. CHD handles chord voicing natively; progression
        // transposition moves chords cleanly without Markov stacking on top.
        TrackVoice(
            engineEdm = OrpheusEngine(
                engineId = OrpheusEngineId.CHD,
                volume = 0.52f,
                noteRangeLow = 48,
                noteRangeHigh = 72,
                reverbBrightness = 0.5f,
                glideRate = 0.05f,
            ),
            engineSpace = OrpheusEngine(
                engineId = OrpheusEngineId.CHD,
                volume = 0.52f,
                noteRangeLow = 48,
                noteRangeHigh = 72,
                reverbBrightness = 0.5f,
                glideRate = 0.05f,
            ),
            role = TrackRole.Melodic(),
            pan = -0.20f,
            density = 0.40f,
            envelopeProfile = EnvelopeProfile.MELODIC,
            macroMap = TrackMacroMap.MELODIC,
            barStrategy = BarStrategy.REPEAT,
        ),
        TrackVoice(
            engineEdm = OrpheusEngine(
                engineId = OrpheusEngineId.WSH,
                volume = 0.50f,
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
                reverbBrightness = 0.5f,
            ),
            engineSpace = OrpheusEngine(
                engineId = OrpheusEngineId.ENS,
                volume = 0.50f,
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
                reverbBrightness = 0.5f,
            ),
            role = TrackRole.Melodic(),
            pan = 0.25f,
            density = 0.25f,
            envelopeProfile = EnvelopeProfile.EFFECT,
            macroMap = TrackMacroMap.EFFECT,
        ),
        TrackVoice(
            engineEdm = OrpheusEngine(
                engineId = OrpheusEngineId.NSE,
                volume = 0.35f,
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
                reverbBrightness = 0.5f,
            ),
            engineSpace = OrpheusEngine(
                engineId = OrpheusEngineId.PAR,
                volume = 0.35f,
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
                reverbBrightness = 0.5f,
            ),
            role = TrackRole.Percussive,
            pan = -0.30f,
            density = 0.15f,
            envelopeProfile = EnvelopeProfile.EFFECT,
            macroMap = TrackMacroMap.EFFECT,
        ),
        TrackVoice(
            engineEdm = OrpheusEngine(
                engineId = OrpheusEngineId.NES,
                volume = 0.20f,
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
                glideRate = 0.05f,
            ),
            engineSpace = OrpheusEngine(
                engineId = OrpheusEngineId.NES,
                volume = 0.20f,
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
                glideRate = 0.05f,
            ),
            role = TrackRole.Melodic(),
            pan = 0.35f,
            density = 0.10f,
            envelopeProfile = EnvelopeProfile.WILD,
            macroMap = TrackMacroMap.WILD,
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
