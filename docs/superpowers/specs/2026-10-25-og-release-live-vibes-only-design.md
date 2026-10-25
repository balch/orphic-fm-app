# OG release builds ship only LIVE vibes

Status: built 2026-10-25 on `claude/og-live-vibes-only`. Not yet installed from an OG release build. Dated
snapshot, 2026-10-25. Once this is built, the code is the source of truth.

## Intent

Many non-LIVE vibes are close studies of copyrighted songs: the `*Og` backups, Corner Office,
Black Cat and others. Today an OG release hides them at runtime through `VibeCatalog`, but every
vibe class, melodies included, is still compiled into the shipped binary. That's because Metro
registers every `VibeProvider`, so R8 and Kotlin/Native dead-code elimination see each class as
reachable. The user wants these vibes absent from the released OG binaries, not just hidden.

## Behaviour (decided with the user)

- **One Gradle switch, `-Porphic.liveVibesOnly=true`.** When it's on, `features:pulsar` reads
  `VibeCatalog.kt` and removes from its `commonMain` source set every vibe file whose vibes are
  all non-LIVE (WIP or SHELF).
  - Promoting a vibe stays a one-word catalog edit. No files move.
- **Scope:** the OG edition's release builds on Android (Play: the `og` flavor's release build
  type) and iOS (App Store: the `DjApp` scheme, built from `:apps:djapp:shared`'s release
  framework).
  - The AI edition, debug builds, `debugRelease`, desktop and wasm are unchanged and keep every
    vibe (non-LIVE ones stay hidden at runtime, as now).
- **Guard 1, every build** (a jvmTest in `features:pulsar`):
  - No vibe file may mix LIVE and non-LIVE vibes.
  - No source outside the removable set may name a class or top-level declaration from that set.
    Comments don't count. Tests are allowed to reference them, because tests never run with the
    switch on.
  - The trace must find a non-trivial set: the files that declare a vibe exist, and some of them
    are removable. A trace that silently finds nothing must fail.
- **Guard 2, OG release refuses to build without the switch:**
  - Android: `preOgReleaseBuild` throws unless `orphic.liveVibesOnly=true`. This is the same
    pattern as the existing `showKraken` guard in `apps/djapp/androidApp/build.gradle.kts`.
    `debugRelease` stays allowed without it.
  - iOS: the OG release framework link (`:apps:djapp:shared:linkReleaseFrameworkIosArm64`) throws
    unless the switch is on.
  - The AI edition's release builds must build **without** the switch: an AI release with it on
    must fail too, since `features:pulsar` is shared and can't be both at once in one run.
- **Guard 3, the finished binary:**
  - Android: after R8, the variant's `mapping.txt` must contain no class from the removed files.
  - iOS: `nm` on the release framework binary must show no symbol from the removed files.
  - Each check also confirms it really looked, with a negative control: a LIVE vibe class must be
    present, or the check fails. That mirrors `verifyOgHasNoInternet`.
- **Release skill:** `.claude/skills/release-djapp/SKILL.md` passes the switch for the OG Android
  bundle and the OG iOS framework steps, and explains the guard errors.

## Trace facts at design time (2026-10-25)

- 44 files declare vibes. 31 are all non-LIVE, so they're removable.
- `vibes/FireSkyVibe.kt` mixes the LIVE Fire Sky with three non-LIVE backups (Fire Sky OG, CX
  and 05F). It must be split so the backups live in their own file. Guard 1 enforces this.
  - The split puts the LIVE vibe in `FireSkyVibe.kt`, together with `FireSkyVibeBase`, which
    becomes `internal`, and the licks the LIVE vibe uses.
  - The backups move to a new file that OG release drops.
  - **User decision:** the LIVE Fire Sky keeps `ogLick` as its rare anomaly (`chance = 0.005f`).
    The original riff is fine in a release when it's used this rarely, so `ogLick` stays in the
    LIVE file and ships.
- Live code mentions non-LIVE vibe classes only in comments: `BandPresets.kt`, `RustBeltVibe.kt`
  and `FifthSymphonyScore.kt`.
- Tests reference several non-LIVE vibes. That's fine, since tests never run with the switch on.

## Constraints

- `features:pulsar` is a single-variant KMP library compiled once per Gradle invocation. That's
  why this is a property and not a per-variant dependency, and why OG release and AI release
  need separate Gradle runs.
- The trace runs at configuration time and must stay compatible with the configuration cache:
  declare `VibeCatalog.kt` and the vibe files as inputs, or recompute from file contents.
  Otherwise a catalog flip won't change the exclusion.
- The catalog may name vibes that have no class (for example "Deep Space"). That's fine.
- A user's saved state can point at a vibe that isn't in the binary. The app already handles a
  missing name (the catalog hides unregistered names), but confirm that restore doesn't crash
  when a saved vibe is absent.
- `features:pulsar` has no kotlin-reflect. The trace is plain text parsing in the build script
  (or `buildSrc`/`build-logic`), keyed on `val name ... = VibeNames.X` and the catalog's
  `VibeNames.X to CatalogEntry(VibeStatus.Y` lines.
