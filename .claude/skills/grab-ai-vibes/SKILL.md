---
name: grab-ai-vibes
description: Retrieve the AI-created Vibe JSON archives that a running Orphic DJ app writes to disk — from JVM desktop (~/.config/orpheus-dj/ai-vibes/) and/or an Android device (app filesDir/ai-vibes/ via adb run-as). Use whenever the user wants to grab, pull, collect, find, or list the JSON files the AI made, get AI-generated vibes off their phone or desktop, see what vibes the agent saved, or recover an AI vibe before importing it into the codebase. Triggers on phrasings like "grab the ai vibes", "pull the json the AI created", "get the archived vibes off android", "what vibes did the AI save", even when the platform or the exact path isn't named. Pairs with the vibe-creator skill, which handles importing a grabbed JSON as a Vibe.
---

# Grab AI Vibes

Every time the AI agent applies a Vibe, the DJ app archives that Vibe's full JSON to local
disk (see the `AiVibeArchive` binding). This is a safety net so a vibe is never lost to the
song auto-advancing or the app closing before it can be saved as a preset. This skill collects
those archived JSON files off a running app so they can be inspected or imported into the repo.

One file is written per applied vibe, named `<epochMillis>_<sanitized-name>.json` (for example
`1783127285298_Saffron_Mirage.json`). The millis prefix makes the files sort chronologically, so
a plain `ls` gives you newest-last order for free. The first JSON field is `"name"`, the vibe's
display name.

## Where the archives live

| Platform | Location | How to read it |
|---|---|---|
| **JVM desktop** | `~/.config/orpheus-dj/ai-vibes/` | Already on the local filesystem — read/copy directly. |
| **Android** | app `filesDir/ai-vibes/` = `/data/data/<pkg>/files/ai-vibes/` | Private app storage — reachable only via `adb exec-out run-as <pkg>` on a **debuggable** build. |

Android package id: base `org.balch.djapp` + the `.ai` edition suffix = **`org.balch.djapp.ai`**,
with a further **`.debug`** suffix on debug builds (`org.balch.djapp.ai.debug`). Don't hard-code a
guess — discover the installed package (the script does this).

## The fast path: run the bundled script

`scripts/pull_ai_vibes.sh` handles both platforms. Default is a dry listing (shows what's
archived without copying anything); pass `--dest DIR` to actually collect the files.

```bash
# List what's archived on both platforms (nothing copied):
scripts/pull_ai_vibes.sh

# Collect everything into a working dir (jvm/ and android/ subfolders):
scripts/pull_ai_vibes.sh --dest /tmp/ai-vibes-grabbed

# Just one platform:
scripts/pull_ai_vibes.sh --jvm
scripts/pull_ai_vibes.sh --android --dest /tmp/ai-vibes-grabbed

# Target a specific device / package when the defaults aren't enough:
scripts/pull_ai_vibes.sh --android --serial R5CT... --package org.balch.djapp.ai.debug --dest /tmp/grab

# Grab AND wire each one into the repo as a hidden (WIP) vibe, in one shot:
scripts/pull_ai_vibes.sh --dest /tmp/grab --import
```

The script prints a `filename → "Display Name"` summary per platform, so you can see at a glance
which vibes are there. Read it before assuming the archive is empty.

## Doing it by hand (when the script doesn't fit)

**JVM** — the files are already local, so just list and read them:

```bash
ls -lt ~/.config/orpheus-dj/ai-vibes/          # newest first
```

Then read a specific one with the Read tool (they're normal JSON files on disk).

**Android** — use `adb exec-out run-as`, not `adb shell` or `adb pull`:

```bash
adb shell pm list packages | grep org.balch.djapp        # find the installed package
PKG=org.balch.djapp.ai.debug
adb exec-out run-as "$PKG" ls -1 files/ai-vibes/          # list (-1: on-device ls defaults to
                                                            # multi-column even when piped)
adb exec-out run-as "$PKG" cat files/ai-vibes/<file>.json > ./<file>.json   # pull one
```

Two details that matter:
- **`run-as`, not `pull`.** `filesDir` is private app storage; `adb pull /data/data/...` fails
  without root. `run-as <pkg>` executes as the app's own uid, which can read it — but only on a
  **debuggable** build. A Play/release-signed build refuses `run-as`, so you can only grab from a
  debug install.
- **`exec-out`, not `shell`.** `adb shell` rewrites `\n` to `\r\n`, which corrupts JSON on the way
  out. `adb exec-out` is binary-safe. Always use `exec-out` when the bytes matter.

## After you grab them: importing

Grabbing is only half the job — usually the goal is to bring a vibe into the codebase.

`scripts/import_vibe.sh <vibe.json>` reads the vibe's `"name"`, calls the `tools:vibe-codegen`
Gradle task to decode the JSON through the app's own lenient decoder (`features/ai`'s
`vibeApplyJson` — the exact leniency the app uses when an AI vibe is applied live) and
reflectively generate a real `<Class>Vibe.kt` — a normal `VibeProvider` with `override val vibe:
Vibe by lazy { Vibe(...) }`, fully spelled out. No embedded JSON string, no runtime decode, no
`kotlinx-serialization-json` dependency added to `features/pulsar`. It then appends a
`VibeCatalog` entry (**WIP by default**, so it stays hidden until you ear-test it — an
uncataloged provider is auto-hidden anyway) and skips cleanly if the provider or catalog entry
already exists.

```bash
scripts/import_vibe.sh /tmp/grab/jvm/1783127285298_Saffron_Mirage.json
scripts/import_vibe.sh /tmp/grab/jvm/<file>.json --status LIVE --tags "ambient,drone"
```

`pull_ai_vibes.sh --dest DIR --import` runs this over everything it just collected. Then compile:
`./gradlew :features:pulsar:compileKotlinJvm`.

The generated file carries a short provenance header comment (not musical prose) — it's a
compiling, faithful starting point, not finished art. Once a vibe earns a keep, hand off to the
**vibe-creator** skill to polish it: collapse a duplicate `engineEdm`/`engineSpace` pair into the
`OrpheusEngine(...).let { x -> TrackVoice(engineEdm = x, engineSpace = x) }` idiom (the generator
always emits both slots fully spelled out — it never attempts this collapse), add the
musical-intent commentary every other vibe carries, and tune values by ear. `Vibe` is
`@Serializable` and the archived JSON was produced and decoded by the current schema, so the
round-trip through the generator is exact — nothing is lost between what the AI made and what's
now sitting in the repo as Kotlin.

## Common snags

- **Empty listing on JVM** — no AI vibe has been applied yet on this machine, or the app ran as a
  different OS user. The dir is created lazily on first archive; its absence just means nothing has
  been saved.
- **`run-as: package not debuggable`** — you're pointed at a release build. Install a debug build to
  grab its archives, or there's no supported way to reach that private dir.
- **`adb: more than one device`** — pass `--serial <id>` (or export `ANDROID_SERIAL`). Get ids from
  `adb devices`.
- **`adb: no devices/emulators found`** — no device attached or USB debugging off. The Android path
  needs a connected, authorized, debuggable device.
- **Package not found by discovery** — the app isn't installed, or was installed under a different
  suffix. List everything with `adb shell pm list packages | grep djapp` and pass `--package`.
