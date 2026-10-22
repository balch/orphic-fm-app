# Mix Bench design

> **Status:** landed on `main` 2026-10-21 (see `git log --grep='(mix)'`). This is a dated snapshot of the
> design and the decisions behind it; the code is the source of truth. Later changes add a
> "Superseded by" line here rather than editing the text below.

Date: 2026-10-20. Branch: `claude/live-vibes-mixing-review-b39c88`.

## Intent

The Pulsar vibes sound good, but the user never felt they were mixed right. A review of the mix path found two kinds of problem:
- **Engine-wide:** the bus is boosted ×3.3 into a soft clipper and then cut again; the 55 Hz HPF takes bass fundamentals; DIST at 0.30 is always on.
- **Per vibe:** the dry mix is near mono; kick and bass are unbalanced; some tracks are buried in the mid-energy notch.

A headless measurement harness now exists (`scripts/mix/measure.sh`, the `vibe_measure` C++ suite, `OrpheusEngine::pulsar_measure_taps`). It lets an AI measure a vibe and try changes without ears.

The Mix Bench is the human half of the same loop. The user plays a vibe in the real engine, jumps between sections, rides faders, pans, sends and the four macro knobs while watching meters that agree with the harness, then captures the result back into the vibe as a reviewed diff.

## Goals

- **Hear the whole vibe as it ships:** desktop DJ app, real C++ engine, real arrangement and sections.
- **Jump to any section** quickly (see "Section jump").
- **Per-track strips** (8 tracks), each with:
  - a live mini waveform;
  - pan, reverb send and delay send;
  - a fader with a dB readout;
  - a level meter;
  - loudness against the dry mix in LU, the same number the harness reports;
  - M and S buttons, with TRACKS-view semantics: mute beats solo, solos add up.
- **Meter-only strips** for the voices outside the tracks: STORM, STREET, SPEECH.
- **The four macro knobs:** Energy, Complexity, Mood, Space.
- **Bus readout:** LUFS, peak before the clipper, % of samples past the knee, correlation.
- **Whole-song overview waveform** with section markers; clicking it jumps to that section.
- **Capture:** turn the current bench state into vibe values, either for the whole vibe or for the current section only. Show the before/after of every value before anything is written.
- **Same numbers as the harness.** A vibe measured on the bench and by `measure.sh` must agree within measurement noise.

## Non-goals

- **Engine-wide mix changes:** the HPF cutoff, `kPulsarOutputGain`, the DIST default, `texture_energy_curve`, `kOrpheusOutGain`. These are code decisions made separately.
- **Platforms other than JVM desktop.** Android, iOS and WASM are out.
- **Editing licks, patterns, arrangement structure or engines.** The user writes musical content by hand.
- **Saving bench state as user data in the app.** The bench edits vibe source, not preferences.

## Anti-goals (failure even if it works)

- **Bench meters that disagree with the harness.** Two meters that disagree are worse than one.
- **A capture that changes a vibe file without the user seeing the diff first.** The bench writes only capture files; source changes go through Claude and `git diff`.
- **Bench code active in normal builds or normal launches.** Without the flag, nothing listens on a port, the taps stay null, and audio cost is zero.
- **Audible glitches caused by the bench** (dropouts, zipper noise on fader moves).
- **Live edits that silently snap back.** Kotlin re-pushes track volume, sends, morph and envelope on every section change (`applyTrackOverridesForSection`), so naive port writes get overwritten at the next section.

## Decided by the user

- **Layout** (mockup v2 in `.superpowers/brainstorm/`):
  - top: vibe picker, play, loop section, the four knobs, bus readout;
  - section chips, then the overview waveform;
  - strips, with a changes panel on the right offering "Capture into <section> only", "Capture into whole vibe" and "Reset to file".
- **Per-strip pan and sends, and a waveform per track:** all in.
- **Mute/solo** behave like the app's TRACKS view.
- **The four knobs are on the bench and are captured with the faders:** to the vibe default (`Vibe.energy` etc.) for a whole-vibe capture, or to that section's `macroOverrides` for a section capture.
- **The bench lives in the desktop DJ app behind a bench flag.** The page is opened in Claude's Browser pane or any browser.

