# Mixer engine switch: swap a track's engine while the vibe plays

Date: 2026-10-27 · Status: draft for approval, not built. Stays uncommitted until it lands. This is a dated snapshot; the code is the source of truth.
Builds on `2026-10-25-mix-change-sets-design.md` and `2026-10-25-whole-vibe-change-sets-design.md`. Sits next to the per-track harmonics, morph and timbre knobs being built on `claude/mixer-voice-knobs-7b75ab`.

## Why

The user wants to change a track's engine from the DJ app's TRACKS mixer while the song plays, hear it at once, and keep it. Example: the lead on KEYS goes from VA to VOWEL_FOF. The in-app mixer should grow toward editing the whole vibe, and the engine is the biggest lever on a track's sound.

## What the code does today

### The live path

1. **Edit.** `pulsar_edit_vibe` (`VibeEditTool`) calls `CollabMixer.editVibe(origin, [("tracks[4].engineEdm.engineId", "VOWEL_FOF")])`. That patches the effective vibe (`Vibe.patched`, `bench/VibeDiff.kt`), records a `HistoryVibeEdit` row (path, before, after) and calls `play(target, layers)`.
2. **Play.** `CollabMixer.play` has two branches. If the target equals `pulsar.vibeFlow.value`, it only re-applies the mix layers. Otherwise it calls `applyOwn`, which calls `PulsarFeature.applyVibe(target)`. So **today an engine edit restarts the song from the top.** It does not swap live.
3. **Ports.** `applyVibe` bumps `vibeLoadCount`, resets mutes, the song ending and the section queue, and writes every per-track port. For engines: `trackEdmIds[i]` and `trackSpaceIds[i]` (the `pulsar_track_engine_edm/space[t]` atomics), and the harmonics pin as `pinHarmonics || engineId.forcePinHarmonics`.
4. **C++ pick.** Each render block, `orpheus_unit_pulsar.cpp` (around line 3720) reads both atomics, picks the slot from energy (above 0.6 EDM, below 0.4 Space, in between keep the current slot) and sets `ts.engine_index`. At each loop boundary (around line 5063) it re-rolls the slot in the 0.4 to 0.6 zone. The per-slot knobs come through `PULSAR_PICK`.
5. **Render.** `render_engine` dispatches on `engine_index`: Braids (100..108) to `unit_process_braids`, Chaos (200..204) to the chaos kernel, OSC (below 0) to the OSC block, Mist (300) to its own path, everything else to `OrpheusVoice::Render`.

The engine port itself is already live: the Orpheus Pulsar panel's HI/LO pickers (`PulsarPanel.kt`, `setTrackEngineEdm`) and djapp's hidden track-edit strip (`-PtrackEdit=true`) write it directly and the next block plays the new engine. They just record nothing.

### What a swap resets

- **Plaits family** (`orpheus_voice.h`, around line 382): on a new `engine_index` the voice loads the DX patch bank (DX, DX2, DX3), calls `Engine::Reset()`, drops the up-to-23-sample render remainder, and re-inits the LPG envelope and filter. The note in flight is cut on that sample.
- **Braids**: `set_shape` on the per-track `MacroOscillator`. It has no gate and no LPG, so it sounds at once.
- **Chaos, OSC, Mist**: each keeps its own per-track state. Switching into one resumes that family's state from wherever it was left.
- **Not reset**: the Pulsar track's own envelope, sequencer, note, hold, double voice, sends and per-slot knobs. Pulsar tracks never read `engine_changed`, so the drum first-hit fix (`60542003a`) does not apply here.

### Clicks, dropped hits and silence

