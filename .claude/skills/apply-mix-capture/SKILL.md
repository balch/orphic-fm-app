---
name: apply-mix-capture
description: Use when the user says "apply my mix capture" or "apply the latest capture", or hands you a file from build/mix-bench/captures/ or ~/.config/orpheus-dj/mix-captures/. Turns a Mix Bench capture JSON into hand edits of the vibe's Kotlin source, then measures before and after. Never commits without the user's ok.
---

# Apply a Mix Bench capture

The Mix Bench (desktop DJ app, `./gradlew :apps:djapp:desktopApp:run -Pbench=true`) lets the user mix a vibe by ear. Its URL is printed and written to `build/mix-bench/url.txt`. Each capture is a JSON file named `<slug>-<yyyyMMdd-HHmmss>.json`: in `build/mix-bench/captures/` from the core edition's bench, in `~/.config/orpheus-dj/mix-captures/` from the AI edition (its bench page and the agent's `mix_capture` tool). Your job is to put those numbers into the vibe's Kotlin source.

## 1. Pick and check the file

- Claude can make a capture itself in the running AI edition with the `orphic-mcp` skill's `mix_capture` tool.
- Use the file the user named, else the newest of `build/mix-bench/captures/*.json` and `~/.config/orpheus-dj/mix-captures/*.json`.
- Fields: `vibe`, `slug`, `source` (repo-relative path, may be null), `baselineHash`, `currentHash`, `stale` (null when the app can't tell: no source, or no hash from when the vibe loaded), `aiVibe`, `capturedAt`, `rows` (mix rows), `vibeRows` (sound edits, step 2b; absent in older captures), `vibeJson` (the vibe's file as the app loaded it, without the vibe rows).
- If `aiVibe` is true, the vibe is AI-made and has no Kotlin source. The capture does not change it. Apply the rows to its archive instead (step 3b), then point the user at the grab-ai-vibes skill to import it.
- If `stale` is true, the source changed after the bench loaded the vibe. Stop and ask the user before editing. Old values in the rows may no longer match the file.
- If `stale` is null (and `source` is set), the app could not tell whether the source changed since load. Say so and ask the user before editing.
- If `source` is null, the bench could not find the vibe file. Search `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/vibes/` for the vibe name (names resolve through `VibeNames.kt`) or ask.
- If `rows` and `vibeRows` are both empty, say so and stop.

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
| VIBE | DELAY_TIME_A / DELAY_TIME_B / DELAY_FEEDBACK (no `track`) | `Vibe.effects` (`VibeEffects` `delayTimeA`, `delayTimeB`, `delayFeedback`) |
| SECTION | DELAY_TIME_A / DELAY_TIME_B / DELAY_FEEDBACK (no `track`) | that section's `delay` (`SectionDelay` `timeA`, `timeB`, `feedback`); add a `SectionDelay(...)` when the section has none |

Check the enum names in `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/bench/BenchCapture.kt` and the models in `.../models/TrackVoice.kt` and `Arrangement.kt` if anything looks off.

Notes:
- `new` is the value to write. `old` is what the source held, so use it to find the spot and to confirm the file matches.
- Macro rows are file values too. The bench auditions over the app's saved knob, which can differ from the file, but a VIBE MACRO row is always file default → knob the user set, and a macro the user never moved has no row.
- A section volume row for a track with no existing override means you add a `TrackSectionOverride(volume = ...)` for that track. Keep any other override fields.
- `clampedDb` greater than 0 means the user heard a gain above 1.0 and the volume was clamped to 1.0. The written value is quieter than what they heard by that many dB. Tell the user for every such row. Do not invent a fix; offer to lower the other tracks instead.

## 2b. Map each vibe row to a Kotlin field

`vibeRows` are sound edits the in-app AI or Claude Code made with `pulsar_edit_vibe` (or a same-name `pulsar_apply_vibe`). Each is `{path, old, new}`. `path` walks the vibe's JSON: dotted names, `[i]` for a list index, and a bare number for a `trackOverrides` key. `old` and `new` are JSON values, encoded with every default written out (enum names as strings, `null` where a side has no value). Mixer fields never appear here: they are mix rows.

