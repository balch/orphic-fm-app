package org.balch.orpheus.features.pulsar

import kotlin.random.Random

object PulsarVibes {

    fun all(): List<Vibe> = listOf(
        cosmicTechno,
        deepSpace,
        dreamLick,
        inhaleAir,
        stardust,
        strangeMagic,
        dogHouse,
        rollingDice(),
    )

    // 1. COSMIC TECHNO — generative, 128 BPM, D minor (D=2, minor=1)
    val cosmicTechno: Vibe = Vibe(
        name = "Cosmic Techno",
        bpm = 128f,
        envelopeMode = 0,  // AD — tight punchy envelopes for techno
        rootNote = 2,
        scaleIndex = 1,
        energy = 0.7f,
        complexity = 0.5f,
        space = 0.5f,
        mood = 0.4f,
        genre = GenreProfile(
            baseDensity = floatArrayOf(0.50f, 0.35f, 0.80f, 0.40f, 0.30f, 0.20f, 0.15f, 0.08f),
            swingAmount = 0.02f,
            ghostProbability = 0.3f,
            noteRangeLow = 36,
            noteRangeHigh = 72,
            rhythmPattern = 3,
        ),
        tracks = listOf(
            TrackVoice(engineEdm = Engine.BD,  engineSpace = Engine.MOD, isPercussive = true,  volume = 0.90f, pan =  0.00f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.SD,  engineSpace = Engine.NSE, isPercussive = true,  volume = 0.60f, pan = -0.15f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.HH,  engineSpace = Engine.HH,  isPercussive = true,  volume = 0.65f, pan =  0.20f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.WSH, engineSpace = Engine.STR, isPercussive = false, volume = 0.75f, pan =  0.00f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC),
            TrackVoice(engineEdm = Engine.CHD, engineSpace = Engine.ENS, isPercussive = false, volume = 0.55f, pan = -0.25f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC),
            TrackVoice(engineEdm = Engine.CHD, engineSpace = Engine.CHD, isPercussive = false, volume = 0.40f, pan = -0.35f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT),
            TrackVoice(engineEdm = Engine.NSE, engineSpace = Engine.NSE, isPercussive = true,  volume = 0.30f, pan =  0.30f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT),
            TrackVoice(engineEdm = Engine.MOD, engineSpace = Engine.STR, isPercussive = false, volume = 0.25f, pan =  0.40f, envelopeProfile = EnvelopeProfile.WILD,    macroMap = TrackMacroMap.WILD),
        ),
    )

    // 2. DEEP SPACE — generative, 70 BPM, A minor
    val deepSpace: Vibe = Vibe(
        name = "Deep Space",
        bpm = 70f,
        envelopeMode = 1,  // Tides — organic evolving envelopes for ambient
        rootNote = 9,
        scaleIndex = 0, // Minor
        energy = 0.3f,
        complexity = 0.2f,
        space = 0.7f,
        mood = 0.6f,
        genre = GenreProfile(
            baseDensity = floatArrayOf(0.25f, 0.15f, 0.30f, 0.20f, 0.15f, 0.35f, 0.25f, 0.10f),
            swingAmount = 0.05f,
            ghostProbability = 0.15f,
            noteRangeLow = 36,
            noteRangeHigh = 84,
            rhythmPattern = 0,
        ),
        tracks = listOf(
            TrackVoice(engineEdm = Engine.MOD, engineSpace = Engine.MOD, isPercussive = true,  volume = 0.70f, pan =  0.00f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.NSE, engineSpace = Engine.PAR, isPercussive = true,  volume = 0.45f, pan = -0.20f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.HH,  engineSpace = Engine.HH,  isPercussive = true,  volume = 0.40f, pan =  0.25f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.WSH, engineSpace = Engine.STR, isPercussive = false, volume = 0.60f, pan =  0.00f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC),
            TrackVoice(engineEdm = Engine.ENS, engineSpace = Engine.ENS, isPercussive = false, volume = 0.50f, pan = -0.30f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC),
            TrackVoice(engineEdm = Engine.CHD, engineSpace = Engine.STR, isPercussive = false, volume = 0.55f, pan =  0.35f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT),
            TrackVoice(engineEdm = Engine.GRN, engineSpace = Engine.WTB, isPercussive = false, volume = 0.35f, pan = -0.40f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT),
            TrackVoice(engineEdm = Engine.MOD, engineSpace = Engine.STR, isPercussive = false, volume = 0.20f, pan =  0.00f, envelopeProfile = EnvelopeProfile.WILD,    macroMap = TrackMacroMap.WILD),
        ),
    )

