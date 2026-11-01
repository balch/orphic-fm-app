# Track pop-out with an engine lock

Dated snapshot, 2026-10-27. The code is the authority once built.

## Why

Each mixer track has two engines: EDM plays when energy is above 0.6, SPACE plays below 0.4, and between the two the loop boundary rolls between them. While a song plays, energy crosses those lines and the track flips to its other engine. That is not what the user wants once they have chosen a sound. The user wants to hold a track on one engine for the whole song and tune that engine's knobs, and to have all of the track's controls in one place.

## What exists today

- Branch `claude/mixer-engine-switch-cce402`, stacked on the knobs branch tip `32060005a`. A chip under each strip's wave (and in each compact row) opens `VoicePopover` in `apps/djapp/ai/.../mixer/VoiceKnobs.kt`. The pop-out holds a slot switch (`SlotHeader`; it picks which slot the knobs edit, `AiMixerActions.pickSlot`), two engine pills with the radial picker (`setEngine`), three knobs (`VoiceKnobRow`, `setVoiceKnob`), a readout and a help paragraph.
- Engine and knob edits are History vibe rows. They play on through `Vibe.retunesTo` / `PulsarFeature.retuneVibe` with no restart, and Capture writes them back to Kotlin (`apply-mix-capture`).
- C++ picks the slot per block in `orpheus_unit_pulsar.cpp` (~3739, energy thresholds) and at the loop boundary (~5091, soloist rule, then thresholds, then a `mutation_seed` roll in 0.4-0.6). No lock exists.
- The strip holds level fader, pan, R and D sends, M, S and pin (`AiMixerPanel.kt` `TrackStrip`; compact rows in `CompactMixer.kt`).

## Goals

1. A per-track lock with three values: Auto, EDM, SPACE. Auto is today's behaviour. EDM or SPACE holds the track on that slot for the whole song, whatever energy, the loop roll or the soloist rule would do.
2. The lock is a field on the vibe's track, edited as a History vibe row, played on live with no restart, and written back to Kotlin by Capture.
3. The pop-out (layout A, user-approved): a single column.
   - Header: track name, Done.
   - PLAYS: a segmented Auto / EDM / SPACE control.
   - Engines: an EDM row and a SPACE row, each with its engine pill and picker. The dot marks the slot playing. When locked, the locked row is highlighted and the other is dimmed but its picker still works.
   - Knobs: the three knobs. Under Auto they are dimmed and do nothing, with one hint line, "Lock EDM or SPACE to tune its knobs". When locked, they tune the locked slot.
   - MIX: level (dB), pan, reverb send, delay send as horizontal sliders, then M, S and pin.
4. The slot switch goes away; the lock replaces it. The long help paragraph goes.

## Non-goals

- Per-section locks. The lock is vibe-wide, like engine picks.
- Changing the strips or compact rows. They keep their own controls; the pop-out duplicates them.
- The Orpheus app's Pulsar panel. It gets no lock UI.
- A crossfade when the lock flips the slot.

## Anti-goals

- A second live path. The lock rides `retunesTo` / `retuneVibe` like engines and knobs.
- Shifting other tracks' sound when one track is locked. The loop-boundary roll must draw from `mutation_seed` exactly as before; the lock overrides the result afterwards.
- Knobs that look live but do nothing. Under Auto they are visibly off.

## Decisions

1. **Model** *(my call)*. `TrackVoice` gains `slotLock: SlotLock = SlotLock.AUTO`, with `enum class SlotLock { AUTO, EDM, SPACE }`, serializable, so shipped vibes and AI JSON stay valid without the field. Path `tracks[i].slotLock`. History row text "KEYS · plays EDM" / "plays SPACE" / "plays Auto".
2. **C++** *(my call)*. A new `std::atomic<int> pulsar_track_slot_lock[8]` (0 Auto, 1 EDM, 2 SPACE) set from port `track_N_slot_lock` in `orpheus_engine_routing.cpp`, beside the engine ports. After the per-block threshold pick and after the loop-boundary pick (soloist rule and roll included, roll still drawn), a non-zero lock forces `space_slot`. The slot flips at once, as an energy flip does today; the engine-id latch is untouched. Vibe load sets the lock with the rest of the track.
3. **Live path.** `retunesTo` accepts `slotLock` differences; `retuneVibe` writes the lock port. `applyVibe` writes it on load.
4. **Knobs follow the lock** *(my call)*. The knob slot is the locked slot. `pickSlot` and its per-track slot picks go. A track that is AUTO shows knobs off; earlier knob rows stay in History and still play.
5. **Engine pick sets nothing else.** Picking an engine no longer sets the knob slot (there is none). An equal pair still moves both slots.
6. **Section scope.** A lock edit under SECTION scope posts the same "changes the whole vibe" notice as an engine pick.
7. **AI.** `vibe_guide.md` and the `pulsar_edit_vibe` description gain one line: `slotLock` holds a track on one engine and plays on live. The AI may set it.
8. **Capture.** `apply-mix-capture` writes `slotLock = SlotLock.EDM` into the track's `TrackVoice(...)`. Check that the skill's path mapping handles a new enum field; add a line to the skill if it does not.

