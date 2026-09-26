# Storefront capture tooling

`adb_ui.py` drives an Android emulator by visible text: `dump` lists every labelled node,
`tap "<label>"` taps the first exact (then substring) match on `text` or `content-desc` and exits 1
if nothing matches, `burst <prefix>` takes three screencaps two seconds apart. `pick_best.py` keeps
the burst frame with the most detail, since an animated viz can land on a dark frame.
`verify_store_assets.py` checks every staged slot against store rules, and `render_frame.sh` renders
one framed image with headless Chrome.

The runbook is the local plan `docs/superpowers/plans/2026-09-24-storefront-screenshot-refresh.md`
(gitignored, main checkout). Build `ogDebugRelease` and the iOS Release config only: debuggable
builds list WIP vibes.

Android status bar demo mode, before each device's shots:

```bash
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
```

## Known selectors and device quirks

Verified 2026-09-24 against `feature/vibe-navigator`'s `ogDebugRelease` on each AVD.

- **Selectors.** Vibe: tap `Select VIBE` on phones, or `Vibe:` in the large-screen top bar, then the
  name. Viz: tap `Viz:`, then the name. Play: `Play, <vibe>` on the new phone bar, plain `Play` on the
  large-screen top bar. The phone tab bar reads `Pause` while playing on the old UI.
- **Phone** `Pixel_10_Pro_XL`, booted `-memory 4096 -gpu host`, shot at `wm density 600` +
  `font_scale 1.3`. Reset both afterwards.
- **Fold** `Pixel_9_Pro_Fold` has two displays. Pass `-d 4619827259835644672` (inner, 2076x2152) to
  `adb_ui.py burst`, or screencap writes a warning into the PNG. Postures: `adb emu posture 3` = open,
  `adb emu posture 2` = half-open (tabletop). Sideways = `settings put system accelerometer_rotation 0`
  then `user_rotation 1`.
- **Tablet** `Pixel_Tablet` 2560x1600 and **TV** `Television_4K` 3840x2160 show the dock on launch.
  Launch TV with `-c android.intent.category.LEANBACK_LAUNCHER`.
- **iOS build** for the simulator needs `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO`,
  or the generic destination tries x86_64 and fails to link.
- **iOS driving.** The simulator tool here has `screenshot` and `tap` but no `inspect`, so read a fresh
  screenshot and tap in device points (screenshot px × 440/921 on the 17 Pro Max). `simctl io
  screenshot` gives RGBA: flatten it.
- **iPad** held in landscape captures as 2752x2064 already upright. No rotation step.
