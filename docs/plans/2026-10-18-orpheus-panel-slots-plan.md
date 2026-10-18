# Orpheus Panel Slots Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the desktop spine row with width-driven panel slots, a recents strip, a side rail and search, so the open Fold, tablets and desktop never clip a panel.

**Architecture:** Slot behavior is a pure model (`PanelSlots`, in commonMain, unit-tested), driven by `HeaderViewModel` and persisted in `AppPreferences`. Stateless composables render the strip, the rail, the slots and the search overlay. Each slot scales its content by overriding `LocalDensity`. `DesktopSynthScreen` feeds them in place of `HeaderPanel`, and `SynthScreen` picks its layout from the previous frame's size instead of `BoxWithConstraints`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Metro DI, kotlinx.serialization, kotlin.test, `ImageComposeScene` render harness (jvmTest).

**Spec:** `docs/plans/2026-10-18-orpheus-panel-slots-design.md`

## Global Constraints

- **Package.** New files go under `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/panels/slots/` (package `org.balch.orpheus.ui.panels.slots`). Tests mirror that path in `commonTest`; render tests go in `jvmTest`.
- **Slot unit:** `320.dp`.
- **Design height:** `330.dp`.
- **Scale clamp:** `0.85f..1.25f`.
- **Recents cap:** `12`.
- **Rail default threshold:** `1200.dp` of window width.
- **Wide panels:** `PanelId.PULSAR` and `PanelId.BEATS`. A wide panel takes 2 units only when `capacity >= 3`.
- **Pins:** pinned units may never exceed `capacity - 1`.
- **Compact screens unchanged.** The compact breakpoint stays as it is (`width < 600dp || height < 400dp`), and `CompactPortraitScreen` and `CompactLandscapeScreen` are not touched.
- **No branch flips in `BoxWithConstraints`.** Never pick a branch from `BoxWithConstraints` values when the branch contains a dialog, popup or sheet. Use `onSizeChanged` into state, as `DjLayoutBox` does (`apps/djapp/shared/src/commonMain/kotlin/org/balch/orpheus/djapp/DjLayout.kt:143`).
- **Knob sensitivity:** do not touch it. It is in pixels, so the density override does not change it.
- **Comments:** 1–3 lines. Never write "load-bearing". Use few em-dashes in comments and in commit messages.
- **Data style:** catalog data is literal and inline, with no helper builders.
- **Search overlay surface:** opaque. No glass on reading surfaces.
- **Commits:** short subject lines ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never push.
- **Tests:** run with `./gradlew :apps:orpheus:shared:jvmTest --tests '<pattern>'`. `commonTest` runs on the JVM target.

## Review Focus

1. **Before the first measure.** Panels opened before the width is known have capacity 0: a restored state, or an AI bus event replayed at startup (the bus has `replay = 1`). None of these panels may be lost, and the first `withCapacity` trims them by recency. Pinned by `opensBeforeCapacityAreKeptThenTrimmed` (Task 1).
2. **Stale panel ids in prefs.** Saved prefs can name panels that are not registered, such as REC on Android or a renamed id. Restore drops them silently. Pinned by `restoreDropsUnregisteredIds` (Task 3).
3. **Resizing across thresholds.** Shrinking past the pin cap and growing back (fold, then unfold) must leave pins within the cap and refill the free slots from recents. Pinned by `shrinkUnpinsBeyondCapThenGrowRefills` (Task 1).
4. **A one-slot window (600–639dp).** Pinning is disabled and opening replaces the only panel. Pinned by `singleSlotReplacesAndRefusesPins` (Task 1).
5. **Typing in search must not play the synth.** That covers A/S/D/K/Space, and K pressed with Cmd or Ctrl, since K is a voice gate. Pinned by `isSearchShortcut` tests (Task 6) and the manual desktop check (Task 7).

---

### Task 1: Slot model and metrics (pure)

**Files:**
- Create: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/panels/slots/PanelSlots.kt`
- Create: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/panels/slots/SlotMetrics.kt`
- Test: `apps/orpheus/shared/src/commonTest/kotlin/org/balch/orpheus/ui/panels/slots/PanelSlotsTest.kt`
- Test: `apps/orpheus/shared/src/commonTest/kotlin/org/balch/orpheus/ui/panels/slots/SlotMetricsTest.kt`

