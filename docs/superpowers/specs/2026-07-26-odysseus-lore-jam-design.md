# Odysseus Lore: Extended Jam Centerpiece

- **Date:** 2026-07-26
- **Status:** Approved in-session by balch
- **Scope:** `OdysseusLoreVibe.kt` arrangement rewrite + two localized C++ seam fixes (Approach B)

## Goal

Turn Odysseus Lore's ~20-40s "jam" section into the centerpiece of the song: a 2-3 minute
staged jam in the classic psychedelic jam-band form, where multiple instruments trade and
build over a harmonic departure, then slam home. The existing identity (lament descent,
twin guitars, walking bass, hard cut back to the slow verse) is preserved.

Decisions locked during brainstorm:

1. **Scale**: jam is the centerpiece, 2-3 minutes. `lengthSeconds` extends to `360..420`.
2. **Harmony**: the jam departs mid-way to a static Dm vamp (`chords(0)`), making the
   verse's descent the homecoming.
3. **Landing**: hard cut from the peak straight into the slow verse (`transitionBars = 0`).
4. **Depth**: Approach B — vibe data plus two engine seam fixes. Approach C (responder
   mechanics, per-section band matrices, per-section lick mutation) is explicitly deferred.

## Engine facts this design depends on (verified 2026-07-26)

- One arrangement "bar" = one track-0 loop wrap = 32 steps = 2 musical bars ≈ 4.7s at
  102 BPM. All `barsMin/Max` below are in arrangement-bars. (Code-derived; confirm at
  runtime before final sizing — see Verification.)
- `SoloMode.*.probability` rolls **once per section entry** (`start_band_solo`). The
  current `0.55` leaves ~45% of jam visits soloist-free.
- Solo state, the LickBuilder live lick, and phrase memory are **fully reset at every
  section boundary**; nothing survives a transition today.
- The handoff crossfade is inert: `kSoloModSlew = 0.5f` snaps every smoothed solo mod to
  target in one per-bar step (max target magnitude is 0.45).
- Chord-follow transposition applies at trigger time only for tracks with
  `chordFollow != FIXED`. All of this vibe's melodic tracks are FIXED, so a per-section
  `customProgression` moves only the Chordal pad (track 5) — no clash with authored licks.
- Track 7 (swirl) is gated by `compute_fx_probability`: audible only when `energy < 0.4`
  or (`complexity > 0.7` and `energy > 0.6`).
- Texture band (tracks 5-7) is notched to 0.05 across energy 0.45-0.55, recovering to
  full by 0.65 (`texture_energy_curve`).
- The soloist boost scales with `BandMember.loudness` (`0.15 + 0.3 x loudness` density,
  `0.1 + 0.2 x loudness` volume); default 0.5 barely lifts a lead.
- Inert/dead params to avoid: `SoloMode.Jam.lickInfluence`, `SoloBehavior` boosts,
  `TrackVoice.duckingProfile` (band-solo path never reads them), `solo_ghost_mod`,
  `solo_reverb_mod`.

## Part 1 — Arrangement (vibe data only)

New section list for `OdysseusLoreVibe.kt` (indices matter; `introIndex = 0`,
`outroIndex = 5`):

| # | Section | bars | Harmony | Solo mode | macroOverrides | Other |
|---|---------|------|---------|-----------|----------------|-------|
| 0 | intro | 2..2 | descent | — | energy 0.7, space 1.2 | unchanged, existing trackOverrides |
| 1 | verse | 2..4 | descent | — | none | -> rise, `transitionBars = 1` |
| 2 | rise | 6..8 | descent | `LickBuilder(0.95, 0.5)` | energy 1.2, complexity 1.15, space 0.9, mood 1.05 | -> vamp, `transitionBars = 2` |
| 3 | vamp | 8..10 | `customProgression = chords(0)` | `LickBuilder(0.95, 0.6)` | energy 1.25, complexity 1.3, space 1.1, mood 1.1 | `jamCarry = true`; -> peak, `transitionBars = 2` |
| 4 | peak | 8..12 | `customProgression = chords(0)` | `Jam(1.0)` | energy 1.5, complexity 1.8, space 0.8, mood 1.15 | `jamCarry = true`; `tensionOverride` (below); -> verse, `transitionBars = 0` (hard cut) |
| 5 | outro | 2..2 | descent | — | energy 0.55, space 1.5 | terminal (`transitions = emptyList()`), armed-only |

