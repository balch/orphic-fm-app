package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlin.String
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.anonmalies.CutAnomaly
import org.balch.orpheus.features.pulsar.anonmalies.VoidAnomaly
import org.balch.orpheus.features.pulsar.models.Album
import org.balch.orpheus.features.pulsar.models.ArpDirection
import org.balch.orpheus.features.pulsar.models.ArpMode
import org.balch.orpheus.features.pulsar.models.Arrangement
import org.balch.orpheus.features.pulsar.models.Band
import org.balch.orpheus.features.pulsar.models.BandMember
import org.balch.orpheus.features.pulsar.models.BarStrategy
import org.balch.orpheus.features.pulsar.models.ChordComping
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.ChordStep
import org.balch.orpheus.features.pulsar.models.CompingFills
import org.balch.orpheus.features.pulsar.models.CompingHumanization
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.Evolution
import org.balch.orpheus.features.pulsar.models.EvolutionTension
import org.balch.orpheus.features.pulsar.models.FillType
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.HalfLick
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickRotation
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.LpgMode
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.MacroSource
import org.balch.orpheus.features.pulsar.models.MacroTarget
import org.balch.orpheus.features.pulsar.models.NoteFollowMode
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.PitchEvolution
import org.balch.orpheus.features.pulsar.models.ProgressionAnchor
import org.balch.orpheus.features.pulsar.models.ProgressionStyle
import org.balch.orpheus.features.pulsar.models.RhythmicEvolution
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SectionInversion
import org.balch.orpheus.features.pulsar.models.SectionTransition
import org.balch.orpheus.features.pulsar.models.SoloMode
import org.balch.orpheus.features.pulsar.models.TensionProfile
import org.balch.orpheus.features.pulsar.models.TonalTension
import org.balch.orpheus.features.pulsar.models.TrackMacroMap
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackSectionOverride
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import org.balch.orpheus.features.pulsar.models.VibeEffects
import org.balch.orpheus.features.pulsar.models.VibeProvider
import org.balch.orpheus.features.pulsar.models.bandMatrix
import org.balch.orpheus.features.pulsar.models.row