- **Click.** Mid-note, the Plaits reset jumps the output from the old engine's sample to the new engine's first sample. That is a step, so likely an audible click on a sustained voice. Not measured yet.
- **Silence.** Swapping to a self-enveloped engine during a held note gives no strike until the next note-on. Two groups behave differently (`orpheus_unit_pulsar.cpp` around line 5995): BD, SD, HH and the DX family keep their gate high through a hold but get no rising edge after the reset; STR and MOD ignore a held gate and strike only on a note-on. A PAD that never re-strikes could stay silent for bars.
- **First hit.** Not dropped: the next note-on is a normal rising edge on the new engine.
- **Prior art.** Every vibe whose EDM and Space engines differ already does this hard swap whenever energy crosses 0.4 or 0.6, and nobody has flagged it. That bounds how bad the click can be, but it happens rarely, while a user swap happens on purpose and gets listened to.

## Goals

1. The user picks a new engine for a track from the mixer, and the song keeps playing. The new engine sounds from the track's next note-on.
2. The swap is a History vibe row (`tracks[i].engineEdm.engineId` or `engineSpace.engineId`), so it shows in History, persists per vibe in `mix-changes/`, folds, toggles, hears, rolls back, captures, and `apply-mix-capture` writes it to the Kotlin class.
3. Toggling, hearing or rolling back an engine row also lands live, with no restart.
4. The same live path serves the AI: a `pulsar_edit_vibe` that changes only engine ids no longer restarts the song.

## Non-goals

- A crossfade between the old and new engine. It would need a second voice set per track. The double voice already owns that second set, and it would double the track's CPU during the fade.
- Per-section engines. `TrackSectionOverride` has no engine field and this design adds none.
- Remapping harmonics, timbre and morph per engine.
- iOS persistence and the og edition, as in the mix change-set design.

## Anti-goals

- A swap that restarts the song, drops the mix rows or changes the vibe's identity (name, file, stored hash).
- A track left silent for more than a bar after a swap.
- A second engine picker or a second track-strip model next to the knobs session's.

## Decisions

All of these are my calls unless marked otherwise. The first one needs the user's eye most.

1. **Which slot.** *(my call, flagged)* The strip shows the track's two slots as two small chips, HI (EDM) and LO (Space), as the Orpheus Pulsar panel does. The slot playing now is lit. Tapping a chip opens the picker for that slot. **When both slots hold the same engine, a pick changes both**, as two rows, because the author meant one sound and a split would make the change vanish whenever energy swings. Alternative the user may prefer: one chip that always edits both slots.
2. **When it lands.** *(my call)* C++ latches the new id per track and adopts it on that track's next note-on. If no note-on comes within one bar (a held PAD), it adopts at the next bar line and forces a retrigger, so self-enveloped engines strike. A vibe load snaps the latch with no wait. Energy slot flips keep today's immediate behaviour. The latch is always built: the held-note retrigger is needed whatever the click measures, and both are the same code. Phase 0's tests fail first and pass after phase 2.
   - **Split pairs.** *(my call)* A track whose two slots go from equal to different starts taking part in the loop-boundary slot roll (`if (edm == spa) continue;`, around line 5063). The roll draws from `mutation_seed`, so the song's generative variation changes from the next loop boundary on. Accepted: it is still deterministic per vibe and edit, and a vibe baked from the capture rolls the same way from bar one. The picker never splits a pair (decision 1); the AI, a toggle of one of the two rows, or a roll back can.
