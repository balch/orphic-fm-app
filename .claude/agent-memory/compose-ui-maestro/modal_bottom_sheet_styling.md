---
name: modal-bottom-sheet-styling
description: ModalBottomSheet styling conventions — containerColor/contentColor overrides, custom dragHandle, divider/gradient patterns for a cosmic-themed sheet.
metadata:
  type: project
---

## ModalBottomSheet Styling Patterns
- Use `containerColor` and `contentColor` params on `ModalBottomSheet` to override the M3 surface defaults (e.g., `containerColor = OrpheusColors.deepPurple`)
- Custom `dragHandle` lambda gives a cosmically-styled pill instead of the default grey bar
- `HorizontalDivider` with `color = accent.copy(alpha = 0.25f)` creates subtle cosmic section separators
- Vertical gradient header (using `Brush.verticalGradient`) gives depth without extra composables
