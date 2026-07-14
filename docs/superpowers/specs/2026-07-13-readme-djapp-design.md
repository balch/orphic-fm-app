# README Overhaul: Two-App Story + Orphic DJ Sections

**Date:** 2026-07-13
**Status:** Approved design, pending implementation plan

## Goal

Restructure the root `README.md` into a two-product story (Orpheus synth + Orphic DJ), add DJ app sections with fresh screenshots of both editions, explain the vibe catalog and the new Vibe Info pane, and relocate deep-dive content to `docs/`. The page must look sharp: teasers with hero shots up top, mermaid charts below each teaser, detail sections after.

## Decisions (user-approved)

| Decision | Choice |
|---|---|
| Structure | Everything in root README. Both apps get teasers (screenshots + brief description + feature list); charts/graphs below each teaser; DJ-from-Orpheus lineage explicit |
| DJ screenshots | Fresh captures, Android emulator, both editions (og + ai) |
| Orpheus screenshots | Reuse existing `docs/screenshots/*.webp` |
| Cleanup depth | Restructure + relocate deep dives to `docs/` (nothing deleted) |
| Charts | Mermaid (GitHub-native render) |
| DJ shot list | Curated 5 shots (S1-S5 below) |
| Pipeline | New worktree; parallel fan-out, 2 emulators, 3 agents/wave |
| AI feed shot | Staged fixture, worktree-only; seeding code dropped before merge |

## New README structure

```
# Orphic FM
Epigraphs (kept, tightened). One codebase, two instruments, one shared C++ DSP engine.

## Orpheus - 8-voice organismic synthesizer
  Hero row: existing desktop + android shots (reused)
  1-paragraph pitch (current Overview condensed)
  Tight feature list (Synthesis / Effects / Performance highlights merged)
  Chart 1: signal-path flowchart in mermaid (replaces ASCII art)

## Orphic DJ - generative pocket beat machine
  Hero row: S1 DJ-deck (og) + S5 AI feed (ai)
  1-paragraph pitch (mined from Play listing copy in apps/djapp/play-store/listing.md)
  Feature list: Pulsar generative engine, 9 vibes, 2 turntables + crossfade/FX,
                mixer + reverb, sleep timer, home-screen widget, AI edition
  "Built from Orpheus" callout: same C++ engine, same plugin modules
  Chart 2: og vs ai edition anatomy (tabs; AI sheet replaces Horn slot)

## One Engine, Two Apps
  Chart 3: lineage diagram - liborpheus_dsp + core/* + features/* fanning out to
  Orpheus (desktop/android/wasm/ios) and Orphic DJ (og/ai android, desktop -Pedition)

## Orphic DJ in detail          (all-new)
  - Layout: Pulsar always on screen; bottom nav swaps DJ / Mix / Horn-or-AI / Timer
  - Two editions: og (no INTERNET, Horn tab) vs ai (AI sheet, Koog agents)
  - Vibe Catalog: table of 9 LIVE vibes (name, BPM, key/scale, genre/feel) + S2 shot
  - Vibe Info pane: what the live sheet shows + S3 shot
  - AI edition: natural-language vibe creation, model switcher, unified feed + S5 shot
  - Chart 4: vibe lifecycle loop (catalog -> Pulsar -> AI-created vibe -> archived
    JSON -> vibe-codegen import -> back into catalog)

## Orpheus in detail
  Condensed current content: Synthesis, Effects, Performance & Control, AI Agent,
  Hand Tracking (3-line summary + link to docs/GESTURES.md)

## How It's Built
  Module layout, plugin architecture, event routing (kept, trimmed);
  platform table kept; WASM/iOS deep paragraphs relocated

## Build & Run
  Orpheus commands (kept) + Orphic DJ commands (installOgDebug/installAiDebug,
  desktop -Pedition=ai|og) sourced from docs/BUILD.md

## Dependencies
  Full table collapses into a <details> block

License footer (unchanged)
```

## Content rules

- **Trademark hygiene:** vibe descriptions in the catalog table describe feel only
  (e.g. Fire Sky = "stomping blues-rock riff in G blues"). Never name the source
  songs/artists that inspired trademark-hidden vibes.
- **Mutable Instruments naming:** Orpheus sections keep MI attribution (GPL credit,
  existing links). DJ app sections use Orpheus-native names only (Pulsar is original
  work; no MI module names needed).
- **Prose style:** minimal em-dashes; short sentences; feature lists stay scannable.
- Catalog table data (BPM, root, scale, genre) read from each LIVE vibe's
  `*Vibe.kt` under `features/pulsar/.../vibes/` per `VibeCatalog.kt` LIVE order:
  Bell Tolls, Dog House, Fire Sky, Filter Funk, Space & Drums, Techno Wobble,
  Velvet Leash, Voltage Strut, Lost In Space.

## Shot list

All portrait, emulator, demo-mode status bar (clean clock, full battery, no
notifications), 1080-wide, converted to webp in `docs/screenshots/djapp/`.

| ID | Edition | Build | Screen | Staging |
|---|---|---|---|---|
| S1 | og | ogDebugRelease | DJ tab hero: Pulsar top + turntables bottom | Dog House playing, viz active |
| S2 | og | ogDebugRelease | VIBE dropdown open showing the 9 LIVE vibes | debugRelease required: debug builds leak WIP/SHELF vibes |
| S3 | og | ogDebugRelease | Vibe Info sheet (tap "Orphic DJ" title) | Playing so section strip highlights + track dots glow |
| S4 | og | ogDebugRelease | Mix tab: reverb + 8-track mixer | Playing |
| S5 | ai | aiDebugRelease + fixture | AI sheet: unified feed (request row, thinking, tool rows, reply) with model selector visible | Staged fixture (below) |

