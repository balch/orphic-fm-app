---
name: orpheus-theme-notes
description: OrpheusTheme per-panel accent color assignments and the dark-by-default posture change (no longer follows isSystemInDarkTheme).
metadata:
  type: project
---

## Panel Accent Colors
- `OrpheusColors.synthGreen` - MediaPipe gesture panel
- `OrpheusColors.echoLavender` - Reverb panel
- `OrpheusColors.vizGreen` - VizPanel
- `OrpheusColors.bassAmber` - Bass Voice panel (`bassDarkAmber` for knob track, `bassKnobCap` for knob cap)
- `OrpheusColors.warpsGreen` - Warps cross-modulator panel

## OrpheusTheme Dark Posture (2026-07-14)
- `OrpheusTheme(darkTheme: Boolean = true, ...)` in `ui/theme/.../OrpheusTheme.kt` — NO LONGER follows `isSystemInDarkTheme()`. Changed because a light system theme leaked M3 light defaults into the synth UI. Parameter + `LightColorScheme` still exist for explicit opt-out.
- Every call site in the repo (~104, both app roots `App.kt`/`DjApp.kt` + all `@Preview` composables) uses bare `OrpheusTheme { ... }` — none ever passed `darkTheme` explicitly, so this default flip is safe/uniform. No other file called `isSystemInDarkTheme()`.
- If adding a new `@Preview` or app entry point: just use `OrpheusTheme { ... }`, dark is now automatic. Only pass `darkTheme = false` if you deliberately want to preview/ship the light scheme.
