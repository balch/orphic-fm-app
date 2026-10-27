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
import org.balch.orpheus.features.pulsar.models.SoloMode
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
 * A Whale's Dream — generated from an AI-archived vibe JSON by tools:vibe-codegen (NOT hand-authored).
 * A compiling, faithful starting point; polish (idiom collapsing, musical notes, tuning)
 * via the vibe-creator skill once this vibe earns a permanent keep.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class WhalesDreamVibe : VibeProvider {
  override val name: VibeName = VibeNames.A_WHALES_DREAM

  override val vibe: Vibe by lazy {
      Vibe(
        name = "Whale's Dream",
        tracks = listOf(
          TrackVoice(
            engineEdm = OrpheusEngine(OrpheusEngineId.BD,
              volume = 0.964f,
              harmonics = 0.57f,
              timbre = 0.42f,
              morph = 0.26f,
            ),
            engineSpace = OrpheusEngine(OrpheusEngineId.MOD,
              volume = 0.827f,
              harmonics = 0.26f, pinHarmonics = true,
              timbre = 0.42f,
              morph = 0.26f,
            ),
            density = 0.43f,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.7f, 1.0f),
              energyDensity = MacroTarget(0.4f, 0.8f),
              complexitySwing = MacroTarget(0.0f, 0.1f),
              complexityVariation = MacroTarget(0.0f, 0.15f),
              spaceDecay = MacroTarget(0.11f, 0.41f),
              moodHarmonics = MacroTarget(0.15f, 0.35f),
              moodTimbre = MacroTarget(0.1f, 0.3f),
            ),
          ),
          TrackVoice(
            engineEdm = OrpheusEngine(OrpheusEngineId.MOD,
              volume = 0.084f,
              harmonics = 0.3f, timbre = 0.6f, morph = 0.65f,
              pinHarmonics = true, pinTimbre = true, pinMorph = true,
              delaySend = 0.05f, reverbSend = 0.25f,
              noteRangeLow = 80, noteRangeHigh = 80,
            ),
            engineSpace = OrpheusEngine(OrpheusEngineId.MOD,
              volume = 0.084f,
              harmonics = 0.3f, timbre = 0.6f, morph = 0.95f,
              pinHarmonics = true, pinTimbre = true, pinMorph = true,
              delaySend = 0.05f, reverbSend = 0.25f,
              noteRangeLow = 80, noteRangeHigh = 80,
            ),
            pan = -0.09f,
            density = 0.36f,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.7f, 1.0f),
              energyDensity = MacroTarget(0.4f, 0.8f),
              complexitySwing = MacroTarget(0.0f, 0.1f),
              complexityVariation = MacroTarget(0.0f, 0.15f),
              spaceDecay = MacroTarget(0.22f, 0.52f),
              moodHarmonics = MacroTarget(0.21f, 0.51f),
              moodTimbre = MacroTarget(0.3f, 0.6f),
              moodPitch = MacroTarget(24.0f, 24.0f),
            ),
          ),
          TrackVoice(
            engineEdm = OrpheusEngine(
              OrpheusEngineId.HH, volume = 0.239f, harmonics = 0.47f, timbre = 0.45f, morph = 0.57f,
              delaySend = 0.34f, reverbSend = 0.65f, pitchRise = 300.0f, pitchRiseTime = 0.4f,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.HH, volume = 0.239f, harmonics = 0.47f, timbre = 0.45f, morph = 0.2f,
              delaySend = 0.34f, reverbSend = 0.65f, pinMorph = true,
            ),
            pan = -0.55f,
            density = 0.4f,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.7f, 1.0f),
              energyDensity = MacroTarget(0.4f, 0.8f),
              complexitySwing = MacroTarget(0.0f, 0.1f),
              complexityVariation = MacroTarget(0.0f, 0.15f),
              spaceDecay = MacroTarget(0.42f, 0.72f),
              moodHarmonics = MacroTarget(0.32f, 0.62f),
              moodTimbre = MacroTarget(0.3f, 0.6f),
            ),
          ),
          TrackVoice(
            engineEdm = OrpheusEngine(
              OrpheusEngineId.VCF, volume = 0.508f, harmonics = 0.44f, timbre = 0.38f, morph = 0.53f,
              reverbSend = 0.05f, noteRangeLow = 28, noteRangeHigh = 47, delayFeedback = 0.7f,
              glideRate = 0.6f,
            ),
            // The take's pair 0 strikes (STR); the panel's FM on the pair has no Pulsar equivalent.
            engineSpace = OrpheusEngine(
              OrpheusEngineId.STR, volume = 0.508f, harmonics = 0.77f, timbre = 0.74f, morph = 0.83f,
              reverbSend = 0.05f, noteRangeLow = 28, noteRangeHigh = 47, delayFeedback = 0.7f,
              glideRate = 0.6f, pinHarmonics = true, pinTimbre = true, pinMorph = true,
            ),
            role = TrackRole.Melodic(
              chordFollow = ChordFollow.FIXED,
              lickMode = LickMode.Fill,
              lickDegreeOffset = 0,
              textureLine = false,
              wahLick = false,
              wahParams = null,
              lickSource = LickSource.BASS,
            ),
            density = 0.4f,
            envelopeProfile = EnvelopeProfile.MELODIC,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.5f, 1.0f),
              energyDensity = MacroTarget(0.4f, 0.9f),
              complexitySwing = MacroTarget(0.0f, 0.15f),
              complexityVariation = MacroTarget(0.0f, 0.3f),
              spaceDecay = MacroTarget(0.38f, 0.68f),
              moodHarmonics = MacroTarget(0.29f, 0.59f),
              moodTimbre = MacroTarget(0.23f, 0.53f),
            ),
            barStrategy = BarStrategy.MUTATE,
          ),
          TrackVoice(
            engineEdm = OrpheusEngine(
              OrpheusEngineId.WSH, volume = 0.124f, harmonics = 0.45f, timbre = 0.6f, morph = 0.33f,
              delaySend = 0.428f, reverbSend = 0.35f, noteRangeLow = 62, noteRangeHigh = 73,
              delayFeedback = 0.72f, glideRate = 0.3f, lpgMode = LpgMode.SUSTAINED, lpgDecay = 0.8f,
              lpgColour = 0.4f, pinHarmonics = true, pinTimbre = true, pinMorph = true, vibratoDepth = 14.0f,
              vibratoRate = 3.5f, doubleDetune = 5.0f, doubleSpread = 0.2f,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.WSH, volume = 0.107f, harmonics = 0.45f, timbre = 0.6f, morph = 0.55f,
              delaySend = 0.55f, reverbSend = 0.5f, noteRangeLow = 62, noteRangeHigh = 73,
              delayFeedback = 0.72f, glideRate = 0.3f, lpgMode = LpgMode.SUSTAINED, lpgDecay = 0.8f,
              lpgColour = 0.4f, pinHarmonics = true, pinTimbre = true, pinMorph = true, vibratoDepth = 14.0f,
              vibratoRate = 3.5f, doubleDetune = 5.0f, doubleSpread = 0.2f,
            ),
            role = TrackRole.Melodic(
              chordFollow = ChordFollow.FOLLOW,
              lickMode = LickMode.Fill,
              lickDegreeOffset = 0,
              textureLine = false,
              wahLick = false,
              wahParams = null,
              lickSource = LickSource.LEAD,
            ),
            pan = 0.7f,
            density = 0.37f,
            envelopeProfile = EnvelopeProfile.DRONE,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.5f, 1.0f),
              energyDensity = MacroTarget(0.4f, 0.9f),
              complexitySwing = MacroTarget(0.0f, 0.15f),
              complexityVariation = MacroTarget(0.0f, 0.3f),
              spaceDecay = MacroTarget(0.18f, 0.48f),
              moodHarmonics = MacroTarget(0.35f, 0.95f),
              moodTimbre = MacroTarget(0.3f, 0.8f),
              // Mood helps the whale sing: flat to sharp, level at the vibe's default mood (0.4).
              moodPitch = MacroTarget(-2.0f, 3.0f),
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
            // The take's pair 1 drone voice (WTB); timbre stays free for the pad's LFO.
            engineSpace = OrpheusEngine(
              OrpheusEngineId.WTB, volume = 0.126f, harmonics = 0.23f, timbre = 0.79f, morph = 0.72f,
              modLfoRate = 0.015f, modLfoDepth = 0.7f, modLfoShape = 0.1f, modLfoCoupling = 0.4f,
              delaySend = 0.25f, reverbSend = 1.0f, noteRangeLow = 49, noteRangeHigh = 61,
              reverbBrightness = 0.35f, glideRate = 0.6f, pinHarmonics = true, pinMorph = true,
            ),
            role = TrackRole.Chordal(
              comping = ChordComping(arpMode = ArpMode.NEVER, restrikeOnChordChange = true),
              chordFollow = ChordFollow.FOLLOW,
            ),
            pan = -0.5f,
            density = 0.12f,
            envelopeProfile = EnvelopeProfile.DRONE,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.5f, 1.0f),
              energyDensity = MacroTarget(1.0f, 1.0f),
              complexitySwing = MacroTarget(0.0f, 0.15f),
              complexityVariation = MacroTarget(0.0f, 0.3f),
              spaceDecay = MacroTarget(0.2f, 0.5f),
              moodHarmonics = MacroTarget(0.3f, 0.7f),
              moodTimbre = MacroTarget(0.2f, 0.5f),
            ),
          ),
          TrackVoice(
            engineEdm = OrpheusEngine(
              OrpheusEngineId.MOD, volume = 0.108f, harmonics = 0.3f, timbre = 0.25f, morph = 0.55f,
              delaySend = 0.45f, reverbSend = 0.5f, noteRangeLow = 76, noteRangeHigh = 76,
              reverbBrightness = 0.35f, pinHarmonics = true, pinTimbre = true, pinMorph = true,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.STR, volume = 0.168f, harmonics = 0.35f, morph = 0.9f, delaySend = 0.85f,
              reverbSend = 0.7f, noteRangeLow = 76, noteRangeHigh = 76, reverbBrightness = 0.35f,
              delayFeedback = 0.7f, lpgMode = LpgMode.SUSTAINED, lpgDecay = 0.8f, lpgColour = 0.45f,
              pinHarmonics = true, pinTimbre = true, pinMorph = true, vibratoDepth = 6.0f,
              vibratoRate = 6.0f,
            ),
            role = TrackRole.Melodic(
              chordFollow = ChordFollow.FIXED,
              lickMode = LickMode.None,
              lickDegreeOffset = 0,
              textureLine = false,
              wahLick = false,
              wahParams = null,
              lickSource = LickSource.LEAD,
            ),
            pan = 0.3f,
            density = 0.1f,
            envelopeProfile = EnvelopeProfile.MELODIC,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.8f, 1.0f),
              energyDensity = MacroTarget(1.0f, 1.0f),
              complexitySwing = MacroTarget(0.0f, 0.1f),
              complexityVariation = MacroTarget(0.0f, 0.15f),
              spaceDecay = MacroTarget(0.55f, 0.55f),
              moodHarmonics = MacroTarget(0.3f, 0.3f),
              moodTimbre = MacroTarget(0.25f, 0.25f),
            ),
            barStrategy = BarStrategy.INDEPENDENT,
          ),
          TrackVoice(
            engineEdm = OrpheusEngine(
              OrpheusEngineId.TRN, volume = 0.215f, timbre = 0.4f, delaySend = 0.557f, reverbSend = 0.831f,
              noteRangeLow = 48, noteRangeHigh = 60, glideRate = 0.5f, vibratoDepth = 20.0f,
              vibratoRate = 3.5f, pitchRise = 500.0f, pitchRiseTime = 3.0f,
            ),
            // The take's pair 1 sweep voice (WTB, 2026-10-24 Orpheus capture), thickened by the double voice.
            engineSpace = OrpheusEngine(
              OrpheusEngineId.WTB, volume = 0.322f, harmonics = 0.23f, timbre = 0.79f, morph = 0.72f,
              delaySend = 0.65f, reverbSend = 0.9f, noteRangeLow = 48, noteRangeHigh = 60, glideRate = 0.85f,
              pinHarmonics = true, pinTimbre = true, pinMorph = true,
              grainScatterMode = GrainScatterMode.SCALE, grainVoicing = GrainVoicing.CENTERED,
              grainScatter = 0.3f, grainRate = 0.35f, grainRandomTiming = true, grainShape = 1.0f,
              grainBlend = 0.55f, grainFx = true, grainSize = 0.75f, vibratoDepth = 30.0f,
              vibratoRate = 2.8f, pitchRise = 600.0f, pitchRiseTime = 2.2f, morphRise = 0.08f,
              doubleDetune = 25.0f, doubleSpread = 0.8f,
            ),
            role = TrackRole.Melodic(
              chordFollow = ChordFollow.FOLLOW,
              lickMode = LickMode.Fill,
              lickDegreeOffset = -7,
              textureLine = true,
              wahLick = false,
              wahParams = null,
              lickSource = LickSource.LEAD,
            ),
            pan = -0.75f,
            density = 0.3f,
            envelopeProfile = EnvelopeProfile.DRONE,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.8f, 1.0f),
              energyDensity = MacroTarget(1.0f, 1.0f),
              complexitySwing = MacroTarget(0.0f, 0.1f),
              complexityVariation = MacroTarget(0.0f, 0.15f),
              spaceDecay = MacroTarget(0.4f, 0.6f),
              moodHarmonics = MacroTarget(0.35f, 0.65f),
              moodTimbre = MacroTarget(0.4f, 0.7f),
              moodPitch = MacroTarget(-7.0f, 12.0f),
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
        lickMutation = 0.1f,
        lickMutationMode = LickMutationMode.CHORD_TONES,
        lickRotation = LickRotation(
          pool = listOf(
            Lick(
              steps = listOf(
                LickStep(-1, 5.0f),
                LickStep(4, 3.0f, 0.7f),
                LickStep(7, 3.5f, 0.85f, glideRate = 0.5f),
                LickStep(4, 2.5f, 0.6f, glideRate = 0.5f),
                LickStep(-1, 2.0f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                LickStep(-1, 6.0f),
                LickStep(9, 4.0f, glideRate = 0.4f),
                LickStep(6, 3.0f, 0.65f, glideRate = 0.5f),
                LickStep(-1, 3.0f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                LickStep(-1, 3.0f),
                LickStep(2, 2.0f, 0.65f),
                LickStep(4, 2.0f, 0.75f, glideRate = 0.4f),
                LickStep(-1, 3.0f),
                LickStep(6, 4.0f, 0.85f, glideRate = 0.5f),
                LickStep(-1, 2.0f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                LickStep(-1, 7.0f),
                LickStep(7, 6.0f, glideRate = 0.5f),
                LickStep(4, 3.0f, 0.5f, glideRate = 0.5f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                LickStep(-1, 4.0f),
                LickStep(7, 3.0f, glideRate = 0.6f),
                LickStep(-1, 5.0f),
                LickStep(9, 2.0f, 0.75f, glideRate = 0.65f),
                LickStep(7, 2.0f, 0.7f, glideRate = 0.6f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                LickStep(7, 1.0f, 0.9f),
                LickStep(-1, 1.0f),
                LickStep(11, 0.5f),
                LickStep(-1, 1.5f),
                LickStep(10, 1.0f, 0.9f),
                LickStep(-1, 1.0f),
                LickStep(13, 0.5f, 0.85f),
                LickStep(-1, 1.5f),
                LickStep(11, 1.0f, 0.85f),
                LickStep(-1, 1.0f),
                LickStep(8, 0.5f, 0.75f),
                LickStep(-1, 1.5f),
                LickStep(6, 1.0f, 0.9f),
                LickStep(-1, 1.0f),
                LickStep(4, 0.5f, 0.75f),
                LickStep(-1, 1.5f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                LickStep(0, 1.5f, 0.9f),
                LickStep(-1, 1.0f),
                LickStep(4, 2.0f, 0.85f),
                LickStep(0, 6.0f, 0.85f),
                LickStep(-1, 1.0f),
                LickStep(0, 3.0f, 0.7f),
                LickStep(-1, 1.5f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                LickStep(0, 0.25f, 0.35f),
                LickStep(0, 0.75f, 0.9f),
                LickStep(0, 0.25f, 0.35f),
                LickStep(0, 0.75f, 0.9f),
                LickStep(0, 0.25f, 0.35f),
                LickStep(0, 0.75f, 0.9f),
                LickStep(0, 0.25f, 0.35f),
                LickStep(0, 0.75f, 0.9f),
                LickStep(0, 0.25f, 0.35f),
                LickStep(0, 0.75f, 0.9f),
                LickStep(0, 0.25f, 0.35f),
                LickStep(0, 0.75f, 0.9f),
                LickStep(0, 0.25f, 0.35f),
                LickStep(0, 0.75f, 0.9f),
                LickStep(2, 0.25f, 0.35f),
                LickStep(4, 0.75f, 0.85f),
                LickStep(0, 0.25f, 0.35f),
                LickStep(0, 0.75f, 0.9f),
                LickStep(0, 0.25f, 0.35f),
                LickStep(0, 0.75f, 0.9f),
                LickStep(0, 0.25f, 0.35f),
                LickStep(0, 0.75f, 0.9f),
                LickStep(0, 0.25f, 0.35f),
                LickStep(0, 0.75f, 0.9f),
                LickStep(0, 0.25f, 0.35f),
                LickStep(0, 0.75f, 0.9f),
                LickStep(0, 0.25f, 0.35f),
                LickStep(0, 0.75f, 0.9f),
                LickStep(2, 0.25f, 0.35f),
                LickStep(4, 0.75f, 0.85f),
                LickStep(0, 0.25f, 0.35f),
                LickStep(0, 0.75f, 0.9f),
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
        bassLineMutation = 0.05f,
        bassLineOctave = 2,
        band = Band(
          members = listOf(
            BandMember(
              name = "Drummer",
              tracks = listOf(0, 1, 2),
              alwaysActive = true,
              loudness = 0.7f,
              creativity = 0.3f,
            ),
            BandMember(
              name = "Sea",
              tracks = listOf(5, 6, 7),
              alwaysActive = true,
              loudness = 0.4f,
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
          customProgression = listOf(ChordStep(degree = 0), ChordStep(degree = 3), ChordStep(degree = 0), ChordStep(degree = 6)),
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
            timbreLow = -1.0f,
            timbreHigh = -1.0f,
            morphLow = -1.0f,
            morphHigh = -1.0f,
            harmonicsLow = -1.0f,
            harmonicsHigh = -1.0f,
          ),
        ),
        arrangement = Arrangement(
          sections = listOf(
            Section(
              name = "ping",
              barsMin = 2, barsMax = 2,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = 1)),
              macroOverrides = MacroOverrides(energy = 0.35f, space = 1.2f),
              rampTo = MacroOverrides(energy = 0.5f),
              tensionOverride = TensionProfile(
                outerBars = 2,
                outerDepth = 0.6f,
                volume = 0.4f,
                timing = 0.15f,
                evolution = EvolutionTension(
                  morphLow = 0.35f,
                  morphHigh = 0.85f,
                  morphProbability = 0.7f,
                  harmonicsLow = 0.3f,
                  harmonicsHigh = 0.85f,
                  harmonicsProbability = 0.8f,
                  attackPoint = 0.2f,
                  releaseSpeed = 0.25f,
                ),
                arc = TensionArc.RISE,
              ),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(density = 0.0f),
                1 to TrackSectionOverride(hits = listOf(6)),
                2 to TrackSectionOverride(
                  volume = 0.239f,
                  morph = 0.05f,
                  reverbSend = 0.57f,
                  // Two bubble bursts a bar, not a clock; the hat's pitch rise makes each hit a rising blip.
                  hits = listOf(9, 10, 25, 26, 27),
                ),
                3 to TrackSectionOverride(density = 0.45f, volume = 0.404f, reverbSend = 0.7f, delaySend = 0.03f),
                4 to TrackSectionOverride(density = 0.2f, volume = 0.149f, pan = 0.65f),
                5 to TrackSectionOverride(density = 0.0f, volume = 0.063f),
                6 to TrackSectionOverride(density = 0.14f, volume = 0.084f, pan = -0.9f),
                7 to TrackSectionOverride(
                  density = 0.08f, volume = 0.681f, morph = 0.6f, morphRise = 0.3f, reverbSend = 0.9f,
                  delaySend = 1.0f, envelopeProfile = EnvelopeProfile.MELODIC, lpgMode = LpgMode.PLUCK,
                ),
              ),
              customProgression = listOf(ChordStep(degree = 0)),
              chordsPerBar = 1,
              lickIndex = 0,
              // Long, wide echoes on the opening pings, matching the vibe-wide delay.
              delay = SectionDelay(timeA = 0.466f, timeB = 0.702f, feedback = 0.45f),
            ),
            Section(
              name = "swell",
              barsMin = 2, barsMax = 2,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 2, weight = 1.0f, transitionBars = 1)),
              macroOverrides = MacroOverrides(energy = 0.6f),
              rampTo = MacroOverrides(energy = 1.1f),
              tensionOverride = TensionProfile(
                outerBars = 2,
                outerDepth = 0.75f,
                volume = 0.5f,
                evolution = EvolutionTension(
                  morphLow = 0.45f,
                  morphHigh = 0.9f,
                  morphProbability = 0.8f,
                  harmonicsLow = 0.4f,
                  harmonicsHigh = 0.9f,
                  harmonicsProbability = 0.85f,
                  attackPoint = 0.15f,
                  releaseSpeed = 0.2f,
                ),
                spurtChance = 0.1f,
                arc = TensionArc.RISE,
              ),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(volume = 0.919f, reverbSend = 0.55f, hits = listOf(0)),
                1 to TrackSectionOverride(hits = listOf(6)),
                2 to TrackSectionOverride(
                  volume = 0.276f, morph = 0.05f, hits = listOf(0, 4, 8, 12, 16, 20, 24, 28),
                ),
                3 to TrackSectionOverride(volume = 0.428f, reverbSend = 0.6f, delaySend = 0.025f),
                4 to TrackSectionOverride(density = 0.0f, reverbSend = 0.3f),
                5 to TrackSectionOverride(
                  volume = 0.078f, reverbSend = 1.0f, breatheBars = 1, breatheFloor = 0.2f,
                  breatheTimbreSpan = 0.35f, breatheSmooth = true,
                ),
                6 to TrackSectionOverride(density = 0.14f),
                7 to TrackSectionOverride(
                  density = 0.35f, volume = 0.681f, morph = 0.7f, morphRise = 0.4f,
                  envelopeProfile = EnvelopeProfile.MELODIC, lpgMode = LpgMode.PLUCK,
                ),
              ),
              weather = SectionWeather(rain = 0.6f, rumble = 0.4f, distance = 0.7f, rainLevel = 0.22f),
              lickIndex = 0,
              delay = SectionDelay(timeA = 0.231f, timeB = 0.348f, feedback = 0.5f),
              // Two crashes in the 2-bar swell: dips at bars 0 and 1.25, swelling to 1.2 at bar 1.
              volumeEnvelope = listOf(
                VolumePoint(bar = 0.0f, level = 0.95f),
                VolumePoint(bar = 0.25f, level = 0.45f),
                VolumePoint(bar = 1.0f, level = 1.2f),
                VolumePoint(bar = 1.25f, level = 0.5f),
                VolumePoint(bar = 1.85f, level = 1.0f),
                VolumePoint(bar = 2.0f, level = 1.15f),
              ),
            ),
            Section(
              name = "pound",
              barsMin = 4, barsMax = 4,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 3, weight = 1.0f, transitionBars = 2)),
              macroOverrides = MacroOverrides(energy = 1.3f, complexity = 0.3f, mood = 0.385f),
              tensionOverride = TensionProfile(
                outerBars = 8,
                outerDepth = 0.95f,
                volume = 0.65f,
                timing = 0.3f,
                evolution = EvolutionTension(attackPoint = 0.1f, releaseSpeed = 0.15f),
                spurtChance = 0.25f,
                arc = TensionArc.RISE,
              ),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(
                  volume = 0.631f, morph = 0.55f, reverbSend = 0.3f, delaySend = 0.0f,
                  hits = listOf(0, 4, 8, 12, 16, 20, 24, 28),
                ),
                1 to TrackSectionOverride(hits = listOf(6)),
                2 to TrackSectionOverride(
                  volume = 0.167f, morph = 0.22f, delaySend = 0.3f, hits = listOf(5, 11, 14, 22, 27),
                ),
                3 to TrackSectionOverride(volume = 0.756f, reverbSend = 0.15f, delaySend = 0.005f),
                4 to TrackSectionOverride(
                  density = 0.5f, volume = 0.057f, pan = -0.55f, doubleDetune = 0.0f, doubleSpread = 0.0f,
                  reverbSend = 0.9f, delaySend = 0.733f,
                ),
                5 to TrackSectionOverride(volume = 0.071f, reverbSend = 1.0f, lpgMode = LpgMode.PLUCK_REPEAT_8TH),
                6 to TrackSectionOverride(density = 0.06f),
                7 to TrackSectionOverride(
                  holdProbability = 0.0f, density = 0.1f, volume = 0.114f, morph = 0.75f, morphRise = 0.5f,
                  reverbSend = 1.0f, delaySend = 0.929f, envelopeProfile = EnvelopeProfile.MELODIC,
                  lpgMode = LpgMode.PLUCK,
                ),
              ),
              customProgression = listOf(ChordStep(degree = 0)),
              chordsPerBar = 1,
              weather = SectionWeather(rain = 0.4f, rumble = 0.5f, distance = 0.8f, rainLevel = 0.12f),
              lickIndex = 0,
            ),
            Section(
              name = "wander",
              barsMin = 6,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 4, weight = 1.0f, transitionBars = 2)),
              macroOverrides = MacroOverrides(energy = 1.25f, complexity = 1.2f),
              // The keys' jam stays close to the whale phrases.
              soloMode = SoloMode.Jam(probability = 1.0f, lickInfluence = 0.7f),
              trackOverrides = mapOf(
                1 to TrackSectionOverride(hits = listOf(6)),
                2 to TrackSectionOverride(
                  volume = 0.319f,
                  morph = 0.05f,
                  // Scattered bubbles, as in pound, in place of straight eighths.
                  hits = listOf(5, 11, 14, 22, 27),
                ),
                4 to TrackSectionOverride(density = 0.2f),
                // The whale keeps calling through wander, plucked and rising as in swell.
                7 to TrackSectionOverride(
                  density = 0.3f, morph = 0.7f, morphRise = 0.4f, envelopeProfile = EnvelopeProfile.MELODIC,
                  lpgMode = LpgMode.PLUCK,
                ),
              ),
              lickIndex = 0,
              fog = SectionFog(level = 0.3f, intervalBars = 8, pattern = FogPattern.SINGLE, distance = 0.75f),
            ),
            Section(
              name = "submerge",
              barsMax = 4,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 5, weight = 1.0f, transitionBars = 1)),
              macroOverrides = MacroOverrides(energy = 1.0f, space = 1.0f),
              rampTo = MacroOverrides(energy = 0.55f, space = 1.3f),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(volume = 0.574f, hits = listOf(0)),
                1 to TrackSectionOverride(hits = listOf(6)),
                2 to TrackSectionOverride(
                  volume = 0.239f, morph = 0.05f, hits = listOf(0, 4, 8, 12, 16, 20, 24, 28),
                ),
                3 to TrackSectionOverride(volume = 0.446f),
                6 to TrackSectionOverride(density = 0.1f),
                7 to TrackSectionOverride(
                  density = 0.2f, morph = 0.6f, morphRise = 0.3f, envelopeProfile = EnvelopeProfile.MELODIC,
                  lpgMode = LpgMode.PLUCK,
                ),
              ),
              weather = SectionWeather(rumble = 0.15f, distance = 0.8f),
              lickIndex = 0,
              fog = SectionFog(level = 0.25f, intervalBars = 8, pattern = FogPattern.SINGLE, distance = 0.9f),
            ),
            Section(
              name = "alone",
              barsMax = 6,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 6, weight = 1.0f, transitionBars = 1)),
              macroOverrides = MacroOverrides(energy = 0.5f, space = 1.3f, mood = 0.8f),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(density = 0.0f),
                1 to TrackSectionOverride(volume = 0.042f, morph = 0.85f, reverbSend = 1.0f, hits = listOf(6)),
                2 to TrackSectionOverride(volume = 0.212f, morph = 0.05f, hits = listOf(0, 8, 16, 24)),
                3 to TrackSectionOverride(density = 0.0f),
                4 to TrackSectionOverride(
                  doubleDetune = 6.0f, doubleSpread = 0.3f, envelopeProfile = EnvelopeProfile.DRONE,
                ),
                5 to TrackSectionOverride(volume = 0.039f),
                6 to TrackSectionOverride(density = 0.08f, reverbSend = 0.78f, delaySend = 0.62f),
                7 to TrackSectionOverride(
                  density = 0.3f, reverbSend = 1.0f, breatheBars = 4, breatheFloor = 0.05f,
                  breatheTimbreSpan = 0.3f,
                ),
              ),
              customProgression = listOf(ChordStep(degree = 0), ChordStep(degree = 6)),
              chordsPerBar = 1,
              weather = SectionWeather(rain = 0.05f, rumble = 0.1f, distance = 0.3f, rainLevel = 0.35f),
              lickIndex = 1,
              // Quarter and dotted-quarter echoes, long tail, for the lone whale's calls.
              delay = SectionDelay(timeA = 0.466f, timeB = 0.702f, feedback = 0.65f),
            ),
            Section(
              name = "pod",
              barsMin = 6,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 7, weight = 1.0f, transitionBars = 2)),
              macroOverrides = MacroOverrides(energy = 0.6f, space = 1.3f, mood = 0.9f),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(density = 0.0f),
                1 to TrackSectionOverride(volume = 0.049f, morph = 0.85f, reverbSend = 1.0f, hits = listOf(6)),
                2 to TrackSectionOverride(volume = 0.212f, morph = 0.05f, hits = listOf(0, 8, 16, 24)),
                3 to TrackSectionOverride(volume = 0.41f),
                4 to TrackSectionOverride(
                  doubleDetune = 22.0f, doubleSpread = 0.9f, envelopeProfile = EnvelopeProfile.DRONE,
                ),
                5 to TrackSectionOverride(volume = 0.083f),
                6 to TrackSectionOverride(density = 0.06f),
              ),
              customProgression = listOf(ChordStep(degree = 0), ChordStep(degree = 6)),
              chordsPerBar = 1,
              weather = SectionWeather(rain = 0.12f, rumble = 0.12f, distance = 0.2f, rainLevel = 0.5f),
              lickIndex = 2,
            ),
            Section(
              name = "surface",
              barsMax = 4,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 8, weight = 1.0f, transitionBars = 1)),
              macroOverrides = MacroOverrides(energy = 0.65f),
              rampTo = MacroOverrides(energy = 1.2f),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(volume = 0.769f, hits = listOf(0, 16)),
                1 to TrackSectionOverride(hits = listOf(6)),
                2 to TrackSectionOverride(
                  volume = 0.276f, morph = 0.05f, hits = listOf(0, 4, 8, 12, 16, 20, 24, 28),
                ),
                3 to TrackSectionOverride(volume = 0.526f),
                5 to TrackSectionOverride(volume = 0.165f),
                6 to TrackSectionOverride(density = 0.1f),
                7 to TrackSectionOverride(density = 0.3f),
              ),
              lickIndex = 3,
            ),
            Section(
              name = "roll",
              barsMin = 6,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 9, weight = 1.0f, transitionBars = 1)),
              macroOverrides = MacroOverrides(energy = 1.35f, complexity = 1.0f),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(hits = listOf(0, 6, 16, 22)),
                1 to TrackSectionOverride(hits = listOf(6)),
                2 to TrackSectionOverride(
                  volume = 0.319f, morph = 0.05f,
                  hits = listOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30),
                ),
                4 to TrackSectionOverride(doubleDetune = 12.0f, doubleSpread = 0.5f),
                7 to TrackSectionOverride(density = 0.0f),
              ),
              lickIndex = 3,
            ),
            Section(
              name = "deep jam",
              barsMin = 6,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 10, weight = 1.0f, transitionBars = 2)),
              macroOverrides = MacroOverrides(energy = 1.4f, complexity = 1.3f),
              soloMode = SoloMode.Jam(probability = 1.0f, lickInfluence = 0.3f),
              trackOverrides = mapOf(
                1 to TrackSectionOverride(hits = listOf(6)),
                2 to TrackSectionOverride(
                  volume = 0.329f, morph = 0.05f,
                  hits = listOf(0, 2, 4, 6, 8, 10, 12, 13, 14, 15, 16, 18, 20, 22, 24, 26, 28, 29, 30, 31),
                ),
                4 to TrackSectionOverride(density = 0.25f),
                7 to TrackSectionOverride(density = 0.0f),
              ),
              lickIndex = 3,
            ),
            Section(
              name = "breach",
              barsMax = 4,
              barStep = 2,
              macroOverrides = MacroOverrides(energy = 0.4f),
              rampTo = MacroOverrides(energy = 0.3f),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(density = 0.0f),
                1 to TrackSectionOverride(hits = listOf(6)),
                2 to TrackSectionOverride(volume = 0.239f, morph = 0.05f, hits = listOf(0, 16)),
                3 to TrackSectionOverride(density = 0.0f),
                5 to TrackSectionOverride(volume = 0.083f),
                6 to TrackSectionOverride(density = 0.14f),
                7 to TrackSectionOverride(density = 0.35f, morph = 0.55f, morphRise = 0.35f),
              ),
              customProgression = listOf(ChordStep(degree = 0)),
              chordsPerBar = 1,
              lickIndex = 2,
              // Quarter and half-note echoes ring the last pings out.
              delay = SectionDelay(timeA = 0.466f, timeB = 0.937f, feedback = 0.7f),
              volumeEnvelope = listOf(
                VolumePoint(bar = 0.0f, level = 1.0f),
                VolumePoint(bar = 2.0f, level = 0.7f),
                VolumePoint(bar = 4.0f, level = 0.0f),
              ),
            ),
          ),
          outroIndex = 10,
          playOnce = true,
        ),
        progressionDriftRange = 0.1f,
        effects = VibeEffects(
          delayTimeA = 0.466f,
          delayTimeB = 0.702f,
          delayFeedback = 0.58f,
          reverbSize = 0.85f,
          reverbBrightness = 0.35f,
          deepFloor = 0.4f,
        ),
        anomalies = listOf(
          FogHornAnomaly(probability = 0.2f, intensity = 0.6f, distance = 0.7f),
        ),
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
