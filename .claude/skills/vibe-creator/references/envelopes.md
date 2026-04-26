# Envelopes — `EnvelopeType` and `EnvelopeProfile`

A vibe has two layers of envelope control:

1. **`Vibe.envelopeType`** — *global* envelope mode for the whole vibe (AD vs sustain-while-held vs energy-blend).
2. **`TrackVoice.envelopeProfile`** — *per-track* envelope shape (RHYTHM, MELODIC, EFFECT, WILD, DRONE) — also drives solo/ducking behavior at the ViewModel level.

These are independent. A vibe in `EnvelopeType.AD` mode still has tracks with different `EnvelopeProfile` values, and each profile gets a different envelope frequency and ducking response.

## `EnvelopeType` — vibe-global mode

Source: `PulsarVibe.kt:889`, `liborpheus_dsp/src/orpheus_unit_pulsar.cpp:2419-2424`.

| Value | C++ id | Behavior |
|---|---:|---|
| `AD` | 0 | Pure attack-decay envelope. Notes hit hard and fall away regardless of gate length. Best for tight EDM/techno/percussive vibes. |
| `TIDES` | 1 | Tides-style attack-release with sustain while gate held. Notes breathe, can hold indefinitely. Best for ambient/drone/pad-heavy vibes. |
| `BLEND` | 2 | Energy-driven crossfade. **At runtime: `envelope_mode = (energy > 0.5) ? AD : TIDES`.** High energy = punchy AD, low energy = loose TIDES. Best for vibes that span a wide dynamic range. |

The crossover is a hard threshold at `energy = 0.5`, not a smooth blend — at energy 0.49 you get full TIDES, at 0.51 you get full AD. There is no middle ground. If a vibe needs a smoother transition between feels, that has to come from track-level parameters (volume curves, density), not from BLEND mode.

### Picking an `EnvelopeType`

| Reference feel | EnvelopeType | Why |
|---|---|---|
| Industrial / mechanical / techno / 4-on-floor | `AD` | Need every hit to land hard and reset. No sustain artifacts. |
| Ambient / drone / pad-heavy / cinematic | `TIDES` | Notes need to breathe and overlap. Holds carry the bed. |
| Anything that goes from "quiet/spacey" to "punchy/loud" via the energy macro | `BLEND` | User dialing energy up = punchier feel. |
| Beat-driven but with breakdowns / drops in dynamic range | `BLEND` | Sections with low macro energy will slip into TIDES naturally. |

ArmyStompVibe uses `BLEND`: at march-section default (energy 0.75), it's AD; in the `drift` section where energy drops to 0.2 × 0.75 = 0.15, it crosses below 0.5 and shifts to TIDES — which is exactly the "army leaves the ground" feel the vibe wants.

## `EnvelopeProfile` — per-track shape

Source: `PulsarVibe.kt:233`, `PulsarViewModel.kt:1375-1411`, `orpheus_unit_pulsar.cpp:2440-2447`.

Five profiles. Each one influences three things:

1. **Envelope shape** in TIDES mode — base frequency selects fast vs slow envelope cycles.
2. **Solo behavior** — what the track does when the band hands it a solo.
3. **Ducking profile** — what the track does when *another* track is soloing.

### Profile reference

| Profile | Use For | TIDES base freq | Solo behavior | Ducking when others solo |
|---|---|---:|---|---|
| `RHYTHM` | Drums, percussion, anything that needs to hit short | 0.0005 (fastest) | `fillProbability = 0.8`, `densityBoost = 0.4`, RHYTHMIC Markov | Volume −0.2, density −0.5, ghost −0.7, fills −0.9 (steps back hard) |
| `MELODIC` | Bass, lead, keys, anything with pitched motion | 0.00008 (mid) | MELODIC Markov, no boosts | No ducking (defaults — stays in the mix) |
| `EFFECT` | Pads, textures, atmospheric layers | 0.00003 (slowest) | Wider harmonics range (0.1–0.9), EFFECT Markov | Volume −0.4, density −0.6, **reverb +0.15** (gets washier), no simplification |
| `WILD` | Experimental / wildcard tracks that should react to the macros | 0.00006 (mid-slow) | Volume +0.3, evolution intensity ×1.5, WILD Markov | Volume −0.5, density −0.7, fills −0.95 (ducks heavily) |
| `DRONE` | Infinite-sustain ambient pads that hold while the gate is high | (uses MELODIC base — drones are gate-controlled, not envelope-clocked) | MELODIC Markov | No ducking — drones are bedrock and shouldn't dip during solos |