**Interfaces:**
- Produces:
  - `@Immutable data class OpenPanel(val id: PanelId, val units: Int)`
  - `@Immutable data class PanelSlots(val open: List<OpenPanel> = emptyList(), val pinned: Set<PanelId> = emptySet(), val recents: List<PanelId> = emptyList(), val lastUsed: List<PanelId> = emptyList(), val capacity: Int = 0)`
    - `open` is in slot order, left to right.
    - `recents` holds closed panels only, most recent first.
    - `lastUsed` holds open panels only, most recent first.
    - `capacity = 0` means unknown: unbounded, and every panel gets 1 unit.
  - Members:
    - `val openIds: List<PanelId>`
    - `val freeUnits: Int` (0 when `capacity == 0`)
    - `fun isOpen(id: PanelId): Boolean`
    - `fun canPin(id: PanelId): Boolean`
  - Pure transitions, each returning a new `PanelSlots`. `isWide` is a parameter, so the model does not depend on the catalog:
    - `fun PanelSlots.open(id: PanelId, isWide: (PanelId) -> Boolean): PanelSlots`
    - `fun PanelSlots.close(id: PanelId): PanelSlots`
    - `fun PanelSlots.touch(id: PanelId): PanelSlots`
    - `fun PanelSlots.togglePin(id: PanelId): PanelSlots`
    - `fun PanelSlots.withCapacity(units: Int, isWide: (PanelId) -> Boolean): PanelSlots`
    - `fun PanelSlots.applySet(ids: List<PanelId>, isWide: (PanelId) -> Boolean): PanelSlots`
  - In `SlotMetrics.kt`:
    - `val SlotUnitWidth = 320.dp`
    - `val SlotDesignHeight = 330.dp`
    - `fun slotCapacity(areaWidth: Dp): Int`, defined as `max(1, floor(areaWidth / SlotUnitWidth))`
    - `fun slotScale(slotWidth: Dp, slotHeight: Dp, units: Int): Float`, defined as `clamp(min(slotWidth / (SlotUnitWidth * units), slotHeight / SlotDesignHeight), 0.85f, 1.25f)`

**Rules the tests pin (from the spec):**
- `open` on a panel that is already open only touches it.
- With free units, the panel is appended. Its width is 2 units if it is wide, `capacity >= 3` and `freeUnits >= 2`; otherwise 1.
- When full, the victim is the least recently used unpinned panel. The new panel replaces the victim at the same index. It takes 2 units only if it is wide, `capacity >= 3` and the victim had 2 units. If the new panel takes 1 unit, the leftover unit becomes free.
- Opening removes the id from `recents`. A closed or evicted panel goes to the front of `recents`, which is deduplicated and capped at 12.
- `close` also unpins.
- `canPin(id)` requires the panel to be open and `pinnedUnits + units(id) <= capacity - 1`. With `capacity == 0` it is false.
- **Shrinking:** keep panels in this order of preference: pinned panels by recency, then unpinned panels by recency, until they fit. Wide panels drop to 1 unit when `capacity < 3`. Survivors keep their relative slot order. While pinned units exceed `capacity - 1`, unpin the least recently used pinned panel. Dropped panels go to the front of recents.
- **Growing:** upgrade wide panels from 1 unit to 2 where a unit is free and `capacity >= 3`. Then fill the free units from `recents`, most recent first.
- `applySet` closes every unpinned panel, then opens `ids` in order until `freeUnits == 0`. It never evicts.

