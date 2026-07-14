# README Two-App Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the root README into a two-product story (Orpheus + Orphic DJ) with fresh emulator screenshots of both DJ editions, 4 mermaid charts, catalog + Vibe Info + AI feed sections, and deep-dive content relocated to docs/.

**Architecture:** All work lands in worktree `readme-djapp` (created via superpowers:using-git-worktrees). Screenshots are captured on two Android emulators from `debugRelease` builds installed out of the main checkout (code is identical; the worktree only ever receives docs/asset commits). Raw PNGs go to the session scratchpad; only processed webp files enter the worktree.

**Tech Stack:** adb + Android emulator (Pixel_9a, Pixel_6a), ImageMagick (`magick`), GitHub-flavored markdown + mermaid, Gradle (`assembleOgDebugRelease`, `assembleAiDebugRelease`).

**Spec:** `docs/superpowers/specs/2026-07-13-readme-djapp-design.md` (read it before any task).

## Global Constraints

- **Builds:** screenshots MUST come from `debugRelease` builds (debug leaks WIP/SHELF vibes into the catalog; release curates to the 9 LIVE vibes).
- **Asset filename contract** (agents A/B/C all rely on it): `docs/screenshots/djapp/dj-deck-og.webp`, `vibe-catalog-og.webp`, `vibe-info-og.webp`, `mixer-og.webp`, `ai-feed-ai.webp`.
- **Raw PNG handoff dir** (never committed): `/private/tmp/claude-501/-Users-balch-Source-orphic-fm-app/fd1cf7a6-db23-4843-8d91-6331d4568195/scratchpad/assets/` (`raw/` for captures, `env.md` for the environment handoff).
- **Trademark hygiene:** vibe descriptions state feel only; NEVER name inspiration songs/artists (Fire Sky, Rust Belt, Black Cat etc. have hidden sources).
- **MI naming:** Orpheus sections keep Mutable Instruments attribution as today; DJ sections use only Orpheus-native names (Pulsar, vibes).
- **Prose:** minimal em-dashes; short sentences; scannable lists.
- **S5 is a real run:** ai build has the API key baked in; model = the entry labeled "Flash 3.5" in the model selector; prompt = `A happy birthday party vibe`. No secrets appear in the UI.
- **Parallelism:** max 3 subagents at once. Task 4 conflicts with nothing; Tasks 2/3 depend on Task 1; Task 5 depends on 2+3+4; Task 6 last.
- **No new dependencies, no app-code changes.** If a task seems to need an app-code change, stop and report instead.
- Worktree path below is `$WT` = `/Users/balch/Source/worktree-readme-djapp` (created at execution start via the using-git-worktrees skill; if the skill materializes a different path, substitute it everywhere).

---

### Task 1: Environment bring-up (APKs + two emulators)

**Files:**
- Create: `<scratchpad>/assets/env.md` (handoff), `<scratchpad>/assets/raw/` (dir)
- No repo files.

**Interfaces:**
- Produces: two booted emulators with demo-mode status bars and both apps installed; `env.md` mapping `OG_SERIAL=emulator-XXXX` (Pixel_9a) and `AI_SERIAL=emulator-YYYY` (Pixel_6a) plus APK paths.

- [ ] **Step 1: Build both APKs from the main checkout**

```bash
cd /Users/balch/Source/orphic-fm-app
./gradlew :apps:djapp:androidApp:assembleOgDebugRelease :apps:djapp:androidApp:assembleAiDebugRelease
ls apps/djapp/androidApp/build/outputs/apk/og/debugRelease/*.apk \
   apps/djapp/androidApp/build/outputs/apk/ai/debugRelease/*.apk
```
Expected: both `ls` lines print one APK each (AGP names them like `androidApp-og-debugRelease.apk`). Record exact paths.

- [ ] **Step 2: Boot both emulators (background), wait for boot**

```bash
~/Library/Android/sdk/emulator/emulator -avd Pixel_9a -no-snapshot-save -no-boot-anim &
sleep 5
~/Library/Android/sdk/emulator/emulator -avd Pixel_6a -no-snapshot-save -no-boot-anim &
```
Poll `adb devices` until TWO `emulator-*` serials are listed (do not use bare `adb wait-for-device`; it errors with multiple devices). Then for each serial: `adb -s <serial> shell getprop sys.boot_completed` until it prints `1` (poll every 10s, up to 3 min). Map serial→AVD with `adb -s <serial> emu avd name`. Pixel_9a = og device, Pixel_6a = ai device.

