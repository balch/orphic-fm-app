package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.anonmalies.CrossfadeAnomaly
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.EvolutionTension
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.MacroSource
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.ProgressionAnchor
import org.balch.orpheus.features.pulsar.models.ProgressionStyle
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SectionTransition
import org.balch.orpheus.features.pulsar.models.SoloMode
import org.balch.orpheus.features.pulsar.models.TensionProfile
import org.balch.orpheus.features.pulsar.models.TonalTension
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.chords
import org.balch.orpheus.features.pulsar.models.row

/**
 * Voltage Strut — peak-time EDM with funky electronic drums and a double-bass rig.
 *
 * Punchy 4-on-floor kick, snappy electronic snare, dense swung 16th hats. The
 * signature move: a main syncopated hook bass (WSH) riding a lick on track 3,
 * paired with a steady sub-bass pedal (VCF, locked to root) droning in the low
 * register on track 5 — the background anchor that keeps the bottom end moving
 * even when the hook rests. FX tracks are strictly percussive (NSE, PAR) — ticks,
 * claps, and tuned clicks, no pads. Built for the floor.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class VoltageStrutVibe : VibeProvider {
    override val name: String = "Voltage Strut"
    override val vibe: Vibe by lazy {
        Vibe(
            name = name,
            album = Album.RIF,
            bpm = 126f,
            envelopeType = EnvelopeType.AD,
            rootNote = RootNote.A,
            scaleType = ScaleType.PHRYGIAN,
            // Syncopated funky hook — rests + stabs, leans forward on the "and" of 2.
            // Sits in the low register for the strutting bass feel.
            lick = Lick(
                steps = listOf(
                    LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.95f),
                    LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.65f),
                    LickStep(scaleDegree = -3, duration = 0.5f, velocity = 0.85f),
                    LickStep(scaleDegree = 2, duration = 0.25f, velocity = 0.80f),
                    LickStep(scaleDegree = 0, duration = 0.25f, velocity = 0.90f),
                    LickStep(scaleDegree = -1, duration = 0.5f, velocity = 0.75f),
                    LickStep(scaleDegree = 4, duration = 0.25f, velocity = 0.85f),
                    LickStep(scaleDegree = 2, duration = 0.25f, velocity = 0.80f),
                    LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.90f),
                ),
                loopLength = 4,
            ),
            lickMutation = 0.35f,
            anomalies = listOf(
                CrossfadeAnomaly(probability = .005f)
            ),
            band = Band(
                members = listOf(
                    BandMember(
                        "Drummer", listOf(0, 1, 2), alwaysActive = true,
                        loudness = 0.8f, creativity = 0.35f
                    ),
                    // Both basses under one member so they move as a unit during handoff.
                    BandMember(
                        "Bassist", listOf(3, 5), alwaysActive = true,
                        loudness = 0.85f, creativity = 0.5f
                    ),
                    BandMember(
                        "Lead", listOf(4),
                        loudness = 0.6f, creativity = 0.65f
                    ),
                    BandMember(
                        "FX", listOf(6, 7),
                        loudness = 0.4f, creativity = 0.75f
                    ),
                ),
                handoffMatrix = bandMatrix(
                    //           DRUM   BASS   LEAD   FX
                    "Drummer" to row(0.00f, 0.30f, 0.50f, 0.20f),
                    "Bassist" to row(0.15f, 0.00f, 0.55f, 0.30f),
                    "Lead"    to row(0.20f, 0.40f, 0.00f, 0.40f),
                    "FX"      to row(0.20f, 0.30f, 0.50f, 0.00f),
                ),
                pullInMatrix = bandMatrix(
                    "Drummer" to row(0.00f, 0.40f, 0.30f, 0.20f),
                    "Bassist" to row(0.35f, 0.00f, 0.45f, 0.25f),
                    "Lead"    to row(0.25f, 0.45f, 0.00f, 0.30f),
                    "FX"      to row(0.20f, 0.30f, 0.35f, 0.00f),
                ),
                pullInBarsMin = 2, pullInBarsMax = 4,
                barsPerLeadMin = 4, barsPerLeadMax = 8,
            ),
            energy = 0.80f,
            complexity = 0.55f,
            space = 0.35f,
            mood = 0.45f,
            deep = 0.40f,
            genre = GenreProfile(
                // Nearly straight — slight 0.04 pocket keeps it from sounding sterile.
                swingAmount = 0.04f,
                // Funky ghosts on drums and FX.
                ghostProbability = 0.35f,
                noteRangeLow = 33,
                noteRangeHigh = 66,
                rhythmDensity = RhythmPattern.DENSE_16TH.density,
                // i–VI–VII–i — classic EDM minor loop; MODAL matrix keeps it cyclic.
                progressionStyle = ProgressionStyle.MODAL,
                chordsPerBar = 2,
                customProgression = chords(3, 3, 3, 0, 6, 5),
            ),
            progressionAnchor = ProgressionAnchor.EVERY_2,
            progressionDriftRange = 0.52f,
            tracks = listOf(
                // 0: electronic kick — locked 4-on-floor, REPEAT (the anchor).
                OrpheusEngine(
                    engineId = OrpheusEngineId.FM,
                    volume = 0.95f,
                    timbre = 0.35f,
                    morph = 0.30f,
                ).let { kick ->
                    TrackVoice(
                        engineEdm = kick,
                        engineSpace = kick,
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.55f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 1: electronic snare — funky backbeat with mutation, clappy edge.
                OrpheusEngine(
                    engineId = OrpheusEngineId.PD,
                    volume = 0.70f,
                    harmonics = 0.55f,
                    timbre = 0.55f,
                    morph = 0.45f,
                ).let { snare ->
                    TrackVoice(
                        engineEdm = snare,
                        engineSpace = snare.copy(engineId = OrpheusEngineId.NSE),
                        role = TrackRole.Percussive,
                        pan = -0.10f,
                        density = 0.40f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 2: dense hats — busy 16ths with ghosts, MUTATE for funk variation.
                OrpheusEngine(engineId = OrpheusEngineId.PAR, volume = 0.65f).let { hat ->
                    TrackVoice(
                        engineEdm = hat,
                        engineSpace = hat,
                        role = TrackRole.Percussive,
                        pan = 0.20f,
                        density = 0.80f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.RHYTHM,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 3: MAIN BASS — waveshaping hook, plays the lick, follows the chord root.
                OrpheusEngine(
                    engineId = OrpheusEngineId.WSH,
                    volume = 0.80f,
                    harmonics = 0.55f,
                    timbre = 0.55f,
                    morph = 0.45f,
                    noteRangeLow = 40,
                    noteRangeHigh = 58,
                    reverbBrightness = 0.45f,
                    reverbSend = 0.10f,
                    glideRate = 0.05f,
                ).let { bass ->
                    TrackVoice(
                        engineEdm = bass,
                        engineSpace = bass,
                        role = TrackRole.Melodic(
                            chordFollow = ChordFollow.ROOT_ONLY,
                            lickMode = LickMode.Fill,
                        ),
                        pan = 0.00f,
                        density = 0.60f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 4: lead — glassy FM stabs, moderate density, energy-driven.
                // OrpheusEngineId.SIX_OP_FM_2 + harmonics=0.50 lands on patch idx 16 = "Xylophone". At this
                // track's C4–G6 register with reverb=0.60, delay=0.30 and MUTATE strategy,
                // the mallet attack reads as glassy/bell-tone FM stabs — NOT a literal
                // xylophone. (Tried OrpheusEngineId.SIX_OP_FM idx 17 "Insert 1 BRASSY" — actual brass
                // stabs — but the strut character wanted the percussive top-end clarity
                // that the mallet patch provides at high register.) Crossfades to VA
                // (clean analog poly) when Energy drops for a softer space-side voice.
                OrpheusEngine(
                    engineId = OrpheusEngineId.DX2,
                    volume = 0.55f,
                    harmonics = 0.4902f,                           // DX2 bucket edge: idx 15 "Clav 3" or 16 "Xylophone" by prior load
                    harmonicsMacroSource = MacroSource.ENERGY,     // drop brightens, break dims
                    harmonicsMacroRange = 0.05f,                   // ±~1.5 patches: Clav 3 ↔ Xylophone ↔ Marimba ↔ Vibe 1
                    timbre = 0.55f,
                    morph = 0.45f,
                    noteRangeLow = 60,
                    noteRangeHigh = 82,
                    reverbBrightness = 0.60f,
                    reverbSend = 0.25f,
                    delaySend = 0.30f,
                    glideRate = 0.10f,
                ).let { lead ->
                    TrackVoice(
                        engineEdm = lead,
                        engineSpace = lead.copy(engineId = OrpheusEngineId.VA),
                        role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
                        pan = 0.05f,
                        density = 0.40f,
                        envelopeProfile = EnvelopeProfile.MELODIC,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.MUTATE,
                    )
                },
                // 5: SECOND BASS — steady sub pedal. FIXED chord-follow + REPEAT + deep
                // register = locked drone under the main hook. Density 0.55 gives it a
                // steady pulse (roughly 8ths) rather than a single sustained drone.
                OrpheusEngine(
                    engineId = OrpheusEngineId.VCF,
                    volume = 0.55f,
                    harmonics = 0.30f,   // dark, round sub
                    timbre = 0.25f,
                    morph = 0.30f,
                    noteRangeLow = 28,
                    noteRangeHigh = 40,
                    reverbBrightness = 0.25f,
                    reverbSend = 0.05f,
                    glideRate = 0.0f,
                ).let { sub ->
                    TrackVoice(
                        engineEdm = sub,
                        engineSpace = sub.copy(engineId = OrpheusEngineId.PD),
                        role = TrackRole.Percussive,
                        pan = 0.00f,
                        density = 0.25f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.MELODIC,
                        barStrategy = BarStrategy.REPEAT,
                    )
                },
                // 6: PERCUSSIVE FX — noise hits, ticks/snaps. No pad, no sustain.
                OrpheusEngine(
                    engineId = OrpheusEngineId.NSE,
                    volume = 0.40f,
                    harmonics = 0.60f,
                    timbre = 0.65f,
                    morph = 0.50f,
                    modLfoRate = 0.3f,
                    modLfoDepth = 0.25f,
                    reverbBrightness = 0.70f,
                    reverbSend = 0.25f,
                    delaySend = 0.30f,
                ).let { fx ->
                    TrackVoice(
                        engineEdm = fx,
                        engineSpace = fx,
                        role = TrackRole.Percussive,
                        pan = 0.30f,
                        density = 0.25f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
                // 7: PERCUSSIVE FX — particle clicks, tuned-ish transients.
                OrpheusEngine(
                    engineId = OrpheusEngineId.GRN,
                    volume = 0.55f,
                    harmonics = 0.45f,
                    timbre = 0.60f,
                    morph = 0.35f,
                    modLfoRate = 0.4f,
                    modLfoDepth = 0.25f,
                    reverbBrightness = 0.65f,
                    reverbSend = 0.20f,
                    delaySend = 0.25f,
                ).let { particles ->
                    TrackVoice(
                        engineEdm = particles,
                        engineSpace = particles,
                        role = TrackRole.Percussive,
                        pan = -0.30f,
                        density = 0.22f,
                        envelopeProfile = EnvelopeProfile.RHYTHM,
                        macroMap = TrackMacroMap.EFFECT,
                        barStrategy = BarStrategy.INDEPENDENT,
                    )
                },
            ),
            stepCount = 32,
            tension = TensionProfile(
                // Classic EDM: 4-bar inner build, 16-bar outer phrase (verse/chorus/drop).
                innerBars = 4,
                outerBars = 16,
                outerDepth = 0.70f,
                volume = 0.45f,
                tonal = TonalTension(octaveShift = true, chromaticPassing = 0.08f),
                timing = 0.10f,
                evolution = EvolutionTension(
                    timbreLow = 0.25f, timbreHigh = 0.70f, timbreProbability = 0.85f,
                    morphLow = 0.30f, morphHigh = 0.65f, morphProbability = 0.6f,
                    attackPoint = 0.45f, releaseSpeed = 0.35f,
                ),
            ),
            effects = VibeEffects(
                delayTimeA = 0.1875f,   // 16th
                delayTimeB = 0.375f,    // dotted-8th
                delayFeedback = 0.35f,
                delayDamping = 0.4f,
                reverbSize = 0.4f,
                reverbDamping = 0.55f,
                reverbBrightness = 0.65f,
                deepFloor = 0.25f,
            ),
            arrangement = Arrangement(
                introIndex = 0,
                lengthSeconds = 90..180,
                sections = listOf(
                    // 0: intro — percussion + sub bass only, build anticipation.
                    Section(
                        name = "intro",
                        barsMin = 4, barsMax = 6,
                        transitions = listOf(SectionTransition(targetIndex = 1, weight = 1.0f)),
                        macroOverrides = MacroOverrides(energy = 0.55f, complexity = 0.5f),
                    ),
                    // 1: groove — full kit, both basses, lead stabs.
                    Section(
                        name = "groove",
                        barsMin = 6, barsMax = 6,
                        transitions = listOf(
                            SectionTransition(targetIndex = 2, weight = 0.5f),
                            SectionTransition(targetIndex = 3, weight = 0.3f),
                            SectionTransition(targetIndex = 4, weight = 0.2f),
                        ),
                        recencyDecay = 0.5f,
                    ),
                    // 2: build — rising tension, more hats, filter opening on sub bass.
                    Section(
                        name = "build",
                        barsMin = 4, barsMax = 8,
                        transitions = listOf(
                            SectionTransition(targetIndex = 3, weight = 0.75f),
                            SectionTransition(targetIndex = 1, weight = 0.25f),
                        ),
                        recencyDecay = 0.5f,
                        macroOverrides = MacroOverrides(
                            energy = 1.35f, complexity = 1.4f, space = 0.8f, mood = 1.15f,
                        ),
                    ),
                    // 3: drop — peak energy, band jams, solos cycle.
                    Section(
                        name = "drop",
                        barsMin = 8, barsMax = 12,
                        transitions = listOf(
                            SectionTransition(targetIndex = 4, weight = 0.5f),
                            SectionTransition(targetIndex = 1, weight = 0.3f),
                            SectionTransition(targetIndex = 2, weight = 0.2f),
                        ),
                        recencyDecay = 0.4f,
                        macroOverrides = MacroOverrides(
                            energy = 1.5f, complexity = 1.3f, mood = 1.25f,
                        ),
                        soloMode = SoloMode.Jam(probability = 0.8f, lickInfluence = 0.6f),
                    ),
                    // 4: break — drums + sub bass hold it down, lead rests, FX rises.
                    Section(
                        name = "break",
                        barsMin = 4, barsMax = 6,
                        transitions = listOf(
                            SectionTransition(targetIndex = 2, weight = 0.6f),
                            SectionTransition(targetIndex = 1, weight = 0.4f),
                        ),
                        recencyDecay = 0.4f,
                        macroOverrides = MacroOverrides(
                            energy = 0.55f, complexity = 0.7f, space = 1.4f,
                        ),
                    ),
                ),
            ),
    )
    }
}
