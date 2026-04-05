# Elysium App Design Spec

## Overview

Elysium is a compact standalone app combining the Pulsar beat machine with the DJ turntable and sleep timer. Pulsar tracks are auto-categorized into Keys/Drums/Bass buses by engine type, feeding the DJ turntable as capture sources. Target platforms: Android (primary), iOS, JVM desktop (dev).

This work also involves renaming `apps/composeApp` → `apps/orpheus` and extracting a reusable Pulsar subgraph that both apps share.

## 1. App Module Rename

Rename `apps/composeApp/` → `apps/orpheus/`.

**Changes required:**
- `git mv apps/composeApp apps/orpheus`
- Update `settings.gradle.kts`: `include(":apps:orpheus")` replacing `include(":apps:composeApp")`
- Update any Gradle task references in CLAUDE.md, CI scripts, or other docs
- Update the `apps/androidApp` dependency if it references `:apps:composeApp`
- Verify WASM, iOS, and desktop build targets still resolve

## 2. Pulsar Subgraph Extraction

### 2a. Engine-Type Bus Classification (C++)

Add a classification table in `orpheus_unit_pulsar.cpp` that maps each Plaits engine ID to a bus category:

| Bus    | Engine Types (examples)                            |
|--------|----------------------------------------------------|
| Drums  | Percussive engines: kick analog, snare, hi-hat, modal drum |
| Bass   | Bass-oriented engines: bass, FM bass                |
| Keys   | Melodic engines: lead, pad, chord, wavetable, FM, string |

The full mapping covers all 24 Plaits engine IDs. Classification is deterministic — each engine ID maps to exactly one bus.

### 2b. Per-Track Bus Accumulation (C++)

In `unit_process_pulsar()`, after rendering each track:
- Look up the track's current engine ID → bus category
- Accumulate the track's stereo output into the corresponding bus buffer (Keys L/R, Drums L/R, Bass L/R)
- Continue accumulating into the existing Master L/R output as today

Expose the 4 bus buffers (Keys, Drums, Bass, Master) as `warps_source_buffers[]` entries so the turntable can capture from them using its existing source-selection mechanism.

### 2c. Kotlin Subgraph Builder

Create a `PulsarSubgraph` builder function in `core/dsp-engine` that encapsulates:
- The Pulsar unit creation
- Bus output wiring (Keys/Drums/Bass/Master → warps_source_buffers)
- Delay and reverb send gain units for Pulsar

This builder is called by both `DefaultWiringGraph` (Orpheus) and the new `PocketDjWiringGraph`.

## 3. Elysium DSP Graph

### Graph Topology

```
PulsarSubgraph
  → warps_source_buffers[0] = Keys
  → warps_source_buffers[1] = Drums
  → warps_source_buffers[2] = Bass
  → warps_source_buffers[3] = Master (full Pulsar mix)

Turntable (captures from warps_source_buffers via source selection)
  → ttDelaySend (gain multiply) → Delay unit
  → ttReverbSend (gain multiply) → Reverb unit
  → Master Out

Delay → Master Out
Reverb → Master Out
```

### Units Required

| Unit           | Type ID | Purpose                        |
|----------------|---------|--------------------------------|
| pulsar         | 35      | 8-track beat machine           |
| turntable      | 33      | Dual-deck DJ with crossfader   |
| ttDelaySend    | MULT    | Turntable delay send gain      |
| ttReverbSend   | MULT    | Turntable reverb send gain     |
| delay          | 18      | Stereo delay                   |
| reverb         | 6       | Stereo reverb                  |
| master         | MASTER  | Stereo output                  |

No Plaits voices, Clouds/Grains, Horn/Leslie, Warps, per-string bender, or distortion.

### Parameter Routing

Elysium needs a reduced `orpheus_engine_routing.cpp` port map — only Pulsar and DJ parameters. The existing routing functions can be reused; unused routes simply won't be present in the graph.

## 4. Elysium App Module

### Module: `apps/elysium/`

```
apps/elysium/
  build.gradle.kts
  src/
    commonMain/kotlin/org/balch/orpheus/elysium/
      PocketDjApp.kt           ← Root composable
      PocketDjScreen.kt        ← Portrait/landscape layout
      di/PocketDjGraph.kt      ← DI graph (expect)
      di/PocketDjModule.kt     ← Module bindings
    androidMain/kotlin/…
      PocketDjActivity.kt
      di/PocketDjGraph.android.kt
    iosMain/kotlin/…
      di/PocketDjGraph.ios.kt
    jvmMain/kotlin/…
      main.kt
      di/PocketDjGraph.jvm.kt
```

### Dependencies (minimal)

```kotlin
commonMain.dependencies {
    api(project(":core:audio"))
    api(project(":core:dsp-engine"))
    api(project(":core:features"))
    api(project(":core:foundation"))
    api(project(":core:plugins:pulsar"))
    api(project(":core:plugins:dj"))
    api(project(":ui:panels"))
    api(project(":ui:theme"))
    api(project(":ui:widgets"))
    api(project(":features:pulsar"))
    api(project(":features:dj"))
    api(project(":features:timer"))
}
```

### DI Graph

`PocketDjGraph` is a slimmed-down version of `OrpheusGraph`:
- Provides `SynthEngine`, `SynthOrchestrator`, `GlobalTempo`
- Registers only DJ, Pulsar, and Timer ViewModels
- Uses the Elysium wiring graph instead of DefaultWiringGraph

## 5. UI Layout

### Portrait Mode
```
┌─────────────────────┐
│                     │
│     DJ Panel        │
│   (turntable +      │
│    crossfader)      │
│                     │
├─────────────────────┤
│                     │
│   Pulsar Panel      │
│   (step grid +      │
│    macro knobs)     │
│                     │
└─────────────────────┘
```

50/50 vertical split. Timer overlay on top.

### Landscape Mode
```
┌───────────┬───────────┐
│           │           │
│  DJ Panel │  Pulsar   │
│           │  Panel    │
│           │           │
└───────────┴───────────┘
```

50/50 horizontal split. Timer overlay on top.

### Layout Detection

Use `BoxWithConstraints` or `WindowSizeClass` to detect orientation. Portrait when height > width, landscape otherwise.

## 6. Orpheus Main App Integration

After extracting the Pulsar subgraph:
- `DefaultWiringGraph.kt` calls `PulsarSubgraph` builder and wires its outputs into the existing effects chain
- Bus classification is a new capability — the DJ turntable in Orpheus could also benefit from Keys/Drums/Bass source options (future enhancement, not in scope)
- No behavioral change to the main app

## 7. Out of Scope

- MediaPipe hand tracking
- AI chat
- Visualizations/Orphoscope
- Presets system
- MIDI input
- Tidal integration
- WASM target (future)
- Pulsar track-to-bus override UI (future — could let users manually reassign tracks)
