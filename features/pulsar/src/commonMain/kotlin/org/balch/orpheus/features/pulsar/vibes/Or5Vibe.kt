package org.balch.orpheus.features.pulsar.vibes

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.OrpheusEngineId
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.pulsar.anonmalies.WahAnomaly
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
import org.balch.orpheus.features.pulsar.models.CompingFills
import org.balch.orpheus.features.pulsar.models.CompingHumanization
import org.balch.orpheus.features.pulsar.models.CompingStyle
import org.balch.orpheus.features.pulsar.models.EnvelopeProfile
import org.balch.orpheus.features.pulsar.models.EnvelopeType
import org.balch.orpheus.features.pulsar.models.EvolutionTension
import org.balch.orpheus.features.pulsar.models.GenreProfile
import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickMode
import org.balch.orpheus.features.pulsar.models.LickMutationMode
import org.balch.orpheus.features.pulsar.models.LickRotation
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.LpgMode
import org.balch.orpheus.features.pulsar.models.MacroOverrides
import org.balch.orpheus.features.pulsar.models.MacroTarget
import org.balch.orpheus.features.pulsar.models.OrpheusEngine
import org.balch.orpheus.features.pulsar.models.ProgressionStyle
import org.balch.orpheus.features.pulsar.models.Rest
import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import org.balch.orpheus.features.pulsar.models.Section
import org.balch.orpheus.features.pulsar.models.SectionDelay
import org.balch.orpheus.features.pulsar.models.SectionTransition
import org.balch.orpheus.features.pulsar.models.SlotLock
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
import org.balch.orpheus.features.pulsar.models.WahParams

