# Fire Sky Re-pick Ear Test

Branch `claude/firesky-chorus-lick-character-e3071e` on main `adfc91e67`. DJ app, Fire Sky
(every Fire Sky variant shares the same tracks, so Fire Sky .5f too). A/B against the 2.0.5
build with Mode One on in both. Ear tests are user run. Items are grouped by how to treat them:
**Verify** (a fix that should already work; a failure is a bug), **Judge** (a call only your
ears can make), **Expect** (known behaviour, flagged so it is not mistaken for a fault).

## Setup

- From the worktree: `./gradlew :apps:djapp:desktopApp:buildDesktopNative` first (the packaged
  dylib lags one build otherwise), then launch the DJ app detached with `nohup`.
- Mode One: long-press the COMPLEXITY label in the Pulsar panel until it pulses. Same on the
  2.0.5 side (`release/2.0.5.1` on the phone, or a `main` checkout for the "before").
- Under Mode One both builds play the same seed, so the notes and timing match. Only the
  articulation of held notes should differ between `main` and this branch.

## What changed

2.0.5 re-picked every held lick note on every hold step by accident: the gate timer was
decremented once per block and underran between hold steps, so the voice saw a gate drop and
a rising edge on nearly every 16th, and a PLUCK vactrol bloomed again each time. That was the
double-picked riff. `7819cbd05` fixed the underrun (it also re-attacked Tides envelopes and
turned the Kicking Bubblegum bass's two notes into fourteen), and every tuning pass since tried
to get the character back with the decay knob alone, which cannot recreate discrete picks.

This branch adds `LpgMode.PLUCK_REPEAT`: PLUCK whose hold steps raise the same note-on the
trigger path does, so the vactrol blooms on every step of a held note. Fire Sky's lead (track 4)
and riff double (track 6) opt in. No other vibe changes. Sequencing is untouched: the re-pick
keeps the head's pitch and velocity and lands on the boundary sample.

## Verify

- Verify: chorus. Every held riff note is double-picked: the 8th-note riff notes arrive as two
  16th picks, the 1-beat notes as four, on the lead and the double together (they are in unison
  there). Against 2.0.5 the off-beat upticks are back.
- Verify: intro and build (half-time). The bare riff re-picks too, at the slow tempo. 2.0.5 did.
- Verify: pitches and timing are identical to `main` under Mode One. If a note moves, that is a
  bug in this change, not a seed difference.
- Verify: the bass (track 3, plain PLUCK) still picks once per note. No double-picking under the
  root pulse.
- Verify: Dog House, unchanged. Nothing there opts in.

## Judge

- Judge: the ring between 16ths. The lead keeps decay 0.6 and colour 0.7 (2.0.5 had 0.50 and
  0.55), the double keeps 0.40 (same as 2.0.5). Those values were tuned to fake the pulse with
  one bloom per note; with real re-picks the decay may want to go back toward 0.5.
- Judge: the lead's level. Each re-pick re-opens the vactrol, so the lead's average level rises
  against `main`. Its EDM volume (0.58) was set against 2.0.5 with one bloom per note.
- Judge: the verse. 2.0.5 re-picked the verse as well, and this branch does the same. If the
  verse should stay single-picked while the chorus double-picks, that is a section override the
  schema does not have yet.
- Judge: low-energy sections (intro, build, breakdown, all at energy 0.45). Below energy 0.5 the
  BLEND envelope is Tides, and a re-pick re-attacks it. Does the slow riff sound choppy?

## Expect

- Expect: re-picks land on the exact 16th boundary. 2.0.5's landed up to a block (10.7 ms) early
  and only when the block alignment let the timer underrun, so the two builds are not
  sample-identical. Same figure, tighter.
- Expect: every hold step re-picks now. 2.0.5 occasionally skipped one when the timer survived
  the boundary, so this side is a little more even than the reference.
- Expect: a re-pick carries the head's velocity. No accent or velocity change on the off-beats.
- Expect: the C++ suite prints `FAIL: Plaits VOICE_FM produced no timbral change vs dry`; that is
  pre-existing, and the suite ends SUCCESS.

## Dials (`features/pulsar/.../vibes/FireSkyVibe.kt`)

| Track | Dial | Value | 2.0.5 |
|---|---|---|---|
| 4 lead (WSH) | lpgMode | PLUCK_REPEAT | PLUCK, re-picked by accident |
| 4 lead | lpgDecay / lpgColour | 0.6 / 0.7 | 0.50 / 0.55 |
| 4 lead | volume EDM / space | 0.58 / 0.78 | 0.78 |
| 6 double (WSH) | lpgMode | PLUCK_REPEAT | PLUCK, re-picked by accident |
| 6 double | lpgDecay | 0.40 | 0.40 |

The mode itself lives in `liborpheus_dsp/src/orpheus_unit_pulsar.cpp` (hold path, sets
`pending_retrig` on the boundary sample) and `orpheus_voice.h` (`LPG_PLUCK_REPEAT = 4`, treated
as PLUCK by the vactrol). Test: `test_pulsar_lpg_pluck_repeat_repicks_every_hold_step` in
`test_pulsar_osc.cpp`.
