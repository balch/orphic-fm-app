---
name: djapp-nav-icons
description: DjNavRoutes.kt per-tab icon assignments, and how to verify a Material Icons Extended icon exists in this project's pinned classpath version by inspecting the resolved jar.
metadata:
  type: reference
---

# DJ App Nav Icons (DjNavRoutes.kt)

File: `apps/djapp/shared/src/commonMain/kotlin/org/balch/orpheus/djapp/DjNavRoutes.kt`
Sealed `DjRoute` interface, one `data object` per tab/dock-panel, each with its own `icon`/`label`.
Route objects are `@Serializable` and persisted in preferences — never rename the object/route
identity when changing only its icon or label.

Current icon assignments (all `androidx.compose.material.icons.rounded.*`):
- DjTab -> Album, TimerTab -> Timer, MixTab -> Tune, HornTab -> SurroundSound
- AiTab -> AutoAwesome (sheet), VibeInfoTab -> Info (sheet), PulsarTab -> GridView
- EndsTab -> SportsScore (chequered flag — swapped from plain `Flag` 2026-08-29 so the
  song-ending control reads as "finish" at a glance on the TV bottom bar's large 56dp icons)

## Verifying a Material icon exists for this project's classpath/version
`material-icons-extended` version is pinned by `composeIcons` in `gradle/libs.versions.toml`
(1.7.3 as of 2026-08-29) — do not trust IDE autocomplete alone, confirm against the resolved jar:
```
find ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.compose.material/material-icons-extended-desktop -iname "*.jar" | grep -v sources
unzip -l <jar> | grep -i IconName
```
Each icon compiles to `androidx/compose/material/icons/<style>/<Name>Kt.class` (e.g.
`rounded/SportsScoreKt.class`). This is the desktop/JVM artifact — matches what
`compileKotlinJvm` links against. `apps/djapp/shared`, `apps/djapp/ai`, and `ui/widgets` all
depend on `libs.compose.material.icons`.

Material's "SportsScore" glyph IS the chequered/racing flag (used for race-finish / final-score
iconography) — there's no separate "CheckeredFlag"-named icon in the Material set.
