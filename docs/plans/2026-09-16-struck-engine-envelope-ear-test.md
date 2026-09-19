# Struck Engines (STR / MOD) Ear Test

Branch `claude/hopeful-goldberg-37481f` off main `b593ad565`. DJ app, JVM desktop. Passed on
2026-09-16 as a whole ("this sounded good"); no per-item marks were recorded. Ear tests are user run. Items are grouped by how to treat them: **Verify** (a fix that should
already work; a failure is a bug), **Judge** (a call only your ears can make), **Expect** (known
behaviour, flagged so it is not mistaken for a fault).

## Setup

- `./gradlew :apps:djapp:desktopApp:buildDesktopNative` first (the packaged dylib lags one build),
  then `./gradlew :apps:djapp:desktopApp:run -Pcatalog=wip` from the worktree. For an A/B, run
  the main checkout side by side.
- Solo a track from the mixer to hear it alone. The per-track activity dot is a quick check: dark
  means the track made no sound above 0.02.

## What changed

1. Pulsar no longer runs its own envelope (AD attack, TIDES, the DRONE swell, and their release)
   over the Plaits String (STR) and Modal (MOD) engines. Both are struck: a note is one
   excitation, loudest at its onset, and the envelope's attack ate it. They now ring out on their
   own decay (morph), as on hardware Plaits and in the synth voices. This restores the
   behaviour Pulsar had before `4e4d9f035` (2026-04-12).
2. A note-on landing in the last few samples of an audio block was dropped when the note before
   it was still held. On STR or MOD the note made no sound; on other engines the PLUCK bloom went
   missing. It now strikes at the start of the next block, well under a millisecond late.

## Verify

Numbers are the per-note level gained in a replay of each vibe's real port writes, at the vibe's
own energy and at energy 0.3.

- Verify: Lost In Space (LIVE), track 6 STR strings are present: a soft strike every few bars
  under the pad. Before: about 40 dB down per note, effectively silent.
- Verify: Space & Drums (LIVE), track 6 STR strings come forward. Before: about 8 dB down.
- Verify: Dog House (LIVE), track 5 STR hits (+18 dB) and track 7 (+15 dB) are audible. Pull
  Energy below 0.4 to put track 3's bass on its STR Space slot (+12 dB).
- Verify: Bell Tolls (LIVE), track 7's STR slot is audible when it plays (+31 to +37 dB). Track 7
  is the FX slot and only fires at the energy extremes, so pull Energy right down.
- Verify: Velvet Leash (LIVE) track 7 pad strings and Techno Wobble (LIVE) track 4 below Energy
  0.4 come up (about +2 to +7 dB).
- Verify: WIP vibes with the biggest change: Tremolo Tide t6 (+30 dB), Ouroboros Bloom t6 (+36),
  Kaleidoscope Drift t6 (+33), Mellow Haze t6 (+21), Cosmic Techno t7 (+9), Deep Space t5 (+8).
- Verify: Fire Sky (LIVE), Energy below 0.4 puts track 3's bass on its STR Space slot. Every note
  should sound, slightly louder than main (+1 to +3 dB in the Fire Sky .5f replay; the other Fire
  Sky variants could not be measured).

## Judge

- Judge: strings and modal hits now ring past the end of the step at their natural decay instead
  of being chopped by the release. Tremolo Tide t6 is the extreme case: most of what you hear is
  the ring after the note. Does it smear? The alternative that keeps the release measured 2 to
  14 dB weaker per note in the same spots (14 dB on Tremolo Tide, 11 on Dog House t5).
- Judge: tracks that were effectively silent are now in the mix at their authored volumes. Some
  may be too loud or too busy and want a volume trim in the vibe.
- Judge: MOD tracks changed the same way. MOD vibes: Aether Natalis, Blacktop Boogie, Cosmic
  Techno, Deep Space, Dog House, Double Shift, Symphony No. 5 in C Minor, Mod Pioneer, Sixties
  Rebel, Swamp Swagger, Techno Wobble, Tremolo Tide.
- Judge: DogHouse A/B against main, the usual benchmark after a DSP change.

## Expect

- Expect: Kicking Bubblegum's STR answer voice is NOT restored by this (measured +0.3 dB). Its
  silence comes from the vibe: the tension evolution bounds written for the OSC bass
  (timbre 0.20-0.80, morph 0.10-0.38) also drive track 4, and park STR where a strike is a dark,
  tens-of-milliseconds click. Setting `evolutionWeight = 0f` on that track, pinning a brighter
  timbre and a longer morph, and bypassing the LPG is the way back if you want STR there.
- Expect: an STR note with morph at 0.95 or above rings indefinitely. That is the Plaits string's
  infinite-decay zone, no longer cut by a release.
- Expect: STR stays quieter than sustained engines at dark timbre and short morph (raw level
  about 0.01-0.03 RMS against 0.17 for VA). That is the engine: one period of noise per strike.

## Dials

| Dial | Where | What it does |
|---|---|---|
| `self_enveloped` 19-23, 2-4 | `liborpheus_dsp/src/orpheus_unit_pulsar.cpp` (Tides envelope stage) | engines Pulsar's envelope skips |
| `pending_retrigger_` | `liborpheus_dsp/src/orpheus_voice.h` | holds a note-on that fell inside the remainder |
| tests | `liborpheus_dsp/test/test_pulsar_str_lick.cpp` | `pulsar_str_lick` suite |