9. **Auto shows what plays** *(my call)*. Under Auto, `MixerTrack`'s knob slot is the heard slot: the chip shows the playing engine, and the pop-out's knobs show the playing slot's values, disabled. The chip's changed/pinned colour counts knob rows on both slots, so a track that sounds different always says so.
10. **Off is not dim** *(my call)*. A disabled knob (Auto) gets reduced alpha and semantics `disabled`, distinct from today's dim "follows MOOD or SPACE" knob. The readout line stays. A one-line note under the knobs keeps the dim meaning: "Dim knobs follow MOOD or SPACE; double-tap returns one to the file."

## Constraints

- Kotlin `jvmTest` for `:features:pulsar` parses `orpheus_engine.h`; a new atomic there needs that test run.
- Generated fixtures (`preset_*.h`, `.odwg`) must not drift; a new port may change `default_graph.odwg` / `dj_graph.odwg`, which then must be regenerated and committed.
- EDM/Space port mirror rule: the lock is one port per track, not per slot.

## Risks

- The soloist rule exists so a solo does not flip timbre. A lock overrides it; locking SPACE on a soloist keeps SPACE through the solo. Accepted: the user asked for it.
- A lock flip under a held note changes engine mid-note with no strike, like an energy flip today.

## Done looks like

On desktop, AI edition:
1. Open KEYS's pop-out. PLAYS shows Auto, knobs are dim with the hint.
2. Tap EDM. The EDM row lights, knobs wake, and as energy drops below 0.4 the track stays on EDM. History shows "KEYS · plays EDM".
3. Turn a knob; it changes the EDM engine's sound.
4. Level, pan, sends, M, S and pin in the pop-out move the strip's controls too.
5. Tap Auto; energy picks again and the knobs dim.
6. Capture and `apply-mix-capture`; the Kotlin track gets `slotLock = SlotLock.EDM`.

## Left to the builder

- Composable structure and file split for the pop-out; reuse the strip's slider and button composables where they fit.
- Whether the MIX sliders reuse `Sends` / `DbFaderRow` from `CompactMixer.kt` or new ones.
- Scrolling the pop-out on short windows.

## Rulings made during the build

- The `track_N_slot_lock` control port is also registered in `PulsarPlugin`, without which the Kotlin write has nowhere to land. The eight new `PulsarSymbol` entries sit after the engine ports. Every lookup is a block-relative offset or matches by name, so nothing persisted moves.
- `MixerTrack.knobsLive` is derived from `slotLock` rather than stored, so the two cannot disagree. `slotHeard` is gone, and the playing dot is `track.slot == slot`.
- A refused lock edit has its own notice: "The lock did not change. Try again once the vibe has loaded."
- The pop-out reuses `DbFaderRow`, `MixerSlider` and `MuteSoloButtons`, which gained optional text sizes whose defaults leave the strips unchanged. It does not lift `CompactMixer.Sends`.
- On the fold, text follows the AI chat pane: nothing in the pop-out is below `LegibleText.SECONDARY`, the hint, readout and values use `CHAT_BODY`, and the track name uses `TITLE`. The pop-out's max width went from 300 dp to 340 dp to fit them. There is 8 dp more room above the knobs.
- The engine-switch work's `pickSlot` is gone. Picking an engine sets nothing but that engine.

## Known gaps

- If the lock flips during a knob drag, one gesture can write rows on both slots. This is rare, and History shows it.
- The pop-out's reverb and delay sliders have no double-tap reset; level and pan do.
- The chip's pinned accent counts only the shown slot's file pins. Changed rows count both slots.
- Under Auto, the "playing" dot is Kotlin's energy estimate, which ignores the loop-boundary roll and the soloist rule.
- Picking an engine on the dimmed (unlocked) row still arms the engine latch's bar-line strike for that slot.
- A soloist locked to SPACE stays on SPACE through its solo, as the spec accepts.
