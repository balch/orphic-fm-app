# Engine picker redesign

Date: 2026-10-28. Status: design, not built.

## Why

The radial ring picker (`EnginePickerButton` / `VoiceEnginePickerPopup` in `ui/widgets`) is hard to use. Reaching an engine on the fourth ring takes four press-and-drag gestures, and nothing on screen says where an engine lives. The mobile `CompactEnginePicker` was started as a fix but keeps its own hand-written engine list, which drifts from `OrpheusEngineId`. DX patches, the sounds people actually reach for ("E piano 1", "Brass 1"), are hidden behind a harmonics knob that steps through 32 unnamed zones.

The user wants one picker, used everywhere, that finds any engine or DX patch by name, category or search in one click.

## Goals

1. One picker component replaces every engine picker in the apps:
   - Orpheus Duo panels (`features/voice/.../DuoVoiceBox.kt`), six duos.
   - Orpheus Drums panel (`features/drum/.../DrumsPanel.kt`), three slots.
   - Pulsar panel (`features/pulsar/.../PulsarPanel.kt`), its two pickers.
   - DJ app mixer engine rows, EDM and SPACE (`apps/djapp/ai/.../mixer/VoiceKnobs.kt`, `EngineRow`).
   - Orpheus mobile compact duo settings (`apps/orpheus/shared/.../compact/CompactDuoSettingsCard.kt`, `CompactEnginePicker.kt`).
2. One click to open, one click to pick.
3. DX patches are first-class entries. Picking "E piano 1" sets the engine to DX2 and the patch in one action.
4. On a DX engine the harmonics knob is locked: it shows the patch name and cannot be turned. The picker owns patch choice.
5. Knob labels follow the engine on the Duo panel, using the mixer's per-engine meaning table, made correct for both signal paths.
6. On the Orpheus panel path, a DX duo's LFO/Tides channel 2 no longer moves harmonics (it would hop patches several times a cycle).

## Non-goals

- Assignable LFO channel routing (per engine type, overridable per duo). This is the next project. This one only adds the per-engine default hook that silences ch2 on DX (see "LFO ch2 on DX").
- Relabeling knobs on the Drums panel. Drums get the new picker only.
- Auditioning engines from the picker (hover or long-press preview).
- Changing what any engine sounds like, apart from the DX ch2 change above.
- Pulsar OSC morph semantics. That change is already merged into this branch (`f9145add2`): Pulsar OSC morph is now a duo-style detune, the same as the panel, and FM depth has its own `fmDepth` field. The label table treats OSC morph as "detune" on both paths.

## Anti-goals

- Any engine reachable only by a gesture the user has to learn (drag rings, long-press pages).
- A second hand-maintained engine list. Every engine list derives from `OrpheusEngineId` plus one catalog.
- A picker that steals synth keyboard input while closed, or lets typed search text trigger notes while open.

## The picker

### Trigger

A pill replaces the circle everywhere: a category-colour dot, the engine code, and on DX the patch name, then a caret. Examples: `VA · Virtual Analog ▾`, `DX2 · E piano 1 ▾`. One click or tap opens the picker. The pill keeps the existing affordances the circle had: the AI highlight (`showExternalSelection`) and the glow (`glowEnergy`).

### Wide screens (desktop, tablet, TV): tile popover (mockup "A")

- Search field on top, focused on open.
- One row of category chips under it. The row scrolls sideways and fades at the right edge; it never wraps.
- Tiles below, grouped under section headers. A tile shows the code (or the patch name for DX) and a one-line description (or `DX2 · 1` for a patch). The current pick is highlighted.
- Keyboard: typing goes to search, arrows move between tiles, Enter picks, Esc closes. While the popover is open, keys never reach the synth key handlers.
- TV: tiles and chips are D-pad focusable.

### Phones: bottom sheet list

Same search, same chip row and same data, laid out as a list of tall rows in a bottom sheet. The current pick shows a check.

Which layout shows is decided by available width, not by platform.

### Chips (categories)

`★ Recent`, `All`, then the categories below. Every slot opens on the chip of its current engine's category, except drum slots, which always open on `Drums`.