    // 3. SATISFACTION — lick, 116 BPM, E pentatonic
    val dreamLick: Vibe = Vibe(
        name = "Dream Lick",
        bpm = 116f,
        envelopeMode = 2,  // Blend — AD at high energy, Tides at low
        rootNote = 4,
        scaleIndex = 2, // Pentatonic
        lick = Lick(
            steps = listOf(
                LickStep(scaleDegree = 0, duration = 0.5f),
                LickStep(scaleDegree = 0, duration = 0.5f),
                LickStep(scaleDegree = 0, duration = 0.5f),
                LickStep(scaleDegree = 1, duration = 0.5f),
                LickStep(scaleDegree = 2, duration = 1.0f),
                LickStep(scaleDegree = 2, duration = 0.5f),
                LickStep(scaleDegree = 1, duration = 0.5f),
                LickStep(scaleDegree = 0, duration = 2.0f),
            ),
        ),
        lickMutation = 0.7f,
        energy = 0.6f,
        complexity = 0.4f,
        space = 0.3f,
        mood = 0.5f,
        genre = GenreProfile(
            baseDensity = floatArrayOf(0.55f, 0.40f, 0.70f, 0.50f, 0.35f, 0.15f, 0.10f, 0.05f),
            swingAmount = 0.08f,
            ghostProbability = 0.25f,
            noteRangeLow = 40,
            noteRangeHigh = 72,
            rhythmPattern = 2,
        ),
        tracks = listOf(
            TrackVoice(engineEdm = Engine.BD,  engineSpace = Engine.MOD, isPercussive = true,  volume = 0.85f, pan =  0.00f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.SD,  engineSpace = Engine.NSE, isPercussive = true,  volume = 0.65f, pan = -0.10f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.HH,  engineSpace = Engine.PAR, isPercussive = true,  volume = 0.55f, pan =  0.15f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.WSH, engineSpace = Engine.VA,  isPercussive = false, volume = 0.75f, pan =  0.00f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC),
            TrackVoice(engineEdm = Engine.VA,  engineSpace = Engine.STR, isPercussive = false, volume = 0.60f, pan = -0.20f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC),
            TrackVoice(engineEdm = Engine.CHD, engineSpace = Engine.ENS, isPercussive = false, volume = 0.35f, pan =  0.30f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT),
            TrackVoice(engineEdm = Engine.GRN, engineSpace = Engine.WTB, isPercussive = false, volume = 0.25f, pan = -0.35f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT),
            TrackVoice(engineEdm = Engine.MOD, engineSpace = Engine.PAR, isPercussive = true,  volume = 0.20f, pan =  0.40f, envelopeProfile = EnvelopeProfile.WILD,    macroMap = TrackMacroMap.WILD),
        ),
    )