3. **Knob values.** *(my call)* Keep the slot's harmonics, timbre and morph as they are. Re-send the harmonics pin port, so entering DX pins harmonics as a patch selector and leaving DX un-pins it unless the vibe pinned it. Nothing in the code holds per-engine defaults, and the knobs next to the picker let the user fix the value by ear.
4. **Engines offered.** *(my call)* The same rings as the Orpheus Pulsar panel (`PulsarPickerConfigs.kt`): the full Plaits ring centred on the track's role (BD on KICK, STR on PAD and so on), V2 (VCF, PD, DX family, TRN, ENS, NES, Braids character), CHD (Braids triples) and CHA (chaos and MST). No filter by role: a pitched engine on a percussive track plays at fixed pitch, which can be useful. OSC stays out, as in Orpheus.
5. **Scope.** *(my call)* Engines are vibe-wide. With the mixer's SECTION scope on, the picker still writes the vibe and says so.
6. **Live play path.** *(my call)* `CollabMixer.play` gets a third branch: when the target differs from the playing vibe only at live-safe per-slot paths, it calls a new `PulsarFeature.applySlotsLive(vibe)` and then applies the layers. Live-safe means `tracks[i].engine(Edm|Space).` followed by `engineId`, `harmonics`, `timbre`, `morph` or a `pin*` flag: each is one plain per-slot port. Covering the knob fields too means an edit that moves an engine and a knob together still lands live, whichever way the knobs session stores its edits. `applySlotsLive` replaces the vibe in place: it sets `PulsarFeature._vibeFlow` and the session's vibe **without** `PulsarSession.updateVibe`, which bumps `songGeneration` and would reset the song-progress arc. No `vibeLoadCount` bump, no mute, song-ending or section reset. It writes only the changed slots' ports, plus that slot's harmonics pin (`pinHarmonics || forcePinHarmonics`). Any other difference still takes the restart path. One function serves the picker, toggle, hear, roll back and the AI.
7. **Muted, soloed or silent tracks.** *(my call)* Their chips show and work like any other; the swap lands on the track's next note-on once it plays again.
8. **Row text.** *(my call)* History shows "KEYS · HI engine: VA → VOW" using the strip's short labels.
9. **Shared UI.** *(my call)* The chips sit in the same strip area as the knobs session's harmonics, timbre and morph knobs and read the same per-track state. If that session adds per-slot fields to `MixerTrack`, the chips reuse them; if not, this work adds `engineEdm`, `engineSpace` (ids) and `spaceSlot` (live, from `pulsar_track_active_space_slot`) to `MixerTrack`. The picker popup is the existing `EnginePickerButton` from `ui/widgets`. Asked that session how it stores knob edits; its answer may move decision 6 into shared code.

## Constraints

- `historyLock` rules hold. Port writes happen under the lock, as `applyLayers` does today.
- Both slot ports must stay correct at all times (the EDM/Space mirror trap): a swap writes only the slot it changes, and the pin port of that slot.
- The C++ latch must not change output for a vibe with no swap. Byte-identical baseline tests must stay green.
- Tests: `:apps:djapp:ai:jvmTest` (CollabMixer, VibeEditTool), `:features:pulsar:jvmTest` for the new port writes, the `pulsar` C++ suite with `-j`, and only the covering djapp UI classes with `--tests`.

## Risks

- **Observers of `vibeFlow`.** An in-place vibe update still emits on `vibeFlow`. `PulsarMetadataProducer` re-renders the artwork on every emission, so it needs a `distinctUntilChanged` on what the artwork reads. `PulsarSongEnding` and `DjApp` key on the name and are fine. `AiMixerViewModel` should re-read, which is what updates the chips.
- **Latch and the loop-boundary roll.** The roll at line 5063 reads the atomics directly and must read the latched ids instead, or a swap would land at the loop boundary regardless of the note-on rule.
- **Stale family state.** Going Plaits VA to Braids VOW and back to VA does not reset the Plaits voice (its previous index is still VA), so the old LPG state returns. Landing on a note-on hides this. Noted, not fixed.
- **DX patch on swap.** Entering DX with a harmonics value meant for VA picks an arbitrary patch. Accepted under decision 3.
- **Knobs session timing.** The UI phase waits for that branch to land or settle.

## Phases and estimate