- [ ] **Step 1: Write the failing tests** in `PanelSlotsTest.kt`, one `@Test` per rule. Fixed ids: `a = PanelId("a")` through `f`, and wide = `{ it == PULSAR }`.
  - `openAppendsUntilFull`: at capacity 2, open a, b. `openIds == [a, b]` and `freeUnits == 0`.
  - `openWhenFullReplacesLeastRecentlyUsedInPlace`: at capacity 2, open a, b, touch a, open c. `openIds == [a, c]` and `recents == [b]`.
  - `pinnedPanelIsNeverEvicted`: at capacity 3, open a, b, c. Pin a, touch b, touch c, open d. `openIds == [a, d, c]`, because b is the least recently used unpinned panel.
  - `wideTakesTwoUnitsOnlyFromThreeSlots`: at capacity 2, open PULSAR, giving `units == 1`. At capacity 3, open PULSAR, giving `units == 2` and `freeUnits == 1`.
  - `wideNeverEvictsForItsSecondUnit`: at capacity 3, open a, b, c, then PULSAR, evicting the LRU panel. PULSAR's `units == 1`.
  - `evictedWidePanelLeavesAFreeUnit`: at capacity 3, open PULSAR (2 units) and a. Touch a, then open b. `openIds == [b, a]`, b has 1 unit and `freeUnits == 1`.
  - `canPinRespectsCap`: at capacity 2, open a, b and pin a. `canPin(b) == false`, and `togglePin(b)` leaves `pinned == {a}`.
  - `singleSlotReplacesAndRefusesPins`: at capacity 1, open a then b. `openIds == [b]` and `canPin(b) == false`.
  - `closeUnpinsAndPushesToRecents`: `pinned` no longer contains the id, `recents.first() == id`, and `recents` never holds more than 12 (open and close 14 distinct ids).
  - `openRemovesFromRecents`: close a, then open a. `a !in recents`.
  - `opensBeforeCapacityAreKeptThenTrimmed`: at capacity 0, open a, b, c, d. All 4 are open. Touch b, then `withCapacity(2)`. `openIds == [b, d]` (b and d are the most recent, in slot order) and `recents` starts with c.
  - `shrinkUnpinsBeyondCapThenGrowRefills`: at capacity 5, open a–e and pin a, b, c. `withCapacity(2)` leaves `pinned.size == 1` and `openIds.size == 2`. Then `withCapacity(5)` refills to 5 open from recents.
  - `applySetKeepsPinsAndFillsInOrder`: at capacity 3, open x and pin x. `applySet([a, b, c])` gives `openIds == [x, a, b]`.
- [ ] **Step 2: Write `SlotMetricsTest.kt`.**
  - `slotCapacity(850.dp) == 2`, `slotCapacity(1280.dp) == 4`, `slotCapacity(1900.dp) == 5`, `slotCapacity(600.dp) == 1` and `slotCapacity(100.dp) == 1`.
  - `slotScale(425.dp, 330.dp, 1) == 1.25f` (clamped), `slotScale(320.dp, 330.dp, 1) == 1f`, `slotScale(320.dp, 200.dp, 1) == 0.85f` and `slotScale(640.dp, 330.dp, 2) == 1f`.
- [ ] **Step 3: Run the tests and check they fail.** Run `./gradlew :apps:orpheus:shared:jvmTest --tests 'org.balch.orpheus.ui.panels.slots.*'`. Expected: they FAIL with unresolved references.
- [ ] **Step 4: Implement `PanelSlots.kt` and `SlotMetrics.kt`** to the interfaces and rules above. The code is pure Kotlin plus `Dp`, with no Compose runtime calls.
- [ ] **Step 5: Run the tests and check they pass.** Run the same command. Expected: PASS.
- [ ] **Step 6: Commit** with the subject `feat(ui): pure panel slot model and metrics`.

### Task 2: Panel catalog and search (pure)

