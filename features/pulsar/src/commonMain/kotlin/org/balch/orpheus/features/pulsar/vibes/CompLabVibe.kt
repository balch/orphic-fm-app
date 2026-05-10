package org.balch.orpheus.features.pulsar.vibes

import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.ProgressionAnchor
import org.balch.orpheus.features.pulsar.models.ProgressionStyle
import org.balch.orpheus.features.pulsar.models.RhythmPattern
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SectionTransition
import org.balch.orpheus.features.pulsar.models.TensionProfile
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects

// ═══════════════════════════════════════════════════════════════════════════
// Comp Lab family — each vibe showcases one CompingStyle with tasteful
// default tuning across all Phase 2 dimensions (hold, arp, inversion,
// humanization, fills). Dial complexity/mood to engage evolution.
// ═══════════════════════════════════════════════════════════════════════════

/*

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class CompPadVibe : VibeProvider {
    override val name: String = "Comp Pad"
    // PAD: sustained chords with section-level comping override demo.
    // verse=PAD default, chorus=FUNK_STABS+FIRST_INVERSION, breakdown=PAD+ROOT_ONLY chord-follow
    override val vibe: org.balch.orpheus.features.pulsar.models.Vibe by lazy {
        generateCompLabVibe(
            name = "Comp Pad",
            compingOverride = ChordComping(
                style = CompingStyle.PAD,
                sectionInversion = SectionInversion.FOLLOW_STYLE,
                humanization = CompingHumanization(
                    extensionProbability = 0.15f,  // subtle 9th/11th color
                ),
                fills = CompingFills(everyNBars = 16, fillType = FillType.ASCENDING_ARP, skipProbability = 0.5f),
            ),
            arrangement = Arrangement(
                introIndex = 0,
                sections = listOf(
                    Section(
                        name = "verse",
                        barsMin = 8, barsMax = 12,
                        transitions = listOf(SectionTransition(targetIndex = 1, weight = 1.0f)),
                        // verse stays on default PAD
                    ),
                    Section(
                        name = "chorus",
                        barsMin = 8, barsMax = 12,
                        transitions = listOf(SectionTransition(targetIndex = 2, weight = 1.0f)),
                        macroOverrides = MacroOverrides(energy = 1.3f),
                        compingStyle = CompingStyle.FUNK_STABS,          // chorus turns to FUNK
                        compingInversion = SectionInversion.FIRST_INVERSION,
                    ),
                    Section(
                        name = "breakdown",
                        barsMin = 4, barsMax = 8,
                        transitions = listOf(SectionTransition(targetIndex = 0, weight = 1.0f)),
                        macroOverrides = MacroOverrides(energy = 0.6f),
                        compingStyle = CompingStyle.PAD,                  // breakdown back to pad
                        chordFollow = ChordFollow.ROOT_ONLY,              // simpler harmony in breakdown
                    ),
                ),
            ),
    )
    }
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class CompRockVibe : VibeProvider {
    override val name: String = "Comp Rock"
    // ROCK: electric piano grace-note + root (PD engine, 2-note DOWN arp)
    // arpDirection=DOWN with voicing=2 plays 5th → root, giving a piano
    // "grace note landing on root" feel without the climbing blip.
    override val vibe: org.balch.orpheus.features.pulsar.models.Vibe by lazy {
        generateCompLabVibe(
            name = "Comp Rock",
            chordEngineEdm = OrpheusEngineId.PHASE_DISTORTION,
            chordEngineSpace = OrpheusEngineId.PHASE_DISTORTION,
            compingOverride = ChordComping(
                style = CompingStyle.ROCK_DOWNBEATS,
                sectionInversion = SectionInversion.ROOT_POSITION,
                arpMode = ArpMode.ALWAYS,
                arpSpeed = 0.6f,              // audibly rolled grace note, not blip
                arpDirection = ArpDirection.DOWN,  // lands on root
                humanization = CompingHumanization(
                    dropProbability = 0.10f,
                    octaveJumpProbability = 0.08f,
                    extensionProbability = 0.10f,
                ),
                fills = CompingFills(everyNBars = 4, fillType = FillType.ASCENDING_ARP),
            ),
    )
    }
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class CompFunkVibe : VibeProvider {
    override val name: String = "Comp Funk"
    // FUNK: clav-like PD root stabs, first inversion (voicing via extensions)
    override val vibe: org.balch.orpheus.features.pulsar.models.Vibe by lazy {
        generateCompLabVibe(
            name = "Comp Funk",
            bpm = 100f,
            chordEngineEdm = OrpheusEngineId.PHASE_DISTORTION,
            chordEngineSpace = OrpheusEngineId.PHASE_DISTORTION,
            compingOverride = ChordComping(
                style = CompingStyle.FUNK_STABS,
                sectionInversion = SectionInversion.FIRST_INVERSION,
                arpMode = ArpMode.NEVER,
                humanization = CompingHumanization(
                    dropProbability = 0.12f,
                    ghostProbability = 0.15f,
                    octaveJumpProbability = 0.05f,
                    extensionProbability = 0.15f,  // bumped for chord color
                ),
                fills = CompingFills(everyNBars = 8, fillType = FillType.ASCENDING_ARP, skipProbability = 0.3f),
            ),
    )
    }
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class CompSkaVibe : VibeProvider {
    override val name: String = "Comp Ska"
    // SKA: high off-beats, second inversion for brightness, tight stabs
    override val vibe: org.balch.orpheus.features.pulsar.models.Vibe by lazy {
        generateCompLabVibe(
            name = "Comp Ska",
            bpm = 130f,
            compingOverride = ChordComping(
                style = CompingStyle.SKA_UPSTROKES,
                sectionInversion = SectionInversion.SECOND_INVERSION,
                arpMode = ArpMode.AUTO,
                arpSpeed = 0.95f,         // almost simultaneous — sharp chord
                humanization = CompingHumanization(
                    dropProbability = 0.05f,       // ska should stay regular
                    octaveJumpProbability = 0.02f,
                ),
                fills = CompingFills(everyNBars = 8, fillType = FillType.ASCENDING_ARP, skipProbability = 0.6f),
            ),
    )
    }
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class CompBluesVibe : VibeProvider {
    override val name: String = "Comp Blues"
    // BLUES: electric piano root stabs on PD, heavy extensions for blues color
    override val vibe: org.balch.orpheus.features.pulsar.models.Vibe by lazy {
        generateCompLabVibe(
            name = "Comp Blues",
            bpm = 92f,
            rootNote = RootNote.E,
            progressionStyle = ProgressionStyle.BLUES,
            chordEngineEdm = OrpheusEngineId.PHASE_DISTORTION,
            chordEngineSpace = OrpheusEngineId.PHASE_DISTORTION,
            compingOverride = ChordComping(
                style = CompingStyle.BLUES_SHUFFLE,
                sectionInversion = SectionInversion.OPEN_VOICING,
                arpMode = ArpMode.NEVER,
                humanization = CompingHumanization(
                    dropProbability = 0.08f,
                    ghostProbability = 0.20f,
                    extensionProbability = 0.30f,  // heavy blues 7ths/9ths
                ),
                fills = CompingFills(everyNBars = 4, fillType = FillType.ASCENDING_ARP),
            ),
    )
    }
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class CompJazzVibe : VibeProvider {
    override val name: String = "Comp Jazz"
    // JAZZ: PD piano root stabs, busy altered-tone comping via extensions
    override val vibe: org.balch.orpheus.features.pulsar.models.Vibe by lazy {
        generateCompLabVibe(
            name = "Comp Jazz",
            bpm = 105f,
            progressionStyle = ProgressionStyle.JAZZ,
            scaleType = ScaleType.MINOR_PENTATONIC,
            chordEngineEdm = OrpheusEngineId.PHASE_DISTORTION,
            chordEngineSpace = OrpheusEngineId.PHASE_DISTORTION,
            compingOverride = ChordComping(
                style = CompingStyle.JAZZ_COMP,
                sectionInversion = SectionInversion.OPEN_VOICING,
                arpMode = ArpMode.NEVER,
                humanization = CompingHumanization(
                    dropProbability = 0.15f,
                    ghostProbability = 0.18f,
                    octaveJumpProbability = 0.10f,
                    extensionProbability = 0.35f,  // tons of altered tones
                ),
                fills = CompingFills(everyNBars = 4, fillType = FillType.ASCENDING_ARP, skipProbability = 0.2f),
            ),
    )
    }
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class CompReggaeVibe : VibeProvider {
    override val name: String = "Comp Reggae"
    // REGGAE: skank on 2 and 4, second inversion for upper-register punch
    override val vibe: org.balch.orpheus.features.pulsar.models.Vibe by lazy {
        generateCompLabVibe(
            name = "Comp Reggae",
            bpm = 78f,
            rootNote = RootNote.A,
            compingOverride = ChordComping(
                style = CompingStyle.REGGAE_SKANK,
                sectionInversion = SectionInversion.SECOND_INVERSION,
                arpMode = ArpMode.AUTO,
                arpSpeed = 0.95f,         // tight, simultaneous stab
                humanization = CompingHumanization(
                    dropProbability = 0.04f,
                    octaveJumpProbability = 0.03f,
                ),
                fills = CompingFills(everyNBars = 16, fillType = FillType.ASCENDING_ARP, skipProbability = 0.7f),
            ),
    )
    }
}

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<VibeProvider>())
class CompGospelVibe : VibeProvider {
    override val name: String = "Comp Gospel"
    // GOSPEL: gospel piano root stabs on PD, dense 8ths, chord color via extensions
    override val vibe: org.balch.orpheus.features.pulsar.models.Vibe by lazy {
        generateCompLabVibe(
            name = "Comp Gospel",
            bpm = 96f,
            rootNote = RootNote.G,
            chordEngineEdm = OrpheusEngineId.PHASE_DISTORTION,
            chordEngineSpace = OrpheusEngineId.PHASE_DISTORTION,
            compingOverride = ChordComping(
                style = CompingStyle.GOSPEL_STABS,
                sectionInversion = SectionInversion.FIRST_INVERSION,
                arpMode = ArpMode.NEVER,
                humanization = CompingHumanization(
                    dropProbability = 0.08f,
                    ghostProbability = 0.15f,
                    extensionProbability = 0.25f,  // gospel 9ths
                ),
                fills = CompingFills(everyNBars = 2, fillType = FillType.ASCENDING_ARP, skipProbability = 0.4f),
            ),
    )
    }
}

 */

