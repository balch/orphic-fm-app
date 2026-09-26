---
name: reference-liquid-effects-per-frame
description: Galaxy/Fireworks/Swirly publish LocalLiquidEffects every frame, so any composition-phase read of it (bars, glass modifiers, accent-capturing draw caches) recomposes or rebuilds at 60 Hz; plus the DJ app's measurement tooling for perf audits
metadata:
  type: reference
---

Three vizzes (GalaxyViz, FireworksViz, SwirlyViz, the only `liquidEffectsFlow` overrides) emit new
`VisualizationLiquidEffects` from their `withFrameNanos` loop: LFO-driven `titleColor`,
energy-driven elevation/size. VizViewModel relays them into state and DjApp provides them as
`LocalLiquidEffects` (a `compositionLocalOf`), so **every reader recomposes every frame while one of
those vizzes is up** (random viz mode picks a new one per vibe).

Readers that pay for it: DjTvTopBar, DjTvBottomBar body (`accent`), DjAppTvChrome (DjAppScreen.kt),
and the landscape rail's `GlassRail`, which applies `Modifier.tvBarGlass(true)` in DjAppBottomNav.kt.
A `drawWithCache` lambda that captures `accent` loses its cache on every such frame (found
2026-09-24 in the dock hairline / song band). Static-viz tests never exercise this: when auditing
chrome, run a scene that swaps `LocalLiquidEffects` per frame.

Tooling that already exists for these audits: `ScopeCounter` (a `CompositionObserver` counting
every scope entered) in `apps/djapp/shared/src/jvmTest/.../FrameMeter.kt`; bytes per frame via
ThreadMXBean, same file.

Related: [[djapp-tv-perf-profile]] (TV dock is RenderThread-bound).