**Files:**
- Create: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/panels/slots/PanelNavCatalog.kt`
- Test: `apps/orpheus/shared/src/commonTest/kotlin/org/balch/orpheus/ui/panels/slots/PanelNavCatalogTest.kt`

**Interfaces:**
- Produces:
  - `enum class PanelGroup(val title: String) { Rhythm("RHYTHM"), Mod("MOD"), Fx("FX"), Tools("TOOLS") }`
  - `@Immutable data class PanelNavEntry(val id: PanelId, val shortLabel: String, val group: PanelGroup, val wide: Boolean = false)`
  - `val PanelNavEntries: List<PanelNavEntry>`: a literal list in this exact order:
    - **Rhythm:** pulsar "PULSE" (wide), beats "BEATS" (wide), drums "808", bass "BASS", dj "DJ", speech "TALK"
    - **Mod:** lfo "LFO", tides "Waves", evo "EVO", flux "FLUX", flux_triggers "TRIGG"
    - **Fx:** distortion "VOL", delay "DELAY", reverb "VERB", horn "HORN", resonator "REZO", grains "GRAIN", warps "BLEND", looper "LOOP"
    - **Tools:** presets "PATCH", midi "MIDI", viz "VIZ", code "CODE", ai "AI", vibe_create "VIBE", mediapipe "ASL", timer "Timer", record "REC"
  - `fun isWidePanel(id: PanelId): Boolean`
  - `@Immutable data class SearchCandidate(val entry: PanelNavEntry, val label: String, val description: String)`
  - `fun searchPanels(query: String, candidates: List<SearchCandidate>): List<PanelNavEntry>`. The query is trimmed and matched case-insensitively. A blank query returns every candidate in input order. Otherwise there are three tiers, each keeping input order:
    1. `shortLabel` starts with the query.
    2. `label` starts with the query.
    3. The query appears anywhere in `shortLabel`, `label`, `id.id` or `description`.

    Each entry appears at most once, in its best tier.

- [ ] **Step 1: Write the failing tests.**
  - `catalogCoversDesktopSetExactly`: the set of ids in `PanelNavEntries` equals `FactoryPanelSets.DesktopScreen.visibleIds.toSet()`, and the list has no duplicates.
  - `wideIsPulsarAndBeats`: `PanelNavEntries.filter { it.wide }.map { it.id }.toSet() == setOf(PanelId.PULSAR, PanelId.BEATS)`.
  - `searchPrefersShortLabelPrefix`: with candidates VERB (label "Reverb") and DELAY (label "Delay"), the query `"de"` gives `[DELAY]` first, and the query `"rev"` gives `[VERB]`.
  - `searchMatchesDescriptionLast`: a candidate whose description contains "echo" is returned for the query `"echo"`, after any prefix hits.
  - `blankQueryReturnsAllInOrder`.
- [ ] **Step 2: Run the tests and check they fail.** Run `./gradlew :apps:orpheus:shared:jvmTest --tests 'org.balch.orpheus.ui.panels.slots.PanelNavCatalogTest'`. Expected: FAIL.
- [ ] **Step 3: Implement `PanelNavCatalog.kt`.**
- [ ] **Step 4: Run the tests and check they pass.** Expected: PASS.
- [ ] **Step 5: Commit** with the subject `feat(ui): panel nav catalog and search`.

### Task 3: Persisted slot state in HeaderViewModel

**Files:**
- Create: `core/foundation/src/commonMain/kotlin/org/balch/orpheus/core/preferences/PanelSlotsPrefs.kt`
- Modify: `core/foundation/src/commonMain/kotlin/org/balch/orpheus/core/preferences/AppPreferences.kt`. Add a field after `lastExpandedPanelsJson`.
- Create: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/panels/slots/HeaderReducer.kt`
- Modify: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/panels/HeaderViewModel.kt`
- Test: `apps/orpheus/shared/src/commonTest/kotlin/org/balch/orpheus/ui/panels/slots/HeaderReducerTest.kt`

**Interfaces:**
- Consumes: Task 1's `PanelSlots` and its transitions, and Task 2's `isWidePanel`.
- Produces:
  - `@Serializable data class PanelSlotsPrefs(val open: List<String> = emptyList(), val lastUsed: List<String> = emptyList(), val pinned: List<String> = emptyList(), val recents: List<String> = emptyList(), val railVisible: Boolean? = null, val recentsStripVisible: Boolean = true)`
  - `AppPreferences.panelSlots: PanelSlotsPrefs? = null`
  - `HeaderPanelUiState` gains `slots: PanelSlots = PanelSlots()`, `railVisible: Boolean? = null` and `recentsStripVisible: Boolean = true`.
    - `isExpanded(id)` becomes `slots.isOpen(id)`.
    - Remove `expandedPanels`, `visiblePanelIds` and `weightOverrides`. Only the desktop screen and `HeaderPanel.kt` read them; `HeaderPanel.kt` is deleted in Task 6.
    - Keep `activePanelSet`.
  - `HeaderPanelActions` becomes:

    ```kotlin
    data class HeaderPanelActions(
        val setExpanded: (PanelId, Boolean) -> Unit,
        val open: (PanelId) -> Unit,
        val close: (PanelId) -> Unit,
        val togglePin: (PanelId) -> Unit,
        val touch: (PanelId) -> Unit,
        val setCapacity: (Int) -> Unit,
        val setRailVisible: (Boolean) -> Unit,
        val setRecentsStripVisible: (Boolean) -> Unit,
    )
    ```

    `EMPTY` sets every field to a no-op.
  - In `HeaderReducer.kt`:
    - `internal sealed interface HeaderIntent` with these cases:
      - `Open(id)`
      - `Close(id)`
      - `TogglePin(id)`
      - `Touch(id)`
      - `SetCapacity(units)`
      - `SetRailVisible(Boolean)`
      - `SetRecentsStripVisible(Boolean)`
      - `ApplySet(panelSet)`
      - `Restore(slots, railVisible, recentsStripVisible)`
    - `internal fun reduceHeader(state: HeaderPanelUiState, intent: HeaderIntent): HeaderPanelUiState`
    - `internal fun restoreSlots(prefs: AppPreferences, defaultSet: PanelSet, registered: Set<PanelId>): HeaderIntent.Restore`
    - `internal fun PanelSlots.toPrefs(railVisible: Boolean?, recentsStripVisible: Boolean): PanelSlotsPrefs`

**Behavior:**
- **Bus mapping.**
  - A bus `PanelExpansionEvent(expand = true)` becomes `Open`, and `expand = false` becomes `Close`.
  - `PanelSetEvent` becomes `ApplySet`, which calls `slots.applySet(set.visibleIds.filter { it in set.expandedIds }, ::isWidePanel)` and sets `activePanelSet`.
  - `actions.setExpanded(id, true/false)` maps to `Open` / `Close`.
- **Restore order:**
  1. If `prefs.panelSlots` is non-null, rebuild the slots from it with `capacity = 0`, opening `open` in order and setting `lastUsed`, `pinned` and `recents`.
  2. Otherwise, take the ids marked `true` in `lastExpandedPanelsJson`, ordered by `FactoryPanelSets.DesktopScreen.visibleIds`.
  3. Otherwise, use `defaultSet`'s expanded ids in set order.

  Every list is filtered to `registered`.
- **Saving:** debounce 1s, then write `panelSlots = state.slots.toPrefs(...)` through `appPreferencesRepository.update`. Stop writing `lastExpandedPanelsJson` (reading it stays, for migration).
- **Restore timing:** keep emitting `Restore` from the existing `restoreSavedExpansion` coroutine through the replaying `uiIntents` flow.

- [ ] **Step 1: Write the failing tests** in `HeaderReducerTest.kt`.
  - `busExpandOpensAndCollapseCloses`
  - `applySetOpensExpandedIdsInSetOrder`: at capacity 2, `ApplySet(FactoryPanelSets.DesktopScreen)` gives `openIds == [DISTORTION, PULSAR]`.
  - `restorePrefersPanelSlotsPrefs`
  - `restoreMigratesLastExpandedPanelsJson`: the JSON `{"reverb":true,"lfo":true,"delay":false}` gives `openIds == [LFO, REVERB]`, because that is the DesktopScreen order.
  - `restoreFallsBackToDefaultSet`
  - `restoreDropsUnregisteredIds`: prefs that open `record` and `gone`, with `registered` missing both, give an empty open list, and neither id appears in pinned or recents.
  - `prefsRoundTrip`: `restoreSlots(prefs.copy(panelSlots = s.toPrefs(...)), ...)` reproduces s's open order, pins and recents.
  - `isExpandedFollowsSlots`
- [ ] **Step 2: Run the tests and check they fail.** Run `./gradlew :apps:orpheus:shared:jvmTest --tests 'org.balch.orpheus.ui.panels.slots.HeaderReducerTest'`. Expected: FAIL.
- [ ] **Step 3: Implement `PanelSlotsPrefs`, the `AppPreferences` field and `HeaderReducer.kt`.** Then rewire `HeaderViewModel` so its `scan` calls `reduceHeader`, and update `previewFeature`.
- [ ] **Step 4: Run the tests and check they pass.** Then run `./gradlew compileKotlinJvm`. Expected: the tests PASS. The only compile errors allowed are in `HeaderPanel.kt` and `DesktopSynthScreen.kt`. Stub their reads to compile: `HeaderPanel` uses `uiState.isExpanded`, and both files take weights from `panel.weight`. Task 6 deletes or rewrites them.
- [ ] **Step 5: Commit** with the subject `feat(ui): header state drives panel slots and persists them`.

### Task 4: Slot area composable with scaling

**Files:**
- Create: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/panels/slots/PanelSlotsArea.kt`
- Modify: `features/dj/src/commonMain/kotlin/org/balch/orpheus/features/dj/DjPanelRegistration.kt:37-39`
- Create: `apps/orpheus/shared/src/jvmTest/kotlin/org/balch/orpheus/ui/panels/slots/SlotsRenderHarness.kt`