- [ ] **Step 3: Install APKs**

```bash
adb -s $OG_SERIAL install -r apps/djapp/androidApp/build/outputs/apk/og/debugRelease/*.apk
adb -s $AI_SERIAL install -r apps/djapp/androidApp/build/outputs/apk/ai/debugRelease/*.apk
```
Expected: `Success` twice. Verify: `adb -s $OG_SERIAL shell pm list packages | grep org.balch.djapp` shows `org.balch.djapp` (og device) and the ai device shows `org.balch.djapp.ai`.

- [ ] **Step 4: Demo-mode status bar on BOTH devices**

```bash
for S in $OG_SERIAL $AI_SERIAL; do
  adb -s $S shell settings put global sysui_demo_allowed 1
  adb -s $S shell am broadcast -a com.android.systemui.demo -e command enter
  adb -s $S shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1000
  adb -s $S shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
  adb -s $S shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
  adb -s $S shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
done
```
Verify with one screencap per device: clock reads 10:00, battery full.

- [ ] **Step 5: Write the handoff file**

Write `<scratchpad>/assets/env.md` containing: `OG_SERIAL`, `AI_SERIAL`, both APK paths, screen sizes (`adb -s <serial> shell wm size`; expect 1080x2424 and 1080x2400). Create `<scratchpad>/assets/raw/`.

- [ ] **Step 6: Verify deliverable**

`adb devices` shows both serials as `device`; both packages installed; `env.md` exists and is accurate. No commit (nothing in repo changed).

---

### Task 2: og captures S1-S4 (Agent A, og emulator)

**Files:**
- Create: `<scratchpad>/assets/raw/dj-deck-og.png`, `vibe-catalog-og.png`, `vibe-info-og.png`, `mixer-og.png`, and `<scratchpad>/assets/raw/capture-log-og.md`

**Interfaces:**
- Consumes: `env.md` (`OG_SERIAL`).
- Produces: the 4 raw PNGs above, each visually verified (agent must Read each PNG and confirm content before finishing).

Capture command (always): `adb -s $OG_SERIAL exec-out screencap -p > <file>.png`

UI driving is iterative: screencap, Read the image, find the control, `adb shell input tap X Y`, screencap again to confirm. Never fire taps blind. Reference map (portrait): Pulsar panel = top region with VIBE / ROOT / SCALE dropdowns in its header; bottom nav = DJ, Mix, [Play/Pause center], Horn, Timer; app title "Orphic DJ" = top-left, tappable.

- [ ] **Step 1: Launch og app**

```bash
adb -s $OG_SERIAL shell monkey -p org.balch.djapp -c android.intent.category.LAUNCHER 1
```
Screencap; confirm DJ tab is showing (two turntables below Pulsar). Dismiss any first-run dialog if present.

- [ ] **Step 2: Select Dog House + start playback (staging for S1/S3/S4)**

Tap the VIBE dropdown in the Pulsar header, tap "Dog House". Tap the center Play/Pause in the bottom nav. Verify playing: take two screencaps ~2s apart; the Pulsar viz / step highlight must differ between them. Let it play ~20s so the arrangement advances past the intro.

- [ ] **Step 3: Capture S1 `dj-deck-og.png`**

On the DJ tab while playing. Read the PNG: Pulsar on top (Dog House shown in VIBE dropdown), turntables + crossfade below, demo status bar. Re-shoot if any dialog/toast is visible.

- [ ] **Step 4: Capture S2 `vibe-catalog-og.png`**

Tap the VIBE dropdown so the list is OPEN. Read the PNG: exactly these 9 entries visible (scroll state permitting): Bell Tolls, Dog House, Fire Sky, Filter Funk, Space & Drums, Techno Wobble, Velvet Leash, Voltage Strut, Lost In Space. If WIP names (e.g. Rust Belt, Black Cat, Vanished Skyline) appear, STOP: the build is not debugRelease; report instead of shooting. Close the dropdown after.

- [ ] **Step 5: Capture S3 `vibe-info-og.png`**

While playing, tap the "Orphic DJ" title (top-left). The Vibe Info sheet slides up. Wait ~5s for track activity. Read the PNG: vibe name + BPM/Key/Scale line, section strip with one section highlighted, 8 track rows with instrument names, at least 2 activity dots glowing, reverb/delay pills. If dots are all dark, wait 10s and re-shoot. Close the sheet (swipe down or back).