// ═══════════════════════════════════════════════════════════════════════════
// Shared base: drums + bass + CHORDAL keys in a simple POP progression.
// Each family member passes a fully-tuned ChordComping override.
// ═══════════════════════════════════════════════════════════════════════════

private fun generateCompLabVibe(
    name: String,
    compingOverride: ChordComping,
    bpm: Float = 110f,
    rootNote: RootNote = RootNote.C,
    scaleType: ScaleType = ScaleType.MINOR,
    progressionStyle: ProgressionStyle = ProgressionStyle.POP,
    chordsPerBar: Int = 2,
    // Chord track engines — defaults work as PAD/pad-like, piano-flavored
    // vibes override to monophonic engines that arp naturally into chords.
    chordEngineEdm: OrpheusEngineId = OrpheusEngineId.CHD,
    chordEngineSpace: OrpheusEngineId = OrpheusEngineId.CHD,
    arrangement: Arrangement? = null,
) = Vibe(
    name = name,
    bpm = bpm,
    envelopeType = EnvelopeType.AD,
    rootNote = rootNote,
    scaleType = scaleType,
    energy = 0.6f,
    complexity = 0.5f,  // higher than 0.3 so humanization is actually audible
    space = 0.3f,
    mood = 0.5f,
    genre = GenreProfile(
        swingAmount = 0.05f,
        ghostProbability = 0.15f,
        noteRangeLow = 36,
        noteRangeHigh = 72,
        rhythmDensity = RhythmPattern.BACKBEAT.density,
        progressionStyle = progressionStyle,
        chordsPerBar = chordsPerBar,
    ),
    progressionAnchor = ProgressionAnchor.EVERY_8,
    progressionDriftRange = 0.7f,
    tracks = listOf(
        // Track 0: Kick
        OrpheusEngine(engineId = OrpheusEngineId.BD, volume = 0.85f).let { kick ->
            TrackVoice(
                engineEdm = kick,
                engineSpace = kick,
                role = TrackRole.Percussive,
                density = 0.5f,
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.REPEAT,
            )
        },
        // Track 1: Snare
        OrpheusEngine(engineId = OrpheusEngineId.SD, volume = 0.65f).let { snare ->
            TrackVoice(
                engineEdm = snare,
                engineSpace = snare,
                role = TrackRole.Percussive,
                pan = -0.1f, density = 0.4f,
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.REPEAT,
            )
        },
        // Track 2: Hihat
        OrpheusEngine(engineId = OrpheusEngineId.HH, volume = 0.55f).let { hat ->
            TrackVoice(
                engineEdm = hat,
                engineSpace = hat,
                role = TrackRole.Percussive,
                pan = 0.15f, density = 0.6f,
                envelopeProfile = EnvelopeProfile.RHYTHM,
                macroMap = TrackMacroMap.RHYTHM,
                barStrategy = BarStrategy.REPEAT,
            )
        },
        // Track 3: Bass
        OrpheusEngine(
            engineId = OrpheusEngineId.VCF,
            volume = 0.7f,
            noteRangeLow = 33,
            noteRangeHigh = 52,
        ).let { bass ->
            TrackVoice(
                engineEdm = bass,
                engineSpace = bass.copy(engineId = OrpheusEngineId.VA),
                role = TrackRole.Melodic(),
                density = 0.5f,
                envelopeProfile = EnvelopeProfile.MELODIC,
                macroMap = TrackMacroMap.MELODIC,
            )
        },
        // Track 4: CHORDAL keys — the comping track with full tuning
        OrpheusEngine(
            engineId = chordEngineEdm,
            volume = 0.55f,
            noteRangeLow = 36,
            noteRangeHigh = 72,  // was 48 low — let piano voicings go lower
        ).let { keys ->
            TrackVoice(
                engineEdm = keys,
                engineSpace = keys.copy(engineId = chordEngineSpace),
                role = TrackRole.Chordal(comping = compingOverride),
                pan = -0.2f,
                envelopeProfile = EnvelopeProfile.MELODIC,
                macroMap = TrackMacroMap.MELODIC,
            )
        },
        // Tracks 5-7: silent placeholders (8-track requirement)
        OrpheusEngine(engineId = OrpheusEngineId.ENS, volume = 0.0f).let { silent ->
            TrackVoice(
                engineEdm = silent,
                engineSpace = silent,
                role = TrackRole.Melodic(),
                density = 0.0f,
                envelopeProfile = EnvelopeProfile.EFFECT,
                macroMap = TrackMacroMap.EFFECT,
            )
        },
        OrpheusEngine(engineId = OrpheusEngineId.ENS, volume = 0.0f).let { silent ->
            TrackVoice(
                engineEdm = silent,
                engineSpace = silent,
                role = TrackRole.Melodic(),
                density = 0.0f,
                envelopeProfile = EnvelopeProfile.EFFECT,
                macroMap = TrackMacroMap.EFFECT,
            )
        },
        OrpheusEngine(engineId = OrpheusEngineId.ENS, volume = 0.0f).let { silent ->
            TrackVoice(
                engineEdm = silent,
                engineSpace = silent,
                role = TrackRole.Melodic(),
                density = 0.0f,
                envelopeProfile = EnvelopeProfile.EFFECT,
                macroMap = TrackMacroMap.EFFECT,
            )
        },
    ),
    stepCount = 16,
    tension = TensionProfile(
        innerBars = 8,
        volume = 0.0f,
        timing = 0.0f,
    ),
    effects = VibeEffects(
        reverbSize = 0.4f,
        reverbDamping = 0.4f,
        reverbBrightness = 0.5f,
    ),
    arrangement = arrangement ?: Arrangement(
        introIndex = 0,
        sections = listOf(
            Section(
                name = "verse",
                barsMin = 8, barsMax = 12,
                transitions = listOf(SectionTransition(targetIndex = 1, weight = 1.0f)),
            ),
            Section(
                name = "chorus",
                barsMin = 8, barsMax = 12,
                transitions = listOf(SectionTransition(targetIndex = 0, weight = 1.0f)),
                macroOverrides = MacroOverrides(energy = 1.3f),
            ),
        ),
    ),
)
