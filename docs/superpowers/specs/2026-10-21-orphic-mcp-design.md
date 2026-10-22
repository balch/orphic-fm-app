# Orphic MCP: live mixing and control for Claude Code, the in-app agent and MIDI

> **Status:** phase 1 (desktop MCP core) built on branch claude/orphic-mcp, 2026-10-21; phases 2-6 not built.
> This is a dated snapshot of the design and its decisions; the code is the source of truth. Later changes
> add a "Superseded by" line rather than editing the text.

Date: 2026-10-21. Builds on the Mix Bench (spec `2026-10-20-mix-bench-design.md`, branch `claude/live-vibes-mixing-review-b39c88`).

## Intent

The Mix Bench proved the loop: meter a vibe live, move faders, capture the result into the vibe file through Claude and `git diff`. But it lives in a desktop-only web page behind a launch flag. The user wants:

- Claude Code to work on the running app as a live mixing partner, through typed tools rather than a web page.
- The in-app agent (AI edition) to have the same powers.
- External control from hardware (MIDI) over the same things.
- Claude to hear what it is doing: raw audio of the mix and of each Pulsar track, not just meter numbers.
- A good in-app mixer UI with live waveforms, like the web bench.

## Goals

1. **An MCP server inside the AI edition of Orphic DJ.**
   - It runs whenever the AI edition runs, on desktop (JVM) and on the phone (Android).
   - It listens on loopback only, with a token. Claude Code connects to it; on the phone that goes through `adb forward`.
   - There is no on/off setting. The user's position: "if we release it, there is no reason to turn it off".
2. **Mix tools**, with the same semantics as the bench controller:
   - read the mix (layers, meters, current section and vibe);
   - set a level, pan, send or macro, in vibe or section scope;
   - mute and solo;
   - jump to a section and loop it;
   - capture.
3. **Port tools** over `PortRegistry`: list ports with filters, read a port, write a port. Writes go through `SynthController.setPluginControl`, so the app's UI stays in sync. Results say whether a change will be captured: mix-tool changes are captured, raw port writes are not.
4. **MIDI tools:** list devices and send CC, note or clock to external gear, through the existing `core:midi` and ktmidi layer.
5. **Raw audio for Claude, in two forms:**
   - **Stem capture:** record N seconds of the final output plus the Pulsar dry stems (8 tracks + STORM, STREET and SPEECH sources) and the pre-limit bus, at full sample rate, as WAV files. Claude analyses them itself (Python, numpy) on desktop. On the phone, the files are pulled with adb, as Record takes are.
   - **In-app analysis tools** for the in-app agent, which can't run Python: spectrum (FFT bands or third octaves), RMS/peak envelopes, onset and transient density, low-end share, and stereo correlation, for the mix and per stem, over the last N seconds.
6. **In-app agent tools:** the same operations exposed as koog `ToolProvider`s, contributed from the AI module. The existing vibe tools (`VibeGuide`, `VibeSchema`) learn the mix fields:
   - `Vibe.levelDb`, `TrackVoice.pan`, `TrackSectionOverride.pan`, sends, `reverbBrightness`;
   - the mixing targets from the `vibe-creator` skill's Mixing section.
7. **In-app mixer panel** (AI edition):
   - strips with live waveform, meter, LU, pan, sends, fader, mute and solo;
   - the four macro knobs;
   - section chips and jump;
   - a scope toggle (whole vibe or section);
   - the changes list, Capture, and Reset.
   It is the bench page's layout, rebuilt in Compose for phone and desktop.
8. **MIDI control:** map incoming CCs and notes onto mix operations and ports, with a learn mode, persisted per device.

## Non-goals

- Changing engine-wide mix constants (HPF, clip drive, notch); that is separate code work.
- Remote network access beyond loopback, or multi-user access.
- iOS and WASM in this design.
- The core edition. It must not contain any of this; `verifyCoreHasNoInternet` and the module split guard it.
- Writing `.kt` source from the app. Captures stay files, applied by Claude through `apply-mix-capture`.

## Anti-goals

- Two MCP or tool definitions that drift. The in-app agent's tools and the MCP tools must come from one definition per operation.
- Audio glitches or CPU drain from metering or capture when nobody is listening.
- A raw port write that silently looks captured.
- Losing what the bench already does well. Its controller semantics, capture maths, tests and meter maths (`mix_meter.h`) are reused, not rewritten.

## Decided by the user

- MCP is part of the AI edition, always on in it.
- Today it runs on the user's phone (and desktop); a public release may come later.
- One set of tools, with three ways in: Claude Code over MCP, the in-app agent, and MIDI.
- Mix tools, plus port tools reaching anything MIDI could control, plus MIDI out.
- Claude gets raw sound (stems) to analyse, especially Pulsar.
- An in-app UI with live waves, like the web bench.