- [ ] **Step 6: Capture S4 `mixer-og.png`**

Tap "Mix" in the bottom nav. Read the PNG: reverb panel + 8-track mixer visible, still playing.

- [ ] **Step 7: Write capture log + verify deliverable**

`capture-log-og.md`: serial, screen size, what each shot shows, any retakes. Verify all 4 PNGs exist, each >100KB, width 1080 (`magick identify` or the Read tool). No repo changes, no commit.

---

### Task 3: ai capture S5 (Agent B, ai emulator)

**Files:**
- Create: `<scratchpad>/assets/raw/ai-feed-ai.png`, `<scratchpad>/assets/raw/capture-log-ai.md`

**Interfaces:**
- Consumes: `env.md` (`AI_SERIAL`).
- Produces: 1 raw PNG of the completed AI feed, visually verified.

- [ ] **Step 1: Launch ai app**

```bash
adb -s $AI_SERIAL shell monkey -p org.balch.djapp.ai -c android.intent.category.LAUNCHER 1
```
Screencap; confirm bottom nav shows an AI item where og has Horn.

- [ ] **Step 2: Open the AI sheet and set the model**

Tap "AI" in the bottom nav; the sheet slides up (~66% height). Find the model selector in the config strip; tap it; select the entry labeled "Flash 3.5". Screencap to confirm the selector now reads Flash 3.5. If a key-entry field is showing instead of a ready state, STOP and report (key should be baked in; the user is monitoring and will assist).

- [ ] **Step 3: Send the prompt**

Tap the prompt field ("Describe a vibe…"), then:
```bash
adb -s $AI_SERIAL shell input text 'A%shappy%sbirthday%sparty%svibe'
```
Screencap to confirm the text reads "A happy birthday party vibe", then tap the send control.

- [ ] **Step 4: Wait for the run to complete**

Poll a screencap every 15s (up to 4 min). Running state = WorkingStatusCard ("Composing your vibe…"). Complete = feed shows the ✦ reply row and the working card is gone. If an error banner appears, retry the send once; if it fails again, STOP and report (user is monitoring).

- [ ] **Step 5: Capture S5 `ai-feed-ai.png`**

Scroll the feed so the request row (`❯ A happy birthday party vibe`) is at the top and reply is visible; model selector must be in frame. Read the PNG: request row, at least one tool row (✓), reply row, model shows Flash 3.5, Pulsar visible behind/above the sheet. Re-shoot until all present.

- [ ] **Step 6: Write capture log + verify deliverable**

`capture-log-ai.md`: serial, model used, prompt, run duration, retakes. PNG exists, >100KB, width 1080. No repo changes, no commit.

---

### Task 4: README restructure + charts + relocations (Agent C, no emulator)

**Files:**
- Modify: `$WT/README.md` (full restructure)
- Modify: `$WT/docs/GESTURES.md` (receive hand-tracking internals)
- Modify: `$WT/docs/BUILD.md` (receive dylib rebuild guide + WASM/iOS deep paragraphs)
- Read-only inputs: spec §"New README structure" + §"Charts spec" + §"Content rules"; `apps/djapp/play-store/listing.md`; `features/pulsar/.../vibes/VibeCatalog.kt` + the 9 LIVE `*Vibe.kt` files; current `README.md`.

**Interfaces:**
- Consumes: asset filename contract (images do NOT exist yet; reference them anyway).
- Produces: final README text with `docs/screenshots/djapp/*.webp` image refs; relocated docs.

- [ ] **Step 1: Build the catalog table data**

Read `VibeCatalog.kt` for LIVE order, then each LIVE vibe file; extract name, bpm, rootNote, scaleType, genre. Draft one feel line per vibe (trademark-safe, no source songs).

- [ ] **Step 2: Restructure README.md**

Follow spec §"New README structure" exactly: epigraphs + one-line two-app framing; Orpheus teaser (existing shots, condensed pitch, merged feature list, mermaid signal path); Orphic DJ teaser (S1 + S5 image refs, pitch mined from listing.md, feature list, "Built from Orpheus" callout, og-vs-ai anatomy chart); "One Engine, Two Apps" lineage chart; "Orphic DJ in detail" (layout, editions, catalog table + S2 ref, Vibe Info + S3 ref, AI edition + S5 ref, vibe-lifecycle chart, S4 in the layout/editions prose); "Orpheus in detail"; How It's Built; Build & Run (add DJ commands from BUILD.md lines 51-62); Dependencies in `<details>`; license footer.

