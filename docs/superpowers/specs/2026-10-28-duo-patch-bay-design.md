# Duo patch bay

Date: 2026-10-28. This is a snapshot of the design as agreed. Once it is built, the code is the source of truth.

Status: landed on main 2026-10-29. Dated snapshot; the code is the source of truth.

## Why

Each Orpheus duo has one mod-source selector (Voice FM, OFF, LFO, Flux, Waves), and the chosen source's channels are welded to fixed knobs. On a DX duo that leaves a channel dead: the engine-picker work stopped LFO channel 2 from moving harmonics, because there harmonics picks a patch. The user wants full control: any modulation output wired to any knob on the duo, several sources at once, with a patch-cable feel. Every knob is now labelled per engine, which makes that legible.

## Goals

- A patch bay per duo that wires any listed output to any listed knob, with an amount per cable.
- Several sources can drive one duo at the same time.
- Every shipped preset and vibe sounds exactly as it does today.
- The AI agent can patch, unpatch, set amounts and flip the voice switches.

## Non-goals (v1)

- MIDI learn of cable amounts. It comes later; nothing in v1 should make it hard.
- Hold, pan and volume as destinations. Pan may come in v2; the audio graph has no per-voice value to offset for pan or volume.
- Prosody and speed as destinations. No DSP reads them today.
- Pulsar tracks, drums and the pooled score voices (C++ duos 6-11). This is the six panel duos only.

## Anti-goals

- Any audible change to an existing preset, vibe or saved state on load.
- A DX duo hopping patches because of wiring the user didn't make on DX.
- A busier duo panel. The patching lives in the pop-out; the panel only gains a PATCH button and small dots.

## What the user sees

**On the duo panel**
- A PATCH button opens that duo's patch bay. It is a pop-out like the engine picker: a popover on wide windows (600dp and up), a bottom sheet on narrower ones. The compact phone card gets the same button.
- The mod-source selector goes away. The mod level fader stays and becomes the duo's master depth: it scales every cable on that duo. MediaPipe hand control and anything else that rides the level keeps working.
- A small dot sits on each knob that has a cable into it.

**In the bay**
- Outputs are on the left, grouped by family:
  - **LFO:** A·B, A, B, A 90°. These are today's four LFO buffers: A·B is the two oscillators combined by the panel's AND/OR button (ch0), A is oscillator A (ch1), B is oscillator B (ch2), and A 90° is A shifted a quarter cycle (ch3). On DRIFT they carry the PolyLFO's four outputs; on CHAOS only A·B carries signal.
  - **Waves:** 1-4 (the Tides outputs).
  - **Flux:** X1, X2, X3, Y, T1, T2, T3. Today only X1 (even duos) or X2 (odd duos) reaches a duo; all seven become patchable.
  - **Voice FM:** the partner voice.
- Knobs are on the right:
  - **DUO:** the engine's three knobs (harmonics, timbre, morph), labelled for the current engine with the panel labels the picker work added (`knobMeaning(..., SignalPath.PANEL)`). On the OSC engine there is also an **fm** input, which is today's audio-rate linear FM.
  - **VOICES:** pitch and envelope, each with an A jack and a B jack. Between them sits a small rotary switch, drawn like a slotted pot. Turned horizontal, its bar bridges A to B and one cable drives both voices. Turned vertical, the voices split and each jack takes its own cable. Click or drag turns it.
- Drag from an output to a knob to patch. Drag a plug off to unpatch. Each cable has an amount from −100% to +100%; negative inverts.
- **Colour:** each family is one hue, and its outputs are shades of it: LFO cyan, Waves green, Flux amber, Voice FM pink.
- **Focus:** a chip row across the top (All · LFO · Waves · Flux · FM).
  - Picking a family keeps its cables bright and thick and fades the rest to faint threads.
  - Tapping a jack or a cable focuses just that cable.
  - Amount knobs appear on the cables only for the focused family or cable. With All selected, the amounts sit in a list under the bay, one row per cable.
