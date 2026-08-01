---
name: panel-viewmodel-feature
description: Use when adding a new feature module, ViewModel, Panel, or Plugin to the synth app, when wiring DI registration, when adding visualization flows, or when debugging why a panel doesn't appear or knobs show wrong defaults. Covers the full Symbol-Plugin-ViewModel-Panel-Registration vertical slice.
---

# Panel-ViewModel-Feature Pattern

## Overview

Every synth feature follows a strict MVI pattern: **Symbol** (port identity) -> **Plugin** (state container) -> **ViewModel** (reactive state) -> **Panel** (Compose UI) -> **Registration** (DI discovery). The canonical reference is the LFO feature.

## Architecture Flow

```
User Input (MIDI/UI/AI/Sequencer)
        |
  SynthController (event bus)
        |
  Plugin.setPortValue() -> C++ via audioEngine.setPort()
        |
  SynthController.onControlChange flow
        |
  ViewModel: controlFlow() -> Intent -> scan() reducer -> StateFlow<UiState>
        |
  Panel: collectAsState() -> Composable UI -> actions -> SynthController
```

## Layer 1: Symbol (Port Identity)

**Location**: `core/plugin-api/src/commonMain/kotlin/.../symbols/*Symbol.kt`

```kotlin
const val TIDES_URI = "org.balch.orpheus.plugins.tides"

enum class TidesSymbol(
    override val symbol: Symbol,
    override val uri: String = TIDES_URI,
    override val displayName: String = symbol.replaceFirstChar { it.uppercase() }
) : PortSymbol {
    FREQUENCY("frequency", displayName = "Frequency"),
    SLOPE("slope", displayName = "Slope"),
    MIX("mix", displayName = "Mix"),
    // ...
}
```

- Enum implements `PortSymbol` -> provides `.controlId` (globally unique `uri:symbol`)
- `symbol` is snake_case, matches C++ `set_port()` hash routing
- One Symbol enum per plugin

## Layer 2: Plugin (State Container)

**Location**: `core/plugins/<name>/src/commonMain/kotlin/.../*Plugin.kt`

```kotlin
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())
class TidesPlugin : DspPlugin {
    override val info = PluginInfo(uri = TIDES_URI, name = "Tides", author = "Balch")

    private var _frequency = 0.5f
    private var _mix = 0.0f  // default off (mix knob pattern)

    private val portDefs = ports(startIndex = 0) {
        controlPort(TidesSymbol.FREQUENCY) {
            floatType {
                default = 0.5f
                get { _frequency }
                set { _frequency = it }
            }
        }
        controlPort(TidesSymbol.MIX) {
            floatType {
                default = 0.0f
                get { _mix }
                set { _mix = it }
            }
        }
    }

    override val ports: List<Port> = portDefs.controlPorts
    override fun setPortValue(symbol: Symbol, value: PortValue) = portDefs.setValue(symbol, value)
    override fun getPortValue(symbol: Symbol) = portDefs.getValue(symbol)
}
```

- `@ContributesIntoSet(AppScope::class, binding = binding<DspPlugin>())` for auto-discovery
- Pure state container — C++ owns all audio processing
- Port `default` values must match C++ engine atomic defaults

## Layer 3: ViewModel (Reactive State)

**Location**: `features/<name>/src/commonMain/kotlin/.../*ViewModel.kt`
**Canonical reference**: `features/lfo/src/commonMain/kotlin/.../LfoViewModel.kt`

### Required Components

**1. UiState** — immutable data class with defaults matching plugin defaults:
```kotlin
@Immutable
data class TidesUiState(
    val frequency: Float = 0.5f,
    val mix: Float = 0.0f,
    // ...
)
```

**2. PanelActions** — action lambdas with `EMPTY` companion:
```kotlin
@Immutable
data class TidesPanelActions(
    val setFrequency: (Float) -> Unit,
    val setMix: (Float) -> Unit,
) {
    companion object {
        val EMPTY = TidesPanelActions({}, {})
    }
}
```

**3. Intent** — sealed interface, one variant per control:
```kotlin
private sealed interface TidesIntent {
    data class Frequency(val value: Float) : TidesIntent
    data class Mix(val value: Float) : TidesIntent
}
```

**4. Feature interface** — with SynthControlDescriptor:
```kotlin
interface TidesFeature : SynthFeature<TidesUiState, TidesPanelActions> {
    override val synthControl: SynthFeature.SynthControl
        get() = SynthControlDescriptor

    companion object {
        internal val SynthControlDescriptor = object : SynthFeature.SynthControl {
            override val panelId = PanelId.TIDES
            override val title = "Tides"
            override val markdown = """
                Function generator with 4 output channels...
            """.trimIndent()
            override val portControlKeys = mapOf(
                TidesSymbol.FREQUENCY.controlId.key to "Rate of the function generator",
                TidesSymbol.MIX.controlId.key to "Output level (0=off)",
            )
        }
    }
}
```

