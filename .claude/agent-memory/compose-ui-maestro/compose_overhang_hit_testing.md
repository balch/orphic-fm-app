---
name: compose-overhang-hit-testing
description: How content drawn outside its parent's bounds stays live in Compose 1.12 (hit testing, boundsInRoot, M3 Surface clip, NavigationBarItem label drift), learned raising the DJ phone bar's dome
metadata:
  type: reference
---

Verified against compose ui 1.12.0 / material3 1.12.0-alpha03 sources and ImageComposeScene tests (Task 18, 2026-09-24, `PhoneBarDomeTest`).

- `InnerNodeCoordinator.hitTestChild` checks only **clipping layers** (`withinLayerBounds`), never the node's own bounds. A coordinator with no pointer-input node passes any hit to its children, even an overhanging child.
- A pointer node that is out of bounds gets only `speculativeHit`. It is **not recorded**, though its children are still tested. So a layout modifier that lifts content must sit **before** the `pointerInput`/`clickable`/`semantics` modifiers. Then those nodes measure the full overhanging area.
- `LayoutCoordinates.boundsInRoot()` / `SemanticsNode.boundsInRoot` clip only at clipping layers. An overhang shows up whole in semantics bounds, and accessibility touch bounds use `clipBounds=false`.
- M3 `Surface` ends in `.clip(shape)` plus an empty `pointerInput(Unit){}`. Anything inside NavigationBar/Surface is clipped for both drawing and hits. Never add an empty `pointerInput` to a replacement Row either.
- M3 `NavigationBarItem` centres icon+4+4+label in its 80dp item, so the **label's y depends on the icon's height**. A 12sp countdown Text in the icon slot lifts that tab's label about 5dp. Under `alignBy(LastBaseline)` it instead grows the Row by 5dp. The fix is `heightIn(min = 24.dp)` around the icon.
- A layout modifier that places its child at negative y passes baselines through (child line + position.y), so `alignBy(LastBaseline)` works across nested Columns and Text.
- **`graphicsLayer { translationY }` is NOT layout-neutral for alignment lines.**
  - A parent Layout merging a child's baseline maps it through `toParentPosition`, which applies the layer's matrix.
  - Result: a Row with `alignBy(LastBaseline)` re-aligns the whole item and cancels the drop. The item moved up by the translation.
  - For a truly draw-only nudge, use `drawWithContent { translate(top = …) { drawContent() } }`. It moves neither the alignment lines nor the semantics bounds.
  - Proof: `PhoneBarDomeTest`, Task 18 round 2.
- **Drawing past a slot without widening the tap target:** a layout modifier measures the child up to the lane width, reports `min(child, slot)`, and places the child centred at a negative x. A Text has no pointer node, so taps on the overhang fall through to the neighbour tab.
- DJ app: `PanelWakeOverlay` (last child of DjLayoutBox) covers the stage while panels are idle-faded. Chrome drawn over the stage must report itself, or it is dead to taps then:
  - a band inside the stage uses `vizStageChrome`;
  - chrome reaching in from outside uses `vizStageOverhang` (unclipped, cleared on detach).
  - `wakeBlockers` cuts any number of holes. See [[djapp-tv-focus-idle-fade]].
- Text line boxes: a fixed `lineHeight` smaller than the font does NOT crop the glyphs on skiko. At 19sp in a 16sp line, the Text lays out 22.5dp tall. So "the line box clips the glyphs" can't be made to fail by shrinking lineHeight. The real risk of a big fixed box around small text is the container clipping it (see the `OrpheusTheme.proportional()` KDoc).
