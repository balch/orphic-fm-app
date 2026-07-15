# Audio timing audit — "sloppy drums toward the end" (Bell Tolls)

*2026-07-15. Investigation of the report that Pulsar Vibe drums (Bell Tolls
especially) sound sloppy, worsening toward the end of a song, on JVM desktop.*

> **Status: fixes implemented** (same branch, follow-up commit). All items in
> the "Suggested fix order" below are done: percussive probability floor,
> swing pair-sum accounting, sub-block trigger rendering, mutation
> non-accumulation, Bell Tolls dub/outro energy retune, elastic-tempo slew +
> cap, `solo_simplify` wired into the gate, genre `swingAmount` consumed as
> the live swing floor, and doc corrections. Regression tests:
> `liborpheus_dsp/test/test_pulsar_timing.cpp` (suite `pulsar_timing`).

## TL;DR

**The audio infrastructure is not degrading over time.** The JVM output path is
a pull-model miniaudio callback with no FIFO anywhere between the DSP graph and
the DAC — latency creep over a session is structurally impossible, and the
Pulsar sequencer clock is sample-locked to the device. No decaying DSP state
was found either (FTZ/DAZ is set, no allocations/locks on the audio thread,
voices reset cleanly).

The sloppiness is real, though, and comes from three stacked sources:

1. **By design:** Bell Tolls *ends* at very low effective energy, and several
   intentional "humanization" systems scale inversely with energy — most
   audibly a probability gate that randomly drops drum hits, plus pattern
   mutations that accumulate between resets.
2. **Bug:** drum onsets are quantized to the 512-sample audio block (0–10.7 ms
   early, error precesses hit-to-hit) — the sample-accurate boundary offsets
   are computed and then thrown away.
3. **Bug:** the swing implementation slows the *actual* grid below the nominal
   BPM by `swing/4`, so everything referenced to nominal BPM (fixed-ms dub
   delays, beat-synced LFOs, the bass/grids/stutter units) drifts off the drum
   grid linearly over the song.

Dog House (the benchmark vibe) never exposes any of this because its outro
*boosts* energy to 1.0; Bell Tolls' dub/outro multiply it down to ~0.22.

---

## Ranked findings

### 1. Bell Tolls ends low-energy, and low energy = maximum slop (design/tuning)

The song-ending logic (`features/pulsar/.../playback/PulsarSongEnding.kt:243-258`)
ramps ending probability between 3:00–5:00 and pins playback into the outro.
Bell Tolls' **dub** section (`BellTollsVibe.kt:179-199`) and **outro**
(`BellTollsVibe.kt:227-236`) both apply `energy ×0.4, complexity ×0.5,
space ×1.8` as multipliers on base `energy = 0.55` / `space = 0.6`
(`orpheus_unit_pulsar.cpp:1582-1594`), giving **effective energy ≈ 0.22,
space → 1.0** for the last minute of a run. That single change activates:

- **Random hit-dropping.** Every step of every track — drums included, only
  tracks ≥ 5 are exempt — fires probabilistically:
  `base_prob = energy * 0.6 + 0.4` plus a velocity boost
  (`orpheus_unit_pulsar.cpp:2444-2461`). At energy 0.22, accented hits fire
  ~74% of the time and ghost/soft hits ~58%, re-rolled per bar. Bell Tolls'
  kick is a one-drop with `density 0.18` (`BellTollsVibe.kt:321`) — randomly
  deleting a quarter of the signature beat-3 kicks *is* "the drummer got
  sloppy." Dog House's outro boosts energy ×1.5 → clamped 1.0 → 100% fire
  (`DogHouseVibe.kt:212`), which is why it ends tight.
- **Softer, longer drums.** Drum decay (Plaits morph) is driven by the space
  macro (`orpheus_unit_pulsar.cpp:1750-1752`, RHYTHM range 0.2..0.5 in
  `models/MacroTarget.kt:42`); space → 1.0 maxes decay and softens transients.
- **Loud dub delay repeats.** Space ×1.8 multiplies the delay/reverb sends
  (`PulsarViewModel.kt:1383-1393`), which maximally exposes the swing tempo
  error (finding 3) exactly at the end.
- **Envelope flip.** `envelopeType = BLEND` (`BellTollsVibe.kt:251`) switches
  bass/skank/organ from punchy AD to slow-attack Tides envelopes below
  energy 0.5 (`orpheus_unit_pulsar.cpp:3062-3063`; attack scales with space,
  `compute_tides_params`, line ~497). A smeared dub bass against the grid
  reads as a sloppy rhythm section even when onsets are accurate.

