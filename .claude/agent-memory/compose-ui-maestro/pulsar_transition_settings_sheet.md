---
name: pulsar-transition-settings-sheet
description: TransitionSettingsSheet (Pulsar MORPH sheet) design details — header layout, style chips, handoff knob, cosmicPurple accent, hidden styles.
metadata:
  type: project
---

## TransitionSettingsSheet Design (Pulsar / cosmicPurple)
- File: `features/pulsar/src/commonMain/kotlin/org/balch/orpheus/features/pulsar/TransitionSettingsSheet.kt`
- Accent: `OrpheusColors.cosmicPurple` throughout
- Header: "MORPH" (all-caps, letter-spacing 3.sp, FontWeight.ExtraBold, 14.sp) + mid-dot + one-line per-style description on same row; vertical padding 8.dp
- Style chips: INLINE Row layout (glyph 13sp + " label" 9sp), ~30dp tall (padding vertical=6.dp), 4dp gap between chips and rows
- Handoff: `HorizontalRotaryKnob` (32dp, cosmicPurple, labelSide=END, label="${handoffMs}ms", valueFormatter=null); dimmed with `Modifier.alpha(0.35f)` on wrapping Column for CUT/RANDOM
- Section gap between STYLE and HANDOFF: `HorizontalDivider` with `Modifier.padding(top = 8.dp)` (no manual Spacer)
- Random pool picker REMOVED — RANDOM implicitly uses runner's SAFE_POOL=[CUT,GAP,FADE,SCRATCH]
- SectionLabel top padding: 10.dp
- Sheet content wrapped in `verticalScroll(rememberScrollState())`
- Public contract: 5 lambdas (spec, onDismiss, onStyleChange, onHandoffMsChange, onPoolChange) — `onPoolChange` retained for API compat, not wired
- HIDDEN_STYLES = setOf(CROSSFADE) — excluded from sheet and SAFE_POOL