**Interfaces:**
- Consumes: `HeaderPanelUiState`, `HeaderPanelActions` (Task 3), `slotCapacity`, `slotScale` (Task 1) and `PanelNavEntry` (Task 2).
- Produces:
  - `@Immutable data class PanelPulse(val id: PanelId, val seq: Int)`
  - The composable:

    ```kotlin
    @Composable
    fun PanelSlotsArea(
        panels: Map<PanelId, FeaturePanel>,
        state: HeaderPanelUiState,
        actions: HeaderPanelActions,
        pulse: PanelPulse?,
        onDialogActiveChange: (Boolean) -> Unit,
        modifier: Modifier = Modifier,
    )
    ```

**Behavior:**
- **Capacity.** Measure with `onSizeChanged` into state. Compute `capacity = slotCapacity(widthDp)` and call `actions.setCapacity(capacity)` from a `LaunchedEffect(capacity)`. Render nothing until the first measure.
- **Layout.** Draw a `Row`. Each open panel's width is `units * (areaWidth / capacity)`, and free units show a dim empty placeholder with the text "Pick a panel from the rail or recents".
- **Each slot draws:**
  - A header row about 24dp tall with the entry's `shortLabel` in the panel's `color`.
  - A pin toggle (📌), enabled when `slots.canPin(id) || id in slots.pinned`, calling `actions.togglePin`.
  - A ✕ button calling `actions.close`.
  - The body, under `CompositionLocalProvider(LocalDensity provides Density(base.density * s, base.fontScale), LocalCompactMode provides true)` with `s = slotScale(...)`. It calls `panel.Content(Modifier.fillMaxSize(), isExpanded = true, onExpandedChange = { if (!it) actions.close(id) }, onDialogActiveChange)`.