**5. ViewModel class**:
```kotlin
@Inject
@SingleIn(FeatureScope::class)          // REQUIRED — without it Metro builds a new VM per resolve
@SynthFeatureKey(TidesFeature::class)   // key by the INTERFACE, not the ViewModel
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<TidesFeature>())
class TidesViewModel(
    private val synthController: SynthController,
    dispatcherProvider: DispatcherProvider,
    scope: FeatureCoroutineScope,
) : TidesFeature {

    // Control flows (reactive to MIDI/UI/Sequencer changes)
    private val freqId = synthController.controlFlow(TidesSymbol.FREQUENCY.controlId)
    private val mixId = synthController.controlFlow(TidesSymbol.MIX.controlId)

    // Actions wired to setters
    override val actions = TidesPanelActions(
        setFrequency = freqId.floatSetter(),
        setMix = mixId.floatSetter(),
    )

    // Control changes -> Intent -> reduce -> StateFlow
    private val controlIntents = merge(
        freqId.map { TidesIntent.Frequency(it.asFloat()) },
        mixId.map { TidesIntent.Mix(it.asFloat()) },
    )

    override val stateFlow: StateFlow<TidesUiState> =
        controlIntents
            .scan(TidesUiState()) { state, intent -> reduce(state, intent) }
            .flowOn(dispatcherProvider.io)
            .stateIn(scope, sharingStrategy, TidesUiState())

    private fun reduce(state: TidesUiState, intent: TidesIntent) = when (intent) {
        is TidesIntent.Frequency -> state.copy(frequency = intent.value)
        is TidesIntent.Mix -> state.copy(mix = intent.value)
    }

    companion object {
        fun previewFeature(state: TidesUiState = TidesUiState()): TidesFeature =
            object : TidesFeature {
                override val stateFlow = MutableStateFlow(state)
                override val actions = TidesPanelActions.EMPTY
            }

        @Composable
        // Only add this if a NON-DI composable reads the feature (App.kt, a *Screen.kt,
        // DjAppScreen.kt). A *PanelRegistration must inject TidesFeature instead.
        // fun feature(): TidesFeature = synthFeature<TidesFeature>()
    }
}
```

### Setter Helpers
- `floatSetter()` — sets float value on control flow
- `enumSetter()` — sets int value for enum controls
- `boolSetter()` — sets boolean value
- Custom setter methods for value transforms (e.g., knob-to-frequency mapping)

## Layer 4: Panel (Compose UI)

**Location**: `features/<name>/src/commonMain/kotlin/.../*Panel.kt`

```kotlin
@Composable
fun TidesPanel(
    feature: TidesFeature,   // required — the registration injects it and passes it down
    vizFlows: List<StateFlow<FloatArray>> = emptyList(),
    modifier: Modifier = Modifier,
    isExpanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val uiState by feature.stateFlow.collectAsState()
    val actions = feature.actions

    CollapsibleColumnPanel(
        title = "TIDES",
        color = OrpheusColors.syzygyOrange,
        // ...
    ) {
        RotaryKnob(
            value = uiState.frequency,
            onValueChange = actions.setFrequency,
            label = "RATE",
            controlId = TidesSymbol.FREQUENCY.controlId.key,  // for MIDI learn
        )
    }
}
```

- Default `feature` parameter from composable helper
- Pass `controlId` to `Learnable`/`RotaryKnob` for MIDI learn mode
- `collectAsState()` at leaf level to minimize recompositions

## Layer 5: Registration (DI + Panel Discovery)

**Location**: `features/<name>/src/commonMain/kotlin/.../*PanelRegistration.kt`

```kotlin
@Inject
@ContributesIntoSet(HeaderPanelScope::class, binding = binding<FeaturePanel>())
class TidesPanelRegistration(
    private val synthEngine: SynthEngine,
    // Inject the feature INTERFACE. Do not call TidesViewModel.feature() here — this is a
    // DI-constructed class, so Metro can verify the binding at build time.
    private val feature: TidesFeature,
) : FeaturePanel {
    override val panelId = PanelId.TIDES
    override val description = "Function generator with 4 channels"
    override val weight = 0.8f
    override val label = "Tides"
    override val color = OrpheusColors.syzygyOrange

    @Composable
    override fun Content(
        modifier: Modifier,
        isExpanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onDialogActiveChange: (Boolean) -> Unit,
    ) {
        TidesPanel(
            feature = feature,
            vizFlows = listOf(
                synthEngine.tidesCh0VizFlow,
                synthEngine.tidesCh1VizFlow,
                // ...
            ),
            modifier = modifier,
            isExpanded = isExpanded,
            onExpandedChange = onExpandedChange,
        )
    }

    companion object {
        fun preview() = featurePanelPreview(
            panelId = PanelId.TIDES, label = "Tides", color = OrpheusColors.syzygyOrange,
        ) { modifier, isExpanded, onExpandedChange, _ ->
            TidesPanel(
                feature = TidesViewModel.previewFeature(),
                modifier = modifier, isExpanded = isExpanded, onExpandedChange = onExpandedChange,
            )
        }
    }
}
```

