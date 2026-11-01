# Whole-vibe change sets: the in-app AI edits any part of a vibe

Date: 2026-10-25 · Status: design, not built. This is a dated snapshot; the code is the source of truth.
Builds on `2026-10-25-mix-change-sets-design.md` (per-vibe change sets, Capture rows, roll back here).

## Why

The user wants to talk to the in-app AI about how a vibe sounds and have it change any part of it:

> "the engine sounds like bag pipes, but i want it to sound like a thin guitar and build in complexity section over section"

Today the AI can already read the whole playing vibe (`pulsar_get_vibe('current')`) and replace it (`pulsar_apply_vibe`). But those edits sit outside the change set:

- They never appear in History, so there is no hear, no roll back, and no before/after capture.
- Replacing a catalog vibe changes its hash, which drops the vibe's change set and its mix rows.
- The only record is a whole-vibe JSON snapshot in `ai-vibes/` under the catalog vibe's name. Claude Code has no diff it can port into the Kotlin class.

Mix rows already have the full workflow: History, Capture, then the `apply-mix-capture` skill ports them to source. This design gives every other part of a vibe the same workflow.

## The user's decisions

1. **Same vibe.** When the AI reworks a built-in vibe, it stays that vibe. The rework lives in History on top of the file until Claude Code ports it into the Kotlin class. For an AI vibe, the rework goes into its `ai-vibes/` JSON, as mix rows do.
2. **One History row per changed field**, like the mix rows, even when one idea touches many fields.
3. **One change at a time.** The AI makes one change, the user hears it, and the AI refines from what the user says next. It does not offer several candidates to audition.
4. **Notes are in scope.** The AI may edit licks, bass lines and chords too, as rows the user can hear and roll back.
5. **Restarting is fine.** A change may restart the song from the top. There is no need to hot-swap mid-song.
6. **The structure is fixed; restructuring means a new vibe.** An edit may change anything inside a section, but not the number or order of sections (and tracks stay at 8). `pulsar_edit_vibe` and the same-name path of `pulsar_apply_vibe` reject an edit that adds, removes or reorders sections. The rejection tells the AI to offer the user a brand new vibe in that direction. If the user says yes, the AI builds it as today (`pulsar_cast_vibe`, then `pulsar_apply_vibe` under a new original name). It becomes a new AI vibe with its own empty change set. The original vibe's change set is kept, untouched. Since sections never move, rows keyed by section or track index always point where they were written.

## Goals

1. Every AI edit to the playing vibe becomes History rows in that vibe's change set. Each row is a diff against the vibe's file value: tracks, engines, arrangement and sections, tension, effects, macros, tempo, key, scale, licks, bass line and chords.
2. These rows behave like mix rows: they persist across vibe switches and relaunch, have hear and roll back here, fold when the same field is edited again above the newest point, and are bracketed by the turn's before/after captures.
3. An edit to the playing vibe never changes its identity. The name, the file and the stored hash stay the same, and existing mix rows survive.
4. A capture file carries every row kind, and the off-board AI (Claude Code via `apply-mix-capture`) can port each one into the Kotlin class or the AI vibe's JSON, then clear the change set.
5. The AI can work this loop well: it knows which fields shape a track's sound and the section arc, and it can listen with its existing audio tools before and after a change.

## Non-goals

- Hot-swapping a change into the running song. A vibe-row change restarts the song (decision 5).
- Offering several candidates per request (decision 3).
- Merging change sets made on two devices.
- iOS persistence and the og/core edition, as in the mix design.
- MCP auto-captures (MCP still has no turns). MCP edits do still become rows (see below).

## Anti-goals

- A sound edit that drops the vibe's mix rows or its change set.
- A catalog vibe edit that writes an `ai-vibes/` JSON under the catalog name, or that shows up as a second vibe.
- One field with two owners: a value that is both a mix row and a vibe row, so that rolling one back leaves the other in force.
- The AI having to resend the whole vibe JSON to change one field.

## Decided by me (flagged for the user to check)