- **Touch.** A `pointerInput(id)` on the slot reads `awaitPointerEvent(PointerEventPass.Initial)`. On `PointerEventType.Press` it calls `actions.touch(id)`, without consuming the event.
- **Pulse.** When `pulse?.id == id`, briefly brighten the slot border, keyed on `pulse.seq`, for about 300ms.
- **Overflow fallback.** If a body taller than the slot still cannot fit at `s = 0.85`, it must scroll vertically. A body that fits must look as it does today: centered, with no scroll. The implementer chooses the mechanism. The render harness proves both cases.
- **DJ fix.** In `DjPanelRegistration`, replace `LaunchedEffect(isExpanded) { synthEngine.setTurntableVizEnabled(isExpanded) }` with a `DisposableEffect(isExpanded)` that sets the value and calls `setTurntableVizEnabled(false)` in `onDispose`.

- [ ] **Step 1: Write the render harness `SlotsRenderHarness.kt`.**
  - Follow `apps/djapp/shared/src/jvmTest/kotlin/org/balch/orpheus/djapp/DjLayoutRenderHarness.kt`: use `ImageComposeScene` and write to `build/orpheus-slots-render/`. Assert nothing, and swallow throwables. `ImageComposeScene` is not `Closeable`, so use `try`/`finally { scene.close() }`.
  - Use fake panels from `featurePanelPreview`, each a `CollapsibleColumnPanel` with a few `Text`s and colored boxes.
  - Render these cases:
    - `fold-2slots`: 850×400dp, 2 panels.
    - `desktop-5slots`: 1900×330dp, 4 panels with PULSAR wide.
    - `onefree`: 850×400dp, 1 panel.
    - `tall-overflow`: 850×250dp, one fake panel 500dp tall.
- [ ] **Step 2: Implement `PanelSlotsArea.kt` and the DJ fix.**
- [ ] **Step 3: Run the harness and look at the images.**
  - Run `./gradlew :apps:orpheus:shared:jvmTest --tests '*SlotsRenderHarness*' --rerun`, then read each PNG in `apps/orpheus/shared/build/orpheus-slots-render/`.
  - Expected: no clipped content, pin and ✕ visible, PULSAR spanning two units on desktop, the placeholder in `onefree`, and the tall panel scrolled rather than clipped.
- [ ] **Step 4: Run** `./gradlew :features:dj:compileKotlinJvm`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** with the subject `feat(ui): scaled panel slot area`.

### Task 5: Recents strip, rail and search overlay