/**
 * OR-5: B minor reggae-rock at 91 bpm, made and mixed in the AI edition (2026-10-26).
 *
 * - one pass up the interstate: Medford (intro), Grants Pass (hook), Eugene (reggae building to
 *   rock), Corvallis (hook), Salem and Beaverton (jamming the hooks), Portland (the band falls apart).
 * - drums: analog kick with click, analog snare / DX2 SYNDM perc, DX2 Cimbal hats.
 * - track 3 is a DX bass, round on EDM and resonant on Space.
 * - track 5 is a DX3 organ (EDM) / soft pad (Space) bed; track 6 skanks low on a doubled wave terrain.
 * - track 4 is the lead: a saw that carries the verse hook, brighter on EDM; the snare and hats dub
 *   out through the tape delay, hardest in Portland.
 * - track 7 is a plucked xylophone echoing the hook a third up; it swells into the delay in
 *   Portland.
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<VibeProvider>())
class Or5Vibe : VibeProvider {
  override val name: VibeName = VibeNames.OR_5

  override val vibe: Vibe by lazy {
      Vibe(
        name = "OR-5",
        tracks = listOf(
          TrackVoice(
            // Analog kick with click, locked to EDM; DX2 idx 24 "Kettle" stays on Space unheard.
            engineEdm = OrpheusEngine(
              OrpheusEngineId.BD, volume = 0.4779901f, harmonics = 0.6f, timbre = 0.5f, morph = 0.5f,
              pinHarmonics = true, pinTimbre = true, pinMorph = true,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.DX2, volume = 0.4779901f, harmonics = 0.75061274f, timbre = 0.49155062f,
              morph = 0.23751944f, pinHarmonics = true, pinTimbre = true, pinMorph = true,
            ),
            density = 0.42f,
            slotLock = SlotLock.EDM,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.7f, 1.0f), energyDensity = MacroTarget(0.4f, 0.8f),
              complexitySwing = MacroTarget(0.0f, 0.1f), complexityVariation = MacroTarget(0.0f, 0.15f),
              spaceDecay = MacroTarget(0.16f, 0.46f), moodHarmonics = MacroTarget(0.35f, 0.65f),
              moodTimbre = MacroTarget(0.23f, 0.53f),
            ),
          ),
          TrackVoice(
            // Analog snare on EDM, DX2 idx 30 "SYNDM 25.8" on Space.
            engineEdm = OrpheusEngine(
              OrpheusEngineId.SD, volume = 0.5347506f, harmonics = 0.5f, timbre = 0.7f, morph = 0.28f,
              delaySend = 0.5f, reverbSend = 0.4f, pinHarmonics = true, pinTimbre = true, pinMorph = true,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.DX2, volume = 0.5347506f, harmonics = 0.9344f, timbre = 0.53f, morph = 0.4f,
              delaySend = 0.5f, reverbSend = 0.4f, pinMorph = true,
            ),
            pan = -0.08f,
            density = 0.42f,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.7f, 1.0f), energyDensity = MacroTarget(0.4f, 0.8f),
              complexitySwing = MacroTarget(0.0f, 0.1f), complexityVariation = MacroTarget(0.0f, 0.15f),
              spaceDecay = MacroTarget(0.21f, 0.51f), moodHarmonics = MacroTarget(0.17f, 0.47f),
              moodTimbre = MacroTarget(0.38f, 0.68f),
            ),
            barStrategy = BarStrategy.FILL,
          ),
          TrackVoice(
            // DX2 idx 29 "Cimbal" in both slots.
            engineEdm = OrpheusEngine(
              OrpheusEngineId.DX2, volume = 0.28117064f, harmonics = 0.9038f, timbre = 0.5215508f,
              morph = 0.29751956f, delaySend = 0.35f, reverbSend = 0.3f, pinTimbre = true, pinMorph = true,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.DX2, volume = 0.28117064f, harmonics = 0.9038f, timbre = 0.44155055f,
              morph = 0.46f, delaySend = 0.35f, reverbSend = 0.3f, pinTimbre = true, pinMorph = true,
            ),
            pan = 0.14f,
            density = 0.55f,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.7f, 1.0f), energyDensity = MacroTarget(0.4f, 0.8f),
              complexitySwing = MacroTarget(0.0f, 0.1f), complexityVariation = MacroTarget(0.0f, 0.15f),
              spaceDecay = MacroTarget(0.03f, 0.33f), moodHarmonics = MacroTarget(0.54f, 0.84f),
              moodTimbre = MacroTarget(0.39f, 0.69f),
            ),
            barStrategy = BarStrategy.MUTATE,
          ),
          TrackVoice(
            // DX idx 1 "Mooger Low", round, on EDM; idx 9 "Resonances" on Space. An octave up,
            // so the patches' sub-octave lands at 46-62 Hz where it can be felt.
            engineEdm = OrpheusEngine(
              OrpheusEngineId.DX, volume = 0.56234133f, harmonics = 0.046f, timbre = 0.25f, morph = 0.5f,
              reverbSend = 0.05f, noteRangeLow = 40, noteRangeHigh = 59, glideRate = 0.35f,
              pinMorph = true,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.DX, volume = 0.56234133f, harmonics = 0.29105392f, timbre = 0.55949354f,
              morph = 0.5f, reverbSend = 0.05f, noteRangeLow = 40, noteRangeHigh = 59, glideRate = 0.35f,
              pinTimbre = true, pinMorph = true,
            ),
            role = TrackRole.Melodic(chordFollow = ChordFollow.ROOT_ONLY),
            density = 0.38f,
            envelopeProfile = EnvelopeProfile.MELODIC,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.6f, 1.0f), energyDensity = MacroTarget(0.4f, 0.9f),
              complexitySwing = MacroTarget(0.0f, 0.15f), complexityVariation = MacroTarget(0.0f, 0.3f),
              spaceDecay = MacroTarget(0.19f, 0.49f), moodHarmonics = MacroTarget(0.08f, 0.22f),
              moodTimbre = MacroTarget(0.12f, 0.28f),
            ),
          ),
          TrackVoice(
            // Saw lead in both slots; mood bends the pitch (moodPitch below). EDM is brighter
            // and quicker: shorter LPG, faster vibrato and rise.
            engineEdm = OrpheusEngine(
              OrpheusEngineId.CSAW, volume = 0.15422377f, harmonics = 0.66f, morph = 0.33751908f,
              delaySend = 0.25f, reverbSend = 0.25f, noteRangeLow = 68, noteRangeHigh = 79,
              lpgMode = LpgMode.SUSTAINED, lpgDecay = 0.6f, lpgColour = 0.6f,
              vibratoDepth = 20.0f, vibratoRate = 6.0f, pitchRise = 20.0f, pitchRiseTime = 0.12f,
              doubleDetune = 10.0f, doubleSpread = 0.55f, pinHarmonics = true, pinMorph = true,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.CSAW, volume = 0.15422377f, harmonics = 0.35f, morph = 0.66052705f,
              delaySend = 0.35f, reverbSend = 0.35f, noteRangeLow = 68, noteRangeHigh = 79,
              lpgMode = LpgMode.SUSTAINED, lpgDecay = 0.7f, lpgColour = 0.45f,
              vibratoDepth = 18.0f, vibratoRate = 5.2f, pitchRise = 20.0f, pitchRiseTime = 0.25f,
              doubleDetune = 14.0f, doubleSpread = 0.55f, pinMorph = true,
            ),
            role = TrackRole.Melodic(lickMode = LickMode.Fill),
            density = 0.35f,
            envelopeProfile = EnvelopeProfile.MELODIC,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.5f, 1.0f), energyDensity = MacroTarget(0.4f, 0.9f),
              complexitySwing = MacroTarget(0.0f, 0.15f), complexityVariation = MacroTarget(0.0f, 0.3f),
              spaceDecay = MacroTarget(0.17f, 0.47f), moodHarmonics = MacroTarget(0.25f, 1.0f),
              moodTimbre = MacroTarget(0.35f, 0.65f), moodPitch = MacroTarget(-0.3f, 0.3f),
            ),
            barStrategy = BarStrategy.MUTATE,
          ),
          TrackVoice(
            // DX3 idx 3 "60s organ" on EDM, idx 13 "Soft touch" on Space.
            engineEdm = OrpheusEngine(
              OrpheusEngineId.DX3, volume = 0.15142974f, harmonics = 0.107f, timbre = 0.4f, morph = 0.5f,
              modLfoRate = 0.04f, modLfoDepth = 0.35f, modLfoShape = 0.15f, delaySend = 0.25f,
              reverbSend = 0.45f, noteRangeLow = 49, noteRangeHigh = 67, reverbBrightness = 0.45f,
              glideRate = 0.3f, doubleDetune = 8.0f, doubleSpread = 0.6f,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.DX3, volume = 0.15142974f, harmonics = 0.414f, timbre = 0.21295911f,
              morph = 0.6f, modLfoRate = 0.04f, modLfoDepth = 0.35f, modLfoShape = 0.15f, delaySend = 0.25f,
              reverbSend = 0.5f, noteRangeLow = 49, noteRangeHigh = 67, reverbBrightness = 0.45f,
              glideRate = 0.3f, doubleDetune = 10.0f, doubleSpread = 0.7f, pinTimbre = true, pinMorph = true,
            ),
            role = TrackRole.Chordal(
              comping = ChordComping(arpMode = ArpMode.NEVER, restrikeOnChordChange = true),
            ),
            pan = 0.2f,
            density = 0.2f,
            envelopeProfile = EnvelopeProfile.DRONE,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.5f, 1.0f), energyDensity = MacroTarget(1.0f, 1.0f),
              complexitySwing = MacroTarget(0.0f, 0.15f), complexityVariation = MacroTarget(0.0f, 0.3f),
              spaceDecay = MacroTarget(0.2f, 0.5f), moodHarmonics = MacroTarget(0.3f, 0.7f),
              moodTimbre = MacroTarget(0.3f, 0.55f),
            ),
          ),
          TrackVoice(
            // Wave terrain skank a half octave lower, doubled and detuned for weight.
            engineEdm = OrpheusEngine(
              OrpheusEngineId.TRN, volume = 0.3459155f, harmonics = 0.0f, timbre = 0.4f, morph = 0.75f,
              delaySend = 0.25f, reverbSend = 0.2f, noteRangeLow = 50, noteRangeHigh = 62,
              pinHarmonics = true, pinMorph = true,
              doubleDetune = 9.0f, doubleSpread = 0.6f,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.TRN, volume = 0.3459155f, harmonics = 0.1337f, timbre = 0.6f, delaySend = 0.3f,
              reverbSend = 0.25f, noteRangeLow = 50, noteRangeHigh = 62, pinHarmonics = true,
              pinTimbre = true, pinMorph = true, doubleDetune = 12.0f, doubleSpread = 0.7f,
            ),
            role = TrackRole.Chordal(
              comping = ChordComping(
                style = CompingStyle.REGGAE_SKANK,
                arpMode = ArpMode.NEVER,
                humanization = CompingHumanization(dropProbability = 0.08f),
                fills = CompingFills(everyNBars = 8, skipProbability = 0.35f),
              ),
            ),
            pan = -0.25f,
            density = 0.52f,
            envelopeProfile = EnvelopeProfile.MELODIC,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.5f, 1.0f), energyDensity = MacroTarget(0.4f, 0.9f),
              complexitySwing = MacroTarget(0.0f, 0.15f), complexityVariation = MacroTarget(0.0f, 0.3f),
              spaceDecay = MacroTarget(0.35f, 0.65f), moodHarmonics = MacroTarget(0.0f, 0.28f),
              moodTimbre = MacroTarget(0.25f, 0.55f),
            ),
          ),
          TrackVoice(
            // DX2 idx 16 "Xylophone", plucked, echoing the hook a third up. The patch sounds an
            // octave under the note, so it sits below the keys. Locked to Space.
            engineEdm = OrpheusEngine(
              OrpheusEngineId.DX2, volume = 0.6716163f, harmonics = 0.5055147f, timbre = 0.5f, morph = 0.5f,
              modLfoRate = 0.07f, modLfoDepth = 0.5f, delaySend = 0.65f, reverbSend = 0.75f,
              noteRangeLow = 78, noteRangeHigh = 91, lpgMode = LpgMode.PLUCK, lpgDecay = 0.35f,
              pinHarmonics = true, pinTimbre = true, pinMorph = true,
            ),
            engineSpace = OrpheusEngine(
              OrpheusEngineId.DX2, volume = 0.6716163f, harmonics = 0.5055147f, morph = 0.5f,
              modLfoRate = 0.07f, modLfoDepth = 0.5f, delaySend = 0.65f, reverbSend = 0.75f,
              noteRangeLow = 78, noteRangeHigh = 91, lpgMode = LpgMode.PLUCK, lpgDecay = 0.35f,
              pinHarmonics = true, pinTimbre = true, pinMorph = true,
            ),
            role = TrackRole.Melodic(lickMode = LickMode.Fill, lickDegreeOffset = 2, textureLine = true),
            pan = -0.46f,
            density = 0.12f,
            envelopeProfile = EnvelopeProfile.MELODIC,
            slotLock = SlotLock.SPACE,
            macroMap = TrackMacroMap(
              energyVolume = MacroTarget(0.2f, 0.5f), energyDensity = MacroTarget(0.05f, 0.25f),
              complexitySwing = MacroTarget(0.0f, 0.2f), complexityVariation = MacroTarget(0.0f, 0.25f),
              spaceDecay = MacroTarget(0.37f, 0.67f), moodHarmonics = MacroTarget(0.36f, 0.66f),
              moodTimbre = MacroTarget(0.37f, 0.67f),
            ),
            barStrategy = BarStrategy.INDEPENDENT,
          ),
        ),
        // The verse hook, same as pool 0.
        lick = Lick(
          steps = listOf(
            Rest(0.5f),
            LickStep(4, 0.5f, 0.9f),
            LickStep(4, 0.25f, 0.8f),
            LickStep(2, 0.75f, 0.85f),
            LickStep(0, 1.0f, 0.9f),
            LickStep(1, 0.5f, 0.85f),
            LickStep(3, 0.5f, 0.85f),
            Rest(0.5f),
            LickStep(2, 0.5f, 0.9f),
            LickStep(2, 0.25f, 0.8f),
            LickStep(0, 0.75f, 0.85f),
            LickStep(2, 1.0f, 0.9f),
            LickStep(1, 1.0f, 0.85f),
            Rest(0.5f),
            LickStep(4, 0.5f, 0.9f),
            LickStep(4, 0.25f, 0.8f),
            LickStep(2, 0.75f, 0.85f),
            LickStep(0, 1.0f, 0.9f),
            LickStep(1, 0.5f, 0.85f),
            LickStep(3, 0.5f, 0.85f),
            Rest(0.5f),
            LickStep(2, 0.5f, 0.9f),
            LickStep(2, 0.25f, 0.8f),
            LickStep(3, 0.75f, 0.85f),
            LickStep(2, 0.5f, 0.85f),
            LickStep(1, 0.5f, 0.85f),
            LickStep(0, 1.0f, 0.9f),
          ),
          loopLength = 16,
        ),
        lickMutation = 0.05f,
        lickMutationMode = LickMutationMode.CHORD_TONES,
        lickRotation = LickRotation(
          pool = listOf(
            // Verse hook over the two-bar B..A | G..A verse, played twice; the second ending
            // comes home to B. Degree 0 = B4, all inside the lead's 68-79 range. The sections
            // that play it pin track 4 to FIXED so these notes sound as written.
            Lick(
              steps = listOf(
                // Over B: F# F# D B, then C# E over A.
                Rest(0.5f),
                LickStep(4, 0.5f, 0.9f),
                LickStep(4, 0.25f, 0.8f),
                LickStep(2, 0.75f, 0.85f),
                LickStep(0, 1.0f, 0.9f),
                LickStep(1, 0.5f, 0.85f),
                LickStep(3, 0.5f, 0.85f),
                // Over G: same rhythm, D D B D, then C# over A.
                Rest(0.5f),
                LickStep(2, 0.5f, 0.9f),
                LickStep(2, 0.25f, 0.8f),
                LickStep(0, 0.75f, 0.85f),
                LickStep(2, 1.0f, 0.9f),
                LickStep(1, 1.0f, 0.85f),
                Rest(0.5f),
                LickStep(4, 0.5f, 0.9f),
                LickStep(4, 0.25f, 0.8f),
                LickStep(2, 0.75f, 0.85f),
                LickStep(0, 1.0f, 0.9f),
                LickStep(1, 0.5f, 0.85f),
                LickStep(3, 0.5f, 0.85f),
                Rest(0.5f),
                LickStep(2, 0.5f, 0.9f),
                LickStep(2, 0.25f, 0.8f),
                LickStep(3, 0.75f, 0.85f),
                LickStep(2, 0.5f, 0.85f),
                LickStep(1, 0.5f, 0.85f),
                LickStep(0, 1.0f, 0.9f),
              ),
              loopLength = 16,
            ),
            // Eugene's lick: each bar taps one note three times, jumps to a long note and comes back,
            // then rests through the A. The tapped note climbs B, D, F#; the last bar drops back to
            // B and builds up to an F# over the A into Corvallis. Track 4 is FIXED here, so the notes
            // play as written.
            Lick(
              steps = listOf(
                // Bar 1 over B: B taps, up to F#, back to B.
                Rest(0.5f),
                LickStep(0, 0.25f, 0.75f),
                Rest(0.25f),
                LickStep(0, 0.25f, 0.8f),
                Rest(0.25f),
                LickStep(0, 0.25f, 0.85f),
                Rest(0.25f),
                LickStep(4, 0.75f, 0.9f),
                Rest(0.25f),
                LickStep(0, 0.5f, 1.0f),
                Rest(0.5f),
                // Bar 2 over G: D taps, down to B, back to D.
                LickStep(2, 0.25f, 0.75f),
                Rest(0.25f),
                LickStep(2, 0.25f, 0.8f),
                Rest(0.25f),
                LickStep(2, 0.25f, 0.85f),
                Rest(0.25f),
                LickStep(0, 0.75f, 0.9f),
                Rest(0.25f),
                LickStep(2, 0.5f, 1.0f),
                Rest(1.0f),
                // Bar 3 over B: F# taps, down to D, back to F#.
                LickStep(4, 0.25f, 0.75f),
                Rest(0.25f),
                LickStep(4, 0.25f, 0.8f),
                Rest(0.25f),
                LickStep(4, 0.25f, 0.85f),
                Rest(0.25f),
                LickStep(2, 0.75f, 0.9f),
                Rest(0.25f),
                LickStep(4, 0.5f, 1.0f),
                Rest(1.0f),
                // Bar 4 over G: back down to B. B B D, a breath, then B B and up to F# over the A.
                LickStep(0, 0.25f, 0.8f),
                Rest(0.25f),
                LickStep(0, 0.25f, 0.85f),
                Rest(0.25f),
                LickStep(2, 0.5f, 0.9f),
                Rest(0.5f),
                LickStep(0, 0.25f, 0.85f),
                Rest(0.25f),
                LickStep(0, 0.25f, 0.9f),
                Rest(0.25f),
                LickStep(4, 0.75f, 1.0f),
                Rest(0.25f),
              ),
              loopLength = 16,
            ),
            Lick(
              steps = listOf(
                Rest(1.0f),
                LickStep(7, 0.5f, 0.9f, glideRate = 0.15f),
                LickStep(9, 1.0f, 0.95f),
                LickStep(8, 0.5f, 0.85f),
                LickStep(7, 1.0f, 0.9f),
                LickStep(5, 2.0f, 0.9f, glideRate = 0.2f),
                Rest(1.0f),
                LickStep(4, 0.5f, 0.85f),
                LickStep(5, 0.5f, 0.85f),
                LickStep(6, 0.5f, 0.9f),
                LickStep(7, 2.5f, 0.95f),
                Rest(1.0f),
                LickStep(9, 0.5f, 0.9f),
                LickStep(8, 0.5f, 0.85f),
                LickStep(7, 1.0f, 0.9f),
                LickStep(5, 1.5f, 0.95f, glideRate = 0.25f),
              ),
              loopLength = 16,
            ),
            // Salem solo: B minor pentatonic, degree 0 = B4. Calls up high (bars 1, 3), answers low
            // (bars 2, 4); glides stand in for bends and slides.
            Lick(
              steps = listOf(
                // Bar 1: F# burst, bend E up to F#, land on D.
                Rest(0.5f),
                LickStep(4, 0.25f, 0.85f),
                LickStep(4, 0.25f, 0.8f),
                LickStep(4, 0.5f, 0.9f),
                Rest(0.25f),
                LickStep(4, 0.25f, 0.8f),
                LickStep(3, 0.5f, 0.85f),
                LickStep(4, 0.5f, 0.95f, glideRate = 0.3f),
                LickStep(2, 1.0f, 0.85f),
                // Bar 2: answer around the low B, hold it.
                Rest(0.5f),
                LickStep(0, 0.25f, 0.8f),
                LickStep(2, 0.25f, 0.85f),
                LickStep(0, 0.5f, 0.85f),
                LickStep(2, 0.5f, 0.85f),
                LickStep(0, 2.0f, 0.9f),
                // Bar 3: A burst, slide up to the high B and hold, step down.
                Rest(0.25f),
                LickStep(6, 0.25f, 0.85f),
                LickStep(6, 0.25f, 0.8f),
                LickStep(6, 0.25f, 0.9f),
                LickStep(7, 1.0f, 0.95f, glideRate = 0.35f),
                LickStep(4, 0.5f, 0.85f),
                LickStep(3, 0.5f, 0.85f),
                LickStep(4, 1.0f, 0.9f),
                // Bar 4: run down and slide home to B.
                LickStep(3, 0.5f, 0.85f),
                LickStep(2, 0.25f, 0.8f),
                LickStep(0, 0.25f, 0.8f),
                LickStep(2, 0.5f, 0.85f, glideRate = 0.2f),
                LickStep(3, 0.5f, 0.85f),
                LickStep(0, 1.5f, 0.95f, glideRate = 0.25f),
                Rest(0.5f),
              ),
              loopLength = 16,
            ),
            // Medford intro: Eugene's tap motif without the climb. Every bar taps B, jumps to the
            // chord's fifth or third (F# over B, D over G) and comes back; the last bar ends on a C#
            // over the A. Softer than Eugene: the section's volume and timbre do the building.
            Lick(
              steps = listOf(
                // Bar 1 over B.
                Rest(0.5f),
                LickStep(0, 0.25f, 0.7f),
                Rest(0.25f),
                LickStep(0, 0.25f, 0.75f),
                Rest(0.25f),
                LickStep(0, 0.25f, 0.8f),
                Rest(0.25f),
                LickStep(4, 0.75f, 0.85f),
                Rest(0.25f),
                LickStep(0, 0.5f, 0.9f),
                Rest(0.5f),
                // Bar 2 over G.
                LickStep(0, 0.25f, 0.7f),
                Rest(0.25f),
                LickStep(0, 0.25f, 0.75f),
                Rest(0.25f),
                LickStep(0, 0.25f, 0.8f),
                Rest(0.25f),
                LickStep(2, 0.75f, 0.85f),
                Rest(0.25f),
                LickStep(0, 0.5f, 0.9f),
                Rest(1.0f),
                // Bar 3 over B.
                LickStep(0, 0.25f, 0.7f),
                Rest(0.25f),
                LickStep(0, 0.25f, 0.75f),
                Rest(0.25f),
                LickStep(0, 0.25f, 0.8f),
                Rest(0.25f),
                LickStep(4, 0.75f, 0.85f),
                Rest(0.25f),
                LickStep(0, 0.5f, 0.9f),
                Rest(1.0f),
                // Bar 4 over G, then C# over the A into Grants Pass.
                LickStep(0, 0.25f, 0.7f),
                Rest(0.25f),
                LickStep(0, 0.25f, 0.75f),
                Rest(0.25f),
                LickStep(0, 0.25f, 0.8f),
                Rest(0.25f),
                LickStep(2, 0.75f, 0.85f),
                Rest(0.25f),
                LickStep(0, 0.5f, 0.9f),
                LickStep(1, 0.5f, 0.95f),
                Rest(0.5f),
              ),
              loopLength = 16,
            ),
            // TODO(lick): Corvallis jam seed, the short opening quote. The band jams around it.
            // Placeholder: Eugene's first bar, then a bar of rest. Degrees in B minor from B4:
            // 0 B, 1 C#, 2 D, 3 E, 4 F#, 5 G, 6 A, 7 B5; -1 rests. Chords change every beat:
            // B E B E | G A F# F#. Track 4 is FIXED here; loopLength 8 is two bars.
            Lick(
              steps = listOf(
                Rest(0.5f),
                LickStep(0, 0.25f, 0.75f),
                Rest(0.25f),
                LickStep(0, 0.25f, 0.8f),
                Rest(0.25f),
                LickStep(0, 0.25f, 0.85f),
                Rest(0.25f),
                LickStep(4, 0.75f, 0.9f),
                Rest(0.25f),
                LickStep(0, 0.5f, 1.0f),
                Rest(0.5f),
              ),
              loopLength = 8,
            ),
          ),
        ),
        band = Band(
          members = listOf(
            BandMember(
              name = "Drummer", tracks = listOf(0, 1, 2), alwaysActive = true, loudness = 0.75f,
              creativity = 0.3f,
            ),
            BandMember(name = "Bassist", tracks = listOf(3), loudness = 0.85f, creativity = 0.4f),
            BandMember(name = "Guitarist", tracks = listOf(4), loudness = 0.7f, creativity = 0.6f),
            BandMember(name = "Skank", tracks = listOf(5, 6, 7)),
          ),
          handoffMatrix = listOf(
            0.0f,
            0.3f,
            0.5f,
            0.2f,
            0.2f,
            0.0f,
            0.6f,
            0.2f,
            0.1f,
            0.3f,
            0.0f,
            0.6f,
            0.1f,
            0.4f,
            0.5f,
            0.0f,
          ),
          pullInMatrix = listOf(
            0.0f,
            0.3f,
            0.3f,
            0.1f,
            0.2f,
            0.0f,
            0.4f,
            0.2f,
            0.1f,
            0.4f,
            0.0f,
            0.3f,
            0.1f,
            0.3f,
            0.4f,
            0.0f,
          ),
          barsPerLeadMin = 4,
          barsPerLeadMax = 8,
        ),
        bpm = 91.0f,
        envelopeType = EnvelopeType.BLEND,
        rootNote = RootNote.B,
        scaleType = ScaleType.MINOR,
        genre = GenreProfile(
          swingAmount = 0.08f,
          ghostProbability = 0.15f,
          noteRangeLow = 36,
          noteRangeHigh = 60,
          rhythmDensity = 0.67f,
          progressionStyle = ProgressionStyle.MODAL,
          chordsPerBar = 4,
          chordTransitionMatrix = null,
          // Two-bar verse: B to A on beat 4, G to A on beat 4.
          customProgression = listOf(
            ChordStep(degree = 0),
            ChordStep(degree = 0),
            ChordStep(degree = 0),
            ChordStep(degree = 6),
            ChordStep(degree = 5),
            ChordStep(degree = 5),
            ChordStep(degree = 5),
            ChordStep(degree = 6),
          ),
        ),
        energy = 0.55f,
        complexity = 0.45f,
        space = 0.3583981f,
        mood = 0.37183592f,
        levelDb = 1.5f,
        stepCount = 64,
        // Licks start at B4. Auto would take the octave from the genre range (36-60), and the
        // FIXED sections never fold it up, so the hook played at B2.
        lickOctave = 5,
        tension = TensionProfile(
          innerBars = 8,
          outerBars = 24,
          outerDepth = 0.4f,
          volume = 0.6f,
          timing = 0.06f,
          evolution = EvolutionTension(
            timbreLow = -1.0f, timbreHigh = -1.0f, morphLow = -1.0f, morphHigh = -1.0f, harmonicsLow = -1.0f,
            harmonicsHigh = -1.0f,
          ),
          spurtChance = 0.15f,
        ),
        arrangement = Arrangement(
          sections = listOf(
            Section(
              name = "Medford",
              barsMax = 4,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 1, weight = 1.0f, transitionBars = 1)),
              // Starts thin and swells into Grants Pass: the mix volume ramps up to full, and a RISE
              // tension opens every unpinned timbre from its dark end to its bright end by the last bar.
              macroOverrides = MacroOverrides(energy = 0.7f, complexity = 0.8f, space = 1.1f, volume = 0.55f),
              rampTo = MacroOverrides(volume = 1.0f),
              tensionOverride = TensionProfile(
                innerBars = 4, volume = 0.8f, timing = 0.06f, arc = TensionArc.RISE,
                evolution = EvolutionTension(timbreProbability = 1.0f, attackPoint = 0.0f, releaseSpeed = 0.2f),
              ),
              // The FX echo stays FIXED with the keys wherever they are, so the third holds.
              trackOverrides = mapOf(
                4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),
                7 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),
              ),
              lickIndex = 4,
            ),
            Section(
              name = "Grants Pass",
              barsMax = 6,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 2, weight = 1.0f, transitionBars = 1)),
              trackOverrides = mapOf(
                // The rock beat under the reggae skank: kick on 1 and 3 with a push, snare on 2 and 4.
                0 to TrackSectionOverride(hits = listOf(0, 8, 10, 16, 24, 26, 32, 40, 42, 48, 56, 58)),
                1 to TrackSectionOverride(hits = listOf(4, 12, 20, 28, 36, 44, 52, 60)),
                4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),
                7 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),
              ),
              lickIndex = 0,
            ),
            Section(
              name = "Eugene",
              barsMax = 4,
              barStep = 2,
              // The pre-roll carries the build over the edge, like Orphic Lore's sirens into storm.
              transitions = listOf(SectionTransition(targetIndex = 3, weight = 1.0f, transitionBars = 2)),
              // Reggae winding up into the rock beat: energy and complexity ramp past Corvallis's
              // levels, and a RISE tension starts at 0 each visit and peaks on the last bar.
              macroOverrides = MacroOverrides(energy = 0.8f, complexity = 0.8f),
              rampTo = MacroOverrides(energy = 1.15f, complexity = 1.2f),
              tensionOverride = TensionProfile(innerBars = 4, volume = 0.8f, timing = 0.06f, arc = TensionArc.RISE),
              // bVI and bVII, two bars each, so the last A pulls up into Corvallis's B minor.
              customProgression = listOf(
                ChordStep(degree = 5),
                ChordStep(degree = 6),
              ),
              chordsPerBar = 2,
              // The band trades the lick around; light mutation keeps the walk as written.
              soloMode = SoloMode.LickBuilder(probability = 0.6f, mutationRate = 0.15f),
              trackOverrides = mapOf(
                // One drop: kick and snare together on 3 of every bar, until Corvallis's rock beat.
                0 to TrackSectionOverride(hits = listOf(8, 24, 40, 56)),
                1 to TrackSectionOverride(hits = listOf(8, 24, 40, 56)),
                4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),
                7 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),
              ),
              lickIndex = 1,
            ),
            Section(
              name = "Corvallis",
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 4, weight = 1.0f, transitionBars = 1)),
              macroOverrides = MacroOverrides(energy = 1.15f, complexity = 1.1f),
              customProgression = listOf(
                ChordStep(degree = 0),
                ChordStep(degree = 3),
                ChordStep(degree = 0),
                ChordStep(degree = 3),
                ChordStep(degree = 5),
                ChordStep(degree = 6),
                ChordStep(degree = 4),
                ChordStep(degree = 4),
              ),
              chordsPerBar = 4,
              // The band jams around the seed quote; high influence keeps it close to the quote.
              soloMode = SoloMode.Jam(probability = 0.8f, lickInfluence = 0.7f),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(hits = listOf(0, 8, 10, 16, 24, 26, 32, 40, 42, 48, 56, 58)),
                1 to TrackSectionOverride(hits = listOf(4, 12, 20, 28, 36, 44, 52, 60)),
                4 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),
                // The skank gives way to the rock comp; the pad keeps its own style.
                6 to TrackSectionOverride(compingStyle = CompingStyle.ROCK_DOWNBEATS),
                7 to TrackSectionOverride(volume = 0.25241855f),
              ),
              lickIndex = 5,
            ),
            // Home stretch: Salem and Beaverton jam the two hooks, each hotter than the last.
            Section(
              name = "Salem",
              barsMin = 6,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 5, weight = 1.0f, transitionBars = 1)),
              macroOverrides = MacroOverrides(energy = 1.35f, complexity = 1.5f),
              // Corvallis's hook on Corvallis's chords, with its jam carried straight in.
              customProgression = listOf(
                ChordStep(degree = 0),
                ChordStep(degree = 3),
                ChordStep(degree = 0),
                ChordStep(degree = 3),
                ChordStep(degree = 5),
                ChordStep(degree = 6),
                ChordStep(degree = 4),
                ChordStep(degree = 4),
              ),
              chordsPerBar = 4,
              jamCarry = true,
              soloMode = SoloMode.Jam(probability = 0.95f, lickInfluence = 0.9f),
              trackOverrides = mapOf(
                0 to TrackSectionOverride(hits = listOf(0, 8, 10, 16, 24, 26, 32, 40, 42, 48, 56, 58)),
                1 to TrackSectionOverride(hits = listOf(4, 12, 20, 28, 36, 44, 52, 60)),
                4 to TrackSectionOverride(
                  volume = 0.1927797f, reverbSend = 0.35f, delaySend = 0.35f,
                  chordFollow = ChordFollow.FIXED,
                ),
                6 to TrackSectionOverride(compingStyle = CompingStyle.ROCK_DOWNBEATS),
                7 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),
              ),
              lickIndex = 5,
            ),
            Section(
              name = "Beaverton",
              barsMax = 6,
              barStep = 2,
              transitions = listOf(SectionTransition(targetIndex = 6, weight = 1.0f, transitionBars = 1)),
              // The peak: the same boosts as Orphic Lore's storm, on Grants Pass's hook.
              macroOverrides = MacroOverrides(energy = 1.5f, complexity = 1.8f, space = 0.8f),
              jamCarry = true,
              soloMode = SoloMode.Jam(probability = 1.0f, lickInfluence = 0.9f),
              tensionOverride = TensionProfile(
                innerBars = 8, outerBars = 0, outerDepth = 0f, volume = 0.35f, timing = 0.15f, spurtChance = 0.2f,
              ),
              trackOverrides = mapOf(
                // The kick doubles up and the snare adds a pickup into each loop.
                0 to TrackSectionOverride(
                  hits = listOf(0, 6, 8, 10, 16, 22, 24, 26, 32, 38, 40, 42, 48, 54, 56, 58),
                ),
                1 to TrackSectionOverride(hits = listOf(4, 12, 20, 28, 36, 44, 52, 60, 62)),
                4 to TrackSectionOverride(
                  volume = 0.1927797f, reverbSend = 0.35f, delaySend = 0.35f,
                  chordFollow = ChordFollow.FIXED,
                ),
                6 to TrackSectionOverride(compingStyle = CompingStyle.ROCK_DOWNBEATS),
                7 to TrackSectionOverride(chordFollow = ChordFollow.FIXED),
              ),
              lickIndex = 0,
            ),
            // The end of the road: the band breaks down, slows and drifts out of focus as it fades.
            Section(
              name = "Portland",
              barsMin = 3,
              barsMax = 3,
              // 91 down to 60 BPM, the tempo floor, across the whole section.
              bpmRampBars = 3,
              bpmRampTo = 0.66f,
              // Playing less and blurring more: energy and complexity fall while the space opens.
              rampTo = MacroOverrides(energy = 0.35f, complexity = 0.25f, space = 1.9f),
              // Volume 0: only the note lengths loosen as the tension rises, with no swell.
              tensionOverride = TensionProfile(innerBars = 3, volume = 0f, timing = 0.35f, arc = TensionArc.RISE),
              // The band barely remembers the hook.
              soloMode = SoloMode.Jam(probability = 0.7f, lickInfluence = 0.15f),
              trackOverrides = mapOf(
                // The beat falls apart: a lone kick, and stray snare and hats into the echo.
                0 to TrackSectionOverride(hits = listOf(0, 40)),
                1 to TrackSectionOverride(density = 0.1f, delaySend = 1.0f),
                2 to TrackSectionOverride(density = 0.12f, reverbSend = 0.7f),
                3 to TrackSectionOverride(density = 0.2f, breatheBars = 2, breatheFloor = 0.5f, breatheSmooth = true),
                // The doubles detune and spread wide, so the pitch itself goes soft.
                4 to TrackSectionOverride(
                  doubleDetune = 38f, doubleSpread = 0.9f, reverbSend = 0.7f, delaySend = 0.7f,
                  chordFollow = ChordFollow.FIXED,
                ),
                5 to TrackSectionOverride(doubleDetune = 30f, doubleSpread = 0.9f, reverbSend = 0.8f),
                6 to TrackSectionOverride(
                  density = 0.25f, doubleDetune = 30f, doubleSpread = 0.9f, pan = 0.4f, delaySend = 0.6f,
                ),
                // The FX echo swells in and out every two bars into a long dub tail.
                7 to TrackSectionOverride(
                  reverbSend = 0.9f, delaySend = 0.95f, envelopeProfile = EnvelopeProfile.DRONE,
                  breatheBars = 2, breatheFloor = 0.1f, breatheTimbreSpan = 0.7f, breatheSmooth = true,
                  chordFollow = ChordFollow.FIXED,
                ),
              ),
              delay = SectionDelay(timeA = 0.38f, timeB = 0.57f, feedback = 0.8f),
              lickIndex = 0,
              volumeEnvelope = listOf(
                VolumePoint(bar = 0.0f, level = 1.0f),
                VolumePoint(bar = 1.5f, level = 0.75f),
                VolumePoint(bar = 3.0f, level = 0.0f),
              ),
            ),
          ),
          outroIndex = 6,
          defaultSectionBars = 4,
          // A pass runs about 5 to 7 minutes, so the cap only catches a stuck song.
          lengthSeconds = 240..480,
          // Told once, Medford to Portland, then the next vibe.
          playOnce = true,
        ),
        effects = VibeEffects(
          delayTimeA = 0.188f, delayTimeB = 0.285f, delayFeedback = 0.48f, reverbSize = 0.65f,
          reverbDamping = 0.4f, reverbBrightness = 0.35f,
        ),
        anomalies = listOf(
          WahAnomaly(
            probability = 0.04f,
            voice = WahParams(
              rateDivision = 4.0f, depth = 0.7f, resonanceQ = 3.5f, centerHz = 750.0f, sweepOctaves = 2.2f,
              wet = 0.85f,
            ),
          ),
        ),
        cast = CastProvenance(
          seed = 1943757336281885971L,
          mode = "BED_FIXED",
          styleTags = listOf("reggae"),
          pins = listOf(
            CastPinRecord(track = 1, role = "snare"),
            CastPinRecord(track = 4, role = "lead"),
            CastPinRecord(track = 6, role = "comp"),
          ),
        ),
      )
      }
}
