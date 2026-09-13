# Bass Voice Smoothing Ear Test

Branch `claude/sharp-antonelli-1da8e1`, rebased on main `e5f5d8194`. Orpheus app
only: the DjApp graph and Pulsar's bass track do not route through this voice.
Ear tests are user run. Items are grouped by how to treat them: **Verify** (a fix that should
already work; a failure is a bug), **Judge** (a call only your ears can make), **Expect** (known
behaviour, flagged so it is not mistaken for a fault).

## Setup

- Launch from the worktree: `./gradlew :apps:orpheus:desktopApp:run` (it rebuilds the DSP dylib
  through `buildDesktopNative`). For an A/B, run a checkout on `main` side by side.
- Bass panel: Mix up, clock running. Slides only exist once Mutation is above 0.5, which starts
  rewriting gates; a gate between 0.3 and 0.7 is a slide.

## What changed

The bass voice stepped its slide glide, accent flare and Cutoff/Resonance smoothing once per
host block with per-sample coefficients. Desktop blocks are 512 frames (10.7 ms), so a slide at
the default Envelope was a 47 Hz trill ending five semitones flat, the flare sat as a constant
brighter filter, and Cutoff smoothing did nothing. The voice now renders in 24-sample chunks and
steps each smoother with an exact coefficient, so all three keep their documented times on any
block size.

## Verify

- Verify: slides glide at the default Envelope (0.7). With Mutation above 0.5, slide notes sweep
  into the next pitch. Before: a fast warble that landed flat.
- Verify: slides stay clean with Envelope at 0.8 to 1.0. No shriek, buzz or dropout. Before: the
  pitch flipped between about 21 kHz and near DC every block, then went NaN after a second.
- Verify: stop the clock while slides are playing, wait two seconds, start again. The bass comes
  back normally.
- Verify: accents sweep. Accent at max, a little Resonance: accented notes snap bright and close
  over about 60 ms; plain notes stay darker. Before: the filter sat brighter all the time and
  accents barely moved it.
- Verify: fast Cutoff and Resonance moves with Resonance high have no zipper.
- Verify: LFO Mix up with Resonance high, the cutoff wobble moves continuously. Before: stepped
  about 94 times a second.
- Verify: with Trigger Source and Pitch Source both on Flux (as Funk 49.1 has them), each note
  starts on its own pitch, with no blip of the previous note's pitch at the attack.

## Judge

- Judge: brightness between accents. At the default Accent (0.5) the filter now closes between
  accents; before it sat about 1.6 octaves brighter all the time. Is the default bass too dull
  now? (Cutoff knob; flare depth 0.35)
- Judge: glide length across Envelope, about 50 ms at 0.2 down to 10 ms at 1.0. Rubbery enough at
  the low end, zippy at the top? (80 ms, -2.1, tau factor 0.3)
- Judge: the accent wow length, a 60 ms decay. (flare decay 0.06 s)
- Judge: Funk 49.1. Its bass is T2-triggered with accents on beats 1 and 3 and sat about 2.4
  octaves brighter between accents before. Does it sound right? This also closes the open Funk
  49.1 bass ear test, which was never auditioned after the bass was restored.

## Expect

- Expect: LFO, Flux X and Flux Y modulation of the bass move every 24 samples: same depth, smoother.
- Expect: notes still start on host-block boundaries, up to about 10 ms early on desktop. This
  commit does not change sequencing.
- Expect: parameter changes land up to 16 samples (0.3 ms) late, from the voice's fixed 24-sample
  render.
- Expect: `FAIL: Plaits VOICE_FM produced no timbral change vs dry` in the C++ suite output is
  pre-existing; the suite ends SUCCESS.

## Dials (all in `liborpheus_dsp/src/orpheus_unit_bass.cpp`)

| Dial | Value | What it does |
|---|---|---|
| glide_ms | 80 x exp(-2.1 x Envelope) | slide length; the one-pole tau is 0.3 of it |
| accent_timbre_target | 0.35 x Accent | how far an accent opens the cutoff |
| flare attack / decay | 0.002 s / 0.06 s | how fast an accent opens and closes |
| cutoff smoothing | 0.005 s | Cutoff and Resonance response |
| chunk | 24 samples (`kOrpheusBlockSize`, `orpheus_voice.h`) | update rate of all of the above |