## Constraints and facts the builder needs

- **Desktop DJ app:** `apps/djapp/desktopApp`, entry point `main.kt`, Metro graph `DjAppGraphDesktop`. Build flags are passed as Gradle properties into `jvmArgs`; `-Pcatalog` → `-Dcatalog` is the pattern to copy, e.g. `-Pbench` → `-Dorpheus.bench=true`. There is no HTTP server in the codebase. The JDK's `com.sun.net.httpserver` needs no dependency; Ktor is in the version catalog as a client only.
- **Taps:** `OrpheusEngine::pulsar_measure_taps` (`orpheus_engine.h`) is a plain struct the audio thread writes each block, when non-null:
  - per-track dry stereo;
  - storm, street and speech sources;
  - the bus before and after the HPF, and before `soft_limit`.
  The harness reads it on the same thread. For the bench, the audio thread must hand off per-block summaries (peak, K-weighted energy, sub/low energy, decimated min/max for waveforms) through a lock-free ring. The UI thread must never read the raw taps.
- **Meter maths:** it currently lives in `liborpheus_dsp/test/test_vibe_measure.cpp`. It should move into a shared header under `liborpheus_dsp/src/` (K-weighting, gated LUFS, sub/low bands, LU against the dry mix) so the suite and the bench run the same code, and the `vibe_measure_meter` sine-tone tests keep guarding it. Loudness on the bench is short-term (3 s window) plus integrated since play.
- **Live controls must not touch the vibe's own ports, because Kotlin overwrites those:**
  - **Fader:** drive `track_N_audition_gain` (already smoothed over 5 ms, range 0-1.5, unity 1.0). A capture then turns the fader dB into vibe values.
  - **Pan and sends:** these ports are re-pushed on section change. The bench keeps an overlay of its edits and re-applies it after every section flip and vibe re-push, the same way the harness re-applies `VIBE_SET`.
  - **Knobs:** the existing macro ports. Remember that sections apply `macroOverrides` as **multipliers** on top of them.
- **Mute/solo:** the existing TRACKS logic (`mixer/TrackTuning.kt`) is the model. It works through audition gain and is never captured.
- **Section jump:** `PulsarFeature`'s `SectionQueue` writes `arrangement_section_request`, which the engine applies at the next section boundary. That can be many bars away, which is too slow for mixing. The bench needs the jump to land within about a bar; the builder decides whether that means an "immediate" flavour of the request (an engine change, with a C++ test) or something else. "Loop section" means: keep re-requesting the current section at each boundary.
- **Overview waveform (my call on how):** a live generative engine cannot know the future, so the overview comes from a headless render made by the existing harness: `scripts/mix/measure.sh` with a waveform/section-timeline output added. It runs out of process (the stmlib random seed is global, so it can't share the live engine's process). The bench is a dev tool and the toolchain is present, so the bench may shell out to `measure.sh` when a vibe is picked and show "rendering…" until it finishes. It caches per vibe and per vibe-file hash, so an unchanged vibe opens at once. The overview is a map, not a sample-accurate timeline: clicking jumps to a section, not to a time. The live playhead is placed by current section plus bar.
- **Fidelity caveats the bench must show, not hide:**
  - SPEECH is silent headless but live on the bench.
  - The overview comes from a Mode One render (name-hash seed), so the live run can differ.

## Capture semantics

The bench writes values the vibe owns, never TRACKS trims. For each control:

| Bench control | Whole-vibe capture | Section capture |
|---|---|---|
| Fader (dB change *d*) | Both engine slots' `OrpheusEngine.volume` × 10^(d/20). Every existing `TrackSectionOverride.volume` for the track is scaled by the same factor, because overrides REPLACE the base volume. | `TrackSectionOverride.volume` for that section = the effective volume heard × 10^(d/20). |
| Pan | `TrackVoice.pan` | Not possible: there is no per-section pan. The UI says so. |
| Reverb / delay send | Both slots' `reverbSend` / `delaySend` | `TrackSectionOverride.reverbSend` / `delaySend` |
| Energy, Complexity, Mood, Space | `Vibe.energy` / `complexity` / `mood` / `space` | That section's `macroOverrides` multiplier = new value / vibe default. A section capture of Space should warn that sends are scaled by Space in Kotlin. |