- The LFO panel's RATE 1 and RATE 2 knobs are renamed **RATE A** and **RATE B** to match the bay's labels.

## Behaviour

**Today's routing, which conversion must reproduce exactly.** These are the formulas in `liborpheus_dsp/src/orpheus_unit_plaits.cpp`. `fm` and `md` are the per-duo smoothed `fm_depth` and `mod_depth`; both are set from the duo's mod level today.
- Plaits path (per voice):
  - timbre += signal × md. Signal is LFO ch0, Waves 1, Flux X(d%2), or for Voice FM the partner's block RMS.
  - morph += ch1 × fm × 0.5
  - harmonics += ch2 × fm × 0.5 (skipped on DX)
  - note += ch3 × fm × 0.5 semitones
  - For Waves, ch1-3 are tides 2-4. For Flux and Voice FM, only timbre is modulated.
- OSC (engine 0):
  - morph, harmonics and pitch are as above, at block rate.
  - timbre is not modulated.
  - Audio-rate FM is freq += signal × fm × 200 Hz per sample. Signal is partner voice, LFO ch0, Flux, or Waves 1.
  - The OSC duo path (both voices OSC, lines ~900-1280) and the per-voice fallback both carry these.
- Braids ignores timbre. Chaos ignores timbre and note.

**Conversion.** Loading a preset, vibe or saved state that has `duo_mod_source_N` and `duo_mod_source_level_N` creates the cables that reproduce the formulas above, and sets the master depth to the old level. OFF means no cables. Example: an LFO duo gets A·B→timbre, A→morph, B→harmonics, A 90°→pitch on both voices, plus A·B→fm when the duo is OSC. Each amount is chosen so the result is sample-identical to today. Shipped usage across 36 duo slots: OFF 12, LFO 17, Voice FM 2, Flux 2, Waves 3.

**Engine switch.** Wiring stays where it is.
- A cable whose destination doesn't exist on the new engine parks. For example, fm parks when you leave OSC. A parked cable is drawn dimmed, makes no sound, and wakes when you switch back.
- On DX, harmonics picks a patch. A cable already on harmonics when the duo switches to DX parks, so the duo never starts hopping on its own.
- A cable you patch into harmonics while on DX steps through patches deliberately. It snaps to patch centres using the existing hysteresis; it never wobbles between patches.

**Bridge switch.** Turning a bridged row to split leaves the sound unchanged: each cable becomes two cables, one per voice, with the same amount. Turning a split row to bridged merges them: every cable on either jack drives both voices. If A and B both had a cable from the same output, the A amount wins.

**Master depth.** Every cable's contribution is multiplied by the duo's mod level, as today.

## Calls made while writing this up

These are the builder-check answers, marked as my calls; the user can overrule any of them.
- **Where conversion lives.** The six preset JSONs are rewritten to cables once, and the C++ fixtures are regenerated from them. Kotlin converts any other legacy data on load: vibes, saved feature state and old takes. The exactness test keeps a test-only copy of today's C++ formulas, so the live code carries only the new routing.
- **Live writes of `duo_mod_source`.**
  - A Tidal pattern's `fm`/`lfo` source keeps working as a shim. It replaces that duo's cables with the matching conversion, the same way setting the source replaced the source before.
  - The AI agent moves to the new patch tools.
  - The MIDI source-cycle control retires.
  - The old ports stay registered only as long as the shim needs them.
- **Parking.**
  - Parking is a flag on each cable, saved with presets, state and takes.
  - Tapping a parked cable wakes it; on DX a woken harmonics cable steps through patches.
  - The rule is the same every time the duo arrives on DX: whatever is on harmonics parks.
  - A legacy LFO duo on DX converts with B→harmonics present but parked. That matches today, where leaving DX brings that modulation back.
  - The AI sees parked cables and can wake them.