## Visualization Integration

### Adding viz flows to SynthEngine

**Interface**: `core/foundation/src/commonMain/kotlin/.../SynthEngine.kt`
```kotlin
val tidesCh0VizFlow: StateFlow<FloatArray>
val tidesCh1VizFlow: StateFlow<FloatArray>
```

### Adding to SignalMonitorViz (Orphoscope)

**File**: `features/visualizations/src/commonMain/kotlin/.../viz/SignalMonitorViz.kt`

Add channel definition + flow collection in matching order:

```kotlin
// In channels list:
Channel("TIDES-0", OrpheusColors.neonOrange),

// In Content(), collect the flow:
val tidesCh0Data by engine.tidesCh0VizFlow.collectAsState()

// In allData list (must match channels index):
tidesCh0Data,
```

The `channels` list and `allData` list must stay in sync by index.

## App Dependency Registration

**File**: `apps/orpheus/shared/build.gradle.kts`

Both plugin AND feature must be added:
```kotlin
commonMain.dependencies {
    api(project(":core:plugins:tides"))    // Plugin (port values, presets)
    api(project(":features:tides"))         // Feature (UI panel)
}
```

**Missing plugin** = knobs show defaults, ignore presets, no C++ routing
**Missing feature** = panel doesn't appear in UI

## PanelId Registration

Add to `core/features/.../FeaturePanel.kt`:
```kotlin
companion object {
    val TIDES = PanelId("tides")
}
```

## New Module Checklist

- [ ] Symbol enum in `core/plugin-api/.../symbols/`
- [ ] Plugin in `core/plugins/<name>/` with `@ContributesIntoSet(..., binding = binding<DspPlugin>())`
- [ ] PanelId added to `FeaturePanel.kt` companion
- [ ] Feature interface extending `SynthFeature<S, A>` with `SynthControlDescriptor`
- [ ] ViewModel with **all four**: `@SingleIn(FeatureScope::class)` + `@SynthFeatureKey(<Name>Feature::class)` + `@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())` + `@ContributesBinding(FeatureScope::class, binding = binding<<Name>Feature>())`. The key is the feature **interface**. Missing `@SingleIn` silently gives a new ViewModel per resolve — `FeatureScopeGuardTest` catches it
- [ ] Panel composable taking the feature as a **required** parameter, no `= <Name>ViewModel.feature()` default
- [ ] PanelRegistration with `@ContributesIntoSet(HeaderPanelScope::class, binding = binding<FeaturePanel>())` that **constructor-injects** `<Name>Feature`
- [ ] Companion `feature()` accessor ONLY if a non-DI composable (`App.kt`, a `*Screen.kt`) reads it
- [ ] Viz flows added to SynthEngine interface + SignalMonitorViz
- [ ] Both `:core:plugins:<name>` and `:features:<name>` added to `apps/orpheus/shared/build.gradle.kts`
- [ ] `previewFeature()` and `preview()` companions for Compose previews
- [ ] `portControlKeys` populated for AI agent discovery
- [ ] Default values consistent across Plugin, ViewModel UiState, and C++ engine atomics

## Common Mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Missing `binding = binding<DspPlugin>()` | Plugin not discovered, knobs dead | Add explicit binding parameter |
| Feature module not in app build.gradle | Panel doesn't appear | Add `api(project(":features:<name>"))` |
| Plugin module not in app build.gradle | Knobs show defaults, presets ignored | Add `api(project(":core:plugins:<name>"))` |
| UiState defaults don't match Plugin defaults | Knob jumps on first emission | Sync defaults across all 3 layers |
| Missing `controlId` on knobs | MIDI learn can't find control | Pass `Symbol.X.controlId.key` to RotaryKnob |
| `collectAsState()` at wrong level | Excessive recomposition | Collect at leaf composables, not parent |
| Missing `EMPTY` companion on Actions | Preview crashes | Add no-op lambda companion |
| channels/allData index mismatch in SignalMonitorViz | Wrong colors on oscilloscope | Keep lists in strict sync |