- Vibes are matched by their `VibeNames` constant, not by file name. Filenames don't match class
  names (`CompLabVibe.kt` declares 8 providers).

## Non-goals

- Score JSON resources (`composeResources/files/scores`, classical and public domain) and presets.
- Changing which vibes are LIVE.
- The Orpheus synth app and the wasm site.
- Removing the `VibeNames` constants, which are only names.

## Decided by me (Claude) after the builder check

- **Telling the editions apart.** Each app's own guards do this, not `features:pulsar`.
  - Android: `preOgReleaseBuild` throws without the switch, and `preAiReleaseBuild` throws with it.
  - iOS: every `linkReleaseFrameworkIos*` task in `:apps:djapp:shared` (OG) throws without the
    switch, and every one in `:apps:djapp:ai` throws with it. This matches the Kraken guard's
    `startsWith` pattern, so simulator and other release links are covered too.
  - One Gradle run that builds release for both editions is therefore always an error, which is
    intended.
- **The iOS framework is built from the command line.** The release skill builds the framework
  in its own Gradle step before `xcodebuild`, and `project.yml` has no Gradle build phase. So the
  switch is passed on that Gradle command line.
- **The mechanism is a Kotlin source exclude** on `features:pulsar`'s source set. Metro
  contributions are declared per class, so a removed file's contribution disappears with it. The
  trace scans every source set of `features:pulsar`, in case vibe files live outside
  `commonMain`.
- **Guard 1 scope.** It scans the main source sets of every module in the repo, not tests. A
  reference from `apps/djapp/shared` or anywhere else fails the guard, not the release compile.
- **No hard-coded LIVE count.** Checks derive it from the catalog. "13" was only the count at
  design time.
- **Saved-state fallback.** Keep whatever the app already does when a saved vibe name isn't
  registered, provided it lands on a LIVE vibe without crashing. If it doesn't, fall back to the
  first LIVE catalog entry (the fresh-install default). Cover it with a unit test if the restore
  path is cheaply testable; otherwise check it by hand.

## Left to the builder

- Where the trace code lives: the `features:pulsar` build script, or a `build-logic` helper shared
  by the build and the Guard 1 test. Sharing one implementation is preferred, so the guard tests
  the same trace the build uses.
- The file name for the split Fire Sky backups.
- The exact `nm`/mapping checks and their task names, wired so the OG release build runs them.

## Done when

- With the switch on:
  - `./gradlew :apps:djapp:androidApp:bundleOgRelease -Porphic.liveVibesOnly=true` succeeds, and
    the mapping check shows none of the removed classes.
  - The iOS OG release framework builds, and the symbol check passes.
- Without the switch:
  - Both OG release builds fail with a clear message.
  - `assembleAiRelease` and debug builds still work.
- Guard 1 passes, and fails on a deliberately mixed file (checked once, by hand).
- The OG release app launches with only the 13 LIVE vibes, and a saved non-LIVE vibe falls back
  cleanly.

## Rulings made during the build

- **SHELF vibes deleted (user decision).**
  - All 28 SHELF vibes were removed from the source in every build, together with their catalog entries and `VibeNames` constants. They stay in git history.
  - Removing the `VibeNames` constants goes past the non-goal above. That non-goal covered WIP vibes, which the switch drops.
- **The exclude is on every Kotlin compile task** (`AbstractKotlinCompileTool`), with the source-set exclude kept. The source-set exclude alone missed `compileAndroidMain`, and Guard 3 caught 7 WIP classes in the R8 mapping.
- **Guard 1 does more than the spec asked:**
  - The `VibeProvider` contribution count must equal the traced-name count. A provider that doesn't declare `val name = VibeNames.X` would otherwise ship unseen.
  - The `CatalogEntry(` call count must equal the parsed entries.
- **Android check.** `removable-classes.txt` also lists each removable file's `<FileStem>Kt` facade, and the R8 mapping check fails on those too.
- **The iOS check matches classes only.** Kotlin/Native names top-level members by package, not by file, so they can't be attributed. The check uses plain `nm`, because K/N symbols are local. The OG link is finalized by the check only when the switch is on.
- **Restore needed no fix.** `PulsarFeature.restoreSavedState` already falls back to the first curated LIVE vibe, and the other restore paths skip unknown names. A test pins it.
- **Recipes.**
  - Recipes that built both editions in one run (`assembleRelease`, `bundleRelease`) now name one edition.
  - A whole-module `build`/`assemble` of the DJ apps can't pass in one run.

## Known gaps

- The contribution check compares totals, not provider-to-name pairs per file, so two opposite mistakes in different files could cancel out.
- The negative control is hard-coded to `DogHouseVibe`. It fails safe: demoting Dog House fails the check loudly.
- Guard 1 flags any word in a main file that matches a removable file's top-level non-private declaration, so a common name like `groove` could give a false positive.
- Still owed by the user: install an OG release on a device and see only LIVE vibes in the picker.
