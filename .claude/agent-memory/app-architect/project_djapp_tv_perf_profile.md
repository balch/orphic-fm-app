---
name: djapp-tv-perf-profile
description: Measured perf shape of the DJ app's TV/LargeScreen layout — RenderThread-bound, not UI-thread-bound — and which panels already recompose every frame by design
metadata:
  type: project
---

A device trace of the DJ app's TV/LargeScreen dock (taken ~2026-08-30, during the
`large-screen-layout-toggles` branch review) measured the app as **RenderThread-bound**: ~81ms/frame
issuing draw commands with three panels docked, while the UI thread did only 1-11ms of real work and
spent the rest BLOCKED.

Also measured (recorded in `TvGlassEnabled`'s kdoc): on a real Chromecast with Google TV, panel glass
OFF averaged ~33fps / 86.5% janky, glass ON ~27fps / 100% janky — roughly 6ms of added frame time.
`TvGlassEnabled = false` is the shipping default because of this.

**Why:** the visualization redraws continuously at 60fps and the docked panels issue a large number
of draw commands, so the GPU/RenderThread saturates long before composition does.

**How to apply:** do not report "this recomposes" as a performance emergency in this app. Recomposition
findings are correctness/hygiene. Draw-phase invalidation findings are worth even less — the viz already
forces a full frame every frame, so an extra draw invalidation costs nothing. Reserve real perf urgency
for things that add draw commands or GPU work.

Corollary worth re-verifying before relying on it: several panels are **already** whole-scope 60fps
recompositions by design, so "X now recomposes per frame" is often not a regression there —
`MixerPanel` (a `withFrameNanos` meter loop writes `MutableFloatState`s that are read in the panel's
content lambda) and `DjPanel` (`vizFlowA`/`vizFlowB`/`beatPhaseFlow` are `collectAsState`d and read in
the content lambda; the monitor polls them at ~60fps). Widgets that DO keep per-frame values in the
draw phase, and where a new composition-phase read is therefore a genuine regression:
`RotaryKnobDial`, `HeartbeatViz.Content`.