## Constraints and facts

**AI edition wiring**
- AI edition gating:
  - Desktop: `-Pedition=ai` adds `:apps:djapp:ai` (`apps/djapp/desktopApp/build.gradle.kts:7,14`).
  - Android: the `ai` product flavor.
  - `AppVariant(Core/Ai)` goes through the Metro graph factory.
  - Contributions merge in the concrete graphs `DjAppGraphDesktop` and `DjAppGraphAndroid`, so `:apps:djapp:ai` contributions are visible (memory: Metro merge-site rule).
- The in-app agent collects `Set<ToolProvider>` generically (`OrpheusAgentConfig`).
  - The existing tools live in `features/ai/.../tools/` (koog `Tool`, `@LLMDescription` args and results, `@ContributesIntoSet(FeatureScope, binding<ToolProvider>())`).
  - New mix tools are contributed from the AI module.

**Bench pieces to reuse**
- `MixBenchController`, `BenchMeters`, `BenchLayers`/`capture`, `CaptureWriter`, `findVibeSource` (desktop only; a repo checkout).
- The engine taps (`pulsar_measure_taps`), `MixBench` summaries plus the SPSC ring, the section jump, and the `MixOverlay` hook.
- The bench web server and page: `MixBenchServer`, behind `-Pbench`.

**Audio and native**
- The native summary drain exists only in `DesktopDspBridge`. Android needs the same pair (`set_mix_bench`, `drain_mix_bench`) in its JNI bridge (`OboeEngine`/Android DSP bridge).
- Raw stems need a new sample-level path. Today's taps give per-track samples per block, but the bench reduces them to summaries.
  - Add an armable multi-stem SPSC capture ring, following `orpheus_record_ring.h`: allocate once, arm/disarm, never block, count drops.
  - Drain it to disk on a non-audio thread.
- **Size:** 12 stereo stems × 48 kHz × 4 bytes ≈ 4.6 MB/s. Cap a capture (e.g. 30 s, about 140 MB) and stream it to disk.
- **CPU:** metering is per sample on the audio thread (13 channels of K-weighting + band filters), unmeasured on phones. On Android, enable taps and metering only while a client is reading: an MCP meter read, an open mixer panel, or a capture. Auto-disable after idle (e.g. 10 s). Same on desktop.

