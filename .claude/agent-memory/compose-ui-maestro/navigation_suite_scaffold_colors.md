---
name: navigation-suite-scaffold-colors
description: DjAppNavScaffold is a plain Column/Row around M3 NavigationBar/NavigationRail with a custom transport slot (NavigationSuiteScaffold dropped, and why); per-item indicator colours; how to read CMP library sources from the Gradle cache.
metadata:
  type: reference
---

## DjAppNavScaffold no longer uses NavigationSuiteScaffold (2026-09, vibe navigator)
- `apps/djapp/shared/.../DjAppBottomNav.kt`: portrait layouts are a `Column` (content, then M3 `NavigationBar`); landscape is a `Row` (M3 `NavigationRail`, then content). The `material3-adaptive-navigation-suite` dependency is gone from `apps/djapp/shared`.
- Why: the play slot became the vibe transport, a custom element rather than a nav destination. In the bar it sits between the items; on the rail it is pinned to the bottom under a weighted `Spacer`, with ◀ ▶ stacked around the ring only when the rail is tall enough (`railFitsStackedTransport`, fed by `onSizeChanged` minus the rail's window insets). The suite's `item()` builder takes destinations only and has no slot for that. Dropping it also drops a dependency on an alpha API.
- The scaffold still gives the stage what the suite did: `LocalContentColor = Color.White`, and each branch consumes the bar's or rail's window insets (`consumeWindowInsets(NavigationBarDefaults/NavigationRailDefaults.windowInsets.only(Bottom/Start))`) so the stage never pads for them twice.
- Guarded by `RailLayoutTest` (semantics bounds: tabs top, transport bottom, stacked at 606dp, ring alone at 412dp) and `RailFitTest`.

## Item colours on the bare M3 items
- Pass `NavigationBarItemDefaults.colors(indicatorColor = NavIndicatorColor)` / `NavigationRailItemDefaults.colors(indicatorColor = NavIndicatorColor)` to every item. `NavIndicatorColor = OrpheusColors.neonCyan.copy(alpha = NavIndicatorAlpha)`, `NavIndicatorAlpha = 0.16f`.
- Overriding only `indicatorColor` is safe: `NavigationBarItemDefaults.colors` leaves the rest `Color.Unspecified` and `.copy()` falls back to theme colours via `takeOrElse`; `NavigationRailItemDefaults.colors` takes concrete token defaults but looks the same.
- Icon/label colour is independent of `colors.iconColor()`/`textColor()` as long as the `icon`/`label` lambdas pass an explicit `tint`/`color` (`Icon.tint` defaults to `LocalContentColor.current`, which `colors` drives; an explicit tint wins). Verified in `NavigationBar.kt`/`NavigationRail.kt`/`Icon.kt` source.

## Reading CMP library sources
- The catalog's `material3` version.ref does not describe what resolves for KMP commonMain: the Compose Multiplatform plugin substitutes `org.jetbrains.compose.material3:*` with its own versions. Check `~/.gradle/caches/modules-2/files-2.1/org.jetbrains.compose.material3/` for the real cached version.
- `find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.compose.material3 -iname "*sources.jar"`, `unzip -q <jar> -d <scratchpad-dir>`, then Read/grep the extracted `commonMain/androidx/compose/material3/**/*.kt`. Works for any CMP artifact.

## Historical: NavigationSuiteScaffold item colours
- It had no scaffold-level item-colour parameter; colours went to every `NavigationSuiteScope.item(colors = NavigationSuiteDefaults.itemColors(...))`. `item()` is not `@Composable`, so the colours had to be built as a `val` before the `navigationSuiteItems` lambda. Only relevant if the suite comes back.