| path | Kotlin target |
|---|---|
| `bpm`, `rootNote`, `scaleType`, `levelDb`, other top-level names | that `Vibe(...)` argument |
| `genre.X` | the `GenreProfile(...)` field |
| `tracks[i].engineEdm.X` / `tracks[i].engineSpace.X` | that `OrpheusEngine` field on track `i` (`engineId` is `OrpheusEngineId.<new>`) |
| `tracks[i].X` (`envelopeProfile`, `macroMap.moodTimbre`, `role`, ...) | that `TrackVoice` field; a `role` row whose `type` changed replaces the whole `TrackRole` |
| `arrangement.sections[s].X` | that `Section(...)` field |
| `arrangement.sections[s].trackOverrides.T.X` | `trackOverrides[T]` (`TrackSectionOverride`); add one for track `T` when the section has none |
| `tension.X`, `effects.X`, `anomalies[k].X` | the `TensionProfile`, `VibeEffects` or anomaly field |
| `lick`, `bassLine`, `band`, `genre.customProgression`, a section's `customProgression` | the whole value, as one row |
| `lickRotation.pool[k].steps[n].X` | one step of a pool lick |

- Confirm each `old` against the file (or against `vibeJson`, which is the file as the app loaded it). A mismatch means the source moved: stop and ask, as for a stale capture.
- An `old` equal to the field's default means the file may not write the argument at all: add it. `old` is `null` where the file has no value (a null field, or a `trackOverrides` key it lacks). `new` is `null` when the edit removed the value: drop the argument.
- Write lick, bassLine and progression values in the file's own style: if the vibe builds licks with a helper or a `val`, write the new steps the same way, at the `val` the vibe already uses. A value that is a `.copy()` of a shared lick or helper gets a local value; ask before changing the shared one.
- Notes in vibe rows (licks, bass lines, chords) are the user's and the AI's work: apply them as given. Do not "fix" them.

## 3. Edit by hand

Vibe files are hand-written. Expect `.copy()` chains, `let` blocks, shared helpers, and files that hold several vibes (for example `FireSkyBackupVibes.kt`). Read the whole vibe first.

- Edit the place that actually defines the value, not a copy of it.
- If a shared helper or constant feeds several tracks or several vibes, ask the user before changing it. The usual alternative is a local `.copy(...)` at the one track.
- Use judgment on layout. Match the file's style. Do not restructure or reformat.
- Do not use sed or scripts to apply rows. Edit each value with the edit tool.
- For mix rows, never touch musical content (licks, patterns, chords): a mix row holds a mix value only. Vibe rows that carry notes are the exception (step 2b).

## 3b. An AI vibe

The archive is the JSON file under `~/.config/orpheus-dj/ai-vibes/` for the capture's `vibe` (paths: grab-ai-vibes skill). Use the capture's `vibeJson` as the base. Write each mix row's `new` into the matching field with the same mapping as step 2, and each vibe row's `new` at its `path` (a `null` `new` removes the key). Save the file under that name. Skip step 4's measure script; there is no Kotlin source to build.

## 4. Show and measure

1. Show `git diff` of the vibe file.
2. Run `scripts/mix/measure.sh "<vibe name>"` with NO_DUMP unset. The edit changed the source, so it must re-dump. Capture the numbers for the same vibe before the edit (run it first, before editing) so you can compare.
3. Results land in `liborpheus_dsp/test/output/vibe_measure/<slug>.json` and `summary.md`.
4. Report before and after LU per changed track, plus LUFS, pre-limit peak and past-knee percent. Point out anything that moved outside the targets in the Mixing section of the vibe-creator skill.

## 5. Finish

- Clear the vibe's saved change set, mix and vibe rows alike, so the rows are not applied twice: call `mix_clear_changes` (app running), or delete `~/.config/orpheus-dj/mix-changes/<slug>.json` (same slug rule as the capture file). A catalog vibe's source change is heard only after a rebuild.
- Do not commit. Ask the user. Commit only after an explicit yes.
- End with a list of ear tests for the user, one line each: the vibe, the sections and tracks that changed, what to listen for (for example "Fire Sky chorus: pad now 3 dB lower, check it still holds the chord bed", or for a vibe row "track 4 lead: WSH pluck in place of the DX3 pipes, check it matches what you heard in the app"). Anything audible must be ear-tested before it lands on main.
- Mention any stale, clamped or skipped rows again at the end.