**S5 fixture:** worktree-only temporary seeding of `DjAiFeedItem` state
(Request -> Thinking -> Tool "Vibe Schema" done -> Tool "Apply Vibe" done -> Reply),
content mirroring a real archived run (check `~/.config/orpheus-dj/ai-vibes/` for
authentic prompt/reply text; else write plausible copy). Key indicator chip shows
the "key set" state; no secret appears anywhere. The fixture is an uncommitted
working-tree patch in the worktree: applied, built, captured, reverted. It is
never committed.

**Status-bar demo mode:**
`adb shell settings put global sysui_demo_allowed 1` then
`am broadcast -a com.android.systemui.demo -e command enter`,
clock 1000, battery level 100 plugged false, network wifi level 4,
notifications visible false. Exit with `-e command exit` after capture.

## Charts spec (mermaid)

1. **Orpheus signal path** (flowchart LR): 8 voices -> per-voice pan -> dry bus ->
   parallel clean/distortion -> dual delays + plate reverb (parallel send) ->
   stereo sum -> master out.
2. **Orphic DJ anatomy** (flowchart TB or two subgraphs): always-on Pulsar region +
   nav destinations; og subgraph shows Horn, ai subgraph shows AI sheet replacing
   Horn slot + Vibe Info sheet reachable from title in both.
3. **Lineage** (flowchart TB): liborpheus_dsp (C++) + core/* + features/* ->
   apps/orpheus (Desktop, Android, WASM/orphic.fm, iOS) and apps/djapp
   (og Android, ai Android, desktop -Pedition). One shared-engine trunk, two crowns.
4. **Vibe lifecycle** (flowchart LR, cyclic): VibeCatalog (9 LIVE) -> Pulsar engine
   -> [ai edition] prompt -> agent -> new Vibe applied + archived JSON ->
   vibe-codegen import -> new *Vibe.kt -> back into VibeCatalog.

Mermaid syntax must be GitHub-flavored (no unsupported plugins); validated before
merge by viewing the pushed branch's rendered README.

## Relocation map

| Content now in README | Destination |
|---|---|
| Hand-tracking classifier internals + fusion detail | docs/GESTURES.md (merge; keep 3-line summary + link) |
| MediaPipe dylib rebuild guide (the `<details>` block) | docs/BUILD.md (new "Rebuilding the hand-tracking dylib" section) |
| WASM worker + iOS cinterop deep paragraphs | docs/BUILD.md (platform sections already exist; merge, dedupe) |
| Dependency table | stays in README inside `<details>` |

Rules: no content deleted, only moved; every move leaves a link; check inbound
anchors/links to README sections that move (grep docs/ for `README.md#`).

## Pipeline

Worktree `readme-djapp` off `main`. Squash-merge into main at the end
(user's standard workflow).

- **Wave 0 (main agent):** create worktree; build `assembleOgDebugRelease`;
  verify AVDs exist (`emulator -list-avds`), boot two (fallback: serialize B
  after A on one AVD); prepare `docs/screenshots/djapp/`.
- **Wave 1 (3 agents, parallel):**
  - **A - og captures** (emulator A): install og APK, demo mode, stage and capture
    S1-S4, pull PNGs into worktree `docs/screenshots/djapp/raw/`.
  - **B - ai captures** (emulator B): apply S5 fixture patch in worktree, build
    `assembleAiDebugRelease` (its own build; wave 0 does not build ai), install,
    demo mode, capture S5, pull PNG, revert fixture patch.
  - **C - charts + prose** (no emulator): 4 mermaid charts, restructured README,
    relocation edits to docs/GESTURES.md + docs/BUILD.md, DJ detail copy.
    Does NOT touch screenshot paths' existence assumptions: uses agreed filenames.
- **Wave 2 (1 agent):** process raws (no cropping; demo-mode status bar stays;
  normalize to consistent width, PNG->webp),
  place final assets, wire image tags into README, alignment/polish pass.
- **Wave 3:** spec-compliance review vs this doc; copy-quality review; push branch;
  view GitHub-rendered README in browser (mermaid + images + links) before merge.

Asset filenames (agreed contract between agents A/B and C):
`docs/screenshots/djapp/dj-deck-og.webp`, `vibe-catalog-og.webp`,
`vibe-info-og.webp`, `mixer-og.webp`, `ai-feed-ai.webp`.

## Verification

- All image paths referenced by README exist in the worktree (case-sensitive).
- Mermaid blocks render on GitHub (checked on pushed branch, not just locally).
- Relocated content: GESTURES.md/BUILD.md diffs reviewed; no orphaned links;
  no content lost (old README sections accounted for in the relocation map).
- Catalog table numbers (BPM/key/scale) match the vibe source files.
- No trademark-source names anywhere in new copy; no MI module names in DJ sections.
- Final visual pass by user on the rendered branch README before squash-merge.

## Out of scope

- Refreshing Orpheus screenshots (follow-up if desired).
- A separate apps/djapp/README.md (user chose root-README approach).
- Play listing changes; store assets untouched.
- Landscape/tablet captures; home-screen widget shot (not in curated 5).
