package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.anonmalies.FogHornAnomaly
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.CastPinRecord
import org.balch.orpheus.features.pulsar.models.CastProvenance
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.ChordStep
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.EvolutionTension
import org.balch.orpheus.features.pulsar.models.FogPattern
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.GrainScatterMode
import org.balch.orpheus.features.pulsar.models.GrainVoicing
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickMutationMode
import org.balch.orpheus.features.pulsar.models.LickRotation
import org.balch.orpheus.features.pulsar.models.LickSource
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.LpgMode
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.MacroTarget
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.ProgressionStyle
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SectionDelay
import org.balch.orpheus.features.pulsar.models.SectionFog
import org.balch.orpheus.features.pulsar.models.SectionTransition
import org.balch.orpheus.features.pulsar.models.SectionWeather
import org.balch.orpheus.features.pulsar.models.TensionArc
import org.balch.orpheus.features.pulsar.models.TensionProfile
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeName
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.VolumePoint

/**
 * Whale's Dream: a whale falls asleep and dreams of finding a mate. C# dorian, 64 bpm, loosely
 * after a long psychedelic sea epic: the riff waits for the middle, and the sound effects tell the story.
 *
 * - ping, swell: asleep at sea. STR gong buoy, FM foghorn, a faint far GRN whale; the swell rises.
 * - heartbeat: one bar of kick on 1 and 3, getting louder, then pound hits.
 * - pound: the dream swim. Four-on-the-floor, a plucked bass pulse, the swim lick (pool 5).
 * - wander, submerge, alone: one crying whale (pool 7), slow TRN swirls, then deep and quiet.
 * - pod: the riff (pool 2) on fat detuned VA keys, whales echo it, the bass noodles the swim lick.
 * - surface, roll: the pair swims off trading the call, then the riff over the pound.
 * - breach: the buoy again and a faint small call, the calf.
 *
 * Lick pool: 0 far whale, 1 foghorn, 2 riff, 3 low whale, 4 horn build, 5 swim, 6 bass pulse, 7 cry.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class WhalesDreamVibe : VibeProvider {
  override val name: VibeName = VibeNames.WHALES_DREAM

  override val vibe: Vibe by lazy {
      Vibe(
        name = "Whale's Dream",
        tracks = listOf(
          TrackVoice(
            engineEdm = OrpheusEngine(
              OrpheusEngineId.BD, volume = 0.964f, harmonics = 0.57f, timbre = 0.42f, morph = 0.26f,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.MOD, volume = 0.827f, harmonics = 0.26f, timbre = 0.42f, morph = 0.26f,
              pinHarmonics = true,
            ),
            density = 0.43f,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.7f, 1.0f), energyDensity = MacroTarget(0.4f, 0.8f),
              complexitySwing = MacroTarget(0.0f, 0.1f), complexityVariation = MacroTarget(0.0f, 0.15f),
              spaceDecay = MacroTarget(0.11f, 0.41f), moodHarmonics = MacroTarget(0.15f, 0.35f),
              moodTimbre = MacroTarget(0.1f, 0.3f),
            ),
          ),
          TrackVoice(
            engineEdm = OrpheusEngine(
              OrpheusEngineId.STR, volume = 0.084f, timbre = 0.45f, morph = 0.7f, delaySend = 0.05f,
              reverbSend = 0.25f, noteRangeLow = 61, noteRangeHigh = 68, pinHarmonics = true,
              pinTimbre = true, pinMorph = true,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.STR, volume = 0.084f, timbre = 0.45f, morph = 0.85f, delaySend = 0.05f,
              reverbSend = 0.25f, noteRangeLow = 61, noteRangeHigh = 61, lpgMode = LpgMode.BYPASS,
              pinHarmonics = true, pinTimbre = true, pinMorph = true,
            ),
            pan = 0.35f,
            density = 0.36f,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.7f, 1.0f), energyDensity = MacroTarget(0.4f, 0.8f),
              complexitySwing = MacroTarget(0.0f, 0.1f), complexityVariation = MacroTarget(0.0f, 0.15f),
              spaceDecay = MacroTarget(0.22f, 0.52f), moodHarmonics = MacroTarget(0.21f, 0.51f),
              moodTimbre = MacroTarget(0.3f, 0.6f), moodPitch = MacroTarget(24.0f, 24.0f),
            ),
          ),
          TrackVoice(
            engineEdm = OrpheusEngine(
              OrpheusEngineId.HH, volume = 0.18f, harmonics = 0.47f, timbre = 0.45f, morph = 0.57f,
              delaySend = 0.34f, reverbSend = 0.65f, pitchRiseTime = 0.4f,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.STR, volume = 0.36f, harmonics = 0.25f, timbre = 0.6f, morph = 0.7f,
              delaySend = 0.34f, reverbSend = 0.65f, noteRangeLow = 49, noteRangeHigh = 49,
              pinHarmonics = true, pinTimbre = true, pinMorph = true,
            ),
            pan = -0.55f,
            density = 0.4f,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.7f, 1.0f), energyDensity = MacroTarget(0.4f, 0.8f),
              complexitySwing = MacroTarget(0.0f, 0.1f), complexityVariation = MacroTarget(0.0f, 0.15f),
              spaceDecay = MacroTarget(0.42f, 0.72f), moodHarmonics = MacroTarget(0.32f, 0.62f),
              moodTimbre = MacroTarget(0.3f, 0.6f),
            ),
          ),
          TrackVoice(
            engineEdm = OrpheusEngine(
              OrpheusEngineId.VCF, volume = 0.508f, harmonics = 0.44f, timbre = 0.38f, morph = 0.53f,
              reverbSend = 0.05f, noteRangeLow = 28, noteRangeHigh = 47, delayFeedback = 0.7f,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.FM, volume = 0.508f, timbre = 0.39f, morph = 0.29f, reverbSend = 0.05f,
              noteRangeLow = 49, noteRangeHigh = 53, delayFeedback = 0.7f, glideRate = 0.5f,
              lpgMode = LpgMode.SUSTAINED, lpgDecay = 0.8f, lpgColour = 0.35f, pinHarmonics = true,
              pinTimbre = true, pinMorph = true,
            ),
            role = TrackRole.Melodic(
              chordFollow = ChordFollow.FIXED, lickMode = LickMode.Fill, lickSource = LickSource.BASS,
            ),
            density = 0.4f,
            envelopeProfile = EnvelopeProfile.MELODIC,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.5f, 1.0f), energyDensity = MacroTarget(0.4f, 0.9f),
              complexitySwing = MacroTarget(0.0f, 0.15f), complexityVariation = MacroTarget(0.0f, 0.3f),
              spaceDecay = MacroTarget(0.38f, 0.68f), moodHarmonics = MacroTarget(0.29f, 0.59f),
              moodTimbre = MacroTarget(0.23f, 0.53f),
            ),
            barStrategy = BarStrategy.MUTATE,
          ),
          TrackVoice(
            engineEdm = OrpheusEngine(
              OrpheusEngineId.VA, volume = 0.186f, harmonics = 0.35f, timbre = 0.45f, morph = 0.6f,
              delaySend = 0.428f, reverbSend = 0.35f, noteRangeLow = 56, noteRangeHigh = 80,
              delayFeedback = 0.72f, glideRate = 0.3f, lpgMode = LpgMode.PLUCK, lpgDecay = 0.8f,
              lpgColour = 0.3f, pinHarmonics = true, pinTimbre = true, pinMorph = true, vibratoDepth = 14.0f,
              vibratoRate = 3.5f, doubleDetune = 5.0f, doubleSpread = 0.2f,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.VA, volume = 0.16f, harmonics = 0.35f, timbre = 0.45f, morph = 0.6f,
              delaySend = 0.55f, reverbSend = 0.5f, noteRangeLow = 56, noteRangeHigh = 80,
              delayFeedback = 0.72f, glideRate = 0.3f, lpgMode = LpgMode.PLUCK, lpgDecay = 0.8f,
              lpgColour = 0.3f, pinHarmonics = true, pinTimbre = true, pinMorph = true, vibratoDepth = 14.0f,
              vibratoRate = 3.5f, doubleDetune = 5.0f, doubleSpread = 0.2f,
            ),
            role = TrackRole.Melodic(chordFollow = ChordFollow.FIXED, lickMode = LickMode.Fill),
            pan = 0.7f,
            density = 0.37f,
            envelopeProfile = EnvelopeProfile.DRONE,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.5f, 1.0f), energyDensity = MacroTarget(0.4f, 0.9f),
              complexitySwing = MacroTarget(0.0f, 0.15f), complexityVariation = MacroTarget(0.0f, 0.3f),
              spaceDecay = MacroTarget(0.18f, 0.48f), moodHarmonics = MacroTarget(0.35f, 0.95f),
              moodTimbre = MacroTarget(0.3f, 0.8f), moodPitch = MacroTarget(-2.0f, 3.0f),
            ),
            barStrategy = BarStrategy.MUTATE,
          ),
          TrackVoice(
            engineEdm = OrpheusEngine(
              OrpheusEngineId.VA, volume = 0.126f, harmonics = 0.25f, timbre = 0.2f, morph = 0.1f,
              modLfoRate = 0.015f, modLfoDepth = 0.7f, modLfoShape = 0.1f, modLfoCoupling = 0.4f,
              delaySend = 0.25f, reverbSend = 1.0f, noteRangeLow = 49, noteRangeHigh = 61,
              reverbBrightness = 0.4f, glideRate = 0.6f, pinHarmonics = true, pinTimbre = true,
              pinMorph = true,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.FM, volume = 0.126f, harmonics = 0.32f, timbre = 0.22f, morph = 0.35f,
              modLfoRate = 0.015f, modLfoDepth = 0.7f, modLfoShape = 0.1f, modLfoCoupling = 0.4f,
              delaySend = 0.25f, reverbSend = 1.0f, noteRangeLow = 54, noteRangeHigh = 66,
              reverbBrightness = 0.35f, glideRate = 0.6f, pinHarmonics = true, pinTimbre = true,
              pinMorph = true,
            ),
            role = TrackRole.Chordal(
              comping = ChordComping(arpMode = ArpMode.NEVER, restrikeOnChordChange = true),
            ),
            pan = -0.5f,
            density = 0.12f,
            envelopeProfile = EnvelopeProfile.DRONE,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.5f, 1.0f), energyDensity = MacroTarget(1.0f, 1.0f),
              complexitySwing = MacroTarget(0.0f, 0.15f), complexityVariation = MacroTarget(0.0f, 0.3f),
              spaceDecay = MacroTarget(0.2f, 0.5f), moodHarmonics = MacroTarget(0.3f, 0.7f),
              moodTimbre = MacroTarget(0.2f, 0.5f),
            ),
          ),
          TrackVoice(
            engineEdm = OrpheusEngine(
              OrpheusEngineId.GRN, volume = 0.15f, harmonics = 0.42f, timbre = 0.67f, morph = 0.58f,
              delaySend = 0.85f, reverbSend = 0.7f, noteRangeLow = 61, noteRangeHigh = 70,
              reverbBrightness = 0.35f, delayFeedback = 0.7f, lpgMode = LpgMode.SUSTAINED, lpgDecay = 0.85f,
              lpgColour = 0.4f, pinHarmonics = true, pinTimbre = true, pinMorph = true, vibratoDepth = 22.0f,
              vibratoRate = 2.0f, pitchRiseTime = 0.4f,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.GRN, volume = 0.168f, harmonics = 0.42f, timbre = 0.67f, morph = 0.58f,
              delaySend = 0.85f, reverbSend = 0.7f, noteRangeLow = 73, noteRangeHigh = 77,
              reverbBrightness = 0.35f, delayFeedback = 0.7f, lpgMode = LpgMode.SUSTAINED, lpgDecay = 0.85f,
              lpgColour = 0.4f, pinHarmonics = true, pinTimbre = true, pinMorph = true, vibratoDepth = 18.0f,
              vibratoRate = 2.5f, pitchRiseTime = 0.4f,
            ),
            role = TrackRole.Melodic(
              chordFollow = ChordFollow.FIXED, lickMode = LickMode.Fill, lickDegreeOffset = 7,
            ),
            pan = -0.85f,
            density = 0.1f,
            envelopeProfile = EnvelopeProfile.MELODIC,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.8f, 1.0f), energyDensity = MacroTarget(1.0f, 1.0f),
              complexitySwing = MacroTarget(0.0f, 0.1f), complexityVariation = MacroTarget(0.0f, 0.15f),
              spaceDecay = MacroTarget(0.55f, 0.55f), moodHarmonics = MacroTarget(0.3f, 0.3f),
              moodTimbre = MacroTarget(0.25f, 0.25f),
            ),
            barStrategy = BarStrategy.INDEPENDENT,
          ),
          TrackVoice(
            engineEdm = OrpheusEngine(
              OrpheusEngineId.TRN, volume = 0.15f, timbre = 0.4f, delaySend = 0.7f, reverbSend = 1.0f,
              noteRangeLow = 48, noteRangeHigh = 60, glideRate = 0.6f, lpgMode = LpgMode.SUSTAINED,
              lpgDecay = 0.9f, vibratoDepth = 35.0f, vibratoRate = 0.5f, pitchRiseTime = 3.0f,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.STR, volume = 0.322f, timbre = 0.45f, morph = 0.85f, delaySend = 0.65f,
              reverbSend = 0.9f, noteRangeLow = 61, noteRangeHigh = 73, lpgMode = LpgMode.BYPASS,
              lpgDecay = 0.9f, pinHarmonics = true, pinTimbre = true, pinMorph = true,
              grainScatterMode = GrainScatterMode.SCALE, grainVoicing = GrainVoicing.CENTERED,
              grainScatter = 0.3f, grainRate = 0.35f, grainRandomTiming = true, grainShape = 1.0f,
              grainBlend = 0.55f, grainSize = 0.75f, vibratoDepth = 12.0f, vibratoRate = 2.8f,
              pitchRiseTime = 2.2f, morphRise = 0.08f, doubleDetune = 25.0f, doubleSpread = 0.8f,
            ),
            role = TrackRole.Melodic(lickMode = LickMode.Fill, lickDegreeOffset = -7, textureLine = true),
            pan = 0.35f,
            density = 0.3f,
            envelopeProfile = EnvelopeProfile.DRONE,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.8f, 1.0f), energyDensity = MacroTarget(1.0f, 1.0f),
              complexitySwing = MacroTarget(0.0f, 0.1f), complexityVariation = MacroTarget(0.0f, 0.15f),
              spaceDecay = MacroTarget(0.4f, 0.6f), moodHarmonics = MacroTarget(0.35f, 0.65f),
              moodTimbre = MacroTarget(0.4f, 0.7f), moodPitch = MacroTarget(-7.0f, 12.0f),
            ),
          ),
        ),
        lick = Lick(
          steps = listOf(
            LickStep(-1, 5.0f),
            LickStep(4, 3.0f, 0.7f),
            LickStep(7, 3.5f, 0.85f, glideRate = 0.5f),
            LickStep(4, 2.5f, 0.6f, glideRate = 0.5f),
            LickStep(-1, 2.0f),
          ),
          loopLength = 16,
        ),
        lickMutation = 0.0f,
        lickMutationMode = LickMutationMode.CHORD_TONES,
        lickRotation = LickRotation(
          pool = listOf(
            Lick(
              steps = listOf(
                LickStep(-1, 3.0f, glideRate = 0.0f),
                LickStep(11, 0.5f, 0.6f, glideRate = 0.0f),
                LickStep(14, 1.0f, 0.75f, glideRate = 0.45f),
                LickStep(13, 0.5f, 0.65f, glideRate = 0.2f),
                LickStep(16, 1.25f, glideRate = 0.5f),
                LickStep(11, 1.75f, 0.6f, glideRate = 0.6f),
                LickStep(-1, 8.0f, glideRate = 0.0f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                LickStep(7, 3.0f, 0.85f, glideRate = 0.0f),
                LickStep(9, 2.0f, glideRate = 0.5f),
                LickStep(7, 4.0f, glideRate = 0.5f),
                LickStep(-1, 7.0f, glideRate = 0.0f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                LickStep(7, 1.0f, glideRate = 0.35f),
                LickStep(11, 1.0f, 0.9f, glideRate = 0.45f),
                LickStep(13, 1.0f, glideRate = 0.0f),
                LickStep(14, 1.5f, 1.0f, glideRate = 0.45f),
                LickStep(11, 0.5f, 0.85f, glideRate = 0.0f),
                LickStep(10, 0.5f, glideRate = 0.0f),
                LickStep(9, 0.5f, 0.75f, glideRate = 0.0f),
                LickStep(8, 0.5f, 0.7f, glideRate = 0.0f),
                LickStep(7, 0.5f, 0.7f, glideRate = 0.0f),
                LickStep(6, 0.5f, 0.65f, glideRate = 0.0f),
                LickStep(8, 0.25f, 0.75f, glideRate = 0.0f),
                LickStep(6, 0.25f, 0.7f, glideRate = 0.0f),
                LickStep(7, 1.0f, glideRate = 0.35f),
                LickStep(11, 1.0f, 0.9f, glideRate = 0.45f),
                LickStep(13, 1.0f, 0.85f, glideRate = 0.45f),
                LickStep(14, 2.0f, 1.0f, glideRate = 0.55f),
                LickStep(13, 0.5f, 0.85f, glideRate = 0.0f),
                LickStep(11, 0.5f, glideRate = 0.0f),
                LickStep(10, 0.5f, 0.75f, glideRate = 0.0f),
                LickStep(9, 0.5f, 0.72f, glideRate = 0.0f),
                LickStep(8, 0.5f, 0.7f, glideRate = 0.0f),
                LickStep(6, 0.5f, 0.65f, glideRate = 0.0f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                LickStep(-1, 3.0f, glideRate = 0.0f),
                LickStep(8, 0.5f, 0.6f, glideRate = 0.0f),
                LickStep(11, 1.0f, 0.75f, glideRate = 0.45f),
                LickStep(10, 0.5f, 0.65f, glideRate = 0.2f),
                LickStep(13, 1.25f, glideRate = 0.5f),
                LickStep(8, 1.75f, 0.6f, glideRate = 0.6f),
                LickStep(-1, 8.0f, glideRate = 0.0f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                LickStep(7, 2.0f, 0.85f, glideRate = 0.0f),
                LickStep(10, 1.5f, glideRate = 0.5f),
                LickStep(7, 2.5f, glideRate = 0.5f),
                LickStep(-1, 0.5f, glideRate = 0.0f),
                LickStep(7, 2.0f, 0.85f, glideRate = 0.0f),
                LickStep(11, 2.0f, 0.85f, glideRate = 0.5f),
                LickStep(7, 3.0f, glideRate = 0.5f),
                LickStep(-1, 2.5f, glideRate = 0.0f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                LickStep(7, 1.0f, 0.85f, glideRate = 0.0f),
                LickStep(11, 1.0f, 0.9f, glideRate = 0.45f),
                LickStep(13, 0.5f, 0.75f, glideRate = 0.0f),
                LickStep(11, 0.5f, 0.7f, glideRate = 0.0f),
                LickStep(-1, 1.0f, glideRate = 0.0f),
                LickStep(7, 1.0f, 0.85f, glideRate = 0.0f),
                LickStep(11, 1.0f, 0.9f, glideRate = 0.45f),
                LickStep(13, 0.5f, 0.75f, glideRate = 0.0f),
                LickStep(11, 0.5f, 0.7f, glideRate = 0.0f),
                LickStep(-1, 1.0f, glideRate = 0.0f),
                LickStep(7, 1.0f, 0.85f, glideRate = 0.0f),
                LickStep(11, 1.0f, 0.9f, glideRate = 0.45f),
                LickStep(13, 0.5f, 0.75f, glideRate = 0.0f),
                LickStep(11, 0.5f, 0.7f, glideRate = 0.0f),
                LickStep(-1, 1.0f, glideRate = 0.0f),
                LickStep(7, 1.0f, 0.85f, glideRate = 0.0f),
                LickStep(11, 1.0f, 0.9f, glideRate = 0.45f),
                LickStep(13, 1.0f, 0.9f, glideRate = 0.45f),
                LickStep(14, 1.0f, 1.0f, glideRate = 0.5f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                LickStep(0, 0.75f, 0.95f, glideRate = 0.0f),
                LickStep(0, 0.25f, 0.6f, glideRate = 0.0f),
                LickStep(0, 0.5f, glideRate = 0.0f),
                LickStep(4, 0.5f, glideRate = 0.0f),
                LickStep(7, 1.0f, 0.9f, glideRate = 0.0f),
                LickStep(4, 0.5f, 0.75f, glideRate = 0.0f),
                LickStep(0, 0.5f, glideRate = 0.0f),
                LickStep(0, 0.75f, 0.95f, glideRate = 0.0f),
                LickStep(0, 0.25f, 0.6f, glideRate = 0.0f),
                LickStep(0, 0.5f, glideRate = 0.0f),
                LickStep(4, 0.5f, glideRate = 0.0f),
                LickStep(7, 1.0f, 0.9f, glideRate = 0.0f),
                LickStep(4, 0.5f, 0.75f, glideRate = 0.0f),
                LickStep(0, 0.5f, glideRate = 0.0f),
                LickStep(3, 0.75f, 0.95f, glideRate = 0.0f),
                LickStep(3, 0.25f, 0.6f, glideRate = 0.0f),
                LickStep(3, 0.5f, glideRate = 0.0f),
                LickStep(7, 0.5f, glideRate = 0.0f),
                LickStep(10, 1.0f, 0.9f, glideRate = 0.0f),
                LickStep(7, 0.5f, 0.75f, glideRate = 0.0f),
                LickStep(3, 0.5f, glideRate = 0.0f),
                LickStep(3, 0.75f, 0.95f, glideRate = 0.0f),
                LickStep(3, 0.25f, 0.6f, glideRate = 0.0f),
                LickStep(3, 0.5f, glideRate = 0.0f),
                LickStep(7, 0.5f, glideRate = 0.0f),
                LickStep(10, 1.0f, 0.9f, glideRate = 0.0f),
                LickStep(7, 0.5f, 0.75f, glideRate = 0.0f),
                LickStep(3, 0.5f, glideRate = 0.0f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                LickStep(-1, 2.0f, glideRate = 0.0f),
                LickStep(13, 1.5f, 0.7f, glideRate = 0.0f),
                LickStep(10, 2.0f, 0.65f, glideRate = 0.6f),
                LickStep(-1, 1.5f, glideRate = 0.0f),
                LickStep(12, 1.0f, 0.7f, glideRate = 0.3f),
                LickStep(14, 1.0f, 0.75f, glideRate = 0.5f),
                LickStep(9, 3.0f, 0.6f, glideRate = 0.7f),
                LickStep(-1, 4.0f, glideRate = 0.0f),
              ),
              loopLength = 16,
            ),
          ),
        ),
        bassLine = Lick(
          steps = listOf(
            LickStep(0, 3.0f, 0.85f),
            LickStep(4, 2.0f, 0.75f, glideRate = 0.7f),
            LickStep(5, 1.5f, 0.7f, glideRate = 0.6f),
            LickStep(4, 1.5f, 0.7f, glideRate = 0.6f),
            LickStep(2, 3.0f, glideRate = 0.75f),
            LickStep(1, 1.0f, 0.65f, glideRate = 0.6f),
            LickStep(0, 2.0f, 0.85f, glideRate = 0.7f),
            LickStep(-1, 2.0f),
          ),
          loopLength = 16,
        ),
        bassLineMutation = 0.0f,
        bassLineOctave = 2,
        band = Band(
          members = listOf(
            BandMember(
              name = "Drummer", tracks = listOf(0, 1, 2), alwaysActive = true, loudness = 0.7f,
              creativity = 0.3f,
            ),
            BandMember(
              name = "Sea", tracks = listOf(5, 6, 7), alwaysActive = true, loudness = 0.4f,
              creativity = 0.2f,
            ),
            BandMember(name = "Bassist", tracks = listOf(3), creativity = 0.9f),
            BandMember(name = "Lead", tracks = listOf(4), alwaysActive = true, creativity = 0.3f),
          ),
          handoffMatrix = listOf(
            0.0f,
            0.0f,
            1.0f,
            0.0f,
            0.0f,
            0.0f,
            1.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            1.0f,
            0.0f,
          ),
          pullInMatrix = listOf(
            0.0f,
            0.0f,
            0.2f,
            0.0f,
            0.0f,
            0.0f,
            0.1f,
            0.0f,
            0.2f,
            0.1f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
            0.0f,
          ),
          barsPerLeadMin = 4,
          barsPerLeadMax = 8,
        ),
        bpm = 64.0f,
        envelopeType = EnvelopeType.BLEND,
        rootNote = RootNote.C_SHARP,
        scaleType = ScaleType.DORIAN,
        genre = GenreProfile(
          swingAmount = 0.0f,
          ghostProbability = 0.05f,
          noteRangeLow = 36,
          noteRangeHigh = 60,
          rhythmDensity = 0.55f,
          progressionStyle = ProgressionStyle.MODAL,
          chordsPerBar = 4,
          chordTransitionMatrix = null,
          customProgression = listOf(
            ChordStep(degree = 0),
            ChordStep(degree = 3),
            ChordStep(degree = 0),
            ChordStep(degree = 6),
          ),
        ),
        energy = 0.62f,
        complexity = 0.48f,
        space = 0.75f,
        mood = 0.52f,
        deep = 0.65f,
        levelDb = 1.9f,
        stepCount = 64,
        tension = TensionProfile(
          innerBars = 8,
          outerBars = 16,
          outerDepth = 0.4f,
          timing = 0.1f,
          evolution = EvolutionTension(
            timbreLow = -1.0f, timbreHigh = -1.0f, morphLow = -1.0f, morphHigh = -1.0f, harmonicsLow = -1.0f,
            harmonicsHigh = -1.0f,
          ),
        ),
        arrangement = Arrangement(
          sections = listOf(
            Section(
              name = "ping",
              barsMin = 6,
              barsMax = 6,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = 1)),
              macroOverrides = MacroOverrides(energy = 0.403f, space = 1.2f),
              rampTo = MacroOverrides(energy = 0.576f),
              tensionOverride = TensionProfile(
                outerBars = 2,
                outerDepth = 0.6f,
                volume = 1.0f,
                timing = 0.15f,
                evolution = EvolutionTension(
                  timbreLow = -1.0f, timbreHigh = -1.0f, morphLow = 0.35f, morphHigh = 0.85f,
                  morphProbability = 0.7f, harmonicsLow = 0.3f, harmonicsHigh = 0.85f,
                  harmonicsProbability = 0.8f, attackPoint = 0.2f, releaseSpeed = 0.25f,
                ),
                arc = TensionArc.RISE,
              ),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(density = 0.0f),
                1 to TrackSectionOverride(density = 1.0f, morph = 0.85f, hits = listOf(0, 19)),
                2 to TrackSectionOverride(
                  volume = 0.45f, morph = 0.05f, reverbSend = 0.57f, hits = listOf(7, 22),
                ),
                3 to TrackSectionOverride(
                  density = 1.0f, volume = 0.161f, reverbSend = 0.7f, delaySend = 0.03f,
                ),
                4 to TrackSectionOverride(density = 0.0f, volume = 0.223f, pan = 0.65f),
                5 to TrackSectionOverride(density = 1.0f, volume = 0.064f),
                6 to TrackSectionOverride(density = 0.14f, volume = 0.126f, pan = -0.9f, delaySend = 0.3f),
                7 to TrackSectionOverride(
                  density = 0.0f, volume = 0.341f, morph = 0.85f, morphRise = 0.0f, reverbSend = 0.9f,
                  delaySend = 1.0f, envelopeProfile = EnvelopeProfile.MELODIC, lpgMode = LpgMode.BYPASS,
                ),
              ),
              customProgression = listOf(ChordStep(degree = 0)),
              chordsPerBar = 1,
              lickIndex = 0,
              bassLineIndex = 1,
              delay = SectionDelay(timeA = 0.466f, timeB = 0.702f, feedback = 0.45f),
            ),
            Section(
              name = "swell",
              barsMin = 8,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 9, weight = 1.0f, transitionBars = 1)),
              macroOverrides = MacroOverrides(energy = 0.484f),
              rampTo = MacroOverrides(energy = 0.887f),
              tensionOverride = TensionProfile(
                innerBars = 8,
                outerBars = 2,
                outerDepth = 0.75f,
                volume = 1.0f,
                evolution = EvolutionTension(
                  timbreLow = -1.0f, timbreHigh = -1.0f, morphLow = 0.45f, morphHigh = 0.9f,
                  morphProbability = 0.8f, harmonicsLow = 0.4f, harmonicsHigh = 0.9f,
                  harmonicsProbability = 0.85f, attackPoint = 0.15f, releaseSpeed = 0.2f,
                ),
                spurtChance = 0.1f,
                arc = TensionArc.RISE,
              ),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(
                  density = 0.0f, volume = 0.919f, reverbSend = 0.55f, hits = listOf(0),
                ),
                1 to TrackSectionOverride(density = 1.0f, morph = 0.85f, hits = listOf(0, 11, 19, 27)),
                2 to TrackSectionOverride(volume = 0.5f, morph = 0.7f, hits = listOf(5, 13, 22, 29)),
                3 to TrackSectionOverride(
                  density = 1.0f, volume = 0.17f, reverbSend = 0.6f, delaySend = 0.025f,
                ),
                4 to TrackSectionOverride(density = 0.0f, reverbSend = 0.3f),
                5 to TrackSectionOverride(
                  density = 1.0f, volume = 0.078f, morph = 0.55f, reverbSend = 1.0f, breatheBars = 1,
                  breatheFloor = 0.2f, breatheTimbreSpan = 0.35f, breatheSmooth = true,
                ),
                6 to TrackSectionOverride(density = 0.14f, volume = 0.251f, delaySend = 0.3f),
                7 to TrackSectionOverride(
                  density = 0.0f, volume = 0.681f, morph = 0.7f, morphRise = 0.4f,
                  envelopeProfile = EnvelopeProfile.MELODIC, lpgMode = LpgMode.PLUCK,
                ),
              ),
              customProgression = listOf(
                ChordStep(degree = 0),
                ChordStep(degree = 0),
                ChordStep(degree = 0),
                ChordStep(degree = 4),
              ),
              chordsPerBar = 4,
              weather = SectionWeather(rain = 0.6f, rumble = 0.4f, distance = 0.7f, rainLevel = 0.22f),
              lickIndex = 3,
              bassLineIndex = 4,
              delay = SectionDelay(timeA = 0.231f, timeB = 0.348f, feedback = 0.5f),
              volumeEnvelope = listOf(
                VolumePoint(bar = 0.0f, level = 0.65f),
                VolumePoint(bar = 2.0f, level = 1.0f),
                VolumePoint(bar = 3.0f, level = 0.7f),
                VolumePoint(bar = 5.5f, level = 1.15f),
                VolumePoint(bar = 6.0f, level = 0.75f),
                VolumePoint(bar = 8.0f, level = 1.25f),
              ),
            ),
            Section(
              name = "pound",
              barsMin = 8,
              barsMax = 10,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 3, weight = 1.0f, transitionBars = 2)),
              macroOverrides = MacroOverrides(energy = 1.3f, complexity = 0.3f, mood = 0.385f),
              tensionOverride = TensionProfile(
                outerBars = 8,
                outerDepth = 0.95f,
                volume = 0.65f,
                timing = 0.3f,
                evolution = EvolutionTension(
                  timbreLow = -1.0f, timbreHigh = -1.0f, morphLow = -1.0f, morphHigh = -1.0f,
                  harmonicsLow = -1.0f, harmonicsHigh = -1.0f, attackPoint = 0.1f, releaseSpeed = 0.15f,
                ),
                spurtChance = 0.25f,
                arc = TensionArc.RISE,
              ),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(
                  volume = 0.447f, morph = 0.55f, reverbSend = 0.3f, delaySend = 0.0f,
                  hits = listOf(0, 4, 8, 12, 16, 20, 24, 28),
                ),
                1 to TrackSectionOverride(volume = 0.06f, hits = listOf(6, 22)),
                2 to TrackSectionOverride(
                  volume = 0.167f, morph = 0.22f, delaySend = 0.3f, hits = listOf(5, 11, 14, 22, 27),
                ),
                3 to TrackSectionOverride(
                  volume = 0.425f, reverbSend = 0.15f, delaySend = 0.005f, lpgMode = LpgMode.PLUCK,
                ),
                4 to TrackSectionOverride(
                  density = 1.0f, volume = 0.22f, pan = 0.3f, doubleDetune = 15.0f, doubleSpread = 0.5f,
                  reverbSend = 0.9f, delaySend = 0.733f,
                ),
                5 to TrackSectionOverride(
                  volume = 0.2f, reverbSend = 1.0f, lpgMode = LpgMode.PLUCK_REPEAT_8TH,
                ),
                6 to TrackSectionOverride(density = 0.0f),
                7 to TrackSectionOverride(
                  holdProbability = 0.0f, density = 0.0f, volume = 0.114f, morph = 0.75f, morphRise = 0.5f,
                  reverbSend = 1.0f, delaySend = 0.929f, envelopeProfile = EnvelopeProfile.MELODIC,
                  lpgMode = LpgMode.PLUCK,
                ),
              ),
              customProgression = listOf(
                ChordStep(degree = 0),
                ChordStep(degree = 0),
                ChordStep(degree = 3),
                ChordStep(degree = 3),
              ),
              chordsPerBar = 4,
              weather = SectionWeather(rain = 0.4f, rumble = 0.5f, distance = 0.8f, rainLevel = 0.12f),
              lickIndex = 5,
              bassLineIndex = 6,
            ),
            Section(
              name = "wander",
              barsMin = 6,
              barsMax = 6,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 4, weight = 1.0f, transitionBars = 2)),
              macroOverrides = MacroOverrides(energy = 1.1f, complexity = 0.8f),
              tensionOverride = TensionProfile(
                innerBars = 8,
                outerBars = 8,
                outerDepth = 0.6f,
                evolution = EvolutionTension(
                  timbreLow = -1.0f, timbreHigh = -1.0f, morphLow = -1.0f, morphHigh = -1.0f,
                  harmonicsLow = -1.0f, harmonicsHigh = -1.0f, attackPoint = 0.15f, releaseSpeed = 0.2f,
                ),
                spurtChance = 0.1f,
                arc = TensionArc.RISE,
              ),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(
                  density = 1.0f, volume = 0.38f, morph = 0.45f, reverbSend = 0.4f,
                  hits = listOf(0, 8, 16, 24),
                ),
                1 to TrackSectionOverride(
                  density = 1.0f, volume = 0.05f, morph = 0.85f, reverbSend = 0.8f, hits = listOf(22),
                ),
                2 to TrackSectionOverride(
                  density = 1.0f, volume = 0.16f, morph = 0.05f, hits = listOf(11, 27),
                ),
                3 to TrackSectionOverride(
                  density = 1.0f, volume = 0.38f, reverbSend = 0.3f, lpgMode = LpgMode.SUSTAINED,
                ),
                4 to TrackSectionOverride(density = 0.0f),
                5 to TrackSectionOverride(density = 1.0f, volume = 0.11f, reverbSend = 1.0f),
                6 to TrackSectionOverride(
                  density = 1.0f, volume = 0.2f, reverbSend = 0.75f, delaySend = 0.45f,
                ),
                7 to TrackSectionOverride(
                  density = 0.35f, volume = 0.13f, reverbSend = 1.0f, delaySend = 0.8f,
                  envelopeProfile = EnvelopeProfile.DRONE, lpgMode = LpgMode.SUSTAINED,
                ),
              ),
              customProgression = listOf(
                ChordStep(degree = 0),
                ChordStep(degree = 0),
                ChordStep(degree = 2),
                ChordStep(degree = 4),
              ),
              chordsPerBar = 4,
              weather = SectionWeather(rain = 0.3f, rumble = 0.3f, distance = 0.8f, rainLevel = 0.15f),
              lickIndex = 7,
              bassLineIndex = 1,
              fog = SectionFog(level = 0.25f, intervalBars = 8, pattern = FogPattern.SINGLE, distance = 0.8f),
              delay = SectionDelay(timeA = 0.466f, timeB = 0.702f, feedback = 0.6f),
            ),
            Section(
              name = "submerge",
              barsMax = 4,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 5, weight = 1.0f, transitionBars = 2)),
              macroOverrides = MacroOverrides(energy = 1.05f, space = 1.2f),
              rampTo = MacroOverrides(energy = 0.6f, space = 1.3f),
              tensionOverride = TensionProfile(
                innerBars = 8,
                outerBars = 8,
                outerDepth = 0.6f,
                volume = 0.4f,
                evolution = EvolutionTension(
                  timbreLow = -1.0f, timbreHigh = -1.0f, morphLow = -1.0f, morphHigh = -1.0f,
                  harmonicsLow = -1.0f, harmonicsHigh = -1.0f, attackPoint = 0.15f, releaseSpeed = 0.2f,
                ),
                spurtChance = 0.1f,
                arc = TensionArc.FALL,
              ),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(
                  density = 1.0f, volume = 0.35f, reverbSend = 0.5f, hits = listOf(0, 16),
                ),
                1 to TrackSectionOverride(density = 0.0f),
                2 to TrackSectionOverride(density = 0.0f),
                3 to TrackSectionOverride(density = 1.0f, volume = 0.18f, lpgMode = LpgMode.SUSTAINED),
                4 to TrackSectionOverride(density = 0.0f),
                5 to TrackSectionOverride(
                  density = 1.0f, volume = 0.12f, reverbSend = 1.0f, breatheBars = 2, breatheFloor = 0.3f,
                  breatheTimbreSpan = 0.3f, breatheSmooth = true,
                ),
                6 to TrackSectionOverride(density = 0.0f),
                7 to TrackSectionOverride(
                  density = 0.6f, volume = 0.26f, reverbSend = 1.0f, delaySend = 0.9f,
                  envelopeProfile = EnvelopeProfile.DRONE, lpgMode = LpgMode.SUSTAINED,
                ),
              ),
              customProgression = listOf(
                ChordStep(degree = 0),
                ChordStep(degree = 0),
                ChordStep(degree = 2),
                ChordStep(degree = 2),
              ),
              chordsPerBar = 4,
              weather = SectionWeather(rain = 0.15f, rumble = 0.25f, distance = 0.9f, rainLevel = 0.1f),
              lickIndex = 7,
              bassLineIndex = 1,
              fog = SectionFog(level = 0.25f, intervalBars = 8, pattern = FogPattern.SINGLE, distance = 0.9f),
              delay = SectionDelay(timeA = 0.466f, timeB = 0.937f, feedback = 0.65f),
              volumeEnvelope = listOf(
                VolumePoint(bar = 0.0f, level = 1.0f),
                VolumePoint(bar = 4.0f, level = 0.7f),
              ),
            ),
            Section(
              name = "alone",
              barsMax = 4,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 6, weight = 1.0f, transitionBars = 1)),
              macroOverrides = MacroOverrides(energy = 0.5f, space = 1.3f),
              rampTo = MacroOverrides(energy = 0.9f),
              tensionOverride = TensionProfile(
                outerBars = 8,
                outerDepth = 0.6f,
                volume = 1.0f,
                evolution = EvolutionTension(
                  timbreLow = -1.0f, timbreHigh = -1.0f, morphLow = -1.0f, morphHigh = -1.0f,
                  harmonicsLow = -1.0f, harmonicsHigh = -1.0f, attackPoint = 0.15f, releaseSpeed = 0.2f,
                ),
                spurtChance = 0.1f,
                arc = TensionArc.RISE,
              ),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(density = 0.0f),
                1 to TrackSectionOverride(density = 1.0f, morph = 0.85f, hits = listOf(0, 19)),
                2 to TrackSectionOverride(density = 1.0f, volume = 0.4f, morph = 0.7f, hits = listOf(7, 22)),
                3 to TrackSectionOverride(density = 1.0f, volume = 0.17f),
                4 to TrackSectionOverride(density = 0.0f),
                5 to TrackSectionOverride(density = 1.0f, volume = 0.07f, morph = 0.55f),
                6 to TrackSectionOverride(
                  density = 1.0f, volume = 0.13f, reverbSend = 0.8f, delaySend = 0.5f,
                ),
                7 to TrackSectionOverride(density = 0.0f),
              ),
              customProgression = listOf(
                ChordStep(degree = 0),
                ChordStep(degree = 0),
                ChordStep(degree = 0),
                ChordStep(degree = 4),
              ),
              chordsPerBar = 4,
              weather = SectionWeather(rain = 0.05f, rumble = 0.15f, rainLevel = 0.3f),
              lickIndex = 0,
              bassLineIndex = 4,
              delay = SectionDelay(timeA = 0.466f, timeB = 0.702f, feedback = 0.65f),
              volumeEnvelope = listOf(
                VolumePoint(bar = 0.0f, level = 0.6f),
                VolumePoint(bar = 4.0f, level = 1.2f),
              ),
            ),
            Section(
              name = "pod",
              barsMin = 8,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 7, weight = 1.0f, transitionBars = 2)),
              macroOverrides = MacroOverrides(energy = 1.15f, space = 1.1f, mood = 0.9f),
              tensionOverride = TensionProfile(
                innerBars = 8,
                outerBars = 8,
                outerDepth = 0.6f,
                volume = 0.4f,
                evolution = EvolutionTension(
                  timbreLow = -1.0f, timbreHigh = -1.0f, morphLow = -1.0f, morphHigh = -1.0f,
                  harmonicsLow = -1.0f, harmonicsHigh = -1.0f, attackPoint = 0.15f, releaseSpeed = 0.2f,
                ),
                spurtChance = 0.1f,
                arc = TensionArc.RISE,
              ),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(
                  density = 1.0f, volume = 0.38f, morph = 0.5f, hits = listOf(0, 8, 16, 24),
                ),
                1 to TrackSectionOverride(
                  density = 1.0f, volume = 0.05f, morph = 0.85f, hits = listOf(6, 22),
                ),
                2 to TrackSectionOverride(
                  density = 1.0f, volume = 0.15f, morph = 0.05f, hits = listOf(5, 13, 22, 29),
                ),
                3 to TrackSectionOverride(density = 1.0f, volume = 0.4f, lpgMode = LpgMode.PLUCK),
                4 to TrackSectionOverride(
                  density = 1.0f, volume = 0.22f, doubleDetune = 22.0f, doubleSpread = 0.9f,
                  envelopeProfile = EnvelopeProfile.MELODIC,
                ),
                5 to TrackSectionOverride(density = 1.0f, volume = 0.1f, reverbSend = 1.0f),
                6 to TrackSectionOverride(
                  density = 0.3f, volume = 0.08f, reverbSend = 0.8f, delaySend = 0.55f,
                ),
                7 to TrackSectionOverride(
                  density = 0.2f, volume = 0.06f, envelopeProfile = EnvelopeProfile.DRONE,
                  lpgMode = LpgMode.SUSTAINED,
                ),
              ),
              customProgression = listOf(
                ChordStep(degree = 0),
                ChordStep(degree = 0),
                ChordStep(degree = 3),
                ChordStep(degree = 3),
              ),
              chordsPerBar = 4,
              weather = SectionWeather(rain = 0.12f, rumble = 0.12f, distance = 0.2f, rainLevel = 0.5f),
              lickIndex = 2,
              bassLineIndex = 5,
            ),
            Section(
              name = "surface",
              barsMax = 4,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 8, weight = 1.0f, transitionBars = 1)),
              macroOverrides = MacroOverrides(energy = 1.0f),
              rampTo = MacroOverrides(energy = 1.3f),
              tensionOverride = TensionProfile(
                outerBars = 8,
                outerDepth = 0.6f,
                volume = 0.5f,
                evolution = EvolutionTension(
                  timbreLow = -1.0f, timbreHigh = -1.0f, morphLow = -1.0f, morphHigh = -1.0f,
                  harmonicsLow = -1.0f, harmonicsHigh = -1.0f, attackPoint = 0.15f, releaseSpeed = 0.2f,
                ),
                spurtChance = 0.1f,
                arc = TensionArc.RISE,
              ),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(density = 1.0f, volume = 0.35f, hits = listOf(0, 16)),
                1 to TrackSectionOverride(density = 0.0f),
                2 to TrackSectionOverride(
                  density = 1.0f, volume = 0.14f, morph = 0.05f, hits = listOf(8, 24),
                ),
                3 to TrackSectionOverride(density = 1.0f, volume = 0.27f, lpgMode = LpgMode.SUSTAINED),
                4 to TrackSectionOverride(
                  density = 0.6f, volume = 0.2f, doubleDetune = 10.0f, doubleSpread = 0.6f,
                  envelopeProfile = EnvelopeProfile.MELODIC,
                ),
                5 to TrackSectionOverride(density = 1.0f, volume = 0.1f),
                6 to TrackSectionOverride(density = 1.0f, volume = 0.16f, delaySend = 0.5f),
                7 to TrackSectionOverride(density = 0.0f),
              ),
              customProgression = listOf(
                ChordStep(degree = 0),
                ChordStep(degree = 0),
                ChordStep(degree = 3),
                ChordStep(degree = 4),
              ),
              chordsPerBar = 4,
              lickIndex = 0,
              bassLineIndex = 1,
              volumeEnvelope = listOf(
                VolumePoint(bar = 0.0f, level = 0.8f),
                VolumePoint(bar = 4.0f, level = 1.15f),
              ),
            ),
            Section(
              name = "roll",
              barsMin = 8,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 10, weight = 1.0f, transitionBars = 2)),
              macroOverrides = MacroOverrides(energy = 1.35f, complexity = 0.6f),
              tensionOverride = TensionProfile(
                innerBars = 8,
                outerBars = 8,
                outerDepth = 0.6f,
                volume = 0.5f,
                evolution = EvolutionTension(
                  timbreLow = -1.0f, timbreHigh = -1.0f, morphLow = -1.0f, morphHigh = -1.0f,
                  harmonicsLow = -1.0f, harmonicsHigh = -1.0f, attackPoint = 0.15f, releaseSpeed = 0.2f,
                ),
                spurtChance = 0.1f,
                arc = TensionArc.RISE,
              ),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(
                  density = 1.0f, volume = 0.45f, morph = 0.55f, hits = listOf(0, 4, 8, 12, 16, 20, 24, 28),
                ),
                1 to TrackSectionOverride(density = 1.0f, volume = 0.06f, hits = listOf(6, 22)),
                2 to TrackSectionOverride(
                  density = 1.0f, volume = 0.15f, morph = 0.05f, hits = listOf(5, 13, 22, 29),
                ),
                3 to TrackSectionOverride(density = 1.0f, volume = 0.42f, lpgMode = LpgMode.PLUCK),
                4 to TrackSectionOverride(
                  density = 1.0f, volume = 0.3f, doubleDetune = 15.0f, doubleSpread = 0.6f,
                  envelopeProfile = EnvelopeProfile.MELODIC,
                ),
                5 to TrackSectionOverride(density = 1.0f, volume = 0.12f, reverbSend = 1.0f),
                6 to TrackSectionOverride(density = 0.2f, volume = 0.1f, delaySend = 0.55f),
                7 to TrackSectionOverride(density = 0.0f),
              ),
              customProgression = listOf(
                ChordStep(degree = 0),
                ChordStep(degree = 0),
                ChordStep(degree = 3),
                ChordStep(degree = 3),
              ),
              chordsPerBar = 4,
              weather = SectionWeather(rain = 0.3f, rumble = 0.4f, distance = 0.8f, rainLevel = 0.12f),
              lickIndex = 2,
              bassLineIndex = 6,
            ),
            Section(
              name = "heartbeat",
              barsMin = 1,
              barsMax = 1,
              transitions = listOf(SectionTransition(targetIndex = 2, weight = 1.0f, transitionBars = 1)),
              macroOverrides = MacroOverrides(energy = 1.1f),
              tensionOverride = TensionProfile(
                innerBars = 8,
                outerBars = 2,
                outerDepth = 0.75f,
                volume = 0.0f,
                evolution = EvolutionTension(
                  timbreLow = -1.0f, timbreHigh = -1.0f, morphLow = 0.45f, morphHigh = 0.9f,
                  morphProbability = 0.8f, harmonicsLow = 0.4f, harmonicsHigh = 0.9f,
                  harmonicsProbability = 0.85f, attackPoint = 0.15f, releaseSpeed = 0.2f,
                ),
                spurtChance = 0.1f,
                arc = TensionArc.RISE,
              ),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(density = 1.0f, morph = 0.55f, hits = listOf(0, 8, 16, 24)),
                1 to TrackSectionOverride(density = 0.0f),
                2 to TrackSectionOverride(density = 0.0f, volume = 0.329f),
                3 to TrackSectionOverride(density = 0.0f),
                4 to TrackSectionOverride(density = 0.0f),
                5 to TrackSectionOverride(density = 1.0f),
                6 to TrackSectionOverride(density = 0.0f),
                7 to TrackSectionOverride(density = 0.0f),
              ),
              customProgression = listOf(
                ChordStep(degree = 0),
                ChordStep(degree = 0),
                ChordStep(degree = 4),
                ChordStep(degree = 4),
              ),
              chordsPerBar = 4,
              weather = SectionWeather(rain = 0.6f, rumble = 0.6f, distance = 0.7f, rainLevel = 0.22f),
              lickIndex = 3,
              bassLineIndex = 4,
              volumeEnvelope = listOf(
                VolumePoint(bar = 0.0f, level = 0.4f),
                VolumePoint(bar = 0.5f, level = 0.7f),
                VolumePoint(bar = 1.0f, level = 1.15f),
              ),
            ),
            Section(
              name = "breach",
              barsMax = 4,
              barStep = 2,
              macroOverrides = MacroOverrides(energy = 0.45f, space = 1.2f),
              rampTo = MacroOverrides(energy = 0.3f),
              tensionOverride = TensionProfile(
                outerBars = 8,
                outerDepth = 0.6f,
                volume = 1.0f,
                evolution = EvolutionTension(
                  timbreLow = -1.0f, timbreHigh = -1.0f, morphLow = -1.0f, morphHigh = -1.0f,
                  harmonicsLow = -1.0f, harmonicsHigh = -1.0f, attackPoint = 0.15f, releaseSpeed = 0.2f,
                ),
                spurtChance = 0.1f,
                arc = TensionArc.FALL,
              ),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(density = 0.0f),
                1 to TrackSectionOverride(density = 1.0f, morph = 0.85f, hits = listOf(0, 19)),
                2 to TrackSectionOverride(density = 1.0f, volume = 0.35f, morph = 0.7f, hits = listOf(7, 22)),
                3 to TrackSectionOverride(density = 1.0f, volume = 0.15f),
                4 to TrackSectionOverride(density = 0.0f),
                5 to TrackSectionOverride(density = 1.0f, volume = 0.07f),
                6 to TrackSectionOverride(
                  density = 1.0f, volume = 0.08f, reverbSend = 0.9f, delaySend = 0.6f,
                ),
                7 to TrackSectionOverride(density = 0.0f),
              ),
              customProgression = listOf(ChordStep(degree = 0)),
              chordsPerBar = 4,
              weather = SectionWeather(rain = 0.2f, rumble = 0.1f, distance = 0.6f, rainLevel = 0.2f),
              lickIndex = 0,
              bassLineIndex = 1,
              delay = SectionDelay(timeA = 0.466f, timeB = 0.937f, feedback = 0.7f),
              volumeEnvelope = listOf(
                VolumePoint(bar = 0.0f, level = 1.0f),
                VolumePoint(bar = 2.0f, level = 0.75f),
                VolumePoint(bar = 4.0f, level = 0.0f),
              ),
            ),
          ),
          outroIndex = 10,
          lengthSeconds = 150..240,
          playOnce = true,
        ),
        progressionDriftRange = 0.1f,
        effects = VibeEffects(
          delayTimeA = 0.466f, delayTimeB = 0.702f, delayFeedback = 0.58f, reverbSize = 0.85f,
          reverbBrightness = 0.35f, deepFloor = 0.4f,
        ),
        anomalies = listOf(FogHornAnomaly(probability = 0.2f, intensity = 0.6f, distance = 0.7f)),
        cast = CastProvenance(
          seed = 7019L,
          mode = "BED_FIXED",
          styleTags = listOf("ambient", "psych"),
          pins = listOf(
            CastPinRecord(track = 0, role = "kick", engine = OrpheusEngineId.BD),
            CastPinRecord(track = 1, role = "snare"),
            CastPinRecord(track = 2, role = "hat"),
            CastPinRecord(track = 7, role = "perc", engine = OrpheusEngineId.MOD),
          ),
        ),
      )
      }
}
