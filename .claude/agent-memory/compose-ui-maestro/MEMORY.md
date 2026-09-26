# Compose UI Maestro Memory

## Panel Structure & Theming
- [CollapsibleColumnPanel layout + registration pattern](collapsible_column_panel.md) — spacing/sizing rules, canonical body shape, `*PanelRegistration` DI shape, import conventions
- [OrpheusTheme accent colors + dark posture](orpheus_theme_notes.md) — per-panel accent assignments, dark-by-default since 2026-07-14

## Gesture Pads
- [Gesture pad overlay design (piano key aesthetic)](gesture_pad_overlay_design.md) — bevel/gradient/glow spec for voice+drum pads, idle/pressed/pinched states, file locations

## Sheet & Widget Styling
- [ModalBottomSheet styling patterns](modal_bottom_sheet_styling.md) — containerColor/dragHandle/divider conventions
- [Jetpack Glance widget patterns (DjWidget)](djapp_glance_widget.md) — full-bleed art layering, GlanceModifier.defaultWeight, R8-kept action classes
- [Pulsar TransitionSettingsSheet design](pulsar_transition_settings_sheet.md) — MORPH header, style chips, handoff knob, cosmicPurple

## Compose Library Internals
- [DjAppNavScaffold bar/rail + item colors](navigation_suite_scaffold_colors.md) — suite scaffold dropped for Column/Row around M3 bar/rail with a custom transport slot (why), per-item indicator colors, how to read CMP sources from the Gradle cache
- [Overhanging content hit testing](compose_overhang_hit_testing.md) — lift outside pointer nodes, M3 Surface clips, NavigationBarItem label drifts with icon height, wake overlay eats stage overhangs
- [iOS Skiko shader support](ios_skiko_shader_support.md) — iOS renders through skiko same as JVM/WASM; `skikoShaderMain` source-set fan-out pattern

## Pulsar Panel Patterns
- ["Armed" tint pattern](pulsar_armed_tint_pattern.md) — latching vs auto-clearing StateFlow-driven highlight, shared `EnumDropdown` `highlighted` param

## DJ App
- [djapp/shared pre-existing broken iOS test compile](djapp_ios_test_compile_broken.md) — TabMergeTest/VibeInfoMapperTest fail on clean main, unrelated to your change
- [DJ App Android TV](djapp_tv_mode.md) — overscan + 10-foot density scaling; see also [D-pad focus treatment](djapp_tv_focus_treatment.md), [top/bottom bar rework](djapp_tv_nav_rework.md), [glass hardware gate](djapp_tv_glass_hardware_gate.md)
- [TV bar chrome = dark base + viz wash](djapp_tv_topbar_viz_theming.md) — `orpheusChromeWash`, 2 rejected designs (viz-as-fill, trim-only), docked-wash-muddies-over-busy-backdrop lesson, armed ring stays fixed cosmicPurple
- [TV region-focus idle fade](djapp_tv_focus_idle_fade.md) — 5s-silence fade on `TvFocusRegionHolder.alpha`, draw-phase-nested read, root `onPreviewKeyEvent` never consumes, Popup key events are a confirmed gap
- [DJ Panel D-pad audit](djapp_dj_panel_dpad_audit.md) — full control enumeration, BenderFaderWidget's missing focus visual
- [DJ App nav icons](djapp_nav_icons.md) — DjNavRoutes.kt icon map, how to verify a Material icon exists in this project's classpath
- [Large-screen toggle fixes](djapp_large_screen_toggle_fixes.md) — opensAsSheet ≠ dockable, conditional-padding footprint bug, double vertical overscan, LaunchedEffect-vs-toggle race

## Rendering Performance
- [Sprite-baking for path-heavy visualizations](sprite_baking_path_heavy_viz.md) — Skia CPU `TriangulatingPathOp` is the cost; bake per-colour `ImageBitmap` once and blit, why `BlendMode.Plus` makes it safe, `ImageComposeScene` A/B technique
- [Frame budget lessons](frame_budget_measurement_lessons.md) — what ScopeCounter counts, pixel-identical swaps that held, bimodal scenes, `--rerun` binds per task
- [Dome cues + shared-worktree testing](dome_cues_and_shared_worktree_testing.md) — `LocalVibeDomeCues` reaches every dome, same-frame moves hide double rolls, S7 first-swipe warm-up, private scratch worktree A/B
- [Scope ring tuned on real audio](scope_ring_real_audio_tuning.md) — port-dump + C++ replay capture recipe, real window levels/trigger rates, hits arrive untriggered, why 40ms
- [ImageComposeScene's shared lifecycle registry](imagecomposescene_shared_lifecycle_registry.md) — LifecycleStartEffect races collectAsStateWithLifecycle in scene tests; HoldWhileStarted; hand-moved lifecycle recipe