**Files:**
- Create: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/panels/slots/RecentsStrip.kt`
- Create: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/panels/slots/PanelRail.kt`
- Create: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/panels/slots/PanelSearchOverlay.kt`
- Modify: `apps/orpheus/shared/src/jvmTest/kotlin/org/balch/orpheus/ui/panels/slots/SlotsRenderHarness.kt`

**Interfaces:**
- Consumes: `PanelNavEntry`, `PanelGroup`, `SearchCandidate` and `searchPanels` (Task 2), and `PanelSlots` (Task 1).
- Produces:

  ```kotlin
  @Composable fun RecentsStrip(
      entries: Map<PanelId, PanelNavEntry>, colors: Map<PanelId, Color>, slots: PanelSlots,
      railVisible: Boolean, onOpen: (PanelId) -> Unit, onSearch: () -> Unit,
      onToggleRail: () -> Unit, onCollapse: () -> Unit, modifier: Modifier = Modifier,
  )
  @Composable fun RecentsHandle(onExpand: () -> Unit, onSearch: () -> Unit, onToggleRail: () -> Unit, modifier: Modifier = Modifier)
  @Composable fun PanelRail(
      entries: List<PanelNavEntry>, colors: Map<PanelId, Color>, openIds: Set<PanelId>,
      onOpen: (PanelId) -> Unit, modifier: Modifier = Modifier,
  )
  @Composable fun PanelSearchOverlay(
      candidates: List<SearchCandidate>, colors: Map<PanelId, Color>, autoFocus: Boolean,
      onPick: (PanelId) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier,
  )
  ```

**Behavior:**
- **Strip.** One `Row` about 32dp tall: 🔍, ☰ (rail toggle), the open chips (bright, 📌 suffix on pinned panels), a divider, the recents chips (dim), and a collapse chevron. When the chips overflow, the strip scrolls sideways (`horizontalScroll`).
- **Handle.** A small pill (about 20dp tall) with ☰, 🔍 and an expand chevron.
- **Rail.** About 72dp wide, `verticalScroll`. Each group's title is drawn in its color, picked from `OrpheusColors`. Chips use the panel's own color, with open panels lit.
- **Search overlay.**
  - An in-layout opaque card, not a `Dialog`, with a `TextField` and the `searchPanels` results grouped by `PanelGroup` when the query is blank.
  - Enter picks the top result and Escape calls `onDismiss`, both through `onPreviewKeyEvent` on the field.
  - With `autoFocus`, it requests focus on the field on first composition. Without it, the field stays unfocused, so no soft keyboard appears.
  - Tapping outside the card dismisses it.

- [ ] **Step 1: Add harness renders.**
  - `strip-open`: 850dp wide, 2 open, 4 recents, 1 pinned.
  - `handle`.
  - `rail`: 400dp tall, 2 open.
  - `search-blank`: the full grouped list.
  - `search-de`: the results for "de".
- [ ] **Step 2: Implement the three files.**
- [ ] **Step 3: Run the harness and check the PNGs.** Expected: readable chips at 850dp, rail groups visible, and the search card opaque with Delay first for "de".
- [ ] **Step 4: Commit** with the subject `feat(ui): recents strip, panel rail and search`.

### Task 6: Wire into the desktop screen

**Files:**
- Create: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/panels/slots/PanelNavigationLayout.kt`
- Modify: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/screens/DesktopSynthScreen.kt:60-95`
- Modify: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/SynthScreen.kt:86-107`
- Delete: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/panels/HeaderPanel.kt`
- Create: `apps/orpheus/shared/src/commonMain/kotlin/org/balch/orpheus/ui/panels/slots/SearchShortcut.kt`
- Test: `apps/orpheus/shared/src/commonTest/kotlin/org/balch/orpheus/ui/panels/slots/SearchShortcutTest.kt`
- Modify: `apps/orpheus/shared/src/jvmTest/kotlin/org/balch/orpheus/ui/panels/slots/SlotsRenderHarness.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces:

  ```kotlin
  @Composable fun PanelNavigationLayout(
      panels: Map<PanelId, FeaturePanel>, state: HeaderPanelUiState, actions: HeaderPanelActions,
      searchRequest: SearchRequest?, onSearchRequestChange: (SearchRequest?) -> Unit,
      onDialogActiveChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
  )
  ```

  - `@Immutable data class SearchRequest(val autoFocus: Boolean)`
  - `fun isSearchShortcut(key: Key, isMetaPressed: Boolean, isCtrlPressed: Boolean): Boolean`, true for `Key.K` with Meta or Ctrl.