/**
 * Aether Natalis — vast, slow, being born in the upper air.
 *
 * A cathedral rather than a vacuum: struck metal, stacked partials and vowels instead of
 * grains and dust. A filtered sub-pedal tolls under an additive choir; a modal bell carries
 * the melody while two chordal voices — a native chord engine and a rolled wavetable — do the
 * actual harmonic work. FM pipes and a swarm hold the bed; a speech voice breathes over the top.
 *
 * D Mixolydian at 52 BPM with a TIDES envelope. Harmony moves I–bVII–IV–I twice a bar, slow
 * enough to float but never frozen. Tension is a long late-cresting build (12-bar inner cycle,
 * peak at 0.62, slow release) rather than an early spike, and a three-lick rotation keeps the
 * bell's figure from settling into a loop.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
public class AetherNatalisVibe : VibeProvider {
  override val name: String = "Aether Natalis"

  /** The hook: a rising modal figure that reaches the bVII and falls back. 16 beats. */
  private val ascentLick = Lick(
    steps = listOf(
      LickStep(scaleDegree = 0, duration = 2.0f, velocity = 0.62f, glideRate = 0.35f),
      LickStep(scaleDegree = -1, duration = 1.0f, velocity = 0.0f, glideRate = 0.0f),
      LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.55f, glideRate = 0.3f),
      LickStep(scaleDegree = 4, duration = 3.0f, velocity = 0.68f, glideRate = 0.3f),
      LickStep(scaleDegree = -1, duration = 1.0f, velocity = 0.0f, glideRate = 0.0f),
      LickStep(scaleDegree = 6, duration = 2.0f, velocity = 0.72f, glideRate = 0.35f),
      LickStep(scaleDegree = 4, duration = 2.0f, velocity = 0.60f, glideRate = 0.3f),
      LickStep(scaleDegree = -1, duration = 1.0f, velocity = 0.0f, glideRate = 0.0f),
      LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.50f, glideRate = 0.3f),
      LickStep(scaleDegree = 0, duration = 2.0f, velocity = 0.52f, glideRate = 0.4f),
    ),
    loopLength = 16,
  )

  /** The answer: enters on the octave and walks down the mode. 16 beats. */
  private val descentLick = Lick(
    steps = listOf(
      LickStep(scaleDegree = 7, duration = 3.0f, velocity = 0.70f, glideRate = 0.35f),
      LickStep(scaleDegree = 6, duration = 1.0f, velocity = 0.60f, glideRate = 0.3f),
      LickStep(scaleDegree = 4, duration = 2.0f, velocity = 0.64f, glideRate = 0.3f),
      LickStep(scaleDegree = -1, duration = 1.0f, velocity = 0.0f, glideRate = 0.0f),
      LickStep(scaleDegree = 3, duration = 3.0f, velocity = 0.66f, glideRate = 0.35f),
      LickStep(scaleDegree = 2, duration = 1.0f, velocity = 0.55f, glideRate = 0.3f),
      LickStep(scaleDegree = -1, duration = 1.0f, velocity = 0.0f, glideRate = 0.0f),
      LickStep(scaleDegree = 1, duration = 2.0f, velocity = 0.50f, glideRate = 0.35f),
      LickStep(scaleDegree = 0, duration = 2.0f, velocity = 0.55f, glideRate = 0.45f),
    ),
    loopLength = 16,
  )

  /** Near-static: three tones and a lot of air, for the sections that should barely move. */
  private val hoverLick = Lick(
    steps = listOf(
      LickStep(scaleDegree = 4, duration = 4.0f, velocity = 0.55f, glideRate = 0.45f),
      LickStep(scaleDegree = -1, duration = 2.0f, velocity = 0.0f, glideRate = 0.0f),
      LickStep(scaleDegree = 6, duration = 3.0f, velocity = 0.60f, glideRate = 0.4f),
      LickStep(scaleDegree = -1, duration = 1.0f, velocity = 0.0f, glideRate = 0.0f),
      LickStep(scaleDegree = 4, duration = 2.0f, velocity = 0.50f, glideRate = 0.4f),
      LickStep(scaleDegree = -1, duration = 2.0f, velocity = 0.0f, glideRate = 0.0f),
      LickStep(scaleDegree = 0, duration = 2.0f, velocity = 0.48f, glideRate = 0.5f),
    ),
    loopLength = 16,
  )

  override val vibe: Vibe by lazy {
      Vibe(
        name = name,
        album = Album.STEALTH,
        tracks = listOf(
          // 0 — UNDERCROFT. Filtered sub-pedal, the only percussive voice: a slow toll that
          // gives the float something to hang off. Macro ranges carry the hand-tuned darker /
          // harder / brighter-attack intent, because the map overwrites the authored knobs.
          OrpheusEngine(
            engineId = OrpheusEngineId.VCF,
            volume = 0.62f,
            harmonics = 0.35f,
            timbre = 0.62f,
            morph = 0.55f,
            modLfoRate = 0.09f,
            modLfoDepth = 0.12f,
            modLfoShape = 0.3f,
            modLfoCoupling = 0.2f,
            delaySend = 0.30f,
            reverbSend = 0.62f,
            noteRangeLow = 30,
            noteRangeHigh = 46,
            reverbBrightness = 0.4f,
            lpgMode = LpgMode.PLUCK,
            lpgDecay = 0.35f,
            lpgColour = 0.45f,
          ).let { undercroft ->
            TrackVoice(
              engineEdm = undercroft,
              // Space slot inherits the hand-tuned push: louder, wetter, longer bloom.
              engineSpace = undercroft.copy(
                volume = 0.52f,
                delaySend = 0.45f,
                reverbSend = 0.72f,
                lpgDecay = 0.55f,
              ),
              role = TrackRole.Percussive,
              pan = 0.0f,
              density = 0.55f,
              envelopeProfile = EnvelopeProfile.RHYTHM,
              macroMap = TrackMacroMap(
                energyVolume = MacroTarget(min = 0.45f, max = 1.0f),
                energyDensity = MacroTarget(min = 0.30f, max = 0.90f),
                // complexitySwing is read from track 0 only — keep the float dead-straight.
                complexitySwing = MacroTarget(min = 0.0f, max = 0.06f),
                complexityVariation = MacroTarget(min = 0.0f, max = 0.25f),
                spaceDecay = MacroTarget(min = 0.50f, max = 0.80f),
                moodHarmonics = MacroTarget(min = 0.15f, max = 0.45f),
                moodTimbre = MacroTarget(min = 0.45f, max = 0.80f),
              ),
              barStrategy = BarStrategy.REPEAT,
              evolution = Evolution(
                rhythmic = RhythmicEvolution(tensionResponse = 0.5f, noteFollow = NoteFollowMode.SLIDE),
              ),
            )
          },
          // 1 — NATAL CHOIR. Additive shimmer, the "born in the upper air" layer. Answers the
          // bell via CALL_RESPONSE and articulates rather than drones.
          OrpheusEngine(
            engineId = OrpheusEngineId.ADD,
            volume = 0.72f,
            harmonics = 0.62f,
            timbre = 0.55f,
            morph = 0.50f,
            modLfoRate = 0.05f,
            modLfoDepth = 0.35f,
            modLfoShape = 0.2f,
            modLfoCoupling = 0.35f,
            holdProbability = 0.15f,
            holdLengthMin = 2,
            holdLengthMax = 6,
            delaySend = 0.50f,
            reverbSend = 0.75f,
            noteRangeLow = 60,
            noteRangeHigh = 84,
            reverbBrightness = 0.70f,
            glideRate = 0.15f,
          ).let { choir ->
            TrackVoice(
              engineEdm = choir,
              engineSpace = choir.copy(volume = 0.62f, delaySend = 0.58f, reverbSend = 0.82f),
              role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
              pan = -0.30f,
              density = 0.45f,
              envelopeProfile = EnvelopeProfile.EFFECT,
              macroMap = TrackMacroMap(
                energyVolume = MacroTarget(min = 0.30f, max = 1.0f),
                energyDensity = MacroTarget(min = 0.15f, max = 0.85f),
                complexitySwing = MacroTarget(min = 0.0f, max = 0.08f),
                complexityVariation = MacroTarget(min = 0.15f, max = 0.55f),
                spaceDecay = MacroTarget(min = 0.35f, max = 0.75f),
                // Hand-tuned intent: many partials, thinner drive. Biased high / low to match.
                moodHarmonics = MacroTarget(min = 0.55f, max = 0.95f),
                moodTimbre = MacroTarget(min = 0.30f, max = 0.65f),
              ),
              barStrategy = BarStrategy.CALL_RESPONSE,
              evolution = Evolution(pitch = PitchEvolution.Contour(driftRange = 0.20f)),
            )
          },
          // 2 — BELL NAVE. Modal struck metal carries the melody. FIXED so the figure pedals
          // while the chordal tracks move the harmony underneath it.
          OrpheusEngine(
            engineId = OrpheusEngineId.MOD,
            volume = 0.70f,
            harmonics = 0.40f,
            timbre = 0.55f,
            morph = 0.45f,
            modLfoRate = 0.045f,
            modLfoDepth = 0.30f,
            modLfoShape = 0.25f,
            modLfoCoupling = 0.3f,
            holdProbability = 0.25f,
            holdLengthMin = 2,
            holdLengthMax = 5,
            delaySend = 0.55f,
            reverbSend = 0.80f,
            noteRangeLow = 55,
            noteRangeHigh = 79,
            reverbBrightness = 0.68f,
            lpgMode = LpgMode.SUSTAINED,
            lpgDecay = 0.75f,
            lpgColour = 0.55f,
          ).let { bell ->
            TrackVoice(
              engineEdm = bell,
              // Space slot rings longer and darker — the same bell in a bigger room.
              engineSpace = bell.copy(lpgDecay = 0.88f, lpgColour = 0.40f, reverbSend = 0.86f),
              role = TrackRole.Melodic(
                chordFollow = ChordFollow.FIXED,
                lickMode = LickMode.Fill,
              ),
              pan = 0.25f,
              density = 0.50f,
              envelopeProfile = EnvelopeProfile.MELODIC,
              macroMap = TrackMacroMap(
                energyVolume = MacroTarget(min = 0.40f, max = 1.0f),
                energyDensity = MacroTarget(min = 0.20f, max = 0.90f),
                complexitySwing = MacroTarget(min = 0.0f, max = 0.10f),
                complexityVariation = MacroTarget(min = 0.10f, max = 0.50f),
                spaceDecay = MacroTarget(min = 0.30f, max = 0.75f),
                // Modal harmonics is inharmonicity — keep it in the bell/bar neighborhood.
                moodHarmonics = MacroTarget(min = 0.20f, max = 0.55f),
                moodTimbre = MacroTarget(min = 0.35f, max = 0.80f),
              ),
              barStrategy = BarStrategy.MUTATE,
              evolution = Evolution(
                rhythmic = RhythmicEvolution(tensionResponse = 0.8f, noteFollow = NoteFollowMode.CONTOUR),
                pitch = PitchEvolution.Contour(driftRange = 0.22f),
              ),
            )
          },
          // 3 — VAULT. Chord engine, native voicings, open spread. This is the track that makes
          // the harmony audible instead of implied. harmonics is a quantized chord selector
          // (11 chords, floor-quantized), so it is pinned rather than left to the macro map.
          // 69 is the one quality that stays diatomic on all three roots of a Mixolydian
          // I-bVII-IV: every 7th-bearing chord would put a major 7th against the mode's bVII.
          OrpheusEngine(
            engineId = OrpheusEngineId.CHD,
            volume = 0.66f,
            harmonics = 0.6684f,  // (7 + 0.5) / (11 * 1.02) — chord idx 7 "69"
            timbre = 0.40f,
            morph = 0.55f,
            modLfoRate = 0.03f,
            modLfoDepth = 0.25f,
            modLfoShape = 0.15f,
            modLfoCoupling = 0.25f,
            holdProbability = 0.85f,
            holdLengthMin = 12,
            holdLengthMax = 24,
            delaySend = 0.25f,
            reverbSend = 0.70f,
            noteRangeLow = 48,
            noteRangeHigh = 72,
            reverbBrightness = 0.55f,
            glideRate = 0.35f,
            pinHarmonics = true,
          ).let { vault ->
            TrackVoice(
              engineEdm = vault,
              engineSpace = vault.copy(volume = 0.60f, reverbSend = 0.78f, glideRate = 0.5f),
              role = TrackRole.Chordal(
                comping = ChordComping(
                  style = CompingStyle.PAD,
                  arpMode = ArpMode.AUTO,
                  arpSpeed = 0.12f,
                  arpDirection = ArpDirection.UP,
                  sectionInversion = SectionInversion.OPEN_VOICING,
                  humanization = CompingHumanization(
                    dropProbability = 0.12f,
                    ghostProbability = 0.10f,
                    octaveJumpProbability = 0.06f,
                    extensionProbability = 0.22f,
                  ),
                  fills = CompingFills(
                    everyNBars = 4,
                    fillType = FillType.ASCENDING_ARP,
                    skipProbability = 0.4f,
                  ),
                ),
                chordFollow = ChordFollow.FOLLOW,
              ),
              pan = 0.0f,
              density = 0.30f,
              envelopeProfile = EnvelopeProfile.DRONE,
              macroMap = TrackMacroMap(
                energyVolume = MacroTarget(min = 0.55f, max = 0.90f),
                energyDensity = MacroTarget(min = 0.20f, max = 0.45f),
                complexitySwing = MacroTarget(min = 0.0f, max = 0.0f),
                complexityVariation = MacroTarget(min = 0.05f, max = 0.30f),
                // OPEN_VOICING forces morph to 0.9 on a native-chord engine every block, so
                // spaceDecay cannot reach morph here — held at the same value to say so.
                spaceDecay = MacroTarget(min = 0.90f, max = 0.90f),
                // Dead under pinHarmonics; matched to the pinned patch so it reads honestly.
                moodHarmonics = MacroTarget(min = 0.6684f, max = 0.6684f),
                moodTimbre = MacroTarget(min = 0.30f, max = 0.60f),
              ),
              barStrategy = BarStrategy.REPEAT,
              evolution = Evolution(pitch = PitchEvolution.Voicing(tensionResponse = 0.7f)),
            )
          },
          // 4 — UPPER VOICES. Wavetable rolled upward on 1 and 3 — the actual "rising" gesture,
          // slow enough at 52 BPM to read as a harp roll rather than an arpeggio.
          OrpheusEngine(
            engineId = OrpheusEngineId.WTB,
            volume = 0.60f,
            harmonics = 0.55f,
            timbre = 0.60f,
            morph = 0.45f,
            modLfoRate = 0.07f,
            modLfoDepth = 0.30f,
            modLfoShape = 0.35f,
            modLfoCoupling = 0.3f,
            holdProbability = 0.30f,
            holdLengthMin = 2,
            holdLengthMax = 6,
            delaySend = 0.60f,
            reverbSend = 0.78f,
            noteRangeLow = 67,
            noteRangeHigh = 91,
            reverbBrightness = 0.75f,
          ).let { upper ->
            TrackVoice(
              engineEdm = upper,
              engineSpace = upper.copy(volume = 0.52f, delaySend = 0.66f, reverbSend = 0.85f),
              role = TrackRole.Chordal(
                comping = ChordComping(
                  style = CompingStyle.ROCK_DOWNBEATS,
                  arpMode = ArpMode.ALWAYS,
                  arpSpeed = 0.18f,
                  arpDirection = ArpDirection.UP,
                  sectionInversion = SectionInversion.FIRST_INVERSION,
                  humanization = CompingHumanization(
                    dropProbability = 0.18f,
                    ghostProbability = 0.14f,
                    octaveJumpProbability = 0.12f,
                    extensionProbability = 0.30f,
                  ),
                  fills = CompingFills(
                    everyNBars = 8,
                    fillType = FillType.STAB_FLURRY,
                    skipProbability = 0.35f,
                  ),
                ),
                chordFollow = ChordFollow.FOLLOW,
              ),
              pan = 0.40f,
              density = 0.42f,
              envelopeProfile = EnvelopeProfile.MELODIC,
              macroMap = TrackMacroMap(
                energyVolume = MacroTarget(min = 0.30f, max = 0.95f),
                energyDensity = MacroTarget(min = 0.15f, max = 0.80f),
                complexitySwing = MacroTarget(min = 0.0f, max = 0.05f),
                complexityVariation = MacroTarget(min = 0.10f, max = 0.45f),
                spaceDecay = MacroTarget(min = 0.30f, max = 0.70f),
                moodHarmonics = MacroTarget(min = 0.40f, max = 0.85f),
                moodTimbre = MacroTarget(min = 0.40f, max = 0.85f),
              ),
              barStrategy = BarStrategy.FILL,
              evolution = Evolution(pitch = PitchEvolution.Voicing(tensionResponse = 1.0f)),
            )
          },
          // 5 — PIPES. FM organ bed (bank 3 idx 6 "Pipes 3"), the long-sustaining floor of the
          // cathedral. harmonicsMacroRange lets each section's mood override walk the patch a
          // couple of slots, so the organ changes stop without a per-section declaration.
          OrpheusEngine(
            engineId = OrpheusEngineId.DX3,
            volume = 0.70f,
            harmonics = 0.1991f,  // (6 + 0.5) / (32 * 1.02) — bank 3 idx 6 "Pipes 3"
            timbre = 0.48f,
            morph = 0.42f,
            modLfoRate = 0.025f,
            modLfoDepth = 0.35f,
            modLfoShape = 0.15f,
            modLfoCoupling = 0.3f,
            holdProbability = 0.90f,
            holdLengthMin = 16,
            holdLengthMax = 32,
            delaySend = 0.30f,
            reverbSend = 0.72f,
            noteRangeLow = 45,
            noteRangeHigh = 69,
            reverbBrightness = 0.50f,
            glideRate = 0.30f,
            lpgMode = LpgMode.SUSTAINED,
            lpgDecay = 0.85f,
            lpgColour = 0.35f,
            harmonicsMacroSource = MacroSource.MOOD,
            harmonicsMacroRange = 0.045f,
          ).let { pipes ->
            TrackVoice(
              engineEdm = pipes,
              engineSpace = pipes.copy(volume = 0.66f, timbre = 0.38f, reverbSend = 0.80f),
              role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
              pan = -0.15f,
              density = 0.16f,
              envelopeProfile = EnvelopeProfile.DRONE,
              macroMap = TrackMacroMap(
                energyVolume = MacroTarget(min = 0.60f, max = 0.90f),
                energyDensity = MacroTarget(min = 0.10f, max = 0.28f),
                complexitySwing = MacroTarget(min = 0.0f, max = 0.0f),
                complexityVariation = MacroTarget(min = 0.0f, max = 0.15f),
                spaceDecay = MacroTarget(min = 0.35f, max = 0.55f),
                moodHarmonics = MacroTarget(min = 0.45f, max = 0.45f),
                moodTimbre = MacroTarget(min = 0.35f, max = 0.60f),
              ),
              barStrategy = BarStrategy.REPEAT,
            )
          },
          // 6 — AETHER. Swarm bed. Carries the hand-tuned pull-back: the EDM slot is the
          // tightened, quieter swarm; the Space slot keeps the fuller, more detuned original.
          OrpheusEngine(
            engineId = OrpheusEngineId.SWM,
            volume = 0.42f,
            harmonics = 0.30f,
            timbre = 0.50f,
            morph = 0.70f,
            modLfoRate = 0.05f,
            modLfoDepth = 0.45f,
            modLfoShape = 0.35f,
            modLfoCoupling = 0.4f,
            holdProbability = 0.70f,
            holdLengthMin = 8,
            holdLengthMax = 20,
            delaySend = 0.62f,
            reverbSend = 0.82f,
            noteRangeLow = 52,
            noteRangeHigh = 76,
            reverbBrightness = 0.65f,
          ).let { aether ->
            TrackVoice(
              engineEdm = aether,
              engineSpace = aether.copy(volume = 0.50f, harmonics = 0.50f, morph = 0.80f),
              role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
              pan = -0.45f,
              density = 0.20f,
              envelopeProfile = EnvelopeProfile.DRONE,
              macroMap = TrackMacroMap(
                energyVolume = MacroTarget(min = 0.35f, max = 0.75f),
                energyDensity = MacroTarget(min = 0.10f, max = 0.40f),
                complexitySwing = MacroTarget(min = 0.0f, max = 0.20f),
                complexityVariation = MacroTarget(min = 0.10f, max = 0.45f),
                // Hand-tuned intent: swarm sits wide-morph, tighter-harmonic.
                spaceDecay = MacroTarget(min = 0.50f, max = 0.85f),
                moodHarmonics = MacroTarget(min = 0.20f, max = 0.55f),
                moodTimbre = MacroTarget(min = 0.35f, max = 0.65f),
              ),
              barStrategy = BarStrategy.INDEPENDENT,
            )
          },
          // 7 — BREATH. Speech engine held in formant/vowel territory (the model switches at
          // harmonics 0.33, so harmonics is pinned below it). Space sweeps the vowel via morph,
          // which is exactly what spaceDecay drives — deliberate, not incidental.
          OrpheusEngine(
            engineId = OrpheusEngineId.SPK,
            volume = 0.40f,
            harmonics = 0.16f,
            timbre = 0.50f,
            morph = 0.45f,
            modLfoRate = 0.035f,
            modLfoDepth = 0.50f,
            modLfoShape = 0.25f,
            modLfoCoupling = 0.45f,
            holdProbability = 0.35f,
            holdLengthMin = 3,
            holdLengthMax = 8,
            delaySend = 0.55f,
            reverbSend = 0.85f,
            noteRangeLow = 57,
            noteRangeHigh = 79,
            reverbBrightness = 0.60f,
            glideRate = 0.20f,
            pinHarmonics = true,
          ).let { breath ->
            TrackVoice(
              engineEdm = breath,
              engineSpace = breath.copy(volume = 0.34f, delaySend = 0.62f, reverbSend = 0.90f),
              role = TrackRole.Melodic(chordFollow = ChordFollow.FOLLOW),
              pan = 0.45f,
              density = 0.30f,
              envelopeProfile = EnvelopeProfile.EFFECT,
              macroMap = TrackMacroMap(
                energyVolume = MacroTarget(min = 0.25f, max = 0.80f),
                energyDensity = MacroTarget(min = 0.12f, max = 0.60f),
                complexitySwing = MacroTarget(min = 0.0f, max = 0.30f),
                complexityVariation = MacroTarget(min = 0.20f, max = 0.65f),
                spaceDecay = MacroTarget(min = 0.25f, max = 0.75f),
                moodHarmonics = MacroTarget(min = 0.16f, max = 0.16f),
                moodTimbre = MacroTarget(min = 0.30f, max = 0.70f),
              ),
              barStrategy = BarStrategy.INDEPENDENT,
              evolution = Evolution(
                rhythmic = RhythmicEvolution(tensionResponse = 1.0f, noteFollow = NoteFollowMode.BLEND),
              ),
            )
          },
        ),
        lick = ascentLick,
        lickRotation = LickRotation(pool = listOf(ascentLick, descentLick, hoverLick)),
        lickMutation = 0.38f,
        lickOctave = 5,
        bassLine = null,
        bassLineMutation = 0.5f,
        bassLineOctave = -1,
        band = Band(
          members = listOf(
            BandMember(
              name = "Bell",
              tracks = listOf(0, 2),
              alwaysActive = false,
              loudness = 0.6f,
              creativity = 0.55f,
              swing = 0.0f,
              drag = 0.02f,
            ),
            BandMember(
              name = "Choir",
              tracks = listOf(1, 7),
              alwaysActive = false,
              loudness = 0.5f,
              creativity = 0.7f,
              swing = 0.0f,
              drag = 0.04f,
            ),
            BandMember(
              name = "Organ",
              tracks = listOf(3, 5),
              alwaysActive = true,
              loudness = 0.55f,
              creativity = 0.3f,
              swing = 0.0f,
              drag = 0.0f,
            ),
            BandMember(
              name = "Upper Voices",
              tracks = listOf(4),
              alwaysActive = false,
              loudness = 0.5f,
              creativity = 0.65f,
              swing = 0.0f,
              drag = -0.02f,
            ),
            BandMember(
              name = "Air",
              tracks = listOf(6),
              alwaysActive = true,
              loudness = 0.4f,
              creativity = 0.5f,
              swing = 0.0f,
              drag = 0.06f,
            ),
          ),
          //                       Bell  Choir Organ Upper Air
          handoffMatrix = bandMatrix(
            "Bell" to row(0.00f, 0.30f, 0.15f, 0.40f, 0.15f),
            "Choir" to row(0.20f, 0.00f, 0.30f, 0.35f, 0.15f),
            "Organ" to row(0.15f, 0.35f, 0.00f, 0.30f, 0.20f),
            "Upper Voices" to row(0.35f, 0.30f, 0.20f, 0.00f, 0.15f),
            "Air" to row(0.20f, 0.30f, 0.25f, 0.25f, 0.00f),
          ),
          pullInMatrix = bandMatrix(
            "Bell" to row(0.00f, 0.45f, 0.35f, 0.30f, 0.25f),
            "Choir" to row(0.30f, 0.00f, 0.40f, 0.45f, 0.30f),
            "Organ" to row(0.25f, 0.50f, 0.00f, 0.35f, 0.30f),
            "Upper Voices" to row(0.40f, 0.45f, 0.30f, 0.00f, 0.25f),
            "Air" to row(0.20f, 0.40f, 0.35f, 0.35f, 0.00f),
          ),
          pullInBarsMin = 3,
          pullInBarsMax = 8,
          barsPerLeadMin = 6,
          barsPerLeadMax = 12,
        ),
        seed = 1747,
        bpm = 52.0f,
        envelopeType = EnvelopeType.TIDES,
        rootNote = RootNote.D,
        scaleType = ScaleType.MIXOLYDIAN,
        genre = GenreProfile(
          swingAmount = 0.02f,
          ghostProbability = 0.22f,
          noteRangeLow = 30,
          noteRangeHigh = 91,
          // SPARSE.density is literally 0.0f, which is the level-0 kick-on-beat-1 pattern.
          // 0.12 blends ~36% of a four-on-floor into it: an irregular toll, not a pulse.
          rhythmDensity = 0.12f,
          progressionStyle = ProgressionStyle.MODAL,
          chordsPerBar = 2,
          chordTransitionMatrix = null,
          // I - bVII - IV - I. The Mixolydian bVII is what keeps it modal and unresolved.
          customProgression = listOf(
            ChordStep(degree = 0, glideRate = 0.5f),
            ChordStep(degree = 6, glideRate = 0.45f),
            ChordStep(degree = 3, glideRate = 0.45f),
            ChordStep(degree = 0, glideRate = 0.55f),
          ),
        ),
        energy = 0.52f,
        complexity = 0.58f,
        space = 0.78f,
        mood = 0.68f,
        deep = 0.76f,
        stepCount = 32,
        // A long late-cresting arc: the build takes most of the 12-bar cycle and lets go
        // slowly, which is the opposite shape from the drone family's early spike + snap-back.
        tension = TensionProfile(
          innerBars = 12,
          outerBars = 24,
          outerDepth = 0.80f,
          volume = 0.55f,
          tonal = TonalTension(
            octaveShift = true,
            keyShift = 0,
            halfLick = HalfLick.JAM,
            chromaticPassing = 0.15f,
          ),
          timing = 0.05f,
          evolution = EvolutionTension(
            timbreLow = 0.25f,
            timbreHigh = 0.80f,
            timbreProbability = 0.70f,
            morphLow = 0.30f,
            morphHigh = 0.85f,
            morphProbability = 0.65f,
            harmonicsLow = 0.20f,
            harmonicsHigh = 0.75f,
            harmonicsProbability = 0.55f,
            attackPoint = 0.62f,
            releaseSpeed = 0.35f,
          ),
          spurtChance = 0.08f,
        ),
        arrangement = Arrangement(
          sections = listOf(
            Section(
              name = "first light",
              barsMin = 6,
              barsMax = 10,
              barStep = 2,
              transitions = listOf(
                SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = 2),
              ),
              recencyDecay = 0.4f,
              macroOverrides = MacroOverrides(
                energy = 0.45f,
                complexity = 0.35f,
                space = 1.25f,
                mood = 0.85f,
              ),
              soloMode = null,
              // Opens on a held tonic before the progression starts moving.
              chordsPerBar = 1,
              bpmMultiplier = 1.0f,
            ),
            Section(
              name = "updraft",
              barsMin = 12,
              barsMax = 20,
              barStep = 4,
              transitions = listOf(
                SectionTransition(targetIndex = 2, weight = 0.60f, transitionBars = 3),
                SectionTransition(targetIndex = 3, weight = 0.25f, transitionBars = 2),
                SectionTransition(targetIndex = 0, weight = 0.15f, transitionBars = 0),
              ),
              recencyDecay = 0.55f,
              macroOverrides = MacroOverrides(
                energy = 1.15f,
                complexity = 0.90f,
                space = 0.95f,
                mood = 1.05f,
              ),
              soloMode = SoloMode.Jam(probability = 0.55f, lickInfluence = 0.65f),
              bpmMultiplier = 1.0f,
            ),
            Section(
              name = "nave",
              barsMin = 10,
              barsMax = 18,
              barStep = 2,
              transitions = listOf(
                SectionTransition(targetIndex = 3, weight = 0.55f, transitionBars = 3),
                SectionTransition(targetIndex = 1, weight = 0.45f, transitionBars = 2),
              ),
              recencyDecay = 0.5f,
              macroOverrides = MacroOverrides(
                energy = 1.35f,
                complexity = 1.20f,
                space = 0.85f,
                mood = 1.15f,
              ),
              soloMode = SoloMode.LickBuilder(probability = 0.50f, mutationRate = 0.45f),
              // The choir stops floating and starts articulating at the peak.
              trackOverrides = mapOf(
                7 to TrackSectionOverride(holdProbability = 0.20f, density = 0.50f, volume = 0.55f),
              ),
              compingStyle = CompingStyle.GOSPEL_STABS,
              compingInversion = SectionInversion.SECOND_INVERSION,
              chordsPerBar = 2,
              bpmMultiplier = 1.0f,
              // Carry an in-flight jam across the seam so the solo develops instead of restarting.
              jamCarry = true,
            ),
            Section(
              name = "dispersal",
              barsMin = 8,
              barsMax = 14,
              barStep = 2,
              transitions = listOf(
                SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = 3),
              ),
              recencyDecay = 0.5f,
              macroOverrides = MacroOverrides(
                energy = 0.35f,
                complexity = 0.60f,
                space = 1.50f,
                mood = 0.90f,
              ),
              soloMode = SoloMode.LongFill(probability = 0.40f, barsMin = 2, barsMax = 5),
              chordsPerBar = 1,
              bpmMultiplier = 1.0f,
            ),
          ),
          introIndex = 0,
          outroIndex = 3,
          defaultSectionBars = 8,
          lengthSeconds = 210..330,
          transitionOut = null,
        ),
        progressionAnchor = ProgressionAnchor.EVERY_4,
        progressionDriftRange = 0.28f,
        effects = VibeEffects(
          delayTimeA = 0.375f,
          delayTimeB = 0.5f,
          delayFeedback = 0.55f,
          delayDamping = 0.30f,
          reverbSize = 0.95f,
          reverbDamping = 0.28f,
          reverbBrightness = 0.62f,
          deepFloor = 0.52f,
        ),
        lickWah = null,
        anomalies = listOf(
          // A slow tidal gate — one cycle per half bar at 52 BPM reads as the air breathing,
          // not as a stutter.
          CutAnomaly(
            probability = 0.035f,
            durationBarsMin = 2.0f,
            durationBarsMax = 4.0f,
            gateRate = 8.0f,
            duty = 0.62f,
            depth = 0.18f,
          ),
          // Deeper and much longer than the usual void, with the ghost bar nearly out —
          // the reverb tails do the work while everything else falls away.
          VoidAnomaly(
            probability = 0.02f,
            floorLevel = 0.08f,
            rampDownBars = 3.0f,
            floorBarsMin = 1.5f,
            floorBarsMax = 3.0f,
            rampUpBars = 2.5f,
            ghostIntensity = 0.15f,
          ),
        ),
      )
      }
}
