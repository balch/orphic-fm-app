---
name: pulsar-armed-tint-pattern
description: "Armed" highlight pattern in PulsarPanel — latching StateFlow (ENDING pill) vs auto-clearing timer-based StateFlow (VIBE dropdown anomaly), and the shared EnumDropdown highlighted param.
metadata:
  type: project
---

## "Armed" Tint Pattern (Pulsar Panel) — Latching vs Transient
- Canonical latching example: ENDING pill in `PulsarPanel.kt` (~line 434) reads `actions.outroArmed: StateFlow<Boolean>` (backed by `PulsarSongEnding.endingTriggered`, latches until song end / vibe change) and tints `OrpheusColors.cosmicPurple.copy(alpha=0.35f)` vs `OrpheusColors.darkVoid.copy(alpha=0.6f)` idle.
- Transient variant (VIBE dropdown "armed" tint for the Void Anomaly long-press, added 2026-07-18): same color pair, but the StateFlow auto-clears on a timer since the underlying C++ event has no exposed active-window. Pattern in `PulsarViewModel.kt`: private `_anomalyArmed = MutableStateFlow(false)` + `anomalyArmedResetJob: Job?` cancelled/relaunched on each trigger (`scope.launch { delay(ANOMALY_HIGHLIGHT); _anomalyArmed.value = false }`), where `ANOMALY_HIGHLIGHT = 8.seconds` lives as a documented `private val` in the VM's companion object. Also eagerly cleared in `applyVibe()` so a vibe switch doesn't leave a stale tint.
- Generic `EnumDropdown` in `PulsarPanel.kt` (private, used by VIBE/ROOT/SCALE) now takes `highlighted: Boolean = false` and animates its background via `animateColorAsState` between the same cosmicPurple/darkVoid pair (`tween(200)`) — reuse this param instead of hardcoding a new tinted Box when another dropdown needs an armed/confirmation state.
- `PulsarPanelActions.EMPTY` covers new StateFlow fields for free (constructor defaults to `MutableStateFlow(false)`), so previews/tests using `PulsarPanelActions.EMPTY` don't need updating when adding a new armed-flag field.