| Phase | Work | Estimate |
|---|---|---|
| 0 | C++ tests, failing first: swap mid-note on VA (step size at the swap), swap mid-hold into STR and into DX (bars to first sound), PAD hold (sounds within a bar), and a no-swap vibe that stays byte-identical | 1 to 2 h |
| 1 | Kotlin live path: `PulsarFeature.applySlotsLive`, an in-place session update with no generation bump, `CollabMixer.play` third branch, the live-safe path check, tests for edit, toggle, hear, roll back and the AI edit | half a day |
| 2 | C++ note-on latch with one-bar fallback and retrigger, load snap, loop-roll reads the latched ids, phase 0 goes green | half a day |
| 3 | UI: HI/LO chips, picker, `MixerTrack` fields, ViewModel intent, row text, covering UI tests | half a day, after the knobs branch |
| 4 | `apply-mix-capture` check for `engineId` rows, AI guide line that engine-only edits land live, desktop walk-through | 1 to 2 h |

About two days of agent time in all.

## Done looks like

On desktop, AI edition, any vibe with a melodic lead:
1. Open the mixer and tap KEYS's lit HI chip. Pick VOW. The song keeps going and the lead sings vowels from its next note. History shows "KEYS · HI engine: VA → VOW".
2. Pick a PAD engine change during a long hold. The pad sounds again within a bar.
3. Turn the row off, then on, then roll back past it. Each lands live with no restart.
4. Capture, switch vibes and relaunch. The row and the sound come back.
5. Run `apply-mix-capture`. The Kotlin class gets `engineId = OrpheusEngineId.VOWEL_FOF` and the change set clears.

## Left to the builder

- The exact signature of `applySlotsLive`, and where the live-safe path check lives (`VibeDiff.kt` is the natural home).
- How the C++ latch stores its pending ids and how it counts the one-bar fallback.
- The chip styling, within the strip's existing look.

## Rulings made during the build

- The knobs branch had already built a live play path (`Vibe.retunesTo`, `PulsarFeature.retuneVibe`). Engine edits ride that path, with no `applySlotsLive` of their own. This branch stacks on knobs commit `0afa540d5`, so knobs lands first.
- The strip keeps the knobs branch's EDM/SPC slot lines and heard-dot, not HI/LO chips lit by ENERGY. The picker is a pill on each slot line, and History rows read "KEYS · EDM engine". One vocabulary across rows and strip.
- Picking an engine also picks that slot for the knobs, so the knobs edit the engine you just chose.
- A held or silent track arms its swap after 16 steps with no note-on, then adopts at the track's next bar line, `(playhead - wrap_start) % 16 == 0`, with a forced strike at that step's sample. Worst case is under two bars.
- The DX harmonics pin is enforced in C++ from the adopted engine, so it flips with the engine and not with the port write. The retune path writes the raw `pinHarmonics`.
- A mid-play engine port write no longer re-seeds the active-engine mirror or the active slot, because Pulsar publishes those itself while it plays.
- `EnginePickerButton` gained `width`, `shape` and `anchorWidth` params, whose defaults leave Orpheus unchanged. Picker labels add MST and OSC.
- A refused pick shows its own notice ("The engine did not change..."). A pick while the knob scope is SECTION says engines change the whole vibe.

## Known gaps

- The block that adopts renders on the new engine from its first sample. A note-on that lands mid-block swaps up to ~10 ms early. A bar-line strike on a held pad can play the new engine held for up to one block before it strikes.
- On the note that adopts, the CHORDAL arp choice and the loop-boundary lick note floor still read the old engine, for one note or one loop.
- The picker pill is about 14 dp tall. Since the rebase onto the knobs pop-out (32060005a), it lives in each track's sound pop-out, reachable from the strips and the compact list alike; the pop-out has room for a larger pill.
- Plaits family state can carry over through VA -> VOW -> VA (spec risk, not fixed).
- The Orpheus Pulsar panel's HI/LO pickers write the same ports, so they now wait for a note-on too.
- The bar line is the track's own loop phase. On a track loop that is not a multiple of 16 steps, the timed strike lands on that track's phrase start, not the song's bar line.
- While a scratch or start-hold freezes the clock, an armed swap waits for the clock to resume.
- The "Engines change the whole vibe." notice stays until it is dismissed.
