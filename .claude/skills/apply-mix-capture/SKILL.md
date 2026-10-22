---
name: apply-mix-capture
description: Use when the user says "apply my mix capture" or "apply the latest capture", or hands you a file from build/mix-bench/captures/ or ~/.config/orpheus-dj/mix-captures/. Turns a Mix Bench capture JSON into hand edits of the vibe's Kotlin source, then measures before and after. Never commits without the user's ok.
---

# Apply a Mix Bench capture

The Mix Bench (desktop DJ app, `./gradlew :apps:djapp:desktopApp:run -Pbench=true`) lets the user mix a vibe by ear. Its URL is printed and written to `build/mix-bench/url.txt`. Each capture is a JSON file named `<slug>-<yyyyMMdd-HHmmss>.json`: in `build/mix-bench/captures/` from the core edition's bench, in `~/.config/orpheus-dj/mix-captures/` from the AI edition (its bench page and the agent's `mix_capture` tool). Your job is to put those numbers into the vibe's Kotlin source.

## 1. Pick and check the file

- Use the file the user named, else the newest of `build/mix-bench/captures/*.json` and `~/.config/orpheus-dj/mix-captures/*.json`.
- Fields: `vibe`, `slug`, `source` (repo-relative path, may be null), `baselineHash`, `currentHash`, `stale` (null when the app can't tell: no source, or no hash from when the vibe loaded), `aiVibe`, `capturedAt`, `rows`, `vibeJson` (the vibe the rows were read from).
- If `aiVibe` is true, the vibe is AI-made and has no Kotlin source. The app writes such a capture into the vibe itself and archives it under `~/.config/orpheus-dj/ai-vibes/`. Tell the user, and point them at the grab-ai-vibes skill to import it.
- If `stale` is true, the source changed after the bench loaded the vibe. Stop and ask the user before editing. Old values in the rows may no longer match the file.
- If `stale` is null (and `source` is set), the app could not tell whether the source changed since load. Say so and ask the user before editing.
- If `source` is null, the bench could not find the vibe file. Search `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes/` for the vibe name (names resolve through `VibeNames.kt`) or ask.
- If `rows` is empty, say so and stop.

## 2. Map each row to a Kotlin field

Each row has `scope` (VIBE or SECTION), `section` (index), `sectionName`, `track` (index into `Vibe.tracks`), `field`, `macro`, `old`, `new`, `clampedDb`.

| scope | field | Kotlin target |
|---|---|---|
| VIBE | VOLUME / REVERB_SEND / DELAY_SEND | `tracks[track].engineEdm` (`OrpheusEngine` `volume`, `reverbSend`, `delaySend`) |
| VIBE | VOLUME_SPACE / REVERB_SEND_SPACE / DELAY_SEND_SPACE | the same fields on `tracks[track].engineSpace` |
| VIBE | PAN | `tracks[track].pan` (`TrackVoice.pan`). Pan is captured at VIBE scope only |
| VIBE | MACRO (`macro` = ENERGY, COMPLEXITY, MOOD, SPACE) | `Vibe.energy`, `.complexity`, `.mood`, `.space` |
| SECTION | VOLUME / REVERB_SEND / DELAY_SEND | `arrangement.sections[section].trackOverrides[track]` (`TrackSectionOverride`). One value covers both edm and space |
| SECTION | MACRO | that section's `macroOverrides` multiplier for the macro. `old` and `new` are the multiplier, not the knob |
| SECTION | MACRO_RAMP | that section's `rampTo` multiplier for the macro |

Check the enum names in `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/bench/BenchCapture.kt` and the models in `.../models/TrackVoice.kt` and `Arrangement.kt` if anything looks off.

Notes:
- `new` is the value to write. `old` is what the source held, so use it to find the spot and to confirm the file matches.
- Macro rows are file values too. The bench auditions over the app's saved knob, which can differ from the file, but a VIBE MACRO row is always file default → knob the user set, and a macro the user never moved has no row.
- A section volume row for a track with no existing override means you add a `TrackSectionOverride(volume = ...)` for that track. Keep any other override fields.
- `clampedDb` greater than 0 means the user heard a gain above 1.0 and the volume was clamped to 1.0. The written value is quieter than what they heard by that many dB. Tell the user for every such row. Do not invent a fix; offer to lower the other tracks instead.

## 3. Edit by hand

Vibe files are hand-written. Expect `.copy()` chains, `let` blocks, shared helpers, and files that hold several vibes (for example `CompLabVibe.kt`). Read the whole vibe first.

- Edit the place that actually defines the value, not a copy of it.
- If a shared helper or constant feeds several tracks or several vibes, ask the user before changing it. The usual alternative is a local `.copy(...)` at the one track.
- Use judgment on layout. Match the file's style. Do not restructure or reformat.
- Do not use sed or scripts to apply rows. Edit each value with the edit tool.
- Never touch musical content (licks, patterns, chords). The capture holds mix values only.

## 4. Show and measure

1. Show `git diff` of the vibe file.
2. Run `scripts/mix/measure.sh "<vibe name>"` with NO_DUMP unset. The edit changed the source, so it must re-dump. Capture the numbers for the same vibe before the edit (run it first, before editing) so you can compare.
3. Results land in `liborpheus_dsp/test/output/vibe_measure/<slug>.json` and `summary.md`.
4. Report before and after LU per changed track, plus LUFS, pre-limit peak and past-knee percent. Point out anything that moved outside the targets in the Mixing section of the vibe-creator skill.

## 5. Finish

- Do not commit. Ask the user. Commit only after an explicit yes.
- End with a list of ear tests for the user, one line each: the vibe, the sections and tracks that changed, what to listen for (for example "Fire Sky chorus: pad now 3 dB lower, check it still holds the chord bed"). Anything audible must be ear-tested before it lands on main.
- Mention any stale, clamped or skipped rows again at the end.