Engine categories (builder's call to adjust, user approved the set):

| Category | Engines |
|---|---|
| Analog | OSC, VA, VCF, WSH, PD |
| Harmonic | FM, ADD, WTB, TRN, CHD, ENS, NES |
| Texture | GRN, NSE, PAR, SWM, MST |
| Physical | STR, MOD |
| Voice | SPK, VOWEL_FOF |
| Braids | TRIPLE_SAW, TRIPLE_SQUARE, TRIPLE_TRIANGLE, TRIPLE_SINE, TRIPLE_RING_MOD, CSAW, TOY, QUESTION_MARK |
| Chaos | LRZ, ROS, DUF, HEN, CHU |
| Drums | BD, SD, HH, FM_DRUM |
| DX Patches | 96 patches, below |

DX, DX2 and DX3 appear only as patches, never as bare engine tiles.

DX patch families, shown as section headers under the DX Patches chip (my call, from the bank names in `FmPatchNames` and `.claude/skills/vibe-creator/references/fm_patches.md`):

| Bank | Family | Patch indices |
|---|---|---|
| DX | Bass | 0-10, 19 |
| DX | Synth | 11-18, 20-31 |
| DX2 | Keys | 0-10 |
| DX2 | Plucked | 11-15 |
| DX2 | Mallets & Bells | 16-23 |
| DX2 | Drums | 24-31 |
| DX3 | Organ | 0-7 |
| DX3 | Pads | 8-22 |
| DX3 | Strings | 23-28 |
| DX3 | Brass | 29-31 |

### Search

Case-insensitive substring match over engine code, display name, category, DX patch name and DX family. Search spans every category regardless of the selected chip. Results group under the same headers.

### Recent

The last 8 distinct picks (engine, or engine plus patch for DX), most recent first, shared across all pickers in an app and persisted across launches.

### Which engines each slot offers

| Slot | Offers |
|---|---|
| Orpheus Duo, compact duo | every engine except `isPulsarOnly` and the Drums category (as today's rings) |
| Orpheus Drums | the engines the drum slots can play (C++ maps drum ordinals 0-24 only, so no Braids, Chaos or OSC); opens on Drums |
| Pulsar panel, DJ mixer | every engine |

On builds without the native DSP, `isNativeOnly` engines are hidden, as the current `hasNativeEngine` gating does.

`FM_DRUM` (my call): hidden from every picker. On Duo and Pulsar it is id 21, an alias of BD, so it can never display as itself. On drum slots ordinal 3 maps to the FM engine, which the picker already offers as FM. A drum slot already holding ordinal 3 shows its pill as `FM`. The catalog test exempts it.

The call sites store engines differently: Duo and Pulsar panel by C++ id, Drums by enum ordinal, the DJ mixer by `OrpheusEngineId`. The picker speaks `OrpheusEngineId` (plus patch index); each call site converts at its edge.

## DX: picking a patch and locking harmonics

- Picking patch N on bank B sets the engine to B and harmonics to the bucket centre `(N + 0.5) / 32.64` (the same value `EngineKnob.snapped()` writes today). No new parameter.
- On DX engines the harmonics knob is locked everywhere it appears (Duo `≡` knob, the compact duo knob in `CompactPortraitVoicePads.kt`, the Drums MOD knob, which drives harmonics on drum slots, and the DJ mixer harmonics knob): it shows a lock and the patch name, and drag does nothing. Clicking it opens the picker on the DX Patches chip. DX patches stay in the drum picker (DX2 has Kettle, Latin Drum and friends); a drum pick writes MOD to the bucket centre (my call).
- Switching from DX to another engine leaves harmonics where it was; the knob unlocks.
- Lock scope (user's ruling, option "a"): the lock covers the on-screen knob and MIDI. A MIDI CC learned to harmonics is ignored while that slot is on DX. Presets, the Orpheus AI agents, the speech tool and the DJ app's vibe tools (`pulsar_edit_vibe`, `mix_set`, `VibeEditTool`) keep writing raw harmonics as they do today. No new AI tool, no schema change.

### Patch walks (Pulsar)

Pulsar DX tracks can walk around their patch: `harmonicsMacroRange` (a user macro moves the patch ± the range) and `harmonicsModulation` (an LFO does). Both are relative to the pinned base patch. Six LIVE vibes use them on DX tracks (Bell Tolls, Dog House, Velvet Leash, Techno Wobble, Voltage Strut, Lost in Space), so the walk stays (user's ruling). Picking a patch moves the base; the walk keeps its width around the new patch. Neither walk field changes.

### What the locked knob and pill show

The patch sounding now, not only the patch picked (user's ruling). On the Orpheus panel this is the picked patch, since ch2 no longer moves DX harmonics. On Pulsar tracks with a walk it follows the walk, so the engine must report each track's effective harmonics (after macro, LFO, tension and solo) to the UI, the way it already reports other per-track viz. The pill shows the picked patch; the locked knob shows the sounding one, with the picked one in its long label when they differ.

## LFO ch2 on DX (Orpheus panel path)

Today `liborpheus_dsp/src/orpheus_unit_plaits.cpp` (~536-546) adds LFO ch2 (or Tides ch2) to harmonics when a duo's mod source is LFO or Tides, scaled by FM depth. On DX this hops patches.

Change: add a per-engine default for the ch2 target, and set it to "none" for DX, DX2 and DX3 (harmonics for every other engine, as today). Ch0 still moves timbre, ch1 morph, ch3 pitch. The default lives where the next project can make it assignable per engine type and per duo. Pulsar already pins DX harmonics (`forcePinHarmonics`) and needs no change.

## Knob labels

### The table

Move `knobMeaning()` and its `MEANINGS` table from `features/pulsar/.../bench/EngineKnobs.kt` into `core/audio`, next to `OrpheusEngineId`, so the Duo panel can use it. The lookup takes the signal path: `knobMeaning(id, knob, path)` with `path` = `PULSAR` or `PANEL`. One base row per engine; panel overrides only where the panel differs.

Fixes from the 2026-10-28 audit (against MI sources and the C++ on main):

| Engine | Knob | Now | Becomes |
|---|---|---|---|
| OSC | harmonics | harm / harmonics | fdbk / feedback |
| OSC | timbre | timbre | tri/sq / triangle to square |
| OSC | morph, both paths | fm / FM depth | detune / detune, ±25 cents |
| SPK | morph, PANEL | word | inactive: the Duo panel sends each voice's env speed as morph (`DspSynthEngine.kt` ~229-235) |
| ENS | harmonics | chord / registration | chord / chord |
| ENS | morph | wave / waveform | reg / registration |
| TRIPLE_* | harmonics | intvl / chord interval | intvl / 2nd osc interval |
| TRIPLE_* | morph | detune | intvl2 / 3rd osc interval |
| MST | any, PANEL | bank / pos / grain | not playable on the panel; lookup returns null |

Not verified: VOWEL_FOF's vowel / voice-type axis order. Leave as is.

### On the Duo panel

The Duo knobs map `≡` to harmonics, `♯` (sharpness) to timbre and `ψ` to morph. Each knob shows the engine's short label under it, with the old glyph as a small caption. The long label shows while the knob is held. Inactive knobs show `-` and dim. The DJ mixer keeps using the same table with `path = PULSAR`.

## Constraints

- Primary platform is JVM desktop. Android, iOS and wasm must compile and work; the layout switch is by width.
- Kotlin Multiplatform, Compose. Shared UI lives in `ui/widgets`; it must not depend on `features/pulsar`. `FmPatchNames` moves to `core/audio` (or another module `ui/widgets` already depends on) along with the label table.
- `OrpheusEngineId` declaration order and serial names stay as they are (save data).
- `FM_DRUM` aliases BD in C++ (id 21); the catalog shows it in Drums with BD's labels.
- Follow the repo's Compose rules (`compose-ui-maestro` agent): previews for new components, theme colours from `OrpheusTheme`, keyboard focus that never triggers synth keys.

## What goes away

- The radial ring: `VoiceEnginePickerPopup`, `PickerConfig`, the `VOICE_PICKER_*` and `DRUM_*_PICKER_CONFIG` rings, `computePickerSegment`, `pickerSegmentToOrdinal`.
- `CompactEnginePicker`'s hand list (`ENGINE_GROUPS`).
- `engineLabel()` / `drumEngineLabel()` in favour of the catalog.

## Tests

- Catalog: every `OrpheusEngineId` is in exactly one category (DX ids via patches); every DX patch resolves back to its own index through `FmPatchNames.patchNameFor`.
- Labels: every id has a row; pinned cases for each audit fix and for the OSC and SPK panel overrides.
- Slot filtering: Duo and Drums never offer `isPulsarOnly`; non-native builds hide `isNativeOnly`.
- Search and Recent: matching rules above; Recent dedupes and caps at 8.
- C++: on DX, LFO and Tides ch2 leave harmonics untouched; other engines unchanged (bit-identical render).
- UI: targeted Compose tests with `--tests` only, per CLAUDE.md "Which Tests When".

## Left to the builder

- Exact tile, row and pill sizes; the width breakpoint; animation.
- Category colours (reuse the existing ring colours where they fit).
- Where Recent is persisted (use whatever settings store each app already has).
- How the pill fits the Duo centre overlay (truncate the patch name, or put it under the code).
- Order of work across the five call sites.