### 2. Drum onsets are block-quantized — sample-accurate offsets computed, never used (bug)

The clock loop finds the exact intra-block sample of each step boundary and
stores it in `step_boundary_samples[]`
(`orpheus_unit_pulsar.cpp:1646, 1677`) — but nothing ever reads the array
(grep: two references, both writes). Triggers set whole-block state
(`ts.voice_active = true`, `ts.tides_prev_gate = GATE_FLAG_LOW`,
`orpheus_unit_pulsar.cpp:2486-2490`) and the voice renders once per block
(`~:3008-3021`), so every hit's envelope starts at **sample 0 of the block**,
i.e. 0–10.7 ms *early* (512 frames @ 48 kHz, `DesktopEngine.cpp:56`).

Since a step period is never a multiple of 512 (e.g. ~9,231 samples at Bell
Tolls' 78 BPM; 9231 mod 512 ≈ 15), the error precesses ~15 samples per step
and snaps back 10.67 ms every couple of bars — a constant, cyclic timing
wobble on every drum hit. This is the baseline slop everything else stacks on.
Gate release (`gate_timer -= num_frames`, `:2666-2673`) and arp retriggers
(`:2675-2710`) are quantized the same way.

**Fix direction:** split the block render at `step_boundary_samples[b]`
(render sub-blocks around each boundary) so envelopes trigger at the true
intra-block offset.

### 3. Swing slows the real grid below nominal BPM; everything synced to nominal drifts off (bug)

`orpheus_unit_pulsar.cpp:1665-1680`: even steps use threshold `S`; odd steps
use `S + swing·0.5·S`; the accumulator subtracts the **full** swung threshold.
Correct swing delays the odd onset but keeps a pair at `2S`; here a pair
totals `2S + swing·0.5·S`, so the **actual tempo is slower than nominal by
`swing/4`** (swing comes from `complexity × complexity_swing` of track 0,
line 1667).

Meanwhile the unit publishes the *nominal* BPM to `engine->clock_bpm`
(`:3270`), which the bass unit (`orpheus_unit_bass.cpp:301`), grids
(`orpheus_unit_grids.cpp:270`), master stutter (`orpheus_unit_basic.cpp:260`),
turntable (`orpheus_turntable.cpp:436-439`), and Pulsar's own free-running
beat-synced mod-LFOs (`:2820-2845`) all consume. And the dub delay times are
**fixed seconds**, not tempo-synced (`orpheus_unit_pulsar_delay.cpp:50-64`),
tuned by vibe authors against nominal BPM.

Result: everything starts aligned at vibe load and diverges linearly with
time — echo repeat *n* lands `n ×` (per-repeat error) off the grid. With Bell
Tolls' effective swing (~0.05) the grid runs ~1.2% slow; combined with the
end-of-song space boost (loudest delay repeats), this is the strongest
"tight at the start, sloppy by the end" mechanism. Section complexity
multipliers (0.25→1.4 across Bell Tolls sections) also change swing and hence
the *effective* tempo at every section boundary.

**Fix direction:** make a swung pair total `2S` (e.g. even threshold
`S − swing·0.5·S`), or accumulate against straight `S` and offset only the
odd-step *onset*.

### 4. Pattern mutation accumulates for ~1¾ minutes between resets (tuning, borderline bug)

`mutate_patterns` (`orpheus_unit_pulsar.cpp:204-236`) runs at every pattern
wrap and mutates the stored steps **in place**:

- Ghost notes: inactive steps activate with prob `track_var × 0.08` per loop
  (`:216-224`) and are never removed until reset — a sparse one-drop kit
  monotonically accrues extra low-velocity hits.
- Accent variation: `step.velocity = clamp01(step.velocity + offset)` with
  `offset ∈ ±complexity·0.15` (`:234-236`) — written as jitter, implemented as
  a random walk that compounds every loop.

The déjà-vu reset (`:2319-2345`) restores the authored pattern only every
`max(8, 32·(1−complexity))` loops — at Bell Tolls' complexity ≈ 0.45 that's
~34 bars ≈ **105 s at 78 BPM**. So drums audibly degrade for ~1¾ minutes,
snap clean, and degrade again; in a 3–4.5 min song the dirtiest stretch lands
"toward the end."

**Fix direction:** jitter velocities around the stored base instead of walking
the stored value; skip (or cap) ghost insertion for PERCUSSIVE tracks.

### 5. Elastic tempo drift: currently near-inert due to a slew bug — a latent booby trap

`orpheus_unit_pulsar.cpp:1610-1624`: a random-walk tempo wobble with ceiling
`(1−energy)·15%` — **±11.7% at Bell Tolls' outro energy**. But the slew
coefficient `1 − exp(−1/(samples_per_step·32))` is a per-*sample* time
constant applied once per *block* (`:1620-1621`), i.e. ~512× slower than
intended; actual drift stays well under ±1% over a song. So it contributes
little today — but if anyone "fixes" the coefficient without also revisiting
the `(1−energy)` scaling, low-energy outros would suddenly wobble ±9 BPM.

### 6. Smaller items

- **"Drunk timing" never moves onsets.** The offsets scaled by
  `TensionProfile.timing` (`orpheus_unit_pulsar.cpp:2298-2317`) are applied to
  `gate_timer` only (`:2488-2490`) — note *length*, not placement. The
  `TensionProfile.timing` docstring oversells it; onset humanization does not
  actually exist.
- **`GenreProfile.swingAmount` is dead.** Pushed to `pulsar_genre_swing` and
  loaded (`orpheus_unit_pulsar.cpp:702, 2328`) but never consumed; live swing
  comes solely from track 0's complexity macro. The doc comment
  (`GenreProfile.kt:61`) is wrong.
- **Solo ducking:** the drummer takes `solo_density_mod = −0.15`
  (`pulsar_band_solo.h:212-220`, applied `:2433-2437`) during solos/choruses —
  intentional, but stacks with finding 1. (`solo_simplify` is set but never
  consumed — dead flag.)
- **Default TAPE ending:** vibes without `transitionOut` get a 500 ms master
  tape-stop (`TransitionStyle.kt:48`, `PulsarTransitionRunner.kt:133-143`) —
  an intentional pitch-dive of the whole mix as the literal last thing heard.
- **Section tempo pushes are a 5 Hz wall-clock poll**
  (`SynthEngineMonitor.kt:364-383` → `PulsarViewModel.kt:856-914`): sections
  with `bpmMultiplier`/`bpmRampBars` start ~200–450 ms at the old tempo, then
  lurch; the `rampBpm` accelerando (`PulsarViewModel.kt:952-968`) is
  uncompensated `delay()` stepping. Inert for Bell Tolls (no tempo sections),
  real for vibes that use them.

## Ruled out

- **Output-path latency creep.** JVM desktop is pull-model end to end:
  miniaudio calls `DesktopEngine::process()` from the OS audio thread
  (`DesktopEngine.cpp:7-10, 52-68`; 512 frames @ 48 kHz). No SourceDataLine,
  no ring buffer, no sleep-paced render loop, no queue whose depth can grow.
  Underruns glitch a period and continue from the same sample position — no
  latency step-up. Resampling (if the device isn't 48 kHz) is slaved to device
  consumption — no cumulative clock drift.
- **Sequencer-vs-DAC drift.** The Pulsar clock advances by the same
  `num_frames` the device consumes (`orpheus_unit_pulsar.cpp:1662-1680`) —
  sample-locked by construction; the accumulator subtracts thresholds rather
  than resetting, so no rounding accumulates.
- **JVM/GC effects on drums.** The audio callback never enters the JVM; the
  hot path has no allocations and no locks (all cross-thread params are
  atomics), FTZ/DAZ set per callback (`orpheus_engine.cpp:444-458`).
  Kotlin `delay()` loops exist (DrumBeats' 24-PPQN clock, TidalScheduler) but
  are not on the Vibe drum path.
- **Decaying DSP state.** Drum voices are self-enveloped Plaits models
  re-triggered per hit; drunk offsets clamped; patterns reset periodically;
  voices reset engine + LPG state on switch (`orpheus_voice.h:307-312`).

## Suggested fix order (not yet applied)

1. Exempt PERCUSSIVE tracks from (or floor) the energy fire-probability gate
   (`orpheus_unit_pulsar.cpp:2444`) — biggest audible win at song ends.
2. Fix swing accounting so a swung pair totals `2S` (`:1665-1680`).
3. Consume `step_boundary_samples[]` — sub-block rendering or per-boundary
   envelope start offsets (`:1677` → `:2486`, `:3008`).
4. Make accent variation jitter around the authored velocity; skip ghost
   insertion on drums (`:216-236`).
5. Retune Bell Tolls' dub/outro energy multipliers upward (e.g. ×0.55–0.6),
   the way Dog House ends hot — or make the ending sections a deliberate
   artistic choice with the gate fixed per (1).
6. Cleanups: dead `GenreProfile.swingAmount`, dead `solo_simplify`, misleading
   `TensionProfile.timing` docs, latent elastic-tempo slew bug (`:1620`).
