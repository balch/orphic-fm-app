# Mix change sets: persistent History, Capture as a checkpoint

Date: 2026-10-25 · Status: landed on local main 2026-10-25, not app-tested. This is a dated snapshot; the code is the source of truth.

## Why

The Mixer's History (AI edition, `apps/djapp/ai/.../collab/MixHistory.kt`) lives only in memory. Two moments lose the user's work:

- Making changes, pressing Capture, switching to another vibe and coming back: History is empty.
- On an AI vibe, Capture bakes the rows into the vibe, re-applies it, and History clears. The changes "disappear" instead of staying in the list as captured.

The user wants History to be a durable **change set per vibe**: the edits on top of whatever the vibe's file holds. Captures are checkpoints inside that change set. Moving the changes into the vibe's file is the off-board AI's job (Claude Code through the `apply-mix-capture` skill), which then clears the change set.

## Goals

1. **Change set per vibe, surviving vibe switches and app relaunch.** Loading a vibe restores its History rows and replays them onto the mix.
2. **Capture is the same for built-in and AI vibes.** It writes a capture file holding the full diff of the change set against the vibe as loaded from its file, and adds a **Capture** row to History. Nothing is baked in, nothing re-applies, the song does not restart, and no rows disappear.
3. **Capture rows act like File in the timeline.** Each has **hear** (preview the mix as it stood at that capture) and **roll back here** (remove every row above it, newest-first). File's roll back removes everything, as today.
4. **Rename "Reset to file" to "Clear All".** It empties the vibe's change set.
5. **In-app AI auto-captures.**
   - *Before* the turn's first change, it adds a Capture, unless the newest History row is already a Capture or the History is empty (only File).
   - *After* a turn that changed the mix, it adds a Capture.
6. **De-dupe uncaptured changes.** A new edit to a control that already has an uncaptured row (above the newest Capture) folds into that row instead of adding another. Captured rows are never rewritten.
7. **Off-board clearing.** The off-board AI can clear a vibe's change set after it has written the rows into the source.

## Non-goals

- Persisting mutes, solos, the section loop or the edit scope. These are performance state, not file values, and Capture already skips them.
- Persisting `BeforeApply` (the vibe an agent apply replaced). It stays in memory for the session.
- iOS. It has no capture writer (`UnavailableMixCaptureService`), so it keeps today's in-memory History.
- Changing the og/core edition. It has no Mixer History.

## Anti-goals

- A capture that drops or hides rows from History.
- Capture restarting the song.
- Two change sets showing for one vibe, or one vibe's change set replaying onto another.

## Decided

**Timeline order.** Newest at the top, File at the bottom (as today). "Roll back here" on any point removes all rows above it. This is today's `MixHistory.rollBackTo`, extended to Capture rows.