**MCP server**
- The repo has no MCP dependency yet.
  - Candidate: the official Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk`) with the streamable HTTP transport on a Ktor server, which runs on JVM and Android.
  - Ktor is already in the catalog, client side only.
  - The builder confirms the current SDK version and Android support before committing to it.
- **Loopback and token:** a random token per install, persisted so the Claude Code config stays valid across launches.
- **Token discovery:**
  - Desktop: written to `~/.config/orpheus-dj/mcp.json` (url + token; same folder family as `ai-vibes`).
  - Android: shown in the AI tab's settings, copyable, and written to app files so Claude can read it with `adb shell run-as` on debug builds.
- **Port:** fixed default (e.g. 47801), so `claude mcp add` and `adb forward` are set up once. If it is taken, fall back to the next free port and write the real one to the token file.

**MIDI**
- MIDI in: `core:midi` (ktmidi) exists, with JVM output via CoreMIDI4J (see the macOS gotchas in memory: use `CoreMidiDestination` only).
- Orpheus has MIDI CC routing; check for reuse before building new mapping code.
- Android MIDI in/out needs the Android MIDI API backend (not built yet).

**Captures and stems on the phone**
- They land in app files (like REC takes and `ai-vibes`).
- Claude pulls them with `adb shell run-as <pkg>`, which needs a debuggable build; release builds would need a share/export path later.
- The `grab-ai-vibes` skill is the pattern.

## Decided by the user after the builder check (2026-10-21)

- **Macros move the real knob.**
  - A macro set through the mix tools, by the agent or by Claude, moves the app's visible knob and saved state, exactly like the user's own knob move.
  - This reverses the bench's current "bench macros never persist" behaviour (commit "bench macros play over the app's knobs and never persist").
  - Section-scope macro edits still live in the section layer and are captured as section multipliers. Whole-vibe macro captures compare the knob against the vibe file's default.
- **Capture without a checkout:**
  - Every capture is still a file in app storage.
  - Built-in vibes: Claude pulls the file and applies it with `apply-mix-capture` (git diff review). Stale detection moves to Claude's side: the capture records the vibe as the app had it (its JSON), and Claude compares that against the source.
  - AI-made vibes (the `ai-vibes` JSON archives): Capture also writes the change back into that vibe's JSON in the app, so it improves in place with no Claude step. A backup of the previous JSON is kept next to it.
- **MIDI:** merge the user's `feature/djapp-minimal-midi` branch (CoreMIDI4J output, macOS fixes, MINIMAL accompaniment) onto main before the MIDI phase, then build MIDI control on it.

## Calls made after the builder check (mine; the user can override)

- **Metering and edit ownership are separate.**
  - Metering (taps + summaries) switches on with the first read and off after ~10 s with no reader.
  - Mix edits (layers, mutes, solos, overlay) belong to the controller and stay until Reset, Capture-and-apply, or a vibe change. A client disconnecting, an agent turn ending, or the metering idle timeout never undoes an edit.
  - The controller takes over a track's audition gain only after the first mix edit to that track, not at app start.
- **The TRACKS view coexists, with one owner.** In the AI edition, the existing TRACKS mixer's fader, mute and solo route through the same controller, so it never fights the mix tools. The core edition is unchanged. The Phase 5 mixer panel is the full view; TRACKS stays as the quick one.
- **`audio_analyze` and `audio_capture` record forward.** They arm the stem ring, record the next N seconds (capped at 15 s for analyze and 30 s for capture), then disarm, so nothing records when idle. The tools block for N seconds; MCP progress notifications cover the wait, and koog and MCP timeouts are set above the cap.
- **`midi_devices` and `midi_send` move to the MIDI phase (6)**, not Phase 1. JVM MIDI output and Android MIDI are not on main today: `MidiAccessFactory.jvm.kt` `openOutput` throws, and Android returns `EmptyMidiAccess`. The working output lives on the unmerged `feature/djapp-minimal-midi` branch.

## Tool surface (names are the builder's to refine; semantics are fixed)

**Mix**
- `mix_read`: vibe, section, scope, layers, effective per-track values, and meters (LUFS short and integrated, pre-clip peak, past-knee, correlation, per-channel LU, peak, pan, sub and low). It enables metering on demand.
- `mix_set`: track/macro, field (level dB heard, pan, reverb, delay, macro), value, scope (vibe/section).
- `mix_mute`, `mix_solo`.
- `section_jump`, `section_loop`.
- `mix_capture`: writes a capture file and returns its path, rows and stale flag.
- `mix_reset`.

**Ports**
- `ports_list(filter)`, `port_get(symbol)`, `port_set(symbol, value)`.
- Each result carries `captured: false`, plus a hint naming the mix tool that would capture the change if one exists.

**Audio**
- `audio_capture(seconds, stems)`: writes WAVs and returns their paths plus per-stem stats.
- `audio_analyze(seconds, stems, kinds)`: spectrum, envelope, onsets, low share, correlation, as numbers.

**MIDI**
- Phase 6: `midi_devices`, `midi_send`, `midi_map_list`, `midi_map_set` and `midi_learn`.

**Context**
- `vibe_list`, `vibe_load`, `transport_play/pause`.
- Reuse the vibe tools: `pulsar_read_vibe` etc. are already ToolProviders.

## Phases (each its own plan, landed in order)

1. **Desktop MCP core:**
   - the MCP server in the AI edition, with token discovery;
   - mix tools and port tools over the existing controller and registry;
   - metering on demand;
   - transport and vibe tools.
   - *Done when:* Claude Code connects with `claude mcp add`, reads meters, moves a fader the user hears, and captures.
2. **Raw audio:**
   - the native multi-stem capture ring plus the WAV writer;
   - `audio_capture` and `audio_analyze`.
   - *Done when:* Claude captures 10 s of Orphic Lore stems and analyses them in Python, and `audio_analyze` returns sensible spectra.
3. **Android:**
   - the JNI drain plus the capture ring on Android;
   - the MCP server in the ai flavor;
   - `adb forward` documented in a skill;
   - pulling captures and stems.
   - *Done when:* the same Claude Code session drives the phone build.
4. **In-app agent:**
   - koog adapters over the same operation definitions;
   - vibe tools learn the mix fields and targets.
5. **In-app mixer panel:**
   - the Compose panel in the AI module, with live waves;
   - phone portrait (a compact strip view), landscape, and desktop.
6. **MIDI control:** CC/note mapping onto mix operations and ports, with learn mode and persistence.

## Left to the builder

- The exact module layout, e.g. a `:features:mcp` or `:apps:djapp:ai` sub-package. The constraint: the core edition never depends on it.
- The tool argument shapes and naming polish.
- The analysis algorithms' details (FFT size, band layout).
- The panel layout details, within the bench page's information set.
- How the idle auto-disable timer works.

## Appendix: phase 1 rulings

Decisions taken while building phase 1, one line each. A `Final:` prefix means it came from the whole-branch review.

- R1: StartupRootGuardTest and FeatureStartupGuardTest can't pass in a worktree; they were checked by reading their regex there, and run for real after landing on main.
- R2: macros move the real knob (the user's decision), so the bench tests asserting "bench macros never persist" were rewritten, not kept.
- Task 1: the section follower ignores layers from a vibe the load follower has not cleared yet (added in Task 2); the exit hook Task 1 also called for was dropped by the Task 2 ruling below.
- Task 1: `mix_reset` keeps mutes, solos and whole-vibe macro moves, and says so.
- Task 2: section-scope macros never move the saved knob. While their section plays they are written straight to the engine port; whole-vibe macro moves stay real, saved knob moves.
- Task 2: two cheap fixes went into Task 3: a vibe load tells the feature to drop held section macros, and section sends clamp to 0..1 with a message saying so.
- Task 3: the capture service records each vibe's source hash when it loads, so MCP captures and the bench page share one load-time stale baseline.
- Task 3: four honesty fixes: the archive message claims only what the archive confirms; the AI write-back message names clamped rows; the baseline is keyed by the captured vibe, and an unknown baseline means `stale = null`; the bench page says an AI write-back restarts the song and clears mutes and solos.
- Task 5: a closed TRACKS fader is exact silence, and TRACKS edits always land in vibe scope, whatever scope the agent left set.
- Task 5: its fix round waited for the Task 4 reopen to commit, so two implementers never committed in one worktree at once.
- Final: DNS-rebinding protection needs no work: SDK 0.15.0 enables it by default, an end-to-end check got 403 for a foreign Host, and the bearer token also defeats rebinding.
- Final: one fix wave: `mix_reset` keeps whole-vibe macro moves in the layer so a capture still writes them; this spec is committed; the TRACKS edit consumer survives a thrown edit and runs off Main; the MCP tool set resolves on Main; level, pan, send, mute and solo edits refuse while a vibe load is unfollowed; the skill explains the first-connect baseline; a test proves the AI edition's FeatureGraph merges `McpToolAccess`.

## Appendix: phase 1 known gaps

Minor findings from the per-task and final reviews, left for later. None blocks phase 1.

- Task 1: `readMeters` at 20 Hz cancels and relaunches a watchdog coroutine per call; a deadline-checking loop would do.
- Task 1: a vibe-scope `setMacro` before `start()` isn't replayed by `start()` (moot while DI starts the controller).
- Task 1: after a solo is turned off, all 8 tracks stay owned until a reset or a vibe load.
- Task 1: no test for a vibe-scope macro edit while a section holds that macro; `isMetering` reads false a moment before the tap is off.
- Task 2: the `MixBenchModule` provider resolves `PulsarFeature` inside its AppScope double-check (the cross-graph-under-lock shape); no deadlock today, and FeatureProviderScopeGuardTest misses it.
- Task 2: on platforms with `NoMeterTap`, `mix_read` says meters start on the next read; add a `metersAvailable` flag (Android phase).
- Task 2: mute and solo are toggles; `port_set` gives no hint for `audition_gain`; `MixChannelMeter.subPct`/`lowPct` and `MixMeters.section` lack `@LLMDescription`.
- Task 2: helpers in `MixTools.kt` are used across files (move to a `ToolSupport.kt`); `followKnobs` has a tiny race, and `distinctUntilChanged` drops an unchanged rewrite.
- Task 3: the section override check loops `0 until 8` rather than `tracks.indices`; no test crosses the 0.5 heard-energy slot boundary.
- Task 3: the `apply-mix-capture` skill says `stale` means the source changed "after the bench loaded the vibe"; for an MCP capture it is the app's first load of the vibe.
- Task 3: an in-place AI vibe update (no song restart) needs new `PulsarFeature` API; the restart is accepted for now.
- Task 3: the load follower may record a new vibe under the old name if `vibeLoadCount` bumps before `vibeFlow` (a null baseline, so safe); the bench banner can't show "unknown"; the no-op archive message shows on platforms with no archive.
- Task 4: the tool adapter decodes arguments with `ignoreUnknownKeys = true`, so a misspelled argument is silently ignored; strict decoding would catch it.
- Task 4: `mcp.json` goes stale when no port is free; the nullable enum schema omits null; the SDK logs DEBUG noise per request.
- Task 5: no AI-edition test that a vibe load clears TRACKS state through `clearForNewVibe`; no test holds a section macro or the overlay across `resetLevels`; capturing a closed fader writes volume 0.
- Task 6: the skill could say to ask the user before launching the app; its log goes to `/tmp` rather than `build/`.
- Final: a TRACKS tap during an unfollowed vibe load is refused and dropped, so the fader snaps back; the load clears TRACKS state anyway.
