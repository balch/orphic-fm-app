# Solo Energy Ear Test

Branch `claude/solo-energy-review-54464c` on top of the rustbelt tip plus dead-params.
Ear tests are user run. Items are grouped by how to treat them: **Verify** (a fix that
should already work; a failure is a bug), **Judge** (a call only your ears can make),
**Expect** (known behaviour, flagged so it is not mistaken for a fault).

## Rust Belt, jam section (release target, A/B against the previous build)

- Verify: the twang (track 5) is audible when it takes the lead in the jam, at about chorus
  level, not buried under the notch.
- Verify: when the twang hands off, it fades back over about a bar rather than cutting.
- Judge: the kit thins and plays plain for the first bars of a solo, then ghosts and hats
  return and it gets louder toward the end of the jam. Does the build read as the band
  leaning in? (kKitRide*, kShapeMin/Max, kKitJitter)
- Judge: each soloist's entrance lands instead of fading in. Too abrupt: lower
  kSoloEntranceFraction.
- Judge: under the twang's lead the bass plays fewer notes but keeps slapping on the
  off-beats. Too busy: lower kSupportBassSlapDensity.
- Expect: the shaker (track 7) still sits out at default energy; the FX gate lifts only for a
  melodic lead.
- Expect: chord progressions now evolve across section flips (the anchor/drift fix is on this
  branch from the storm lane, not this lane).
- Expect: Rust Belt never produces a drum solo (Jam mode cannot reach the drum-lead path).

## Filter Funk, interlude (LickBuilder; you need three or more drum leads)

- Verify: after a drum lead ends, the kit's groove comes back exactly; no lingering sparse
  mirror pattern.
- Verify: on a BREAK-style drum lead the melodic lines all but drop while the bass keeps a
  thinned, slappy line; everything snaps back on the downbeat when the drummer hands off.
  Chordal comping (keys, skank) is not part of the break and keeps its normal duck.
- Judge: the drum lead builds. Lick accents fade in over the groove, hats and ghosts climb,
  and the last bar ends in a snare ramp into a kick. Does the climax land on the handoff?
  (kDrumOverlayStart, kDrumHatStart/End, kDrumGhostEnd, kClimaxVelStart, kDrumJitter)
- Judge: a LOCK_IN drum lead still reads as the drummer quoting the riff. On a busy hat the
  groove masks most of the mirror hits.
- Judge: the FX member leading (the grains and wild voice on track 7) is audible now, as a
  colour. Too often or too loud: the FX column of FilterFunkVibe.kt's handoffMatrix (0.05).
- Expect: drum leads are about one handoff in eight and never two in a row.
- Expect: the kit ride under a melodic lead behaves as in Rust Belt.

## Bell Tolls, solo and chorus sections

- Verify: the bass solo stays in E1..A2; no leaps up into thin phase-distortion territory,
  and a leap no longer sweeps into a saw.
- Judge: the steel-slide feel. glideRate is 0.35 on the bass; too smeary, lower it; too dry,
  raise it (BellTollsVibe.kt).
- Judge: under the keys' lead the bass plays less but slaps more (densityReduction 0.40 on
  the bass plus kSupportBassSlapDensity).
- Expect: the "keys solo" is the skank and organ getting louder and denser, not a melodic
  line; both are chordal. The melodica carries the lines.
- Expect: ducked tracks everywhere are quieter than before this branch. volumeReduction went
  live with the velocity fix (46c475ba9); the KDoc now says so.

## DogHouse, A/B (benchmark vibe)

- Judge: before and after, nothing worse. Solos build, the kit rides, no new artifacts.

## Everywhere

- Expect: every band vibe's ducked bass now slaps on the off-beats at kSupportBassSlapDensity.
- Expect: every always-active kit rides the build under a melodic solo instead of holding a
  fixed -0.15 duck and simplify for the whole solo.
- Expect: every soloist enters at half its boost instead of fading in over three bars.
- Expect: `FAIL: Plaits VOICE_FM produced no timbral change vs dry` in the C++ suite output is
  a pre-existing informational line; the suite still ends SUCCESS.

## Dials (all EAR TUNE)

| Dial | Value | File | What it does |
|---|---|---|---|
| kShapeMin / kShapeMax | 0.6 / 1.6 | pulsar_solo_curves.h | per-solo bend of every build: <1 early, >1 late |
| kKitJitter | 0.04 | pulsar_solo_curves.h | per-bar wobble on the kit ride |
| kKitRideVolumeEnd | 0.15 | pulsar_solo_curves.h | kit velocity mod at full build |
| kKitRideDensityStart / End | -0.15 / 0.10 | pulsar_solo_curves.h | kit density cut opening, boost closing |
| kKitRideFillFrom / kKitRideFill | 0.70 / 0.30 | pulsar_solo_curves.h | late-solo fill arming |
| kKitRideSimplifyUntil | 0.50 | pulsar_solo_curves.h | ghosts return past this progress |
| kSupportEase | 0.5 | pulsar_solo_curves.h | how much a support duck eases by full build |
| kDrumJitter | 0.05 | pulsar_solo_curves.h | per-bar wobble on the drum arc |
| kDrumOverlayStart | 0.60 | pulsar_solo_curves.h | lick accents start this loud over the groove |
| kDrumHatStart / End | 0.35 / 0.85 | pulsar_solo_curves.h | hat gap-fill probability across the span |
| kDrumGhostEnd | 0.35 | pulsar_solo_curves.h | snare ghost probability at the end of the span |
| kClimaxVelStart | 0.55 | pulsar_solo_curves.h | snare ramp start on the climax quarter |
| kHandoffFillChance / Depth | 0.6 / 0.45 | pulsar_band_solo.h | the kit's fill on a handoff bar |
| kSoloLiftFull | 0.1 | pulsar_band_solo.h | solo mod at which a lead escapes the notch and FX gate |
| kSoloEntranceFraction | 0.5 | pulsar_band_solo.h | fraction of the boost a soloist starts with |
| kLeadBassSlapDensity / kSupportBassSlapDensity | 0.35 / 0.20 | pulsar_band_solo.h | bass slaps when leading, when ducked |
| kBreakMelodicDuck / kBreakBassDensityDuck | -0.9 / -0.5 | pulsar_band_solo.h | BREAK depths (-1.0 is silence) |
| should_drum_lead prob | 0.12 | pulsar_handoff.h | drum-lead odds per handoff (LickBuilder only) |
| kSoloModSlew | 0.15 | orpheus_unit_pulsar.h | per-bar crossfade of the solo mods |
| Bell Tolls bass glideRate | 0.35 | BellTollsVibe.kt | the slide |
| Bell Tolls bass densityReduction | 0.40 | BellTollsVibe.kt | how much thinner under the keys |
| Filter Funk FX handoff weights | 0.05 | FilterFunkVibe.kt | how often the FX member leads |