Values are clamped to their schema ranges. The changes panel lists every field as *scope, track, field: old → new*, with "Reset to file" to discard.

**Clamping (my call).** Live boost is capped by audition gain at +3.5 dB, which is enough for mixing; widening it is out of scope. When a captured volume would pass 1.0, the changes panel flags that row ("clamped: written value is X dB quieter than heard"), writes the clamped value, and suggests the matching cut on the other tracks. The bench never rescales other tracks on its own.

**Section macro capture (my call).** Keep what was heard. In a section with multiplier *m* (default 1), the heard value is knob × *m*. A section capture leaves the vibe default alone and writes *m′* = (knob_new × *m*) / default. If the section has a `rampTo`, both ends are scaled by the same ratio, so the ramp's shape survives.

### Edit layers (decided by the user: whole-vibe plus per-section)

The bench holds two kinds of edit layer:
- **The whole-vibe layer.** It holds *relative* moves: fader dB offset, send ratio, pan (absolute; pan only exists per vibe), knob value.
- **One layer per section.** It holds that section's own edits: fader dB offset against what the section plays now, absolute sends, and the knob as heard.

A scope toggle beside the changes panel ("editing: whole vibe | <section>") picks which layer a move lands in. The default is the whole vibe.

The strips always show the effective value for the section that is playing, so a chorus that is wetter than the verse stays wetter under a whole-vibe send move. When the section changes, the controls swap to that section's effective values. Live, both layers are applied through the overlay (and audition gain for level), so jumping around never loses or flattens anything.

The user can mix the verse, jump to the chorus, mix it, and capture once. Every layer is captured together, into the rows of the table above, with the whole-vibe layer first.

### Who edits the `.kt` file (decided by the user: Claude applies it)

The bench never writes source. "Capture" writes one structured file per capture to `build/mix-bench/captures/<vibe-slug>-<timestamp>.json`, holding the vibe, the file hash at capture, and for each layer: scope, track, field, old value, new value, plus any clamp flags.

Claude applies it to the vibe's `.kt` file by hand-editing the source, so `.copy()` chains and shared helpers are handled with judgment. The user reviews the real `git diff`, and Claude runs `scripts/mix/measure.sh "<vibe>"` to confirm the numbers moved as expected. A small skill ("apply a mix capture") documents that procedure, so any session can do it.

The changes panel is a preview of the capture file, not of source. After applying, the user relaunches the bench (or uses hot reload where it reloads vibe data). The bench then reads the new values as its baseline. A capture whose file hash no longer matches the current vibe file is flagged as stale.

## What "agree with the harness" means (my call)

The bench and the harness share one meter implementation (a C++ header under `src/`), guarded by the `vibe_measure_meter` sine-tone tests. That is the bar. The two paths will not give identical numbers for a vibe, because the live seed differs from Mode One and SPEECH sounds only live. The bench shows "live" next to its integrated LUFS so nobody compares them as equal. No automated cross-path comparison test is planned.

## Relation to the AI harness

The bench writes each capture as a structured file (vibe, scope, field, old, new), e.g. under `build/mix-bench/captures/`. Claude can read it, apply it, and run `scripts/mix/measure.sh "<vibe>"` to confirm the numbers moved as expected. The harness and the bench share the meter code, so a capture's "after" can be checked headless.

## Left to the builder