**Behavior:**
- **`PanelNavigationLayout` layout.**
  - Top: `RecentsStrip`, or `RecentsHandle` when `!state.recentsStripVisible`.
  - Below: a `Row` holding `PanelRail` (when the rail is shown) and `PanelSlotsArea`.
  - Over everything: `PanelSearchOverlay` when `searchRequest != null`.
- **Rail visibility.** `state.railVisible ?: (windowWidth >= 1200.dp)`. The window width is measured with `onSizeChanged` on the layout itself. The toggle calls `actions.setRailVisible(!shown)`.
- **Opening.** Rail, strip and search all go through one `onOpenRequest(id)`:
  - If the panel is open, bump a local `PanelPulse`.
  - Otherwise call `actions.open(id)`.
  - From search, also close the overlay.
- **Dialog flag.** While the search overlay is showing, call `onDialogActiveChange(true)`, and `false` when it closes.
- **Panel filter.** Only panels in `panels` (the registered ones) appear in the rail, recents and search. REC is absent on Android.
- **`DesktopSynthScreen`.**
  - Replace `HeaderPanel(...)` with `PanelNavigationLayout(...)`, keeping `Modifier.fillMaxWidth().weight(0.75f)`.
  - `panels` = `headerFeature.resolvePanels(FactoryPanelSets.DesktopScreen).associateBy { it.panelId }`.
  - Hold `searchRequest` in `remember { mutableStateOf<SearchRequest?>(null) }`.
  - In `onPreviewKeyEvent`, check `isSearchShortcut(...)` before `SynthKeyboardHandler`. On KeyDown, set `SearchRequest(autoFocus = true)`. Return true for both KeyDown and KeyUp, so the K gate never sees them.
- **`SynthScreen`.** Replace `BoxWithConstraints` with a `Box(Modifier.fillMaxSize().onSizeChanged { measured = it })`. Render nothing until it is measured, then branch on `determineLayoutMode` using dp from `LocalDensity`.
- **Cleanup.** Delete `HeaderPanel.kt` and fix any remaining references.

- [ ] **Step 1: Write the failing test** `SearchShortcutTest`. `isSearchShortcut(Key.K, true, false)` and `isSearchShortcut(Key.K, false, true)` are true. `isSearchShortcut(Key.K, false, false)` and `isSearchShortcut(Key.J, true, false)` are false.
- [ ] **Step 2: Run the test and check it fails.** Run `./gradlew :apps:orpheus:shared:jvmTest --tests '*SearchShortcutTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement `SearchShortcut.kt` and `PanelNavigationLayout.kt`, rewire the two screens and delete `HeaderPanel.kt`.**
- [ ] **Step 4: Add harness renders** of `PanelNavigationLayout` with fake panels.
  - `nav-fold`: 850×420dp, rail hidden by default.
  - `nav-fold-rail`: `railVisible = true`.
  - `nav-desktop`: 1900×420dp, rail shown by default.
  - `nav-search`: 1280×420dp, search open.
- [ ] **Step 5: Run everything.**
  - Run `./gradlew :apps:orpheus:shared:jvmTest --rerun` and `./gradlew compileKotlinJvm`, and read the new PNGs.
  - Expected: all tests PASS and BUILD SUCCESSFUL. The Fold render shows 2 slots, desktop shows 5 units with the rail, and nothing is clipped.
- [ ] **Step 6: Commit** with the subject `feat(ui): panel slots replace the spine row on wide screens`.

### Task 7: Device and desktop verification (controller)

The controller does this in the main session, not a subagent.

- [ ] **Step 1: Fold emulator.** Build with `./gradlew :apps:orpheus:androidApp:assembleDebug` and install on `emulator-5554`. Launch `org.balch.orpheus.debug` (not `org.balch.orpheus`, which is an old March install). Unfold and capture the inner display with `screencap -d 4619827259835644672`. Expected: 2 slots, no clipping, the strip at the top and no rail.
- [ ] **Step 2: Fold and unfold.** Fold, unfold, and check that the same panels are open. Open three panels in a row and check that the least recently used one went to recents.
- [ ] **Step 3: Desktop.** Run `./gradlew :apps:orpheus:desktopApp:run`, launched with nohup per the project memory. Resize the window across about 600, 960, 1280 and 1920dp.
  - Check that Cmd+K opens search with focus. Type `asdk ` (with a space) and check that no voice or drum sounds.
  - Check that Escape closes search and that A then plays again.
- [ ] **Step 4: Phone layout.** On the folded outer display, check that the compact pager is unchanged.