- **Voice FM partner.** It follows the FM Structure (cross-quad) toggle as today. The toggle stays. The odd partner duos 4-5 get under cross-quad is today's behaviour; it is kept for exactness and noted as a separate bug.
- **Envelope.** An envelope cable moves `decay` in C++: the envelope and the hold scaling, as the knob does. It does not change SPK's word choice, which is morph's job. Patch morph for that.
- **Recorded takes.** A take stores the full cable list plus the master depth. The orpheus-recording-to-vibe skill lists the patch as something Pulsar can't reproduce, apart from the LFO cables that map onto Pulsar's own macros.

## Constraints

- Realtime-safe DSP: no allocation or locks on the audio thread. Routing reaches C++ through atomics or a lock-free handoff.
- Keep today's rates: audio-rate FM stays per sample; everything else stays per block.
- Patching saves with presets (`portValues`), with saved feature state, and with recorded takes (`TakeSnapshotMapper`). The C++ preset fixtures (`liborpheus_dsp/test/data/preset_*.h`) are regenerated from the JSON, never hand-edited (CLAUDE.md).
- Everything that reads `duo_mod_source` today moves to the bay or to a conversion:
  - AI agent tools and configs: `SynthControlTool`, `OrpheusAgentConfig`, the Solo and Drone configs, and `AiOptionsViewModel` randomisation.
  - `TidalScheduler`.
  - The MIDI source-cycle control. In v1 it retires, since MIDI is on the backburner.
  - `ModFaderSelector`, `ModSourceSelector`, `CompactDuoSettingsCard` and `CompactPortraitVoicePads`.
  - `test_control_routing.cpp`.
- Platforms: desktop JVM first, then Android, iOS and wasm. The bay follows the engine picker's popover and sheet split, including its key handling so typing never plays notes.

## Left to the builder

- How a patch is stored and sent to C++: a fixed amount matrix per duo, a cable list, or something else. It has to meet the constraints above, and the AI agent and presets must be able to read and write it.
- Each destination's full-scale range, so long as conversion is exact and the ranges feel musical. Pitch, for example, can reach well past today's ±0.5 semitone at 100%.
- How Voice FM behaves on destinations other than timbre and fm. Block RMS of the partner is the obvious choice.
- How Flux X, which runs −0.75..+3 rather than ±1, is scaled. Conversion must still be exact.
- Cable drawing, drag hit-testing and the amount-list layout, matching the mockups in `.superpowers/brainstorm/32756-1793239153/content/patch-bay-v3.html` and `patch-bay-v4.html` (local).

## Done when

- A C++ test renders every shipped preset's duos through the old and new routing and gets sample-identical output.
- Tests cover: parking on engine switch, both directions of the bridge switch, a cable patched on DX stepping patches, the AI tools patching and unpatching, and the bay's drag and focus behaviour.
- The desktop app shows the bay on all six duos and on the compact card. The LFO panel reads RATE A and RATE B.

## Rulings made during the build

- Storage: a fixed amount matrix per duo, `[duo][voice][source][dest]`, sent cell by cell through the patch plugin's ports. Full scales are 1 for harmonics, timbre, morph and env, 400 Hz for FM and 12 semitones for pitch.
- Per-cable math is `value * (depth * amount)`, the shape the release build already fused. The preset renders are byte-identical to the old routing.
- Flux X3, Y and T are read per sample on FM. They are new sources, so there is no legacy behaviour to match.
- Voice FM on block-rate destinations uses the duo partner's RMS. Panel duos never take the standalone Plaits path.
- An AI `connect` to harmonics on a DX duo is live, the same as a drop in the bay. Cables park only when a duo arrives on DX.
- A `duo_patch` string that fails to decode falls back to legacy conversion when `duo_mod_source` is present. Without it, the patch is empty.
- The preset sync is an app-scope startup root, so presets restore patches without the voice panel open.

## Known gaps

- A change to several cables can be heard half-applied for one audio block (about 3 ms), as the old source and level pair could.
- Saved MIDI mappings that targeted `duo_mod_source` no longer do anything.
- Cable amounts can't be MIDI learned yet.