- HTTP and push transport (JDK HttpServer with SSE, or a WebSocket library). Bind to 127.0.0.1 only, with a random token in the URL, like the brainstorm companion.
- How the page is served (static files from resources), and the front-end stack. Plain JS and canvas is enough.
- Ring buffer design, update rates (meters about 20 Hz, waveforms about 30 fps), and decimation.
- Exactly how the overlay hooks section changes.
- The section-jump mechanism, within the constraint above.
- Test strategy: the C++ meter tests stay; add tests for capture maths (dB → volume, override scaling, macro multipliers and ramps, clamp flags), for the layer model (effective values per section, relative whole-vibe moves preserving section differences), and for overlay re-application across section flips.

## Related deliverable

The user also asked for mixing guidance in the skills. Separate from the bench build, the `vibe-creator` skill gets a short mixing section:
- the gain path and what the numbers mean;
- target ranges taken from the harness (kick vs bass LU, pan spread, low-end ownership, a send budget);
- running `scripts/mix/measure.sh` before and after a vibe change.
The new "apply a mix capture" skill ships with the bench.

## Appendix: rulings made during the build

Decisions taken while executing the plan, each with what it costs if wrong. A `Final:` prefix means it came from the whole-branch review.

- R1 Ruling: T2 `dry_is_sum` asserts DRY energy (ch[11].e_l+e_r) equals e_pre_hpf within 1e-3 relative (the pre-HPF bus is the sum of tracks + sources) instead of recomputing from taps — the tap is cleared after summarising — cost if wrong: a weaker check that misses a channel the bus has but the taps don't.
- R2 Ruling: kBenchSummaryFloats is a literal constexpr in mix_bench.h, with a static_assert against the computed sizeof layout; Kotlin mirrors it in BenchSummaryLayout.kt, and a jvmTest parses the header literal (pulsar_limits.h pattern) — jvmTest can't load the native lib — cost if wrong: the layout check misses a reordered field of equal count.
- R3 Ruling: T5 clampFlagsWhenOverOne expects clampedDb = 2.08 ± 0.01 (the plan's 2.05 is an arithmetic slip) — cost: none.
- R4 Ruling: T5 declares CaptureRow (and its enums) @Serializable from the start; T8 does not re-touch them — cost: none.
- R5 Ruling: MixBenchServer computes baselineHash = sha256(findVibeSource(name)) on each vibe load and keeps it per vibe — cost if wrong: stale detection is off by one vibe load.
- R6 Ruling: T9 exposes the audio engine on DjAppGraph (`audioEngine: AudioEngine`, already bound in AppScope, or whichever binding exists) and the desktop main casts it to MixBenchTap; it fails loudly if the cast fails — cost if wrong: one extra graph accessor.
- R7 Ruling: BenchMeters consumes per-block K-weighted energies from C++ only; its tests feed synthetic summaries with known k_energy (a constant power p = 10^((-20+0.691)/10) per sample gives -20 LUFS) and never K-weight in Kotlin — cost: none; it keeps one meter maths.
- R8 Ruling: "one null check per block" means a constant handful of null checks per block (the taps and mix_bench pointers), no per-sample work while disabled — cost: none.
- Task 2: Ruling: automatic suites must not read untracked build outputs; mix_bench tests drive Pulsar via ports, not the .ports dump — cost if wrong: none
- Task 2: Ruling: reviewer Minor "disable can free during a drain after the 100 ms timeout" upgraded into the fix loop. It is a use-after-free in the same lifetime code as Important #1 — cost if wrong: one extra line in the fix
- Task 3: Ruling: bars_remaining set to 1 (advance_section decrements first) instead of the plan's 0 — the test pins the contract (flip at the next wrap) — cost if wrong: none, tests decide
- Task 5: Ruling: bench layer/capture tests may live in jvmTest (not commonTest) so they can reuse mkMinimalVibe from the jvmTest fixtures — cost if wrong: they run on JVM only, which is fine for pure maths
- Task 5: Ruling: VIBE-scope send capture emits per-slot rows (add CaptureField.REVERB_SEND_SPACE and DELAY_SEND_SPACE, like VOLUME/VOLUME_SPACE). The plan had only single send fields, which would overwrite the space slot's own send (e.g. Velvet Leash marimba .25 edm vs .75 space). SECTION send rows stay single, because TrackSectionOverride applies to both slots — cost if wrong: one more enum value
- Task 5: Ruling: a SECTION-scope fader capture on a track with no section volume override takes the heard level from the slot the section actually plays: edm when the section's effective energy (vibe energy × section energy multiplier, clamped 0..1) is >= 0.5, else space. Plan text said "effective heard" without naming a slot — cost if wrong: a written override off by the slot ratio in hysteresis-band sections
- Task 5: Ruling: revise the active-slot rule to use the energy the bench plays (whole-vibe energy knob if set, else vibe.energy; × section multiplier, or the section's macrosHeard[ENERGY] if set). Reviewer minor #2 upgraded into the fix loop — cost if wrong: none; it only follows the heard energy more closely
- Task 6: Ruling: loop returns only after the next section is observed (one loop of the next section plays first); accepted for v1, revisit by pre-arming the jump at section entry if it bothers the user — cost if wrong: an audible blip of the next section when looping
- Task 6: Ruling: reviewer minors #2 (loadsAtStop records the live count, not the processed one), #3 (a loop persists across stop) and the ⚠️ engine-recreate freeze (re-enable the tap on every vibeLoadCount change; it is idempotent) are upgraded into the fix loop. All three are start/stop lifetime bugs in the same code as Important #1 — cost if wrong: a few lines
- Ruling: merge refs/heads/claude/vibe-mix-fixes-46bdef as soon as Task 6 closes (before Task 7), not only before the final review. The user asked for that branch merged in; doing it earlier lets Tasks 7-10 build on the restaged engine (clipDrive, 35 Hz HPF, section pan via MixOverlay) — cost if wrong: merge conflicts handled now rather than later
- Task 7: Ruling: reviewer minor "inherited VIBE_TAG/VIBE_SET/VIBE_TRIM env changes the cached output" upgraded into the fix loop. It silently poisons the cache, and the fix is one line in the same launcher — cost if wrong: none
- Task 9: Ruling: reviewer minors #1 (/cmd concurrency: a wrong layer or out-of-order drag values), #2 (a background job dies silently and freezes the page or stale loop) and #7-knob (no pointercancel, so the knob ignores state forever) upgraded into the fix loop. They break the core interaction in daily use — cost if wrong: a few lines each
- Final: Ruling: Important #2. Multiply pulsar_vibe_gain into the storm/street/speech blocks. levelDb is a whole-vibe trim and those voices were ear-tuned against the band — cost if wrong: one vibe's weather sits differently than the mix-pass session intended; covered by the ear-test list
- Final: Ruling: Important #4. Move the jump to the next bar boundary (the spec's bound); loop keeps returning after the next section is observed (now <= 1 bar plus the 5 Hz poll) — cost if wrong: more section-state surface in C++
- Final: Ruling: include cheap minors in the one fix wave: 32-byte token + MessageDigest.isEqual; capture() returns stale without re-reading; an every-LIVE-vibe findVibeSource loop test; a comment plus parity test tying BenchMeters.gatedLufs to mix_meter integrated_lufs
- Final: parked — setMixOverlay skips a snap when the bench pan equals the gliding section pan — Ruling: real, cosmetic for a few seconds, deferred
- Final: parked — forced jump under JAM_LAST_BAR with a FILL lick on track 0 never consumes (an existing wrap blind spot); non-16-multiple loops force at step 16 — Ruling: bench-only edge cases, deferred
- Final: parked — brief vibe-load race in pushMacros self-heals; applyVibe applies the outgoing overlay pan before clearForNewVibe snaps it back — Ruling: transient, deferred

## Appendix: known gaps (deferred, none blocking)

Minor findings from the per-task and final reviews, left for later.

- Task 1: mix_meter.h uses M_PI/M_SQRT1_2 (non-standard; MSVC needs _USE_MATH_DEFINES)
- Task 2: stale tap data in the first summary after a mid-block enable
- Task 2: enable is check-then-set (two concurrent enables leak); a compare_exchange would harden it
- Task 2: disable_frees can't observe the free; burns the 100 ms timeout
- Task 2: no threaded stress test (render/toggle/drain) under ASan
- Task 2: ring is ~516 KB per enable (note for any Android port)
- Task 2: vibe_measure and the bench both set pulsar_measure_taps; add a "mutually exclusive" comment
- Task 2: no concurrent enable/disable/drain race test; filter cost on phones unmeasured
- Task 3: jump with an inactive or 1-section arrangement goes unconsumed or stays pending (same as request); comment it
- Task 3: confusing "section 0 -> 0 restarts it" C++ comment
- Task 4: MixOverlayPushTest copies the VM setup (NoopAudioEngine, MemoryPrefs) from the render harness; extract a shared jvmTest fixture if later tasks add VM tests
- Task 4: setMixOverlay not synchronized against a concurrent applyVibe (benign, the next push heals it)
- Task 5: SECTION macro rows silently skipped when the knob <= 0 or the multiplier is 0 (no flagged row)
- Task 5: EffectiveTrack.levelDb is a layer offset, not an absolute level (UI task must know)
- Task 5: section absolute sends are multiplied by the whole-vibe ratio; the UI must store desired/ratio when dragging (KDoc it)
- Task 5: no test asserting capture == overlay for section abs send + vibe ratio
- Task 5: o.volume!! twice; four near-identical unitRow calls
- Task 5: R5's 0.5 slot threshold approximates the engine's 0.4-0.6 hysteresis/random band
- Task 5: no test that macrosHeard[ENERGY] overrides knob x multiplier; wholeVibeEnergyKnobMovesActiveSlot new-value half is clamp-weak
- Task 6: sub-block overshoot dropped (closes at the first summary past sr/10); 512-frame summaries give 427 ms blocks; steady LUFS unaffected
- Task 6: ~20 KB allocated per snapshot; gatedLufs recomputes log10 over the whole block log on each block (grows with session)
- Task 6: ORPHEUS_MIX_BENCH_SUMMARY_FLOATS #define sits between the drain doc comment and its declaration
- Task 6: Task 9 must guarantee stop() runs on teardown
- Task 6: a section-layer macro lands up to ~200 ms after the C++ flip (5 Hz poll); note it on the bench page
- Task 6: jumpTo() ignores running
- Task 6: an engine rebuild (Android route change) runs clearForNewVibe and drops bench edits
- Task 6: an exception in a follower kills the follow until stop/start
- Task 7: failures aren't remembered (a broken vibe re-renders 240 s on each pick)
- Task 7: measure.sh header comment line is over-long
- Task 7: the cache key ignores C++ engine changes (only the vibe file hash)
- Task 7: no real-process test for the timeout/kill path; a timeout kill may take a fresh Gradle daemon with it
- Task 8: stale=false when source not found (consider nullable or KDoc)
- Task 8: the name regex would also match a commented line or copy(name = ...)
- Task 8: no "every LIVE vibe resolves" loop test
- Task 9: token is 16 bytes, not the constraint's literal 32; plain != compare (loopback, negligible)
- Task 9: /cmd body read unbounded
- Task 9: snapshot built before the clients lock (misses one broadcast until the next change)
- Task 9: SSE writes have no timeout (a stalled TCP client pins a daemon thread)
- Task 9: after a relaunch the key changes; the disconnected banner should say to reload from url.txt
- Task 9: R/D double-click is a no-op; VIBE-scope readout can overstate past +3.52 dB; section-scope knob "changed" amber compares against file x mult
- Task 9: no server-level tests for SECTION/VIBE level and send conversions
- Task 9: BenchJson duplicates vibeDefault/multiplier/heardEnergy (internal in features/pulsar); make them public and reuse
- Task 9: double-click reset on fader or pan bypasses the throttle; a queued value can overwrite the reset
- Task 9: overview render coroutine is unguarded (OverviewCache catches internally)
- Task 9: a stale value can flash after a slow command (500 ms release window)
- Task 10: apply skill lists the "before" measurement after the diff step; no note on MacroOverrides.volume vs volumeEnvelope require; Mixing section mixes pre-clip and output peak points