    // 4. DARK SIDE — lick, 68 BPM, B minor
    val inhaleAir: Vibe = Vibe(
        name = "Breather",
        bpm = 68f,
        envelopeMode = 1,  // Tides — breathy slow envelopes
        rootNote = 11,
        scaleIndex = 0, // Minor
        lick = Lick(
            steps = listOf(
                LickStep(scaleDegree =  0, duration = 1.0f, velocity = 0.70f),
                LickStep(scaleDegree =  1, duration = 1.0f, velocity = 0.75f),
                LickStep(scaleDegree =  2, duration = 1.0f, velocity = 0.80f),
                LickStep(scaleDegree =  4, duration = 2.0f, velocity = 0.85f),
                LickStep(scaleDegree =  2, duration = 1.0f, velocity = 0.70f),
                LickStep(scaleDegree =  1, duration = 1.0f, velocity = 0.65f),
                LickStep(scaleDegree =  0, duration = 2.0f, velocity = 0.60f),
                LickStep(scaleDegree = -1, duration = 1.0f),
            ),
        ),
        lickMutation = 0.4f,
        energy = 0.3f,
        complexity = 0.2f,
        space = 0.8f,
        mood = 0.7f,
        genre = GenreProfile(
            baseDensity = floatArrayOf(0.20f, 0.10f, 0.15f, 0.25f, 0.20f, 0.40f, 0.30f, 0.15f),
            swingAmount = 0.03f,
            ghostProbability = 0.1f,
            noteRangeLow = 36,
            noteRangeHigh = 84,
            rhythmPattern = 0,
        ),
        tracks = listOf(
            TrackVoice(engineEdm = Engine.BD,  engineSpace = Engine.MOD, isPercussive = true,  volume = 0.65f, pan =  0.00f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.MOD, engineSpace = Engine.PAR, isPercussive = true,  volume = 0.40f, pan = -0.20f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.HH,  engineSpace = Engine.NSE, isPercussive = true,  volume = 0.35f, pan =  0.20f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.VCF, engineSpace = Engine.STR, isPercussive = false, volume = 0.65f, pan =  0.00f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC),
            TrackVoice(engineEdm = Engine.ENS, engineSpace = Engine.ENS, isPercussive = false, volume = 0.55f, pan = -0.30f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC),
            TrackVoice(engineEdm = Engine.STR, engineSpace = Engine.STR, isPercussive = false, volume = 0.60f, pan =  0.35f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT),
            TrackVoice(engineEdm = Engine.SWM, engineSpace = Engine.GRN, isPercussive = false, volume = 0.40f, pan = -0.40f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT),
            TrackVoice(engineEdm = Engine.PAR, engineSpace = Engine.WTB, isPercussive = false, volume = 0.25f, pan =  0.00f, envelopeProfile = EnvelopeProfile.WILD,    macroMap = TrackMacroMap.WILD),
        ),
    )

