# Odysseus Lore Triplets Ear Test

Branch `claude/pluck-repeat-grids`, run from its worktree. DJ app launched with
`-Pcatalog=wip -Pedition=ai` (Odysseus Lore is a WIP vibe). Ear tests are user run. Items are
grouped by how to treat them: **Verify** (should already work; a failure is a bug), **Judge** (a
call only your ears can make), **Expect** (known behaviour, flagged so it is not mistaken for a
fault).

## What changed

- `TrackSectionOverride.lpgMode` lets a section swap one track's vactrol LPG mode for that section
  only. The track's own mode comes back on exit.
- Odysseus Lore's second guitar (track 6, WSH, a third above the lead) switches to
  `PLUCK_REPEAT_TRIPLET` in rise, vamp and peak. The intro (guitar out), verse and outro keep
  today's sustained tone.
- Fix: the triplet grid now always picks the third that lands 2/3 of a 16th after a note starting
  on the "&". It used to come and go with sample rounding; five of six tested tempos dropped some.

## Where the triplets land

The second guitar reads the lead's wah phrase a third up. Before `lickMutation` 0.45 and the
call/response answer reshape it, one pass of the phrase gets:

| Note | Triplet picks |
|---|---|
| 8th on the beat | one, a third of the way into the beat |
| 8th on the "&" | one, 2/3 of a 16th later |
| the hanging top note (1 beat) | two, a third and two thirds into its beat |
| the falling note (1.5 beats from the "&") | four: 2/3 of a 16th in, the next downbeat, then a third and two thirds |

About 10 triplet picks on top of the phrase's 7 note-ons.

## Verify

- Verify: the verse sounds as it does on `main`. The second guitar sustains, no picks.
- Verify: at the rise the second guitar switches to triplet picks on its held notes, and keeps them
  through vamp and peak.
- Verify: the hard cut from the peak back to the verse lands clean, the guitar sustained again.
- Verify: the lead (track 4) and the bass (track 3) are unchanged everywhere.
- Verify: Fire Sky still upticks on the "e" and "a". Dog House unchanged.

## Judge

- Judge: do triplets under the lead's quarter-note wah read as a second player's colour, or clutter?
- Judge: the plucked tone. Track 6 had no vactrol before; in the jam it rings at the default decay
  0.5 and colour 0.5.
- Judge: all three jam sections, or only vamp and peak?

## Expect

- Expect: the switch lands on the section boundary. The phrase ends in a 3-beat rest, so the guitar
  usually changes over in silence.
- Expect: a Guitarist solo (LickBuilder in rise and vamp, Jam in peak) plays through the same
  triplet grid on track 6.
- Expect: a third right after a note starting on the "e" is skipped, because it would flam.

## Dials (`features/pulsar/.../vibes/OdysseusLoreVibe.kt`)

| Where | Dial | Value |
|---|---|---|
| track 6 | `lpgMode` | unset (WSH: no vactrol) |
| rise, vamp, peak | `trackOverrides[6].lpgMode` | `PLUCK_REPEAT_TRIPLET` |
| track 6 | `lpgDecay` / `lpgColour` | 0.5 / 0.5 (defaults, only heard in the jam) |

The section override lives in `liborpheus_dsp/src/orpheus_unit_pulsar.cpp`
(`active_track_lpg_mode`, applied at load and at every section seam). Tests:
`test_section_lpg_mode_override_applies_for_its_section_only` in `test_pulsar_sections.cpp` and the
lpgMode push test in `PulsarSectionProgressionPushTest`.