- *(my call)* **Two row kinds, one timeline.** `HistoryChange` today holds mix `LayerWrite`s. A new row kind, a **vibe row**, holds one field path in the `Vibe`, its file value and its new value, both as JSON elements. Example: `tracks[2].engineEdm.harmonics: 0.62 → 0.30`. Mix rows and vibe rows interleave in one `MixHistory`, with the same ids, folding, points, hear and roll back.
- *(my call)* **Row size for structured values.** Scalars and enums are one row per leaf field (decision 2). Musical structures are one row per whole value, not one row per note: `lick`, `bassLine`, `band`, `genre.customProgression`, each section's `customProgression`, and each track's or section's `hits`. Sections are diffed per item, by index (their count and order can't change, per decision 6). Lists inside a vibe, such as a section's transitions or the anomalies, are diffed per item while their count stays the same, and replaced whole when it changes. The builder picks the exact list of atomic paths, using "would a user ever want to roll back half of this?" as the test.
- *(my call)* **Mixer fields stay mix rows.** Any vibe path the Mixer already owns (engine volume, pan, reverb and delay sends, the four macros, section track volume and sends, section macro and ramp multipliers, delay time and feedback) is recorded as the mix row the Mixer would write, never as a vibe row. One field, one owner.
- *(my call)* **The effective vibe.** The vibe that plays is the file vibe with the vibe rows patched in (in row order), followed by the mix layers as today. The change set's stored hash stays the hash of the file vibe, so a rebuilt file still drops a stale set.
- *(my call)* **A new edit tool.** Add `pulsar_edit_vibe` to the in-app AI and MCP. It takes a list of `{path, value}` edits against the playing vibe, validates the patched vibe the way `decodeVibe` does, records the rows, and re-applies the effective vibe (restarting the song). It counts against the turn's apply budget. `pulsar_apply_vibe` stays the tool for a brand new vibe. Called with the playing vibe's name, `pulsar_apply_vibe` diffs the JSON against the file and records the rows the same way, with no archive write, so an AI that sends a whole vibe still lands in History.
- *(my call)* **Hear and roll back.** If the vibe rows at the target point differ from those now in force, hear and roll back re-apply the effective vibe as of that point, which restarts the song. If only mix rows differ, they behave as today. Ending a hear re-applies the current effective vibe.
- *(my call)* **Replay on load.** When a vibe with stored vibe rows loads, the app plays the effective vibe, not the bare file. This must not count as a new vibe identity (no `BeforeApply`, no change-set reset), and the song still starts from the top.
- *(my call)* **Capture file.** It gains a `vibeRows` list: `path`, `old`, `new` (JSON). `vibeJson` stays the file vibe, so `old` values can be checked against it. The `apply-mix-capture` skill gains a mapping from a vibe path to its Kotlin target and a rule for structured values: write the new lick, bass line or progression in the file's own style, and keep any shared helper or constant intact (ask the user before changing one, as for mix rows).
- *(my call)* **AI guidance.** The vibe guide and the `pulsar_edit_vibe` description teach the loop:
  - read `current`, listen if useful, make one change, say what changed in plain words, then wait for the user's reaction
  - which fields shape a track's timbre (engine family, harmonics, timbre, morph, the macroMap window, LPG, envelope profile)
  - which fields build a section arc: a section's `macroOverrides` and `rampTo` for complexity, and its `trackOverrides` for morph, density and the rest
  - "go back" means rolling back to the turn's before-capture
  - a request that needs new, removed or reordered sections means asking the user whether to make a brand new vibe in that direction
  - the existing DX-patch and engine-family rules carry over.

- **Rows are always overlays.** This holds for catalog vibes and AI vibes alike. Recording a row never rewrites the vibe's file: not the Kotlin class, and not the `ai-vibes/` JSON. Only the off-board skill writes the file, and then it clears the set. So `old` is always the file value, and the stored hash stays valid until the file really changes.
- *(my call)* **Overlapping paths.**
  - A whole-value row covers the paths under it. Writing a whole section folds in any leaf rows under that section that are above the newest point.
  - A later leaf edit under an uncaptured whole-value row folds into that row.
  - A row whose new value equals the file value is dropped, as for mix rows.
- *(my call)* **The Mixer routing is one to one.** The Mixer-owned paths listed above are exactly the existing `LayerField`s, so each has a `LayerWrite`. No new mix layer kinds are needed.
- *(my call)* **Controls outside the Mixer are performance state.** A change the user makes on screen outside the Mixer records no row. Re-applying the effective vibe replaces it.
- *(my call)* **After-capture timing.** A capture is a diff against the file, not a recording, so it can run as soon as the rows are recorded. It doesn't need to wait for the restart.
- *(my call)* **Every load path uses the effective vibe.** Catalog loads, AI vibe loads and the relaunch restore all go through `CollabMixer.syncVibeLoad`. Once a load is claimed and the set has vibe rows, the app plays the effective vibe through an internal apply that skips the identity steps.
- *(my call)* **Older capture files still work.** `vibeRows` is optional and defaults to empty, so the skill still accepts captures written before this change.

## Constraints

- The `historyLock` rules from the mix design still hold. Re-applying the vibe is a side effect: run it outside the lock, after the snapshot.
- Turn budgets (`TurnBudget.MAX_APPLIES`) cover edits too, so one turn can't loop forever.
- A patched vibe that fails validation records no rows and changes nothing. The tool returns the error so the AI can fix it.
- Vibe-row values must round-trip through `vibeApplyJson`, the same config `pulsar_get_vibe` encodes with.
- Tests: the same suites as the mix design, plus the path diff/patch (round trip: `patch(file, diff(file, edited)) == edited`), the atomic-path rule, the mixer-field routing, and the tool. Run `./gradlew :apps:djapp:ai:jvmTest :features:ai:jvmTest`.

## Left to the builder

- The path syntax and where the diff/patch code lives (`features/pulsar` is the natural home, since the `Vibe` model is there).
- How a vibe row reads in History (for example "Track 2 · EDM engine: MST → STR").
- Whether `pulsar_apply_vibe`'s same-name diff shares code with `pulsar_edit_vibe` (it should).

## Done looks like

On desktop, AI edition, on A Whale's Dream:
1. Ask the in-app AI "track 2 sounds like bagpipes, make it a thin guitar". History shows a before-capture, rows for the engine fields, and an after-capture. The song restarts with the new sound, and the earlier mix rows are still in force.
2. Say "too bright". A new turn adds rows and folds the repeated fields.
3. Ask it to build complexity section over section. Section rows appear.
4. Roll back to the first before-capture. The bagpipes are back and the mix rows remain.
5. Redo, press Capture, switch vibes and relaunch. The rows and the sound come back.
6. Run `apply-mix-capture`. The Kotlin class gets the engine, section and mix edits, and the change set is cleared.