    // 5. STARDUST — lick, 110 BPM, C major
    val stardust: Vibe = Vibe(
        name = "Stardust",
        bpm = 110f,
        envelopeMode = 2,  // Blend — sparkly at high energy, dreamy at low
        rootNote = 0,
        scaleIndex = 1, // Major
        lick = Lick(
            steps = listOf(
                LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.85f),
                LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),
                LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.75f),
                LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.80f),
                LickStep(scaleDegree = 4, duration = 1.0f, velocity = 0.90f),
                LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.70f),
            ),
        ),
        lickMutation = 0.5f,
        energy = 0.5f,
        complexity = 0.5f,
        space = 0.4f,
        mood = 0.6f,
        genre = GenreProfile(
            baseDensity = floatArrayOf(0.45f, 0.35f, 0.60f, 0.40f, 0.45f, 0.25f, 0.20f, 0.10f),
            swingAmount = 0.04f,
            ghostProbability = 0.2f,
            noteRangeLow = 48,
            noteRangeHigh = 84,
            rhythmPattern = 1,
        ),
        tracks = listOf(
            TrackVoice(engineEdm = Engine.BD,  engineSpace = Engine.MOD, isPercussive = true,  volume = 0.80f, pan =  0.00f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.SD,  engineSpace = Engine.MOD, isPercussive = true,  volume = 0.55f, pan = -0.15f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.HH,  engineSpace = Engine.HH,  isPercussive = true,  volume = 0.50f, pan =  0.20f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.FM,  engineSpace = Engine.VA,  isPercussive = false, volume = 0.70f, pan =  0.00f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC),
            TrackVoice(engineEdm = Engine.CHD, engineSpace = Engine.ENS, isPercussive = false, volume = 0.60f, pan = -0.35f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC),
            TrackVoice(engineEdm = Engine.ENS, engineSpace = Engine.STR, isPercussive = false, volume = 0.50f, pan =  0.40f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT),
            TrackVoice(engineEdm = Engine.SWM, engineSpace = Engine.GRN, isPercussive = false, volume = 0.35f, pan = -0.30f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT),
            TrackVoice(engineEdm = Engine.ADD, engineSpace = Engine.SPK, isPercussive = false, volume = 0.20f, pan =  0.00f, envelopeProfile = EnvelopeProfile.WILD,    macroMap = TrackMacroMap.WILD),
        ),
    )

    // 6. STRANGE MAGIC — lick, 116 BPM, A major
    val strangeMagic: Vibe = Vibe(
        name = "Strange Magic",
        bpm = 116f,
        envelopeMode = 0,  // AD — crisp articulation for ascending lick
        rootNote = 9,
        scaleIndex = 1, // Major
        lick = Lick(
            steps = listOf(
                LickStep(scaleDegree = 0, duration = 0.5f, velocity = 0.80f),
                LickStep(scaleDegree = 1, duration = 0.5f, velocity = 0.80f),
                LickStep(scaleDegree = 2, duration = 0.5f, velocity = 0.80f),
                LickStep(scaleDegree = 3, duration = 0.5f, velocity = 0.85f),
                LickStep(scaleDegree = 4, duration = 0.5f, velocity = 0.85f),
                LickStep(scaleDegree = 5, duration = 0.5f, velocity = 0.90f),
                LickStep(scaleDegree = 6, duration = 1.0f, velocity = 0.90f),
            ),
        ),
        lickMutation = 0.3f,
        energy = 0.5f,
        complexity = 0.4f,
        space = 0.5f,
        mood = 0.6f,
        genre = GenreProfile(
            baseDensity = floatArrayOf(0.40f, 0.30f, 0.55f, 0.35f, 0.45f, 0.35f, 0.25f, 0.10f),
            swingAmount = 0.03f,
            ghostProbability = 0.2f,
            noteRangeLow = 48,
            noteRangeHigh = 84,
            rhythmPattern = 1,
        ),
        tracks = listOf(
            TrackVoice(engineEdm = Engine.BD,  engineSpace = Engine.MOD, isPercussive = true,  volume = 0.80f, pan =  0.00f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.SD,  engineSpace = Engine.NSE, isPercussive = true,  volume = 0.55f, pan = -0.10f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.HH,  engineSpace = Engine.HH,  isPercussive = true,  volume = 0.50f, pan =  0.15f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.FM,  engineSpace = Engine.STR, isPercussive = false, volume = 0.70f, pan =  0.00f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC),
            TrackVoice(engineEdm = Engine.CHD, engineSpace = Engine.ENS, isPercussive = false, volume = 0.60f, pan = -0.30f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC),
            TrackVoice(engineEdm = Engine.ENS, engineSpace = Engine.ENS, isPercussive = false, volume = 0.50f, pan =  0.35f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT),
            TrackVoice(engineEdm = Engine.ADD, engineSpace = Engine.WTB, isPercussive = false, volume = 0.35f, pan = -0.35f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT),
            TrackVoice(engineEdm = Engine.TRN, engineSpace = Engine.SWM, isPercussive = false, volume = 0.20f, pan =  0.00f, envelopeProfile = EnvelopeProfile.WILD,    macroMap = TrackMacroMap.WILD),
        ),
    )

    // 7. DOG HOUSE — generative, 85 BPM, E Phrygian (E=4, Phrygian=3)
    val dogHouse: Vibe = Vibe(
        name = "Dog House",
        bpm = 85f,
        envelopeMode = 2,  // Blend — gritty AD at high energy, loose Tides at low
        rootNote = 4,
        scaleIndex = 3,
        energy = 0.5f,
        complexity = 0.4f,
        space = 0.4f,
        mood = 0.5f,
        genre = GenreProfile(
            baseDensity = floatArrayOf(0.45f, 0.35f, 0.55f, 0.40f, 0.30f, 0.25f, 0.15f, 0.08f),
            swingAmount = 0.10f,
            ghostProbability = 0.25f,
            noteRangeLow = 36,
            noteRangeHigh = 72,
            rhythmPattern = 2,
        ),
        tracks = listOf(
            TrackVoice(engineEdm = Engine.BD,  engineSpace = Engine.BD,  isPercussive = true,  volume = 0.85f, pan =  0.00f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.SD,  engineSpace = Engine.SD,  isPercussive = true,  volume = 0.60f, pan = -0.10f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.HH,  engineSpace = Engine.HH,  isPercussive = true,  volume = 0.55f, pan =  0.15f, envelopeProfile = EnvelopeProfile.RHYTHM,  macroMap = TrackMacroMap.RHYTHM),
            TrackVoice(engineEdm = Engine.WSH, engineSpace = Engine.STR, isPercussive = false, volume = 0.75f, pan =  0.00f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC),
            TrackVoice(engineEdm = Engine.ENS, engineSpace = Engine.ENS, isPercussive = false, volume = 0.50f, pan = -0.25f, envelopeProfile = EnvelopeProfile.MELODIC, macroMap = TrackMacroMap.MELODIC),
            TrackVoice(engineEdm = Engine.STR, engineSpace = Engine.STR, isPercussive = false, volume = 0.40f, pan =  0.30f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT),
            TrackVoice(engineEdm = Engine.GRN, engineSpace = Engine.GRN, isPercussive = false, volume = 0.30f, pan = -0.30f, envelopeProfile = EnvelopeProfile.EFFECT,  macroMap = TrackMacroMap.EFFECT),
            TrackVoice(engineEdm = Engine.MOD, engineSpace = Engine.STR, isPercussive = false, volume = 0.20f, pan =  0.00f, envelopeProfile = EnvelopeProfile.WILD,    macroMap = TrackMacroMap.WILD),
        ),
    )

    // 8. ROLL THE DICE — randomized each call
    private val drumEngines    = arrayOf(Engine.NSE, Engine.PAR, Engine.MOD, Engine.BD, Engine.SD, Engine.HH)
    private val bassEngines    = arrayOf(Engine.VCF, Engine.VA, Engine.WSH, Engine.FM, Engine.STR)
    private val melodicEngines = arrayOf(Engine.PD, Engine.DX, Engine.TRN, Engine.ENS, Engine.NES, Engine.VA, Engine.FM, Engine.GRN, Engine.ADD, Engine.WTB, Engine.CHD, Engine.SPK, Engine.SWM, Engine.STR)

    fun rollingDice(): Vibe {
        val rng = Random.Default
        val bpm = (60 + rng.nextInt(101)).toFloat() // 60–160
        val rootNote = rng.nextInt(12)
        val scaleIndex = rng.nextInt(6)

        fun randomFrom(pool: Array<Engine>) = pool[rng.nextInt(pool.size)]

        return Vibe(
            name = "Rolling Dice",
            bpm = bpm,
            envelopeMode = rng.nextInt(3),  // random: AD, Tides, or Blend
            rootNote = rootNote,
            scaleIndex = scaleIndex,
            genre = GenreProfile(
                baseDensity = floatArrayOf(
                    rng.nextFloat(),
                    rng.nextFloat(),
                    rng.nextFloat(),
                    rng.nextFloat(),
                    rng.nextFloat(),
                    rng.nextFloat(),
                    rng.nextFloat(),
                    rng.nextFloat(),
                ),
                swingAmount = rng.nextFloat() * 0.15f,
                ghostProbability = rng.nextFloat() * 0.4f,
                noteRangeLow = 36 + rng.nextInt(24),
                noteRangeHigh = 60 + rng.nextInt(25),
                rhythmPattern = rng.nextInt(4),
            ),
            tracks = listOf(
                // Tracks 0–2: drums (percussive)
                TrackVoice(
                    engineEdm = randomFrom(drumEngines),
                    engineSpace = randomFrom(drumEngines),
                    isPercussive = true,
                    volume = 0.7f + rng.nextFloat() * 0.3f,
                    pan = 0.0f,
                    envelopeProfile = EnvelopeProfile.RHYTHM,
                    macroMap = TrackMacroMap.RHYTHM,
                ),
                TrackVoice(
                    engineEdm = randomFrom(drumEngines),
                    engineSpace = randomFrom(drumEngines),
                    isPercussive = true,
                    volume = 0.5f + rng.nextFloat() * 0.3f,
                    pan = -0.1f - rng.nextFloat() * 0.2f,
                    envelopeProfile = EnvelopeProfile.RHYTHM,
                    macroMap = TrackMacroMap.RHYTHM,
                ),
                TrackVoice(
                    engineEdm = randomFrom(drumEngines),
                    engineSpace = randomFrom(drumEngines),
                    isPercussive = true,
                    volume = 0.4f + rng.nextFloat() * 0.3f,
                    pan = 0.1f + rng.nextFloat() * 0.2f,
                    envelopeProfile = EnvelopeProfile.RHYTHM,
                    macroMap = TrackMacroMap.RHYTHM,
                ),
                // Track 3: bass (melodic)
                TrackVoice(
                    engineEdm = randomFrom(bassEngines),
                    engineSpace = randomFrom(bassEngines),
                    isPercussive = false,
                    volume = 0.6f + rng.nextFloat() * 0.3f,
                    pan = 0.0f,
                    envelopeProfile = EnvelopeProfile.MELODIC,
                    macroMap = TrackMacroMap.MELODIC,
                ),
                // Tracks 4–7: melodic
                TrackVoice(
                    engineEdm = randomFrom(melodicEngines),
                    engineSpace = randomFrom(melodicEngines),
                    isPercussive = false,
                    volume = 0.4f + rng.nextFloat() * 0.4f,
                    pan = -(rng.nextFloat() * 0.4f),
                    envelopeProfile = EnvelopeProfile.MELODIC,
                    macroMap = TrackMacroMap.MELODIC,
                ),
                TrackVoice(
                    engineEdm = randomFrom(melodicEngines),
                    engineSpace = randomFrom(melodicEngines),
                    isPercussive = false,
                    volume = 0.3f + rng.nextFloat() * 0.3f,
                    pan = rng.nextFloat() * 0.4f,
                    envelopeProfile = EnvelopeProfile.EFFECT,
                    macroMap = TrackMacroMap.EFFECT,
                ),
                TrackVoice(
                    engineEdm = randomFrom(melodicEngines),
                    engineSpace = randomFrom(melodicEngines),
                    isPercussive = false,
                    volume = 0.2f + rng.nextFloat() * 0.3f,
                    pan = -(rng.nextFloat() * 0.4f),
                    envelopeProfile = EnvelopeProfile.EFFECT,
                    macroMap = TrackMacroMap.EFFECT,
                ),
                TrackVoice(
                    engineEdm = randomFrom(melodicEngines),
                    engineSpace = randomFrom(melodicEngines),
                    isPercussive = false,
                    volume = 0.1f + rng.nextFloat() * 0.3f,
                    pan = (rng.nextFloat() - 0.5f) * 0.8f,
                    envelopeProfile = EnvelopeProfile.WILD,
                    macroMap = TrackMacroMap.WILD,
                ),
            ),
        )
    }
}