- [ ] **Step 3: Write the 4 mermaid charts**

Per spec §"Charts spec". GitHub-flavored mermaid only (flowchart syntax, no plugins, no `%%{init}` themes). Keep node labels short so mobile rendering doesn't wrap badly.

- [ ] **Step 4: Relocate content**

Move hand-tracking classifier/fusion detail into GESTURES.md; move the dylib rebuild `<details>` block and the WASM/iOS deep paragraphs into BUILD.md (merge with existing platform sections, dedupe). README keeps 3-line summaries + links. Nothing deleted: every old README section must be accounted for (checklist in commit message).

- [ ] **Step 5: Verify**

- Every relative link + image ref in the new README resolves (`ls` each; the 5 djapp webp files are the ONLY allowed missing files at this stage; list them in the commit message).
- Mermaid: if `npx -y -p @mermaid-js/mermaid-cli mmdc --version` works offline, render each block to a scratch SVG (`mmdc -i block.mmd -o /tmp/out.svg`); otherwise defer render check to Task 6 and say so.
- Grep repo for inbound `README.md#` anchors; fix any that broke.
- Catalog table values match the vibe source files (re-check two at random).

- [ ] **Step 6: Commit (in $WT)**

```bash
git add README.md docs/GESTURES.md docs/BUILD.md
git commit -m "docs: two-app README restructure with mermaid charts + relocations"
```

---

### Task 5: Asset processing + wiring (after 2+3+4)

**Files:**
- Create: `$WT/docs/screenshots/djapp/{dj-deck-og,vibe-catalog-og,vibe-info-og,mixer-og,ai-feed-ai}.webp`

**Interfaces:**
- Consumes: raw PNGs from Tasks 2/3; README image refs from Task 4.

- [ ] **Step 1: Convert**

```bash
mkdir -p $WT/docs/screenshots/djapp
for f in dj-deck-og vibe-catalog-og vibe-info-og mixer-og ai-feed-ai; do
  magick <scratchpad>/assets/raw/$f.png -resize 1080x -quality 85 $WT/docs/screenshots/djapp/$f.webp
done
```

- [ ] **Step 2: Verify**

All 5 webp exist; `magick identify` shows width 1080; each <500KB (if larger, re-encode at `-quality 75`); Read 2 of them to spot-check visual quality (no banding on the dark UI). Every README image ref now resolves: extract all `docs/screenshots/` paths from README and `ls` each.

- [ ] **Step 3: Commit (in $WT)**

```bash
git add docs/screenshots/djapp
git commit -m "docs: Orphic DJ screenshots (og + ai editions)"
```

---

### Task 6: Render verification + final review + merge prep

**Files:** none new (fixes only, in `$WT`).

- [ ] **Step 1: Push the branch**

```bash
cd $WT && git push -u origin readme-djapp
```

- [ ] **Step 2: GitHub render check**

Open the branch README on GitHub (`git remote get-url origin` for the repo URL, then `/blob/readme-djapp/README.md`) in the Browser pane. Verify: all 4 mermaid charts render (not code blocks or error boxes), all images display, `<details>` blocks collapse, tables aligned. Fix + push until clean.

- [ ] **Step 3: End-of-plan quality review**

Dispatch a copy/quality review subagent (per user cadence: end-of-plan): checks spec compliance overall, prose style rules, trademark + MI naming rules, link integrity, relocation completeness (old README fully accounted for). Fix findings.

- [ ] **Step 4: User sign-off, then squash-merge**

Show the user the rendered branch URL for the visual pass ("minor tweaks once I see it"). After approval: squash-merge into main per the user's standard workflow, push from terminal, clean up worktree + emulators (`adb -s <serial> emu kill`).

---

## Self-Review Notes

- Spec coverage: structure→T4, shots→T2/T3, charts→T4, relocations→T4, processing→T5, verification→T6, pipeline waves→dependency notes in Global Constraints. Env bring-up→T1.
- No placeholders; all commands concrete; filename contract stated once, used everywhere.
- Cross-task names consistent: serials + APK paths flow via `env.md`; image names identical in T2/T3/T4/T5.