**Capture rows.** A `HistoryPoint` gains a kind: `MARK` (today's ◆ Mark point) or `CAPTURE`. A Capture also keeps its capture file's path, which the row can show.

**What a capture file holds.** `capture(vibe, history.layers())`: every field the change set moves, against the vibe as loaded. This is today's row computation, which is already "diff against the file". So each capture file supersedes the ones before it, and the off-board AI applies the newest.

**AI vibes.** `MixCaptureFlow.capture()` no longer calls `withCapture` / archive / `applyVibe`. AI and built-in vibes take the same path. `MixCaptureResult.appliedToAiVibe` and the related text go away. The `mix_capture` tool description and the in-app guide (`DjAppGuide.kt`, `OrpheusAgentConfig.kt`, `AppGuideTool.kt`) change to match.

**Empty capture.** If there are no changes above the newest Capture, Capture adds no row and writes no file, and says so. The before-turn auto-capture follows the same rule, which is how "unless the newest row is a Capture or File" falls out.

**Persistence must survive relaunch.**

**Where the off-board AI moves the changes (two cases).**
- *A catalog vibe* (in `vibe_list`): the only home is the Kotlin vibe class, via `apply-mix-capture` as today.
- *A new AI-made vibe*: the rows go into its JSON under `ai-vibes/` until the vibe is imported into Kotlin. Once imported, it is a catalog vibe and the first case applies. `apply-mix-capture` gains this JSON path, and the capture file already says `aiVibe: true` and carries `vibeJson`.
Either way the skill then clears that vibe's change set.

**A stale change set is dropped.** The store records a hash of the vibe as loaded (the `Vibe` JSON-encoded the way `CaptureWriter.vibeJson` encodes it). On load, a stored change set whose hash doesn't match the vibe now loading is deleted, and the vibe plays its new file. This covers a rebuilt source, an AI vibe re-applied under the same name with new content, and an Android build with no repo checkout, since it doesn't depend on source files. *(My call: hashing the loaded `Vibe` rather than the `.kt` source.)*

## Builder's latitude and my calls (flagged for the user to check)

- *(my call)* **Storage.** One JSON file per vibe at `OrphicFiles.root/mix-changes/<slug>.json` (slug as `CaptureWriter` makes it), holding the vibe name, the loaded-vibe hash, `items`, `nextId` and `pointCount`. This needs `@Serializable` on `HistoryItem`, `MixControl` and `EditOrigin`; `LayerField`/`LayerWrite` already are. Put a `MixChangeStore` interface in commonMain with a no-op default binding, and the file-backed one in `jvmAndroidMain`, mirroring `MixCaptureService` / `JvmMixCaptureService`.
- *(my call)* **Restore on load.** When `CollabMixer.syncVibeLoad` claims a load, it seeds the new `MixHistory` from the store for that vibe name, keeping `beforeApply` and `loadedAtMs` as today. The layers are applied once the controller has followed the load, the way `restoreBeforeApply` does it (`awaitLoadFollowed` then `applyLayers`). A store file whose vibe name doesn't match is ignored.
- *(my call)* **Save.** After every committed change to `_history`, off the audio path on the IO dispatcher. Hear doesn't change the history, so it doesn't save. An empty change set deletes the file.
- *(my call)* **De-dupe rule.** On `record`, look for the newest **on** change above the newest Capture with the same key (control, section, written field keys). Origin doesn't count. If there is one, remove it and append one row whose `before` comes from the old row and `after` and origin from the new. Drop it if they are equal. Appending at the end is safe for replay because it writes the same fields last. Today's 2-second drag fold becomes a special case of this rule. Off rows are never folded. Within one agent turn, rows above the before-Capture fold the same way, so the turn's bracket stays tight.
- *(my call)* **Auto-capture labels.** "before: <turn label>" and "after: <turn label>", cut with `cutLabel`. They replace today's "before:" mark point, which goes away. The after-capture runs where `MixCollab.endTurn` is triggered at the end of the turn. MCP has no turns, so MCP edits get no auto-captures.
- *(my call)* **`mix_clear_changes` MCP tool.** It clears the change set for a named vibe (defaulting to the playing one), **ignoring pins**, because the rows now live in the file. If that vibe is playing, its layers go back to the file. Mutes and solos are left as they are. The `apply-mix-capture` skill gets one step added: after editing the source, call `mix_clear_changes`, or delete `mix-changes/<slug>.json` when the app isn't running. The skill should note that a built-in vibe's source change is only heard after a rebuild, so until then the cleared mix plays the old file values.
- *(my call)* **Clear All.** It keeps today's Reset behaviour for pinned tracks: their edits survive, so the agent can't wipe what the user pinned. The wipe confirmation for the agent stays.
- *(my call)* **History label.** The header counts rows above the newest Capture as "uncaptured".
- *(my call)* **Song-end hold (`UncapturedMixHold`) is removed**, along with its `markCaptured` hook in `JvmMixCaptureService` and its tests. Its job was to keep uncaptured layer edits from being wiped by the next song. With persistence they aren't wiped. Mutes and solos are performance state and may clear at song end, as they do on any load.
- *(my call)* **Agent wipe guard on loads.** `DjVibeApplyGuard`'s refusal and capture-first before an agent apply or load existed because a load wiped the mix. A load no longer loses layer edits, so the refusal is removed for loads. It stays only for Clear All. The guide text changes to match.
- *(my call)* **Bench page** (`apps/djapp/desktopApp/.../bench/`) stays as it is: its direct controller edits aren't recorded or persisted, and a bench capture adds no History row. It's a dev tool. To give the in-app Capture button, `mix_capture` and auto-captures one code path that adds the row, Capture moves behind a `CollabMixer.capture()` that calls `MixCaptureService` and then marks the point.

## Constraints

- `CollabMixer`'s `historyLock` rules hold: storage I/O must not run under the lock in a way that blocks edits. Snapshot under the lock and write outside it.
- The controller's layers must always equal `history.layers()` while not hearing.
- Follow `docs/di-architecture.md`: the store is AppScope, resolved lazily like the existing providers.
- Tests: `MixHistoryTest`, `CollabHistoryTest`, `CaptureToolTest`, `AiMixerViewModelTest`, and the `OrphicFiles` test. Run `./gradlew :apps:djapp:ai:jvmTest`. Only run the UI tests that cover the panel, with `--tests`.

## Done looks like

On desktop: edit two levels, Capture, switch vibe, switch back. History shows both rows under a Capture row and the mix plays them. Quit, relaunch, load the vibe again: same. Roll back on File: everything goes. Ask the in-app AI for a change: a before-Capture (when needed) and an after-Capture bracket its rows. Do the same on an AI vibe: no song restart, rows stay.

## Rulings made during the build

- De-dupe folds only above the newest point of any kind (Mark or Capture), not just Capture: folding across a Mark changed what hear and roll back at that Mark play.
- Clear All drops Capture rows along with marks; pinned rows that survive become uncaptured.
- A queued turn gets no before-capture when the previous turn's "after:" is already the newest row.
- Each agent turn carries an id, so a message queued during a turn still gets its own after-capture; a turn with no label uses "song reference".
- An apply or load inside a turn re-arms the turn's before/after bracket on the new vibe; the old vibe gets no after-capture from it.
- The vibe guide still tells the agent to `mix_capture` before re-applying an edited vibe: the apply path writes no capture file, and an edited vibe's change set is dropped.

## Known gaps

- Two vibe names with the same slug share one store file; the later write wins.
- A failed store write is only logged; the in-memory cache still holds the set until relaunch.
- Capture-file writes and the first store read per vibe run under `historyLock`.
- A before-capture fires on an agent edit that is then not applied.
- Any `Vibe` schema change alters every hash, so stored change sets are dropped on the next load after an upgrade.
- Capture rows don't show their file path.
- The bench page (`mixbench/index.html`) still says "Reset to file" and does not record History.