### Picking an `EnvelopeProfile`

Match the profile to what the track is *for*, not just what engine it uses:

| Track's job | Profile | Examples |
|---|---|---|
| Kick / snare / hat / shaker | `RHYTHM` | Drum tracks 0–2 in nearly every vibe |
| Sub bass that pulses | `RHYTHM` (yes, really) | Sometimes a sub-bass stab needs to behave like a kick — short, punchy, ghosted on bar boundaries |
| Bass with melodic motion | `MELODIC` | Riff bass, walking bass, ROOT_ONLY chord bass |
| Comping keys / lead lines | `MELODIC` | Chordal keys, FM/VA leads, squash leads |
| Wash pad / atmospheric strings | `EFFECT` | Pad textures, ambient layers — the things that should crest on solos |
| Granular / particle textures | `EFFECT` | The "stuff happening in the background" |
| Wildcard / experimental track | `WILD` | The 7th/8th track that you want to be unpredictable |
| Infinite-hold drone bed | `DRONE` | Sustained ambient bass under a long-form vibe (DeepSpaceVibe-style) |

### Common mistakes

- **`RHYTHM` on a melodic track** — solo will boost density+fill probability (good for drums, weird for bass). Fixes: use `MELODIC`.
- **`EFFECT` on a lead** — when another track solos, this one will drop volume by 40% and add reverb send. A lead disappearing mid-solo is rarely intentional.
- **`MELODIC` on a pad you wanted to swell during solos** — defaults mean no ducking and no swelling. Use `EFFECT` if you want the pad to gain space when others step out.
- **`DRONE` on anything with attacks** — `DRONE` assumes infinite sustain. A track with `density > 0.3` and `holdProbability < 0.5` doesn't behave drone-y; pick `MELODIC` or `EFFECT` instead.
- **`WILD` everywhere** — WILD ducks aggressively during others' solos and boosts itself when soloing. If half your tracks are WILD, the band becomes a tug-of-war.

### How `envelopeProfile` interacts with `macroMap`

`macroMap` (RHYTHM / MELODIC / EFFECT / WILD) controls how the user's macro knobs (Energy/Complexity/Space/Mood) move *this track's* parameters. It's a different concern from envelope shape, but the names line up because they tend to match the same use cases. **Default is to set them in pairs** — `envelopeProfile = RHYTHM` + `macroMap = TrackMacroMap.RHYTHM`. Mismatch only when you have a reason (e.g. a drum track that should respond to mood like a melodic track).

### Default solo / ducking behavior is overridable per-track

The `defaultSoloBehavior` and `defaultDuckingProfile` only kick in if the track doesn't override. A `TrackVoice` can ship its own `SoloBehavior` and `DuckingProfile` for cases where the profile defaults don't fit (rare — but available). Most vibes never need to override.

## Cheat sheet

```
Vibe wants snap & punch end-to-end?       envelopeType = AD
Vibe wants long sustained pads?           envelopeType = TIDES
Vibe spans both moods?                    envelopeType = BLEND  (split at energy=0.5)

Track is a drum?                          envelopeProfile = RHYTHM
Track is a bass / lead / keys?            envelopeProfile = MELODIC
Track is a pad / texture / wash?          envelopeProfile = EFFECT
Track is a wildcard / unpredictable?      envelopeProfile = WILD
Track is an infinite-hold drone?          envelopeProfile = DRONE
```

## Verification

If you suspect the runtime envelope behavior has changed, the source-of-truth files are:

- `liborpheus_dsp/src/orpheus_engine.h:728` — `pulsar_envelope_mode` atomic.
- `liborpheus_dsp/src/orpheus_unit_pulsar.cpp:2419` — runtime crossover for BLEND.
- `liborpheus_dsp/src/orpheus_unit_pulsar.cpp:2440-2447` — TIDES base frequencies per profile.
- `features/pulsar/src/commonMain/kotlin/.../PulsarViewModel.kt:1375-1411` — solo/ducking defaults per profile.