All transitions are single-edge weight 1.0. Total jam: 22-30 arrangement-bars ≈ 1.7-2.4
minutes; with `lengthSeconds = 360..420` most plays cycle verse -> jam twice.

Stage rationale (macro numbers are draft positions for balch's ear; the *structure* is
the approved part):

- **rise** — energy 0.55x1.2 = 0.66 crosses the texture-curve knee, so the second guitar
  wakes here (it whispers in the verse). Solo probability 0.95 fixes the silent-jam roll.
  Trading develops the authored lament lick.
- **vamp** — harmony freezes on i (Dm). **The bass keeps the descent, deliberately
  unoverridden**: a `ROOT_ONLY` per-section override was considered and rejected because
  chord-follow transposition applies at trigger time and would flatten a bassist's
  LickBuilder solo to roots mid-vamp. Instead the descent becomes the ostinato floor
  under a frozen sky, and the bass "departs" organically when the Bassist takes a trade
  and LickBuilder mutates the descent itself. Mutation deepens 0.5 -> 0.6.
- **peak** — flips to `SoloMode.Jam`: chord-anchored free improv with the engine's 0.7
  phrase carryover between soloists, anchored to Dm. complexity 0.4x1.8 = 0.72 and
  energy 0.55x1.5 = 0.83 open the swirl gate, so track 7 joins exactly at the climax.
  `tensionOverride = TensionProfile(innerBars = 8, outerBars = 0, outerDepth = 0f,
  volume = 0.35f, timing = 0.15f, spurtChance = 0.2f)` — spurts (lick mutation x3 for
  ~4 bars, then reeled back) fire often during the boil; innerBars stays 8 so the
  sawtooth peak (0.875) still crosses the 0.85 auto-spurt threshold. outerBars = 0 in a
  single-visit section is meaningless, so disabled.
- **The hard cut** — the peak's biggest bar slams into the slow verse. The vamp departure
  is what makes the verse's descent read as homecoming.

Band-level data changes (vibe-level, apply in all sections):

- `Guitarist` gets `loudness = 0.85f`, `Bassist` gets `loudness = 0.8f` — leads step
  forward audibly. Matrices and `barsPerLead` (default 2..6 ≈ 9-28s per trade) unchanged.
- `lengthSeconds = 360..420`.

Everything else in the vibe (tracks, licks, wah, effects, vibe-level tension, anomalies)
is unchanged. One comment hygiene item: track 6's comment block hardcodes the old jam
multipliers ("the jam's 1.35x/1.3x only lift them to 0.74 / 0.52") — refresh it to the
new stage numbers during the rewrite so the index/texture-gate explanation stays true. Section-entry anomaly rolls now happen at every stage entry, so the 0.03
wah takeover reaches ~1-(0.97^n) per song across n entries — a free bonus, no change.

## Part 2 — Engine change 1: real handoff crossfade

**File:** `liborpheus_dsp/src/orpheus_unit_pulsar.cpp` (file-scope constant).

`kSoloModSlew: 0.5f -> 0.15f`. `slew_toward` applies once per arrangement-bar to
`solo_volume_mod_current` / `solo_density_mod_current`; since every target magnitude is
<= 0.45, the current value snaps in one step and the documented "~1 bar crossfade" never
happens. At 0.15: a typical duck (-0.18) breathes in over ~2 arrangement-bars, the
largest soloist boost (+0.45) over ~3. Faster vibes have shorter bars, so it stays
snappier there.

- **Blast radius:** every vibe with band solos — deliberate. This is the design's only
  global-feel change. Gate: DogHouse A/B.
- **Tests** (`test_pulsar_solos.cpp`): after a forced handoff, smoothed mods sit strictly
  between start and target after one bar (today they equal target — that assertion
  currently passing is the bug); target reached within ceil(0.45/0.15)+1 = 4 bars.

## Part 3 — Engine change 2: `Section.jamCarry`

New opt-in `Boolean` on `Section` (default `false`): the jam survives this section's
entry seam.

**Semantics on entering section S with `jamCarry = true`:**

