# Fire Sky Off-16th Ear Test

Branch `claude/pluck-repeat-grids`, run from its worktree. DJ app launched with
`-Pcatalog=wip -Pedition=ai`, native library rebuilt after the grid code landed. Ear tests are user
run. Items are grouped by how to treat them: **Verify** (should already work; a failure is a bug),
**Judge** (a call only your ears can make), **Expect** (known behaviour, flagged so it is not
mistaken for a fault).

## What 2.0.5 actually did

2.0.5 re-armed a held note's gate for exactly one unswung 16th on every step, and the per-block
timer ran out whenever a step lasted longer than that. Swing stretches the beat and the "&" and
shortens the "e" and the "a", so the gate dropped during the beat and "&" steps and the next step
picked the note again. Measured on the real v2.0.5 engine (Fire Sky's climb lick, 84 BPM), share
of held steps that re-picked:

| 2.0.5 engine | beat | e | & | a |
|---|---|---|---|---|
| Chorus | 3% | 86% | 4% | 90% |
| Chorus, swing off | 55% | 50% | 55% | 51% |
| Verse | 12% | 80% | 11% | 85% |

The first audition (`PLUCK_REPEAT_8TH_OFF`, the "&") was the one position 2.0.5 skipped.

## What changed

`PLUCK_REPEAT_16TH_OFF` re-picks a held note on the "e" and the "a" only, every time. A note's first
pick always plays. A branch-only commit puts Fire Sky's lead (track 4) and riff double (track 6) on
it; `main` still has them on `PLUCK_REPEAT` (every 16th).

| Lick | Off-16th upticks per 2-bar loop | 16th grid (main) |
|---|---|---|
| Climb | 16: every "e" and "a". The 8th notes each get one; the ringing b7 and home note pulse on each | 24 |
| Tweak | 15 | 18 |

## Verify

- Verify: chorus. Held riff notes re-pick on the swung "e" and "a", never on the beat or the "&".
- Verify: the double upticks with the lead (same lick a fourth down, pinned with it in the chorus).
- Verify: pitches and timing match `main` under Mode One (long-press the COMPLEXITY label until it
  pulses, in both builds). If a note moves, that is a bug.
- Verify: the bass (track 3, plain PLUCK) still picks once per note.
- Verify: Dog House, unchanged. Nothing there opts in.

## Judge

- Judge: against 2.0.5 under Mode One, is this the uptick? 2.0.5 skipped about 1 in 8 at random and
  sounded each one 0-10.7 ms ahead of the swung step; this plays every one, on the step.
- Judge: the ring between picks. Lead decay 0.50 / colour 0.55 are 2.0.5's values.
- Judge: the lead's level against 2.0.5.

## Expect

- Expect: upticks need the lead's EDM engine (WSH). Below energy 0.4 the lead switches to DX3 and the
  double to ENS, both SUSTAINED, which never re-pick; between 0.4 and 0.6 the engine is re-rolled at
  each loop boundary. At the vibe's default energy 0.54, intro (x0.45), build (x0.65) and breakdown
  (x0.45) land under 0.4.
- Expect: in those Space-engine sections 2.0.5 still dipped and re-opened notes from its gate drops;
  this build holds them.
- Expect: an uptick keeps the note's pitch and velocity. No accent.
- Expect: the "e" and "a" are the swung steps, so the upticks move with the hi-hat swing.

## Dials (`features/pulsar/.../vibes/FireSkyVibe.kt`)

| Track | Dial | Audition | main | 2.0.5 |
|---|---|---|---|---|
| 4 lead (WSH) | lpgMode | PLUCK_REPEAT_16TH_OFF | PLUCK_REPEAT | PLUCK, re-picked by accident |
| 4 lead | lpgDecay / lpgColour | 0.50 / 0.55 | same | same |
| 6 double (WSH) | lpgMode | PLUCK_REPEAT_16TH_OFF | PLUCK_REPEAT | PLUCK, re-picked by accident |
| 6 double | lpgDecay | 0.40 | same | same |

The grids live in `liborpheus_dsp/src/orpheus_unit_pulsar.cpp` (`pluck_repeat_fires`). Test:
`test_pulsar_lpg_pluck_repeat_grids_follow_the_beat` in `test_pulsar_osc.cpp`.
