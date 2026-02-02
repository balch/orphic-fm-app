# ViewModel Refactoring Report

## Objective
Refactor ViewModels to remove direct dependency on `PresetLoader` and rely on `SynthController` for event-driven state management.

## Changes

### 1. PresetLoader (Core)
- **Updated Emission Logic**: `PresetLoader` now emits all preset parameters as `ControlChange` events via `SynthController`.
- **Added Missing Parameters**: Added emissions for Grains, Drum Triggers, and Pitch Sources.
- **Fixed Normalizations**: Corrected normalization for:
  - `HYPER_LFO_MODE` (Mapped to 0..1 based on Enum size)
  - `WARPS_ALGORITHM` (Normalized 0..1)
  - `BEATS_EUCLIDEAN_LENGTH` (Mapped 1..32 -> 0..1)
- **Integration**: Added `EventOrigin.UI` to all emissions to identify preset loads as UI-driven changes.

### 2. MidiMappingState (Core)
- **New Control IDs**: Added missing control IDs to support full remote control/automation:
  - Resonator: `RESONATOR_TARGET_MIX`, `RESONATOR_SNAP_BACK`
  - Grains: Full set (`GRAINS_POSITION`, `GRAINS_SIZE`, `GRAINS_PITCH`, `GRAINS_DENSITY`, `GRAINS_TEXTURE`, `GRAINS_DRY_WET`, `GRAINS_FREEZE`, `GRAINS_MODE`, `GRAINS_TRIGGER`)
  - Drums: Split Trigger/Pitch sources (`DRUM_BD_TRIGGER_SOURCE`, `DRUM_BD_PITCH_SOURCE`, etc.)

### 3. ViewModels
The following ViewModels were refactored to remove `PresetLoader` from their constructor and replace `presetIntents` flow with `controlIntents` listening to `SynthController`.

- **WarpsViewModel**: Full refactor. `synthController` made private property.
- **DelayViewModel**: Full refactor.
- **ResonatorViewModel**: Full refactor. Updated logic to handle new Control IDs.
- **DistortionViewModel**: Full refactor. `synthController` made private property.
- **LfoViewModel**: Full refactor. `synthController` made private property. Logic updated to expect normalized Mode values.
- **GrainsViewModel**: Full refactor. Added Control ID handling.
- **DrumViewModel**: Full refactor. Logic updated to handle separate Trigger/Pitch source IDs. Removed duplicate `synthEngine` property.
- **DrumBeatsViewModel**: Full refactor. Logic updated to handle normalized Euclidean Lengths.
- **FluxViewModel**: Full refactor. Updated `FluxPanel` to use `ControlIds` and fixed syntax errors. `synthController` made private property.

### 4. UI Components
- **FluxPanel**: Updated to use `ControlIds` directly, removing dependency on deprecated ViewModel constants. Fixed callback syntax.

## Verification
- **Build Status**: Successful (`assemble` passed for all modified feature modules).
- **Consistency**: All ViewModels now follow the standard pattern:
  - `actions` emit `SynthController` events.
  - `controlIntents` listen to `SynthController` events and map to internal Intents.
  - State is updated via MVI reduction.
  - Engines are updated via side-effects of Intents.

## Next Steps
- Verify runtime behavior of Preset Loading to ensure 0-latency restoration of complex patches.
- Verify normalization logic for all parameters during actual usage.