- If a band solo is in flight from the previous section AND `S.soloMode != NONE`:
  - **Skip**: the probability re-roll, `select_initial_lead`, the live-lick re-seed, and
    the phrase/lead reset. Preserved across the seam: current lead, member roles,
    `bars_since_lead`, `member_bars_remaining`, `live_lick_*` buffers + the
    `live_lick_base_degrees` drift anchor, `last_phrase` + `phrase_cursor`,
    `solo_lick_octave`.
  - **Still apply** S's own solo params (mode, mutation rate) — a carried jam continues
    under the new section's rules. This is how vamp deepens mutation and peak flips
    LickBuilder -> Jam without dropping the soloist.
- If no solo is in flight (previous roll failed, or previous section had no solo mode):
  normal `start_band_solo` with S's params (fresh roll).
- If `S.soloMode == NONE`: normal `clear_band_solo`. The flag gates nothing else —
  progression restart, tension override, comping/chordFollow overrides, anomaly rolls,
  lick-rotation resolve, and viz reset all still run on every entry.

**Plumbing:**

- Kotlin: `Section.jamCarry: Boolean = false` in `Arrangement.kt`; marshal in
  `PulsarViewModel.pushArrangement` next to the existing solo slots (solo fields occupy
  SectionParam slots 10-14; jamCarry takes the next free slot — verify exact index at
  implementation).
- C++: field on `SectionParam` (`orpheus_unit_pulsar.h`), unpack beside the solo params,
  gate the solo-reset block in the section-changed handler in `orpheus_unit_pulsar.cpp`
  (the `start_band_solo`/`clear_band_solo`/live-lick-reseed block).
- Vibe: `jamCarry = true` on **vamp** and **peak** only.

**Behavior guarantees:** default `false` reproduces today's behavior byte-for-byte;
opt-in only. A carry across a LickBuilder -> Jam flip keeps the lead but starts the Jam
generator's melody fresh (only Jam mode records phrases) — accepted; continuity there is
the soloist, the skipped re-roll, and the smoothed dynamics.

**Tests** (`test_pulsar_sections.cpp` / `test_pulsar_solos.cpp`, seeds pinned — both
`pulsar_seed` and `stmlib::Random::Seed`):

1. Carry preserves lead + mutated live lick across the boundary.
2. A non-carry section still fully resets (pins existing behavior).
3. Carry with no solo in flight performs a fresh probability roll/start.
4. Carry into a `soloMode == NONE` section clears solo state.

## Verification

1. C++: build + run `orpheus_dsp_test` pulsar suites (new tests above; existing suites
   green; known flaky: pulsar 11/12 wall-clock seed).
2. Kotlin: `./gradlew :features:pulsar:compileKotlinJvm` + existing arrangement
   marshalling tests.
3. Runtime bar-tick sanity: stopwatch the intro (2 arrangement-bars should be ~9.4s at
   102 BPM) before final stage sizing; adjust barsMin/Max if the derived unit is wrong.
4. Audition via DJ app WIP harness: `./gradlew :apps:djapp:desktopApp:run -Pcatalog=wip`
   (orpheus desktopApp hides WIP vibes; do not add -D forwarding — prior decision).
   Ear checklist: guitar 2 wakes in the rise; vamp freeze lands and the descent-as-
   ostinato works; trades breathe instead of snapping; swirl joins at the peak; the hard
   cut still slams; the song fits two jam cycles.
5. A/B gates: DogHouseVibe (benchmark; feels the slew change), FireSky (bass-channel
   canary), Odysseus Lore before/after.

## Risks

- **Slew change is global.** Mitigation: single constant, DogHouse A/B, trivially
  revertible/tunable.
- **Bar-unit assumption wrong for 64-step vibes.** Mitigation: verification step 3
  before final sizing; only barsMin/Max need retuning if so.
- **Jam mode audibility depends on member eligibility.** JAM leads require a MELODIC
  track; Bassist/Guitarist/Haze qualify, Drummer excluded — as intended. Haze leading
  produces granular-swirl improv; if that reads as mush, drop its handoff weights in a
  tuning pass (data-only).
- **Deja-vu reset at peak complexity (0.72) fires every ~8-9 bars**, wiping backing
  pattern drift mid-peak. Accepted: reads as the band tightening up; live lick and solo
  state are unaffected.

## Out of scope (deferred to a future round)

- Backing tracks answering the soloist's phrases; per-section band matrices;
  per-section lick mutation / per-bar Fill re-render (Approach C).
- `kSoloModSlew` as a vibe-tunable field.
- Any change to `models/` beyond `Section.jamCarry`; any change to other vibes.
